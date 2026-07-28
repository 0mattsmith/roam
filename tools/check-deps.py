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

SMART_CAST = re.compile(r"if\s*\(\s*(\w+)\.(\w+)\s*!=\s*null\s*\)")
NULLABLE_PROP = re.compile(r"\s*(?:val|var)\s+(\w+)\s*:\s*[\w<>, ]+\?")


def cross_module_nullables() -> set[str]:
    """Nullable properties declared in :core:* and :data:* modules."""
    found: set[str] = set()
    for pattern in ("core/*/src/**/*.kt", "data/*/src/**/*.kt"):
        for kt in glob.glob(pattern, recursive=True):
            with open(kt, encoding="utf-8", errors="ignore") as fh:
                for line in fh:
                    m = NULLABLE_PROP.match(line)
                    if m:
                        found.add(m.group(1))
    return found


def smart_cast_problems(nullables: set[str]) -> dict[str, set[str]]:
    """
    Kotlin refuses to smart-cast a property declared in another module: it
    cannot prove the getter is stable. `if (x.p != null) use(x.p)` compiles
    inside the declaring module and fails outside it, with an error that reads
    like a type problem rather than a module-boundary one. Capture to a local.
    """
    problems: dict[str, set[str]] = collections.defaultdict(set)
    for pattern in ("feature/*/src/**/*.kt", "app/src/**/*.kt"):
        for kt in glob.glob(pattern, recursive=True):
            with open(kt, encoding="utf-8", errors="ignore") as fh:
                lines = fh.read().split("\n")
            for i, line in enumerate(lines):
                m = SMART_CAST.search(line)
                if not m:
                    continue
                receiver, prop = m.groups()
                if prop not in nullables:
                    continue
                body = "\n".join(lines[i + 1 : i + 15])
                if re.search(rf"\b{receiver}\.{prop}\b", body):
                    problems[kt].add(
                        f"line {i + 1}: {receiver}.{prop} is smart-cast across a module "
                        f"boundary -- capture it to a local val"
                    )
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

    problems = {m: v for m, v in problems.items() if v}

    if not problems:
        print("check-deps: no dependency, supertype or smart-cast problems detected")
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
