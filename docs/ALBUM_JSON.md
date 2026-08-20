# `album.json` — the metadata contract

The album folder owns its own metadata. Roam reads this file, the downloader
writes it, and an external editor may rewrite it freely. It is the reason a
reinstall no longer costs a library's worth of corrections: the edits live
beside the music, not in a database on one phone.

This document is the agreement between all three. Anything not written here is
not guaranteed.

---

## Where it lives

```
Music/
└── Royal Blood/
    ├── artist.json
    ├── artist.jpg
    ├── banner.jpg
    ├── logo.png
    └── Typhoons (2021)/
        ├── album.json
        ├── cover.jpg
        ├── 01 - Trouble's Coming.mp3
        └── ...
```

Album folders are `ALBUM (YEAR)` inside `ARTIST`. Roam checks for the folder
before creating it, and only creates one once the metadata is settled — a
folder is never named from a guess.

Multi-disc sets put tracks in `CD1/`, `CD2/` subfolders. The `file` field
carries that prefix; nothing else changes.

---

## Precedence — the rule everything else follows

1. **`album.json`** — what you decided
2. **embedded tags** in the audio file
3. **the folder path** — artist and album inferred from the directory names

This is a deliberate inversion of the old behaviour, which was "tags win, the
path fills the gaps". The json now outranks both, because it is the only one of
the three that represents a decision rather than an accident of how a file was
encoded. A file whose ID3 says `Track 03` and whose json says
`Don't Look Back in Anger` is titled from the json, every time.

Roam does not need the file's tags to be correct. It never has to rewrite them
either, which is what makes an edit cheap: changing a title is a few hundred
bytes of json, not a re-upload of a 40 MB album.

---

## Schema

### Top level

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `schema` | integer | yes | Currently `1`. Lets a reader refuse a file it does not understand rather than misreading it. |
| `album_artist` | string | yes | The artist the ALBUM belongs to. On a compilation this is what holds it together — see below. |
| `album_title` | string | yes | Without the year. The year lives in its own field and in the folder name. |
| `year` | integer | no | Year of THIS release. |
| `original_year` | integer | no | First release of the material. See "Two years". |
| `genre` | string | no | Free text. |
| `is_compilation` | boolean | no | Defaults `false`. |
| `total_discs` | integer | no | Derived from `tracks` when absent. |
| `total_tracks` | integer | no | Derived from `tracks` when absent. |
| `cover_art` | string | no | Filename relative to the album folder. Defaults to `cover.jpg`. |
| `tracks` | array | yes | May be empty; an album with no entries is legal and simply has no overrides. |

### Each track

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `file` | string | **yes** | Path relative to the album folder, forward slashes. THE identity of the entry. |
| `title` | string | yes | |
| `artist` | string | no | Falls back to `album_artist`. Set it per track on a compilation. |
| `disc` | integer | no | Defaults `1`. |
| `track` | integer | no | Position within the disc. |
| `year` | integer | no | Overrides the album's year for this track. Rare, but a compilation wants it. |
| `genre` | string | no | Overrides the album's genre. |
| `duration` | string | no | `mm:ss` or `hh:mm:ss`. INFORMATIONAL ONLY — see the warning below. |
| `start_at` | string | no | Trim point. **Omit unless deliberately trimming.** |
| `end_at` | string | no | Trim point. **Omit unless deliberately trimming.** |

---

## `start_at` / `end_at` are trim points, not the track length

**This is the easiest way to break a whole library, so it gets its own
section.**

`end_at` tells Roam to STOP THERE. It is for a hidden track, a locked groove,
or a minute of applause you never want to hear. It is not a description of how
long the file is.

Writing `end_at` equal to the track's duration means every track in your
library stops fractionally early — a stored duration is rounded, and the last
moment of the song gets clipped. Roam's own gotchas list already carries this
one: *"The last second is clipped off every track — `endMs` fell back to the
stored duration."*

So:

```jsonc
// WRONG - clips the end of every track
{ "file": "03 - Wonderwall.mp3", "title": "Wonderwall", "end_at": "04:18" }

// RIGHT - no trim, plays the whole file
{ "file": "03 - Wonderwall.mp3", "title": "Wonderwall" }

// RIGHT - a real trim, skipping two minutes of silence before a hidden track
{ "file": "12 - Champagne Supernova.mp3", "title": "Champagne Supernova",
  "start_at": "00:00", "end_at": "07:27" }
```

If you want the length recorded for your own reference, use `duration`. Roam
reads it for display only and never turns it into a clip.

---

## Two years

`year` is the year of the release the file came from. `original_year` is when
the material first appeared.

They differ more often than you would think, and always in the cases that
matter to a decade playlist:

- a 2014 remaster of a 1995 album — `year: 2014`, `original_year: 1995`
- *100 Hits of the 80s*, released 2005 — the album is `year: 2005`, but each
  track carries its own `original_year`

Roam's decade playlists read `original_year` and fall back to `year`. Without
that distinction an 80s playlist fills up with whichever compilation the tracks
were ripped from, which is precisely the wrong answer.

Both are optional. Set `original_year` when you know it and it differs.

---

## Matching entries to files

Entries are matched to audio files by **`file`**, compared case-insensitively
after normalising separators. Not by track number, and not by title — both of
those are things an edit is likely to be changing.

- A `file` with no matching audio file on disk is **ignored**, not an error.
  Roam will not invent a track that is not there.
- An audio file with no entry falls through to its embedded tags, then to the
  path. It still appears in the library.
- Neither case is a failure. A partial `album.json` is legitimate: correcting
  three titles on a fourteen-track album needs three entries.

---

## Rewriting the file

Anything writing `album.json` must **preserve fields it does not recognise**,
at both album and track level. You and Roam will not always be on the same
version, and a writer that drops what it did not understand would silently
undo the other one's work.

Roam writes the file with two-space indent and a trailing newline, keys in the
order given in this document, so that a diff between two saves shows what
actually changed.

---

## `artist.json`

Optional, and a manifest rather than a source of truth. Its purpose is to point
at images that may not follow the usual names.

```json
{
  "schema": 1,
  "name": "Royal Blood",
  "sort_as": "Royal Blood",
  "image": "artist.jpg",
  "banner": "banner.jpg",
  "logo": "logo.png"
}
```

All fields optional. Where it is absent, Roam falls back to the filename
conventions it already uses (`artist.jpg`, `folder.jpg`, `banner.jpg`,
`fanart.jpg`, `logo.png`), so an artist folder without one behaves exactly as
it does today.

`sort_as` files the artist under a different name for ordering — the mechanism
that puts The Beatles under B.

---

## What does NOT go in these files

**User state.** Loved flags, play counts, skip counts, last played, and hidden
tracks are yours rather than the album's, and they change constantly while the
metadata sits still. Mixing them in would mean rewriting `album.json` every
time a song finishes.

They belong in `.roam/state.json`, which is a separate problem with a separate
lifecycle — see the phase 6 notes on per-field timestamps and summed play
counts.

**Lyrics.** Already handled: a `.lrc` or `.txt` beside the audio file, which is
what every other player reads too.

---

## Worked example

```json
{
  "schema": 1,
  "album_artist": "Royal Blood",
  "album_title": "Typhoons",
  "year": 2021,
  "genre": "Alternative Rock",
  "is_compilation": false,
  "total_discs": 1,
  "total_tracks": 3,
  "cover_art": "cover.jpg",
  "tracks": [
    { "file": "01 - Trouble's Coming.mp3", "title": "Trouble's Coming",
      "disc": 1, "track": 1, "duration": "03:56" },
    { "file": "02 - Oblivion.mp3", "title": "Oblivion",
      "disc": 1, "track": 2, "duration": "03:20" },
    { "file": "03 - Typhoons.mp3", "title": "Typhoons",
      "disc": 1, "track": 3, "duration": "03:53" }
  ]
}
```

Note what is absent: no `start_at`, no `end_at`, no `artist` on the tracks. All
three are inherited or unset, and that is the normal case.
