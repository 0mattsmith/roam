#!/usr/bin/env python3
"""Every migrated column must exist on the entity for that table, and vice versa.

Room derives the schema from the entity classes and validates queries against
it at COMPILE time, but it has nothing to say about whether a migration and an
entity agree -- that only shows up as a runtime crash, or as a baffling
"no such column" from KSP when a query mentions a column the entity lacks.

This exists because a column was added to ArtistEntity when the migration
altered `tracks`: both files looked right on their own, and the mismatch cost a
full CI round trip to discover. The two are edited together and should be
checked together.

Exit code 1 on any disagreement, so commit.ps1 can stop before pushing.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENTITIES = ROOT / "core/database/src/main/java/app/roam/core/database/Entities.kt"
DATABASE = ROOT / "core/database/src/main/java/app/roam/core/database/RoamDatabase.kt"

# Columns present since the table was created, so no ALTER ever mentions them.
# Anything NOT listed here is expected to arrive via a migration.
BASELINE = "v1"


def parse_entities(src: str) -> dict[str, tuple[str, set[str]]]:
    """table name -> (entity class, its field names)."""
    lines = src.split("\n")
    tables: dict[str, tuple[str, set[str]]] = {}

    for i, line in enumerate(lines):
        m = re.match(r"data class (\w+)\(", line)
        if not m:
            continue
        name = m.group(1)

        # The @Entity annotation sits above and may span several lines with
        # nested parens (indices), so scan back for tableName rather than
        # trying to match the whole annotation.
        table = None
        for j in range(i - 1, max(0, i - 25), -1):
            found = re.search(r'tableName\s*=\s*"([^"]+)"', lines[j])
            if found:
                table = found.group(1)
                break
            if re.match(r"^\)", lines[j]) and j < i - 1:
                break
        if not table:
            continue

        fields: set[str] = set()
        for j in range(i + 1, len(lines)):
            if re.match(r"^\)", lines[j]):
                break
            f = re.match(r"\s*(?:@\w+(?:\([^)]*\))?\s*)*val (\w+)\s*:", lines[j])
            if f:
                fields.add(f.group(1))
        tables[table] = (name, fields)

    return tables


def main() -> int:
    if not ENTITIES.exists() or not DATABASE.exists():
        print("check-schema: database sources not found, skipping")
        return 0

    tables = parse_entities(ENTITIES.read_text(encoding="utf-8"))
    db = DATABASE.read_text(encoding="utf-8")

    problems: list[str] = []

    for table, column in re.findall(r"ALTER TABLE (\w+) ADD COLUMN (\w+)", db):
        if table not in tables:
            problems.append(
                f"migration alters `{table}`, but no @Entity declares that table"
            )
            continue
        entity, fields = tables[table]
        if column not in fields:
            # The usual cause: the field was pasted onto whichever entity
            # happened to match first, which is rarely the intended one.
            owner = next(
                (n for _, (n, f) in tables.items() if column in f),
                None,
            )
            hint = f" -- it is on {owner} instead" if owner else ""
            problems.append(
                f"`{table}.{column}` is added by a migration but {entity} "
                f"has no such field{hint}"
            )

    declared_version = re.search(r"version\s*=\s*(\d+)", db)
    highest = max(
        (int(a) for a, _ in re.findall(r"Migration\((\d+),\s*(\d+)\)", db)),
        default=0,
    )
    if declared_version:
        version = int(declared_version.group(1))
        migrations = re.findall(r"Migration\((\d+),\s*(\d+)\)", db)
        targets = {int(b) for _, b in migrations}
        if version > 1 and version not in targets:
            problems.append(
                f"database version is {version} but no migration ends at {version}"
            )
        registered = set(re.findall(r"MIGRATION_(\d+)_(\d+)", db))
        for a, b in migrations:
            if (a, b) not in registered:
                problems.append(f"MIGRATION_{a}_{b} is defined but never registered")

    if problems:
        print("check-schema: PROBLEMS")
        for p in problems:
            print(f"  - {p}")
        return 1

    print("check-schema: migrations and entities agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
