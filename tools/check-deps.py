#!/usr/bin/env python3
"""
Cross-check every module's Kotlin imports against its declared Gradle
dependencies.

Four of the first six CI failures on this project were a module importing
something it never declared -- kotlinx.coroutines leaking in transitively via
Hilt, media3-datasource reaching only as far as media3-datasource-okhttp's
implementation scope, Dagger's @MapKey with no Dagger on the classpath. Each
cost a two-minute round trip to discover one line.

This catches that class of mistake in under a second, before pushing.

    python tools/check-deps.py

Exits 1 if anything looks missing. It is a heuristic, not a compiler: it can
miss a transitive route that happens to work, and it can flag something that
resolves fine. Treat a hit as "look at this", not "this is definitely broken".
"""

from __future__ import annotations

import collections
import glob
import os
import re
import sys

# import prefix -> any one of these substrings must appear in build.gradle.kts
NEEDS: list[tuple[str, tuple[str, ...]]] = [
    ("dagger.",                    ("hilt.android", "libs.dagger")),
    ("javax.inject",               ("hilt.android", "libs.dagger")),
    ("kotlinx.coroutines",         ("kotlinx.coroutines",)),
    ("kotlinx.serialization",      ("kotlinx.serialization.json",)),
    ("androidx.media3.datasource", ("media3.datasource",)),
    ("androidx.media3.exoplayer",  ("media3.exoplayer",)),
    ("androidx.media3.session",    ("media3.session",)),
    ("androidx.media3.common",     ("media3.common", "media3.exoplayer", "media3.session")),
    ("androidx.media3.database",   ("media3.exoplayer",)),
    ("androidx.room",              ("room.runtime",)),
    ("androidx.work",              ("work.runtime",)),
    ("androidx.paging",            ("paging.runtime", "paging.compose")),
    ("retrofit2",                  ("libs.retrofit",)),
    ("com.jakewharton.retrofit2",  ("retrofit.serialization",)),
    ("okhttp3",                    ("libs.okhttp",)),
    ("com.google.android.gms",     ("play.services.auth",)),
    ("androidx.datastore",         ("libs.datastore",)),
    ("androidx.navigation",        ("navigation.compose",)),
    ("androidx.activity",          ("activity.compose",)),
    ("androidx.lifecycle.compose", ("lifecycle.compose",)),
    ("androidx.hilt.work",         ("hilt.work",)),
    ("androidx.hilt.navigation",   ("hilt.navigation",)),
    ("com.google.common",          ("media3.session",)),   # guava, transitively
    ("coil",                       ("coil.compose",)),
    ("com.hierynomus",             ("libs.smbj",)),
    ("com.yausername",             ("youtubedl",)),
]

SUPERTYPE = re.compile(
    r"^(?!.*\b(?:private|internal)\b)"        # public declarations only
    r"\s*(?:@\w+(?:\([^)]*\))?\s*)*"        # annotations
    r"(?:abstract\s+|open\s+|sealed\s+|data\s+)*"
    r"(?:class|interface|object)\s+\w+"
    r"(?:<[^>]*>)?\s*"
    r"(?:\([^)]*\))?\s*"                      # primary constructor
    r":\s*([\w.]+)"                            # <- the supertype
)


def leaked_supertypes(module: str, imports: set[str], gradle: str) -> set[str]:
    """
    A public class whose SUPERTYPE comes from an `implementation` dependency is
    invisible to consumers: they can resolve the class but not walk its
    hierarchy. Symptoms are baffling -- lint reporting a Service "must extend
    android.app.Service", or a consumer failing to resolve a method it can see.
    Supertypes on the public surface belong on `api`.
    """
    found: set[str] = set()
    by_simple = {i.rsplit(".", 1)[-1]: i for i in imports}

    for kt in glob.glob(f"{module}/src/**/*.kt", recursive=True):
        with open(kt, encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                m = SUPERTYPE.match(line)
                if not m:
                    continue
                fqn = by_simple.get(m.group(1).split(".")[0])
                if not fqn:
                    continue
                for prefix, wants in NEEDS:
                    if not fqn.startswith(prefix):
                        continue
                    for w in wants:
                        # The table stores bare aliases ("media3.session") but
                        # build files write them as libs.media3.session.
                        alias = w[len("libs."):] if w.startswith("libs.") else w
                        pat = rf"\((?:libs\.)?{re.escape(alias)}\)"
                        on_impl = re.search(r"implementation" + pat, gradle)
                        on_api = re.search(r"\bapi" + pat, gradle)
                        if on_impl and not on_api:
                            found.add(
                                f"public supertype {m.group(1)} comes from {alias} "
                                f"-- should be api(), not implementation()"
                            )
    return found

# Deliberately NOT anchored to "if (x.p != null)". The real cases are compound
# -- `if (a != null && x.p != null && y.q != null)` -- and an anchored pattern
# silently passes every one of them. That is exactly how a LyricsRepository
# guard with four conditions reached CI.
SMART_CAST = re.compile(r"(\w+)\.(\w+)\s*!=\s*null")
NULLABLE_PROP = re.compile(r"\s*(?:val|var)\s+(\w+)\s*:\s*[\w<>, ]+\?")


_SOURCE_CACHE: dict[str, list[str]] | None = None

# Both checks walk the same trees. Reading them twice is pure waste, and on a
# network or virtualised filesystem the walk dominates everything else.
SOURCE_PATTERNS = (
    "core/*/src/**/*.kt",
    "data/*/src/**/*.kt",
    "feature/*/src/**/*.kt",
    "app/src/**/*.kt",
)


def sources() -> dict[str, list[str]]:
    """path -> lines, read once per run."""
    global _SOURCE_CACHE
    if _SOURCE_CACHE is None:
        _SOURCE_CACHE = {}
        for pattern in SOURCE_PATTERNS:
            for kt in glob.glob(pattern, recursive=True):
                try:
                    with open(kt, encoding="utf-8", errors="ignore") as fh:
                        _SOURCE_CACHE[kt] = fh.read().split("\n")
                except OSError:
                    continue
    return _SOURCE_CACHE


def module_of(path: str) -> str:
    """`data/catalog/src/...` -> `data/catalog`; `app/src/...` -> `app`."""
    parts = path.replace("\\", "/").split("/")
    if parts[0] in ("core", "data", "feature") and len(parts) > 1:
        return f"{parts[0]}/{parts[1]}"
    return parts[0]


def cross_module_nullables() -> dict[str, set[str]]:
    """Nullable property name -> the modules that DECLARE it.

    Tracking the declaring module matters: smart-casting a property is fine
    inside the module that owns it, so flagging by name alone would cry wolf on
    every entity's own file and quickly get the whole check ignored.
    """
    found: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        if not kt.startswith(("core/", "data/")):
            continue
        for line in lines:
            m = NULLABLE_PROP.match(line)
            if m:
                found[m.group(1)].add(module_of(kt))
    return found


def smart_cast_problems(nullables: dict[str, set[str]]) -> dict[str, set[str]]:
    """
    Kotlin refuses to smart-cast a property declared in another module: it
    cannot prove the getter is stable. `if (x.p != null) use(x.p)` compiles
    inside the declaring module and fails outside it, with an error that reads
    like a type problem rather than a module-boundary one. Capture to a local.

    Two things this has to get right, both learned the hard way:

    * The condition is usually COMPOUND and spans lines, so the null check
      cannot be anchored to `if (x.p != null)`. Parens are tracked instead.
    * `enabled = x.p != null` is an ordinary boolean and needs no smart cast at
      all. Only a use where non-null is REQUIRED counts, so a later
      `x.p != null` does not make the first one a problem.
    """
    problems: dict[str, set[str]] = collections.defaultdict(set)

    # Every module. :data:catalog consumes :core:database and hits this exactly
    # as hard -- scanning only the UI layers is why this missed a real one.
    for kt, lines in sources().items():
            here = module_of(kt)

            i = 0
            while i < len(lines):
                opener = re.search(r"\b(?:if|while)\s*\(", lines[i])
                if not opener:
                    i += 1
                    continue

                # Walk to the end of the condition, however many lines it takes.
                depth = 0
                condition: list[str] = []
                j = i
                while j < len(lines):
                    fragment = lines[j] if j > i else lines[j][opener.end() - 1:]
                    condition.append(fragment)
                    depth += fragment.count("(") - fragment.count(")")
                    if depth <= 0:
                        break
                    j += 1

                guarded = {
                    (r, p)
                    for r, p in SMART_CAST.findall(" ".join(condition))
                    if nullables.get(p) and here not in nullables[p]
                }

                if guarded:
                    body = "\n".join(lines[j + 1 : j + 16])
                    for receiver, prop in guarded:
                        use = rf"\b{re.escape(receiver)}\.{re.escape(prop)}\b(?!\s*[!=]=\s*null)"
                        if re.search(use, body):
                            problems[kt].add(
                                f"line {i + 1}: {receiver}.{prop} is smart-cast across "
                                f"a module boundary (declared in "
                                f"{', '.join(sorted(nullables[prop]))}) -- "
                                f"capture it to a local val"
                            )

                i = j + 1

    return problems


IMPORT = re.compile(r"\s*import\s+([\w.]+)")


def modules() -> list[str]:
    found = {os.path.dirname(p) for p in glob.glob("*/*/build.gradle.kts")}
    found |= {os.path.dirname(p) for p in glob.glob("*/build.gradle.kts") if os.path.dirname(p)}
    return sorted(found)


def imports_of(module: str) -> set[str]:
    result: set[str] = set()
    for kt in glob.glob(f"{module}/src/**/*.kt", recursive=True):
        with open(kt, encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                if not line.startswith(("import", " ", "\t")):
                    # imports are all at the top; stop once we hit real code
                    if line.strip() and not line.startswith(("package", "//", "/*", "*")):
                        break
                m = IMPORT.match(line)
                if m:
                    result.add(m.group(1))
    return result


PRIVATE_MEMBER = re.compile(
    r"^\s{4}private\s+(?:const\s+)?(?:val|var|fun|suspend\s+fun)\s+(\w+)\b"
)
DECLARING_TYPE = re.compile(r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:\w+\s+)*?(?:object|class|interface)\s+(\w+)")


def private_leaks() -> dict[str, set[str]]:
    """
    A private member of an object, read from somewhere else as `Type.member`.

    Kotlin catches this instantly and the message is perfectly clear -- the
    problem is that catching it costs a push and a CI run, and it is the exact
    shape of mistake a search-and-replace makes: `ALBUM_KEYS` was left private
    while `ARTIST_KEYS` beside it was made public, and nothing in the diff
    looked wrong.

    Deliberately narrow. Only top-level `object` declarations, only members
    indented exactly one level, and only qualified `Type.member` reads -- so a
    private helper called from inside its own object, which is the overwhelming
    majority of them, is never mentioned.
    """
    # Type name -> its private members, for objects only. Anything nested or
    # unusually formatted simply does not match and is skipped.
    privates: dict[str, dict[str, str]] = {}
    for kt, lines in sources().items():
        current: str | None = None
        for line in lines:
            if line.startswith("object "):
                m = DECLARING_TYPE.match(line)
                current = m.group(1) if m else None
                continue
            if line and not line[0].isspace():
                current = None
                continue
            if current:
                m = PRIVATE_MEMBER.match(line)
                if m:
                    privates.setdefault(current, {})[m.group(1)] = kt
    if not privates:
        return {}

    problems: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        body = "\n".join(lines)
        for type_name, members in privates.items():
            for member, declared_in in members.items():
                if kt == declared_in:
                    continue
                if re.search(rf"\b{type_name}\.{member}\b", body):
                    problems[kt].add(
                        f"reads {type_name}.{member}, which is private in {declared_in}"
                    )
    return problems


FUN_START = re.compile(
    r"^(\s*)((?:(?:private|internal|public|protected|override|open|abstract|suspend|inline|operator|tailrec)\s+)*)"
    r"fun\s+(?:<[^>]*>\s+)?(\w+)\s*\("
)
TOP_LEVEL_TYPE = re.compile(r"^(?:@\w+\s*)*(?:\w+\s+)*?(?:object|class|interface)\s+(\w+)")


def duplicate_declarations() -> dict[str, set[str]]:
    """
    The same function declared twice in one type.

    Kotlin calls it "Conflicting overloads" and points at both, which is clear
    enough -- but only after a push and a CI run. The mistake behind it is
    adding a helper without checking whether one already exists, and on a
    900-line ViewModel that is an easy thing to do twice.

    Signatures are compared as text after collapsing whitespace, so genuine
    overloads differing by parameter type are left alone, and the enclosing
    top-level type is part of the key so two nested classes in one file may
    each have their own.
    """
    problems: dict[str, set[str]] = collections.defaultdict(set)

    for kt, lines in sources().items():
        seen: dict[tuple[str, str], int] = {}
        enclosing = ""
        i = 0
        while i < len(lines):
            line = lines[i]
            if line and not line[0].isspace():
                m = TOP_LEVEL_TYPE.match(line)
                if m:
                    enclosing = m.group(1)

            start = FUN_START.match(line)
            if not start:
                i += 1
                continue

            # Overrides are skipped, and that is what makes this usable rather
            # than noisy. A file holds several anonymous `object : Listener`
            # blocks -- twelve Migrations in RoamDatabase, two player listeners
            # in RoamLibraryService -- each legitimately implementing the same
            # method, and tracking those scopes properly would mean parsing
            # braces. The mistake being hunted here is adding a helper that
            # already exists, which is never an override.
            if "override" in start.group(2):
                i += 1
                continue

            # Parameter lists wrap, so gather until the parens balance.
            chunk, depth, j = "", 0, i
            while j < len(lines):
                chunk += lines[j]
                depth += lines[j].count("(") - lines[j].count(")")
                if depth <= 0 and "(" in chunk:
                    break
                j += 1

            signature = re.sub(r"\s+", " ", chunk[chunk.index("fun "):]).strip()
            # Bodies and defaults are not part of what makes two declarations
            # conflict; the parameter list is.
            signature = signature.split(")", 1)[0] + ")"
            key = (enclosing, signature)
            if key in seen:
                problems[kt].add(
                    f"{signature} is declared twice in {enclosing or 'this file'} "
                    f"(lines {seen[key] + 1} and {i + 1})"
                )
            else:
                seen[key] = i
            i = j + 1

    return problems


def _decomment(text: str) -> str:
    """
    Code only: comments and string CONTENTS blanked, everything else in place.

    Both have to go. A KDoc sentence and a filename in a test fixture read
    exactly like code to a regex -- "Disc 1/01 Track.mp3" was reported as an
    unimported `Track` until strings were included here.

    Every character is replaced by a space rather than deleted, and newlines
    are kept, so offsets and line numbers still refer to the real file. A
    scrubber that shortens the text reports the wrong line, which is worse
    than not reporting at all.
    """
    out = list(text)
    i = 0
    n = len(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        two = text[i : i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif two == "/*":
            depth = 1
            j = i + 2
            while j < n and depth:                  # Kotlin block comments nest
                if text[j : j + 2] == "/*":
                    depth += 1
                    j += 2
                elif text[j : j + 2] == "*/":
                    depth -= 1
                    j += 2
                else:
                    j += 1
            blank(i, j)
            i = j
        elif text[i : i + 3] == '"""':
            j = text.find('"""', i + 3)
            if j < 0:
                j = n
            else:
                j += 3
                # A raw string ends at the LAST quote of a run, not the first
                # three: `""""x""""` is a raw string whose content is `"x"`.
                # Stopping early leaves a stray quote that opens a string over
                # the code after it -- which is how ten closing parens in
                # LibraryDocsTest went missing.
                while j < n and text[j] == '"':
                    j += 1
            blank(i, j)
            i = j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                if text[j] == "\\":
                    j += 1
                if text[j : j + 1] == "\n":         # unterminated: do not run on
                    break
                j += 1
            blank(i, min(j + 1, n))
            i = j + 1
        elif text[i] == "'":
            # A char literal, which Kotlin has and which holds brackets:
            # out.indexOf('{') is a real line in this repo, and counting that
            # brace made a balanced file look one short.
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                if text[j] == "\\":
                    j += 1
                j += 1
            if j < n and text[j] == "'":
                blank(i, j + 1)
                i = j + 1
            else:
                i += 1                              # a stray quote, not a literal
        else:
            i += 1
    return "".join(out)


def _arg_text(src: str, open_paren: int) -> tuple[str | None, int]:
    """The text inside the parens starting at [open_paren], nesting-aware."""
    depth = 0
    i = open_paren
    out: list[str] = []
    while i < len(src):
        ch = src[i]
        if ch == '"':                       # a string may hold any bracket
            out.append(ch)
            i += 1
            while i < len(src) and src[i] != '"':
                if src[i] == "\\":
                    out.append(src[i])
                    i += 1
                if i < len(src):
                    out.append(src[i])
                    i += 1
            if i < len(src):
                out.append(src[i])
                i += 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
            if depth == 0:
                return "".join(out)[1:], i
        out.append(ch)
        i += 1
    return None, i


def _split_args(text: str) -> list[str]:
    """Top-level comma split -- a nested call's commas are not ours."""
    parts: list[str] = []
    depth = 0
    cur = ""
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    parts.append(cur)
    return [p for p in parts if p.strip()]


HAS_DEFAULT = re.compile(r"(?<![=!<>])=(?!=)")
PARAM_NAME = re.compile(r"(?:vararg\s+)?(?:\w+\s+)*?([A-Za-z_]\w*)\s*:")
NAMED_ARG = re.compile(r"\s*([A-Za-z_]\w*)\s*=(?!=)")
CALL = re.compile(r"(?<![\w.])(?:[A-Za-z_]\w*\.)?([a-z]\w*)\s*\(")
DECL = re.compile(r"\bfun\s+(?:<[^>]+>\s*)?(?:[A-Za-z_][\w.]*\.)?([a-z]\w*)\s*\(")

# Below this, a name is far more likely to collide with a library function of
# the same name than to be the one meant -- BrowseTree.item(id) against
# LazyGridScope.item(span). Wide functions are also where this bug actually
# lives: nobody forgets an argument to a function that takes two.
MIN_PARAMS_TO_JUDGE = 4


def missing_arguments() -> dict[str, set[str]]:
    """
    A fully-named call that does not pass every required parameter.

    Kotlin says "No value passed for parameter 'x'", which is perfectly clear
    -- once CI has run. The mistake behind it is widening a signature and
    missing one of its call sites, and a DAO method called from two places is
    exactly the shape that hides one: `applyUserEdit` grew to sixteen
    parameters and the bulk-album path kept passing nine.

    Only fully-named calls are judged, because a positional call cannot be
    matched to parameters without resolving types, and only names declared
    exactly once in the repo, because an overload set needs the same. Both
    limits cost recall and buy silence, which is what makes a check like this
    worth running.
    """
    declared: dict[str, list[tuple[str, set[str]]]] = collections.defaultdict(list)
    for kt, lines in sources().items():
        src = _decomment("\n".join(lines))
        for m in DECL.finditer(src):
            params, _ = _arg_text(src, m.end() - 1)
            if params is None:
                continue
            required = {
                name.group(1)
                for param in _split_args(params)
                if param.strip() and not HAS_DEFAULT.search(param)
                for name in [PARAM_NAME.match(param.strip())]
                if name
            }
            declared[m.group(1)].append((kt, required))

    problems: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        src = _decomment("\n".join(lines))
        for m in CALL.finditer(src):
            name = m.group(1)
            if len(declared.get(name, [])) != 1:
                continue
            _, required = declared[name][0]
            if len(required) < MIN_PARAMS_TO_JUDGE:
                continue
            args, _ = _arg_text(src, m.end() - 1)
            if args is None:
                continue
            parts = _split_args(args)
            passed = {n.group(1) for p in parts for n in [NAMED_ARG.match(p)] if n}
            if not parts or len(passed) != len(parts):
                continue                    # positional somewhere: not ours to judge
            missing = required - passed
            if missing:
                line = src[: m.start()].count("\n") + 1
                problems[kt].add(
                    f"line {line}: {name}(...) does not pass "
                    f"{', '.join(sorted(missing))}"
                )
    return problems


def unimported_types() -> dict[str, set[str]]:
    """
    A repo type used by name that the file never imported.

    "Unresolved reference 'Genres'" is the compiler's version, and the mistake
    behind it is always the same: a helper object gets used in a second file
    and the import does not follow it there. Cheap to check exactly, because
    every declaration in this repo has a known package.

    A name declared in the file itself, reachable through its own package, or
    imported from anywhere at all -- including a library package that happens
    to share the name, as kotlinx's Json does with ours -- is left alone.
    """
    home: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        text = "\n".join(lines)
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        for m in re.finditer(
            r"^(?:public\s+)?(?:sealed\s+|data\s+|enum\s+|annotation\s+)?"
            r"(?:object|class|interface)\s+([A-Z]\w*)",
            text,
            re.M,
        ):
            home[m.group(1)].add(pkg.group(1))

    problems: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        text = "\n".join(lines)
        pkg_match = re.search(r"^package\s+([\w.]+)", text, re.M)
        package = pkg_match.group(1) if pkg_match else ""
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        imported_names = {i.rsplit(".", 1)[-1] for i in imports}
        stars = {i[:-2] for i in imports if i.endswith(".*")}

        src = _decomment(text)
        local = set(re.findall(r"\b(?:object|class|interface|enum class)\s+(\w+)", src))
        for m in re.finditer(r"(?<![\w.])([A-Z]\w*)\.[a-z]", src):
            name = m.group(1)
            if name in local or name in imported_names or name not in home:
                continue
            packages = home[name]
            if package in packages or (packages & stars):
                continue
            line = src[: m.start()].count("\n") + 1
            problems[kt].add(
                f"line {line}: '{name}' is used but not imported "
                f"(declared in {sorted(packages)[0]})"
            )
    return problems


_FUN = re.compile(r"\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(")
_ASSIGN = re.compile(r"^\s*(\w+)\s*(?:=(?!=)|\+=|-=|\*=|/=)")


def _body_span(src: str, after: int) -> tuple[int, int] | None:
    """The {...} span of a function whose parameter list closed at [after]."""
    open_brace = src.find("{", after)
    if open_brace < 0:
        return None
    # An expression body, or the next declaration entirely: either way this
    # function has no block to scan.
    gap = src[after:open_brace]
    if "=" in gap.replace("==", "") or "fun " in gap or ";" in gap:
        return None
    depth = 0
    for i in range(open_brace, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return open_brace, i
    return None


def reassigned_parameters() -> dict[str, set[str]]:
    """
    A function parameter being assigned to. Kotlin parameters are `val`.

    "Val cannot be reassigned", and the shape that causes it is specific: a
    function threads an accumulator through as a parameter, and an edit later
    adds `report = report.copy(...)` to it as though it were the local `var` the
    caller holds. It reads perfectly and it does not compile.

    Only statement-level assignments count, so a named argument -- `copy(year =
    ...)` where the enclosing function also has a `year` parameter -- is not it.
    A name the body redeclares as its own local is shadowing, which is legal.
    """
    problems: dict[str, set[str]] = collections.defaultdict(set)
    for kt, lines in sources().items():
        src = _decomment("\n".join(lines))
        for m in _FUN.finditer(src):
            args, close = _arg_text(src, m.end() - 1)
            if args is None:
                continue
            names: set[str] = set()
            for arg in _split_args(args):
                if ":" not in arg:
                    continue
                head = arg.split(":", 1)[0].strip().split()
                if head and re.fullmatch(r"\w+", head[-1]):
                    names.add(head[-1])
            span = _body_span(src, close)
            if not names or span is None:
                continue
            body = src[span[0] : span[1]]
            names = {n for n in names if not re.search(rf"\b(?:val|var)\s+{n}\b", body)}
            if not names:
                continue

            depth = 0
            for line in body.split("\n"):
                hit = _ASSIGN.match(line)
                if depth == 0 and hit and hit.group(1) in names:
                    at = src[: span[0]].count("\n") + 1 + body[: body.find(line)].count("\n")
                    problems[kt].add(
                        f"near line {at}: '{hit.group(1)}' is a parameter of "
                        f"{m.group(1)}() and cannot be reassigned"
                    )
                depth += line.count("(") - line.count(")")
    return problems


def main() -> int:
    problems: dict[str, set[str]] = collections.defaultdict(set)

    for module in modules():
        gradle_path = f"{module}/build.gradle.kts"
        with open(gradle_path, encoding="utf-8", errors="ignore") as fh:
            gradle = fh.read()
        found = imports_of(module)

        for prefix, wants in NEEDS:
            if any(i.startswith(prefix) for i in found):
                if not any(w in gradle for w in wants):
                    problems[module].add(f"imports {prefix}* but declares none of {', '.join(wants)}")

        problems[module] |= leaked_supertypes(module, found, gradle)

    for path, issues in smart_cast_problems(cross_module_nullables()).items():
        problems[path] |= issues

    for path, issues in private_leaks().items():
        problems[path] |= issues

    for path, issues in duplicate_declarations().items():
        problems[path] |= issues

    for path, issues in missing_arguments().items():
        problems[path] |= issues

    for path, issues in unimported_types().items():
        problems[path] |= issues

    for path, issues in reassigned_parameters().items():
        problems[path] |= issues

    problems = {m: v for m, v in problems.items() if v}

    if not problems:
        print(
            "check-deps: no dependency, supertype, smart-cast, visibility, "
            "duplicate, argument, import or reassignment problems detected"
        )
        return 0

    print("check-deps: possible problems\n")
    for module in sorted(problems):
        print(f"  {module}")
        for issue in sorted(problems[module]):
            print(f"      {issue}")
    print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
