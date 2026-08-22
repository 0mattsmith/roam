# The metadata contract

Three files describe a library: one per artist, one per album, one per track.
Roam reads them, the downloader writes them, and an external editor may rewrite
any of them. This document is the agreement between all three.

It exists because metadata used to live only in Roam's database, where a
reinstall destroyed it. Now the corrections live beside the music and the
database is a cache that can be thrown away.

```
MUSIC/
└── Oasis/
    ├── artist.json
    ├── artist.jpg
    ├── logo.jpg
    └── Definitely Maybe (Deluxe Version) (1994)/
        ├── album.json
        ├── cover.jpg
        └── Disc 1 - Remastered Album/
            ├── 01 Rock 'n' Roll Star.mp3
            ├── 01 Rock 'n' Roll Star.json
            └── 01 Rock 'n' Roll Star.lrc
```

Album folders are `ALBUM (YEAR)` inside `ARTIST`. Disc subfolders may be named
however the release makes sense; nothing depends on their names.

---

## Which file is read when

**`album.json` is the index, and the only file read to browse.** It carries
everything a list needs, so opening an album costs ONE request no matter how
many tracks it holds.

**`<track>.json` is always written, and read on demand.** Every audio file has
one, so there is never a question of where a track's information lives. Roam
fetches it when it needs something the index does not carry, or when the index
is missing.

This split is the whole performance story. Roam reads from Drive over HTTP,
where the per-request overhead dominates: one file for a 44-track deluxe
edition is a fraction of a second, forty-four is most of a minute. Writing them
all costs nothing at browse time; reading them all would cost everything.

**Precedence, highest first:**

1. `<track>.json` — for fields only it carries, and when the index is absent
2. `album.json` — for everything it carries; this is what browsing uses
3. embedded tags in the audio file
4. the folder path

Where `album.json` and a track file disagree about a field they BOTH carry,
the index wins for browsing, because that is the copy Roam has in hand.
Anything writing these files must write both together so the question does not
arise — Roam always does.

---

## `artist.json`

```json
{
  "schema": 1,
  "artist_name": "Oasis",
  "active_from": 1991,
  "active_to": null,
  "debut_album": "Definitely Maybe",
  "debut_album_year": 1994,
  "total_studio_albums": 7,
  "artist_image": "artist.jpg",
  "artist_logo": "logo.jpg",
  "artist_banner": "banner.jpg",
  "sort_as": "Oasis"
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `schema` | integer | `1`. Lets a reader refuse a file it does not understand. |
| `artist_name` | string | |
| `active_from` / `active_to` | integer, null | `null` for still active. |
| `debut_album` / `debut_album_year` | string, integer | |
| `total_studio_albums` | integer | |
| `artist_image` | string | Square photo. |
| `artist_logo` | string | Transparent PNG usually. |
| `artist_banner` | string | Wide background. |
| `sort_as` | string | Files the artist under another name — The Beatles under B. |

Only `sort_as` and the three image fields change what Roam does today. The
biographical fields are read and stored, but there is nowhere to show them yet;
they are recorded now so the data is there when there is.

Where a field or the whole file is absent, Roam falls back to the filename
conventions it already uses: `artist.jpg`, `folder.jpg`, `banner.jpg`,
`fanart.jpg`, `logo.png`.

---

## `album.json`

```json
{
  "schema": 1,
  "album_artist": "Oasis",
  "album_title": "Definitely Maybe (Deluxe Version)",
  "year": 1994,
  "original_year": 1994,
  "genre": "Britpop",
  "is_compilation": false,
  "total_discs": 3,
  "total_tracks": 44,
  "cover_art": "cover.jpg",
  "tracks": [
    {
      "disc": 1,
      "track": 1,
      "title": "Rock 'n' Roll Star",
      "artist": "Oasis",
      "file": "Disc 1 - Remastered Album/01 Rock 'n' Roll Star.mp3",
      "track_meta": "Disc 1 - Remastered Album/01 Rock 'n' Roll Star.json",
      "lyrics": "Disc 1 - Remastered Album/01 Rock 'n' Roll Star.lrc"
    }
  ]
}
```

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `schema` | integer | yes | `1` |
| `album_artist` | string | yes | On a compilation this is what holds the album together. |
| `album_title` | string | yes | Without the year; that lives in its own field and the folder name. |
| `year` | integer | no | Year of THIS release. |
| `original_year` | integer | no | First release of the material. See "Two years". |
| `genre` | string | no | |
| `is_compilation` | boolean | no | Defaults `false`. Decides which name the folder takes — see "Uploading". |
| `total_discs` / `total_tracks` | integer | no | Derived from `tracks` when absent. |
| `cover_art` | string | no | Relative to the album folder. Defaults `cover.jpg`. |
| `tracks` | array | yes | May be empty. |

### Each entry

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `file` | string | **yes** | Relative to the album folder, forward slashes. THE identity of the entry. |
| `track_meta` | string | yes | Path to that track's own json. |
| `lyrics` | string, null | no | Path to the `.lrc` or `.txt`, `null` when there is none. |
| `title` | string | yes | |
| `artist` | string | no | Falls back to `album_artist`. Set per track on a compilation. |
| `disc` / `track` | integer | no | `disc` defaults to 1. |

`lyrics` saves a lookup, but is not the only way words are found: a `.lrc`
sharing the audio file's basename is still picked up when the field is absent
or stale. Files get dropped in without the index being updated.

---

## `<track>.json`

Named for the audio file, beside it. `01 Rock 'n' Roll Star.mp3` is accompanied
by `01 Rock 'n' Roll Star.json`.

```json
{
  "schema": 1,
  "title": "Rock 'n' Roll Star",
  "track_number": 1,
  "disc_number": 1,
  "artist": "Oasis",
  "album": "Definitely Maybe (Deluxe Version)",
  "album_artist": "Oasis",
  "year": 1994,
  "original_year": 1994,
  "genre": "Britpop",
  "composer": "Noel Gallagher",
  "duration_seconds": 323,
  "audio_file": "01 Rock 'n' Roll Star.mp3",
  "lyrics_file": "01 Rock 'n' Roll Star.lrc"
}
```

Paths here are relative to the **track's own folder**, not the album folder —
the file describes its neighbours.

Repeating `album` and `album_artist` is deliberate: it means a file moved
somewhere else still knows what it is, which is the point of having the file at
all. It also means those fields can drift from `album.json`, which is why the
index wins for browsing and why anything writing one writes both.

`duration_seconds` is informational. Roam works in milliseconds internally and
measures playback from the file itself.

### Trim points

`start_at` and `end_at` are **optional and normally absent**. They tell Roam to
begin or stop somewhere other than the ends of the file — a locked groove, a
minute of applause, silence before a hidden track.

They are NOT a description of how long the track is. Writing `end_at` equal to
the duration clips the last moment off every song, because a stored duration is
rounded. Roam's gotchas list already carries that one. Use `duration_seconds`
to record length; leave the trim points out unless you mean them.

```jsonc
// WRONG - clips the end
{ "title": "Wonderwall", "duration_seconds": 258, "end_at": "04:18" }

// RIGHT - no trim
{ "title": "Wonderwall", "duration_seconds": 258 }

// RIGHT - a real trim
{ "title": "Champagne Supernova", "start_at": "00:00", "end_at": "07:27" }
```

---

## Two years

`year` is the year of this release; `original_year` is when the material first
appeared. They differ in exactly the cases a decade playlist cares about:

- a 2014 remaster of a 1995 album — `year: 2014`, `original_year: 1995`
- *100 Hits of the 80s* from 2005 — the album is 2005, each track carries its own

Decade playlists read `original_year` and fall back to `year`. Without it an
80s playlist fills with whichever compilation the tracks came from.

---

## Matching entries to files

By **`file`** (or `audio_file`), compared case-insensitively after normalising
separators. Never by track number or title — those are what an edit changes.

- An entry with no matching audio file is ignored, not an error.
- An audio file with no entry falls through to its tags, then the path. It
  still appears in the library.
- Neither is a failure. A partial `album.json` is legitimate.

---

## Rewriting

Anything writing these files must **preserve fields it does not recognise**, at
every level. You and Roam will not always be on the same version, and a writer
that drops what it did not understand would silently undo the other's work.

Roam writes two-space indent, a trailing newline, keys in the order given here,
so a diff between saves shows what actually changed.

---

## Uploading

Nothing reaches Drive until its metadata is settled. The folder names come FROM
the metadata, so writing the file first and correcting it later leaves a wrongly
named folder behind that only a human can tidy.

Order:

1. Resolve the metadata — catalogue lookup, or what the review sheet decided
2. Work out the folder: `ARTIST > ALBUM (YEAR)`
   - **ARTIST** is the track's own artist, **unless `is_compilation`**, in which
     case it is `album_artist`. Otherwise a compilation scatters across every
     guest performer.
3. Check whether those folders exist; create only what is missing
4. Write the audio file, `<track>.json`, and `album.json` together
5. Only then let sync see it

---

## What does NOT go in these files

**User state** — loved, play counts, skips, last played, hidden. Those are
yours rather than the album's and change constantly while the metadata sits
still; mixing them in would mean rewriting `album.json` every time a song ends.
They belong in `.roam/state.json`, with its own lifecycle.

**Lyrics themselves** — a `.lrc` or `.txt` beside the audio file, which is what
every other player reads. These files only point at them.
