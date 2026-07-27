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

    if not problems:
        print("check-deps: no missing dependencies detected")
        return 0

    print("check-deps: possible missing dependencies\n")
    for module in sorted(problems):
        print(f"  {module}")
        for issue in sorted(problems[module]):
            print(f"      {issue}")
    print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
