#!/usr/bin/env python3
"""
Generate the web interface's icons from the app's own launcher mark.

The paths below are lifted verbatim from
`app/src/main/res/drawable/ic_launcher_foreground.xml` and
`ic_launcher_background.xml`, on the same 512 viewport. Redrawing the mark by
eye would have produced something that looks like Roam's icon without being
it, and the difference shows up exactly where it is most annoying: side by side
in a taskbar with the phone screenshot next to it.

Everything here is generated, including the SVG -- so there is one place the
geometry lives, and it is this file. Re-run after changing the launcher icon:

    python3 tools/webui-icons.py

Writes into feature/webui/src/main/assets/web/.
"""

from __future__ import annotations

import os
import sys

try:
    import cairosvg
except ImportError:  # pragma: no cover - a developer machine thing
    print("webui-icons: needs cairosvg (pip install cairosvg)")
    sys.exit(1)

ASSETS = "feature/webui/src/main/assets/web"

INK = "#0C1215"
BAND = "#162024"

# The mark: a play triangle with two arcs to the right of it. Stroke widths and
# coordinates are the launcher icon's, unchanged.
MARK = """
  <path d="M285,166.7 A101.48,101.48 0 0 1 285,335.2"
        fill="none" stroke="#0D9488" stroke-width="22.36"
        stroke-linecap="round" stroke-opacity="0.85"/>
  <path d="M277.27,206.23 A55.04,55.04 0 0 1 277.27,295.67"
        fill="none" stroke="#2DD4BF" stroke-width="22.36"
        stroke-linecap="round"/>
  <path d="M184.4,182.15 L184.4,319.75 L254.9,250.95 Z"
        fill="#FFFFFF" stroke="#FFFFFF" stroke-width="22.36"
        stroke-linejoin="round"/>
"""


def svg(rounded: bool) -> str:
    """
    [rounded] is the normal icon; the square one is for `purpose: maskable`.

    A maskable icon is cropped to whatever shape the platform fancies -- a
    circle on most Android launchers -- so it must bleed to the edges and keep
    its content inside the middle 80%. The mark already sits well inside that,
    which is why the only difference here is the corner radius: rounding a
    maskable icon would leave four dark notches after the platform rounds it
    again.
    """
    corner = ' rx="112" ry="112"' if rounded else ""
    # The band is CLIPPED to the outline rather than given the same radius.
    # Rounding it directly curves its bottom corners too, which turns a flat
    # two-tone into a visible rounded rectangle sitting in the middle of the
    # icon -- the launcher icon's band is a plain rect that the adaptive mask
    # cuts, and this is the same thing said in SVG.
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <clipPath id="outline">
      <rect width="512" height="512"{corner}/>
    </clipPath>
  </defs>
  <g clip-path="url(#outline)">
    <rect width="512" height="512" fill="{INK}"/>
    <rect width="512" height="300" fill="{BAND}" fill-opacity="0.55"/>
  </g>
{MARK}</svg>
"""


# name -> (size, maskable). 192 and 512 are what an installable manifest is
# required to carry; 180 is what iOS reads from apple-touch-icon.
PNGS = {
    "icon-192.png": (192, False),
    "icon-512.png": (512, False),
    "icon-maskable-512.png": (512, True),
    "apple-touch-icon.png": (180, False),
}


def main() -> int:
    if not os.path.isdir(ASSETS):
        print(f"webui-icons: run me from the repo root ({ASSETS} not found)")
        return 1

    # The SVG is the favicon as well as the source: browsers have taken SVG
    # favicons for years, and it is the only one that stays sharp at whatever
    # size a tab strip decides to use.
    path = os.path.join(ASSETS, "icon.svg")
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(svg(rounded=True))
    print(f"webui-icons: wrote {path}")

    for name, (size, maskable) in PNGS.items():
        out = os.path.join(ASSETS, name)
        cairosvg.svg2png(
            bytestring=svg(rounded=not maskable).encode("utf-8"),
            write_to=out,
            output_width=size,
            output_height=size,
        )
        print(f"webui-icons: wrote {out} ({size}px)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
