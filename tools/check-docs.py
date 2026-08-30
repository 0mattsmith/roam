#!/usr/bin/env python3
"""
Checks the example documents against the contract they are examples OF.

These files exist to be copied. An external tool -- TAME, or anything else --
is written by reading them, so a field missing from an example is a field
missing from every document that tool will ever generate. That is not a
documentation problem, it is a data problem arriving a year later.

It has already happened once. The examples were written without
`previous_artwork`, `album_sort` and the per-entry `artist`, all three of which
DocBuilder emits and LibraryDocs reads, and the omission was invisible because
the examples parse perfectly and look complete. Nothing compared them to
anything.

So the key orders in DocBuilder are the source of truth -- they are what Roam
actually writes -- and this compares the examples against them in both
directions: a key the contract does not know is a typo, and a key the contract
has and the example lacks is a field somebody will not implement.
"""

import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUILDER = ROOT / "data/catalog/src/main/java/app/roam/data/catalog/metadata/DocBuilder.kt"
EXAMPLES = ROOT / "docs/examples"

# Keys a complete example is allowed not to show, with the reason. Absence is
# the demonstration in each case, so requiring them would be requiring a worse
# example.
OPTIONAL = {
    "external": "the away-from-the-album-folder case, shown in the spec instead",
    "start_at": "trim points are unset on almost everything",
    "end_at": "trim points are unset on almost everything",
}


def key_lists() -> dict[str, list[str]]:
    """Pull the key orders out of DocBuilder rather than restating them here."""
    source = BUILDER.read_text(encoding="utf-8")
    found = {}
    for name in ("ALBUM_KEYS", "ARTIST_KEYS", "ENTRY_KEYS", "TRACK_KEYS"):
        match = re.search(rf"val {name} = listOf\((.*?)\)", source, re.S)
        if not match:
            print(f"check-docs: could not find {name} in DocBuilder")
            sys.exit(2)
        found[name] = re.findall(r'"([^"]+)"', match.group(1))
    return found


def classify(path: pathlib.Path, doc: dict) -> str | None:
    """Which contract this document is meant to satisfy."""
    if path.name == "artist.json":
        return "ARTIST_KEYS"
    if path.name == "album.json":
        return "ALBUM_KEYS"
    if "PLAYLISTS" in path.parts or path.name == "tagmap.json":
        return None                      # their own shapes, not this contract
    if "audio_file" in doc or "track_number" in doc:
        return "TRACK_KEYS"
    return None


def main() -> int:
    keys = key_lists()
    problems: list[str] = []

    for path in sorted(EXAMPLES.rglob("*.json")):
        try:
            doc = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as bad:
            problems.append(f"{path.relative_to(ROOT)}: will not parse -- {bad}")
            continue

        which = classify(path, doc)
        if which is None:
            continue

        rel = path.relative_to(ROOT)
        expected = keys[which]

        for key in doc:
            if key not in expected:
                problems.append(f"{rel}: '{key}' is not in {which}")
        for key in expected:
            if key not in doc and key not in OPTIONAL:
                problems.append(f"{rel}: missing '{key}' -- in {which} and not shown")

        # Order matters as much as presence: a patch re-emits in this order, so
        # an example in a different one teaches the wrong shape and produces a
        # diff on every line the first time Roam rewrites it.
        present = [k for k in doc if k in expected]
        if present != [k for k in expected if k in doc]:
            problems.append(f"{rel}: keys are not in {which} order")

        if which == "ALBUM_KEYS":
            entries = doc.get("tracks") or []
            for index, entry in enumerate(entries):
                for key in entry:
                    if key not in keys["ENTRY_KEYS"]:
                        problems.append(f"{rel}: tracks[{index}] has unknown '{key}'")
                for key in keys["ENTRY_KEYS"]:
                    if key not in entry and key not in OPTIONAL:
                        problems.append(f"{rel}: tracks[{index}] missing '{key}'")

    if not problems:
        print("check-docs: examples match the document contract")
        return 0

    print("check-docs: examples have drifted from DocBuilder\n")
    for line in problems[:60]:
        print(f"  {line}")
    if len(problems) > 60:
        print(f"  ... and {len(problems) - 60} more")
    return 1


if __name__ == "__main__":
    sys.exit(main())
