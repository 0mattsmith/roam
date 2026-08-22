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
  "artist_info": "Oasis were an English rock band formed in Manchester in 1991...",
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
| `artist_info` | string | A paragraph about the artist, shown on their page. |
| `artist_image` | string | Square photo. |
| `artist_logo` | string | Transparent PNG usually. |
| `artist_banner` | string | Wide background. |
| `sort_as` | string | Files the artist under another name — The Beatles under B. |

All of it is shown on the artist page, beside the photo: the biography as a
paragraph, the rest as a line of facts. `sort_as` files the artist under another
name for ordering and is the one field that changes behaviour rather than
display.

`artist_info` is filled from **TheAudioDB**, whose `strBiographyEN` arrives in
the same response the artist photo and logo passes already request -- so the
biography costs no extra call. That API's shared key is public and capped at 30
requests a minute for everyone using it, which is why those passes stamp an
attempt even on failure and never retry in a loop. The same discipline applies
here.

Everything in this file is editable by hand, from the artist's long-press sheet
in the app or from an external editor. A value written by a person is never
replaced by a lookup.

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
  "genres": ["Britpop"],
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
| `genres` | array of strings | no | See "Genres". A singular `genre` string is still read. |
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

`null` here means no lyrics are known — NOT that nobody has looked. That
distinction lives in the track file, as `lyrics_checked`.

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
  "genres": ["Britpop", "Alternative Rock"],
  "composer": "Noel Gallagher",
  "duration_seconds": 323,
  "audio_file": "01 Rock 'n' Roll Star.mp3",
  "lyrics_file": "01 Rock 'n' Roll Star.lrc",
  "lyrics_checked": "2026-08-22"
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

### Knowing there are no words

`lyrics_file` is `null` when there are none. `lyrics_checked` is the date
somebody last looked.

Those two together say something neither says alone:

| `lyrics_file` | `lyrics_checked` | Means |
| --- | --- | --- |
| a path | any | Here are the words. |
| `null` | absent | Nobody has looked yet. Look. |
| `null` | a date | Looked, found nothing. Do not keep asking. |

This is the reason not to create an empty `.lrc` as a placeholder. An empty
file cannot tell an instrumental apart from a failed lookup, it has to be
downloaded before you learn it is empty, and for Poweramp, Navidrome and Kodi
the file existing is precisely the signal that lyrics DO exist.

Roam already avoids re-asking, but it remembers in its database — which is the
thing a reinstall throws away, so every instrumental gets looked up again on a
fresh install and again on the desktop. Recording it here instead means the
answer travels with the music.

A date rather than a boolean, so a sweep years later can decide to re-ask about
tracks nobody has checked since. Any `YYYY-MM-DD` will do; the precision is not
load-bearing.

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

## Genres

An array, because a track has several and a rule asking for one of them should
find it:

```json
"genres": ["Britpop", "Alternative Rock", "Indie"]
```

Crammed into one string they can only be matched by guessing at separators,
which is what makes genre rules unreliable. The array is what smart playlists
read.

A singular `"genre"` string is still accepted, because embedded tags only ever
carry one and hand-written files often do. A packed string is split on `;`, `,`
and `/`, so `"Britpop; Indie Rock"` from an old tag becomes two. Duplicates
collapse case-insensitively, keeping the first spelling seen.

In the editor these are chips. Typing offers matches from the **MusicBrainz
genre list** — about two thousand names, free, no key, fetched once and cached,
so suggestions are instant and work offline. Backspace at the start of the
input removes the last chip, which is the behaviour every email To: field
already taught everybody; each chip also has a small remove control for touch.
No edit mode, because a mode is a thing to be in and get out of.

---

## Two years

`year` is the year of this release; `original_year` is when the material first
appeared. They differ in exactly the cases a decade playlist cares about:

- a 2014 remaster of a 1995 album — `year: 2014`, `original_year: 1995`
- *100 Hits of the 80s* from 2005 — the album is 2005, each track carries its own

Decade playlists read `original_year` and fall back to `year`. Without it an
80s playlist fills with whichever compilation the tracks came from.

---

## Paths name real files

Every path field — `artist_image`, `artist_logo`, `artist_banner`, `cover_art`,
`file`, `track_meta`, `lyrics`, `audio_file`, `lyrics_file` — names a file that
**exists at the moment of writing**. A writer does not invent a name it intends
to create later, and does not leave one behind pointing at something it did not
write.

For an upload that means the audio, its `.json`, its `.lrc` if there is one,
and the index are written as one operation. Half-written is worse than absent:
absent falls back to the tags, whereas a path to nothing is a claim that turns
out to be false, and the failure appears somewhere unrelated later.

### Keeping the index true

`album.json` is a description of a folder, so it is rewritten whenever that
folder changes:

- a file **added** to an album gains an entry
- a file **removed** loses its entry
- a file **renamed** has its entry's paths updated

An index that has drifted is not merely stale — its entries point at files that
are gone, and its missing entries hide files that are there.

---

## A track that lives somewhere else

Sometimes a file is already on Drive in the wrong place, and the fix is either
to move it or to record where it actually is. Roam **asks** rather than
deciding, and the question says plainly what will happen: which file, from
which folder, to which folder, and that nothing is deleted either way.

**If the move is accepted**, the file and its `.json` and `.lrc` move together,
both albums' indexes are rewritten, and the entry is an ordinary relative
`file` like any other.

**If it is declined**, the file stays exactly where it is and the entry records
where that is:

```json
{
  "disc": 1,
  "track": 4,
  "title": "Half the World Away",
  "file": null,
  "external": {
    "path": "Oasis/The Masterplan (1998)/10 Half the World Away.mp3",
    "source": "drive",
    "file_id": "1a2b3c4d5e6f",
    "folder_id": "9x8y7z6w5v"
  }
}
```

| Field | Notes |
| --- | --- |
| `path` | Relative to the LIBRARY ROOT, not the album folder. Portable; readable by anything. |
| `source` | Which backend the ids belong to. `drive` today; SMB and WebDAV arrive in phase 5. |
| `file_id` | The backend's own identifier. |
| `folder_id` | Its parent, for resolving the neighbours. |

`file` is `null` when `external` is present. `track_meta` and `lyrics` for such
a track are relative to **wherever the file actually is**, since those sidecars
live beside the audio.

### Why both a path and an id

They fail in opposite directions, so each covers the other. A Drive id survives
a move or a rename and a path does not; a path is readable by your editor,
by rclone and by a future SMB source, and an id is meaningless to all three.

When they disagree, the **id wins** and the path is repaired. An id that no
longer resolves falls back to the path. Both failing is a broken entry, which
Roam reports rather than guessing at.

### An explicit claim beats the folder it sits in

A file in `The Masterplan/` is part of that album by default, because that is
where it is. An `external` entry elsewhere **claims** it, and the claim wins —
the same rule as everywhere else in this document, where what was written down
deliberately beats what was inferred.

So the track appears once, under the album that claimed it, and not under the
album whose folder happens to hold the bytes. Without that rule it would show
up twice, which is worse than either answer on its own.

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
so a diff between saves shows what actually changed. Key order is not a
courtesy: `org.json` backs its objects with a `HashMap` on the JVM and a
`LinkedHashMap` on Android, so a writer that inherited its ordering would
reshuffle the file on every save and every diff would look total.

**These files are replaced in place, not archived.** Roam never overwrites an
image and never deletes one — it numbers the outgoing file aside instead. An
index is different: it is regenerable from the catalogue, and it is written
every time somebody corrects a track, so archiving would bury the folder in
`album1.json`, `album2.json`. Preserving unrecognised fields is what protects
this file instead, and for something written this often it is the stronger
protection.

**Nothing creates a folder.** The album folder is worked out from where the
tracks actually are — the longest path prefix they share, taken segment by
segment, so a disc subfolder resolves to its parent for free. If it cannot be
found on the source, the write is refused rather than conjuring one, exactly as
the artwork and lyric passes do. An album whose tracks share no folder at all
has nowhere for an index to live, and Roam says so.

---

## Creating the files from the app

The editor says where the values on screen came from, in a line above the
fields:

```
Reading from: album.json
Reading from: file tags
Reading from: folder names
```

That matters beyond curiosity. A title that looks wrong is a different problem
depending on which of those three produced it, and the line says which without
anyone having to know the precedence rules.

When nothing has supplied the values but tags or the path — meaning this album
has no index, or has one with no entry for this track — a checkbox appears:

> **☐ Create album.json**  (?)

It is absent when the values already came from json, so its presence is itself
the answer to "is this album described yet". Ticking it enables **Apply** even
with no other change, and writes `album.json` and the track's own `.json`
together.

**Apply is otherwise disabled until something actually changes.** Comparison is
against trimmed values, or a trailing newline pasted into the lyrics box leaves
Apply lit on a form nobody edited — which is the false positive the disabling
exists to prevent.

### The help text behind the (?)

Verbatim, because the last line is the part that matters and is the part a
cheerful summary would leave out:

> **Why create album.json?**
>
> Roam keeps your corrections in its own database, which is deleted if you ever
> reinstall the app. An album.json file sits beside the music on your Drive, so
> your edits survive a reinstall and any other player or device can read them.
>
> It is also faster. Roam reads one small file per album instead of opening
> every track to check its tags.
>
> Nothing is written into your audio files, and nothing is deleted.
>
> Once the file exists, Roam trusts it over the tags — and you edit it here,
> the same as now.

### Generating is an album-sized operation

`album.json` lists every track in the album, so it cannot be written from one
track's editor without knowing the whole album. Roam knows it from its own
database, so the checkbox works wherever it appears — but ticking it in a
single track's editor writes the album index AND a file for every track in that
album, not just the one on screen.

The checkbox says so, because otherwise editing one track and finding
forty-four new files is a surprise:

> **☐ Create album.json** — writes the index for this album and a file for each
> of its 44 tracks

The album editor is the natural place to do it. Once the files exist, each
track's own editor edits its `<track>.json` and the index together.

### Local copies

There is no separate local cache of these files, because Room already is one.
Reading json puts its values in the tracks table, which is what browsing, the
car and offline playback read from.

A second copy of the raw json would be a second thing that can go stale against
the first, and it would not survive what it was meant to survive: app-private
storage is deleted on uninstall along with the database. **The copy on the
source is the durable one** — that is the entire reason the metadata moved out
of the database.

An edit writes both: Room so the screen updates now, the source so it lasts.

Paths make this work. Every path is relative to its own file's folder, never
absolute and never a Drive id, so the same `album.json` resolves correctly read
from Drive, from an rclone mount, or from a copy on a desktop. The one
exception is `external`, which is the case where an absolute reference cannot
be avoided — and that is why it carries a path AND an id rather than only an
id.

### The one case worth guarding

Roam already prefers embedded tags over the folder path, so json cannot usually
freeze a worse answer than the one already on screen — and if it does, the
editor corrects it exactly as it always did.

The exception is a track whose **tags have not been read yet**. Until the tag
pass reaches it, Roam is showing values inferred from the filename. Creating
the file at that moment records the guess, and because json then outranks tags,
the real ones are never consulted again. Nothing looks wrong, so nobody goes
looking.

So the app handles it rather than warning about it:

- The dialog says **"Tags for this track have not been read yet"** beside the
  checkbox when `tagState` is not `OK`. That is a fact about this track, not a
  rule the reader has to know.
- The bulk sweep **skips tracks whose tags are still pending**, and says how
  many it left. Across ten thousand tracks nobody is going to catch this by
  eye, so it cannot be the person's job.

A library-wide **Write metadata files** action lives in Settings for seeding in
bulk, and the same action is on an album's long-press sheet. Ten thousand
tracks is not a job for one dialog at a time.

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

## `playlist.json`

Playlists live in `MUSIC/PLAYLISTS/`, one file each, away from the music so a
crawl of the library never trips over them.

```
MUSIC/
├── PLAYLISTS/
│   ├── 90s Grunge.json
│   ├── 80s Mix.json
│   └── Driving.json
└── Oasis/
    └── ...
```

Two kinds, one file format. A **manual** playlist lists its tracks. A **smart**
playlist states a rule and Roam works out the answer.

```json
{
  "schema": 1,
  "name": "90s Grunge",
  "kind": "smart",
  "description": "Seattle, mostly",
  "artwork": "90s Grunge.jpg",
  "pinned": true,
  "rules": {
    "genres": ["Grunge", "Alternative Rock"],
    "year_from": 1989,
    "year_to": 1999
  },
  "sort": "random",
  "limit": 100,
  "cached_tracks": [
    { "artist": "Nirvana", "album": "Nevermind", "title": "In Bloom",
      "path": "Nirvana/Nevermind (1991)/02 In Bloom.mp3" }
  ]
}
```

| Field | Type | Notes |
| --- | --- | --- |
| `schema` | integer | `1` |
| `name` | string | What the tab shows. The filename is not read for this. |
| `kind` | string | `"manual"` or `"smart"`. |
| `description` | string | Optional line under the title. |
| `artwork` | string | Beside the json. Absent means a mosaic of the covers. |
| `pinned` | boolean | Pinned playlists take the car's home tiles first. |
| `rules` | object | Smart only. See below. |
| `sort` | string | See "Sort and limit". |
| `limit` | integer | Cap on how many tracks the playlist yields. |
| `tracks` | array | Manual only. The membership, in order. |
| `cached_tracks` | array | Smart only. The last resolution. See below. |

### `cached_tracks` is a convenience, not the truth

For a smart playlist the **rules are authoritative**. `cached_tracks` is what
the rules resolved to last time somebody asked, written back so the playlist has
something to show before it has been evaluated.

It exists for one situation: opening Roam with no signal, on a fresh install, or
before the first sync finishes. Without it a smart playlist is an empty screen
and a spinner. With it, the songs are there and get quietly replaced the moment
the rules can run.

That means nothing may depend on it being right. A track listed in it that no
longer matches the rules drops out on the next evaluation, and one that started
matching appears — neither is a conflict to resolve, because the cache never had
a vote. This is the opposite of a manual playlist's `tracks`, which IS the
membership and is never regenerated from anything.

Entries carry `artist`, `album`, `title` and `path` rather than an id, for the
same reason every other path in these files does: an id is meaningless on
another machine, and the four fields together survive a file being moved.

Playlist files are **cached on the device like `album.json` and `<track>.json`**
— which in practice means Room, exactly as described under "Local copies". The
point is the same one: an app opened offline should show a library, not a blank
screen apologising for the network.

### Rules

Every key present must match. An absent key does not constrain anything.

| Key | Type | Matches |
| --- | --- | --- |
| `genres` | array | Track carries ANY of these. See "Genres". |
| `artists` | array | Track or album artist is one of these. |
| `year_from` / `year_to` | integer | Inclusive. Reads `original_year` when set. |
| `loved` | boolean | Only loved tracks. |
| `added_within_days` | integer | Recently added. |

`genres` is any-of and the rest are all-of, which is what people mean: "90s
Indie" is Indie **or** Indie Rock **or** Britpop, released in the nineties.

Reading `original_year` rather than `year` is what stops a 2011 remaster of a
1994 record falling out of a nineties playlist — the case the two-year split
exists for.

### Sort and limit

```json
"sort": "random",
"limit": 100
```

| `sort` | Order |
| --- | --- |
| `random` | Shuffled. Reshuffled on each evaluation, not once. |
| `added` | Most recently added first. |
| `year` | Oldest first. |
| `played` | Most played first. |
| `artist` | Artist, then album, then track number. |

`limit` caps the result. Applied **after** the sort, which is the whole reason
both exist: `random` + `limit: 100` is a different playlist every time and the
only sensible way to have a "90s Indie" that does not run to two thousand
tracks. `year` + `limit: 50` is the fifty oldest, which it could not be if the
cap came first.

Absent `sort` means the app's default ordering; absent `limit` means everything
that matches. Neither applies to a manual playlist — its order is the file's
order and its length is its length.

---

## What does NOT go in these files

**User state** — loved, play counts, skips, last played, hidden. Those are
yours rather than the album's and change constantly while the metadata sits
still; mixing them in would mean rewriting `album.json` every time a song ends.
Tapping the heart in the car would become a Drive write. They also need merge
rules metadata does not: per-field timestamps, and play counts that SUM across
devices rather than overwrite. They belong in `.roam/state.json`, with its own
lifecycle.

**Playlist membership** — a track does not list the playlists it is in. The
playlist owns its membership, in its own file. Both directions would mean
adding one track to one playlist rewrites the track file, its album index and
the playlist: three copies of one fact, and eventually three answers.

**Lyrics themselves** — a `.lrc` or `.txt` beside the audio file, which is what
every other player reads. These files only point at them.
