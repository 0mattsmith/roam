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

    problems = {m: v for m, v in problems.items() if v}

    if not problems:
        print("check-deps: no dependency, supertype, smart-cast or visibility problems detected")
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
