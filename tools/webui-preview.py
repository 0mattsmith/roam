#!/usr/bin/env python3
"""
Drive the web interface's front end without a phone.

Reads the three real assets out of :feature:webui and writes self-contained
HTML with `fetch` stubbed, so the whole page -- the gate, the queue, a job
list, paging -- can be opened in any browser. Nothing is duplicated: the CSS
and JS are the shipping files, read at generation time, so the preview cannot
drift from what the server serves.

This exists because of a bug a phone was a terrible way to find. `.gate` and
`.queue` set `display`, which out-specifies the browser's `[hidden]` rule, so
`.hidden = true` changed the attribute and nothing on screen: the PIN was
accepted and the gate stayed up at full height with the unlocked page below
the fold. From the outside, an Unlock button that did nothing.

Usage:
    python3 tools/webui-preview.py [outdir]

Writes webui-gate.html and webui-queue.html. Open either one.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.parse

ASSETS = "feature/webui/src/main/assets/web"

# A believable library, so the page is judged at the size it will really be
# rather than with three rows in it.
COUNTS = {
    "needs-look": 41,
    "no-year": 180,
    "no-genre": 263,
    "no-cover": 12,
    "frozen": 4,
    # Zero on purpose: a finished job must still render, and it is the state
    # most likely to be got wrong because it is the one nobody mocks up.
    "missing": 0,
}

TRACKS = [
    ("Champagne Supernova", "Oasis", "(What's the Story) Morning Glory?", None, None, "PATH_INFERRED"),
    ("Enjoy the Silence", "Depeche Mode", "Violator", 1990, None, "OK"),
    ("Track 11", "Unknown artist", "Songs of Faith and Devotion", None, None, "FAILED"),
    ("Rock 'n' Roll Star", "Oasis", "Definitely Maybe", 1994, None, "OK"),
    ("Policy of Truth", "Depeche Mode", "Violator", 1990, "Synth-pop", "PENDING"),
]

ALBUMS = [
    ("Violator", "Depeche Mode", 9, 1990, "Depeche Mode/Violator"),
    ("Kid A", "Radiohead", 11, 2000, "Radiohead/Kid A"),
    ("Low", "David Bowie", 11, 1977, "Bowie, David/Low"),
]


def stub(unlocked: bool) -> str:
    """The fake server. Installed before app.js, which calls it on load."""
    tracks = [
        {
            "id": i + 1, "title": t, "artist": a, "album": al,
            **({"year": y} if y else {}), **({"genre": g} if g else {}),
            "tagState": s,
        }
        for i, (t, a, al, y, g, s) in enumerate(TRACKS)
    ]
    albums = [
        {"id": i + 1, "title": t, "artist": a, "trackCount": n, "year": y, "folderPath": p}
        for i, (t, a, n, y, p) in enumerate(ALBUMS)
    ]
    return f"""
<script>
// Stubbed API. Replaces fetch before app.js runs, and answers exactly what the
// real server answers -- including the 401 that puts the gate up, which is the
// only thing that makes the gate reachable at all.
(function () {{
  var unlocked = {json.dumps(unlocked)};
  var PIN = '4817';
  var counts = {json.dumps(COUNTS)};
  var tracks = {json.dumps(tracks)};
  var albums = {json.dumps(albums)};

  function reply(status, body) {{
    return Promise.resolve({{
      status: status, ok: status >= 200 && status < 300,
      statusText: String(status),
      json: function () {{ return Promise.resolve(body); }}
    }});
  }}

  window.fetch = function (url, options) {{
    options = options || {{}};
    var method = options.method || 'GET';

    if (url === '/api/pin' && method === 'POST') {{
      var offered = String(options.body || '').replace('pin=', '');
      if (offered !== PIN) return reply(403, {{ error: 'Wrong PIN' }});
      unlocked = true;
      return reply(200, {{ ok: true }});
    }}
    if (!unlocked) return reply(401, {{ error: 'PIN required' }});

    if (url === '/api/counts') {{
      return reply(200, {{ counts: counts, library: {{ tracks: 2132 }} }});
    }}
    var job = url.match(/^\\/api\\/job\\/([a-z-]+)/);
    if (job) {{
      var slug = job[1];
      var offset = Number((url.match(/offset=(\\d+)/) || [0, 0])[1]);
      var isAlbums = slug === 'no-cover';
      var rows = isAlbums ? albums : tracks;
      return reply(200, {{
        job: slug, kind: isAlbums ? 'albums' : 'tracks',
        total: counts[slug] || 0, offset: offset,
        rows: counts[slug] === 0 ? [] : rows
      }});
    }}
    return reply(404, {{ error: 'No such thing' }});
  }};
}})();
</script>
"""


def build(unlocked: bool, open_job: str | None = None) -> str:
    html = open(f"{ASSETS}/index.html", encoding="utf-8").read()
    css = open(f"{ASSETS}/style.css", encoding="utf-8").read()
    js = open(f"{ASSETS}/app.js", encoding="utf-8").read()

    # The real files, inlined rather than linked: a preview that fetched them
    # would need a server, and the point is to need nothing.
    html = html.replace(
        '<link rel="stylesheet" href="/style.css">', f"<style>\n{css}\n</style>"
    )

    # The icon inlined for the same reason; the manifest dropped because an
    # installable app is the one thing a file:// preview genuinely cannot be,
    # and leaving the link in only buys a console error.
    icon = open(f"{ASSETS}/icon.svg", encoding="utf-8").read()
    html = html.replace(
        '<link rel="icon" href="/icon.svg" type="image/svg+xml">',
        '<link rel="icon" type="image/svg+xml" href="data:image/svg+xml;utf8,'
        + urllib.parse.quote(icon)
        + '">',
    )
    for dead in (
        '<link rel="manifest" href="/manifest.webmanifest">',
        '<link rel="apple-touch-icon" href="/apple-touch-icon.png">',
    ):
        html = html.replace(dead, "")
    banner = (
        '<div style="position:fixed;right:10px;bottom:10px;z-index:99;'
        'font:11px system-ui;color:#5F7178;border:1px solid #24333A;'
        'border-radius:6px;padding:4px 8px;background:#0C1215">'
        f'preview &middot; stubbed API &middot; PIN 4817</div>'
    )
    # openJob is a top-level function in app.js, so the row view can be reached
    # without a click -- which is the only way to see it in a screenshot.
    drive = f"<script>openJob({json.dumps(open_job)}, 0);</script>" if open_job else ""
    html = html.replace(
        '<script src="/app.js"></script>',
        f"{banner}{stub(unlocked)}<script>\n{js}\n</script>{drive}",
    )
    return html


def main() -> int:
    if not os.path.isdir(ASSETS):
        print(f"webui-preview: run me from the repo root ({ASSETS} not found)")
        return 1

    outdir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(outdir, exist_ok=True)
    variants = (
        ("webui-gate.html", False, None),
        ("webui-queue.html", True, None),
        ("webui-rows.html", True, "needs-look"),
    )
    for name, unlocked, job in variants:
        path = os.path.join(outdir, name)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(build(unlocked, job))
        print(f"webui-preview: wrote {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
