#!/usr/bin/env python3
"""
Every XML in the repo actually parses.

Not a heuristic. The manifest merger runs a real XML parser, so a malformed
manifest or resource file is a certain build failure -- and a failure that says
nothing about Android:

    SAXParseException; lineNumber: 9; columnNumber: 58;
    The string "--" is not permitted within comments.

That one cost a CI round trip. This codebase writes "--" as an em-dash in
hundreds of Kotlin comments, where it is fine, and the habit carried into an
AndroidManifest comment, where XML forbids it outright -- a comment may not
contain "--" anywhere, because that is how the parser finds the end of one.

Checked here rather than left to the compiler for the same reason as
check-schema: a second locally beats five minutes of CI, and the message you
get locally names the file you just edited.
"""

from __future__ import annotations

import os
import sys
import xml.dom.minidom

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "node_modules"}


def xml_files() -> list[str]:
    found = []
    for root, dirs, files in os.walk("."):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if name.endswith(".xml"):
                found.append(os.path.join(root, name).replace("\\", "/").removeprefix("./"))
    return sorted(found)


def problems() -> dict[str, str]:
    """
    Path -> what the parser said.

    minidom rather than ElementTree: it reports a line and column, which is the
    difference between "fix the manifest" and "find the manifest".
    """
    found: dict[str, str] = {}
    for path in xml_files():
        try:
            xml.dom.minidom.parse(path)
        except Exception as e:  # noqa: BLE001 - any parse failure is the finding
            found[path] = str(e).splitlines()[0]
    return found


def double_hyphens(path: str) -> list[int]:
    """
    Lines inside an XML comment that contain "--", with the line numbers.

    Worked out separately rather than read out of the parser's message, because
    which parser you are standing in front of decides what it says: Python's
    expat calls it "invalid token", Xerces (which the manifest merger uses)
    names the rule. Finding it ourselves means the advice is the same either
    way, and it is the advice that saves the round trip.

    Scanned by hand rather than by regex: a comment's own text can contain
    anything, including the characters a regex would use as landmarks.
    """
    text = open(path, encoding="utf-8", errors="replace").read()
    hits: list[int] = []
    at = 0
    while True:
        start = text.find("<!--", at)
        if start < 0:
            return hits
        body_from = start + 4
        end = text.find("-->", body_from)
        body = text[body_from:] if end < 0 else text[body_from:end]
        for offset in range(len(body) - 1):
            if body[offset : offset + 2] == "--":
                hits.append(text.count("\n", 0, body_from + offset) + 1)
                break
        at = body_from if end < 0 else end + 3


def main() -> int:
    bad = problems()
    total = len(xml_files())

    if not bad:
        print(f"check-xml: {total} xml file(s) parse")
        return 0

    print("check-xml: malformed xml, which will fail the build\n")
    for path in sorted(bad):
        print(f"  {path}")
        print(f"      {bad[path]}")
        # The cause that is not self-explanatory, and the one this exists for.
        for line in double_hyphens(path):
            print(f'      line {line}: an XML comment contains "--".')
            print("      A comment may not contain it anywhere, because that is how")
            print("      the parser finds the end of one. Use a comma or a semicolon.")
    print()
    return 1


if __name__ == "__main__":
    sys.exit(main())
