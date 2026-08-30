# A worked example

One real album, described in full, as a companion to `../ALBUM_JSON.md`. The
spec is prose; this is what it produces.

```
Oasis/
├── artist.json
├── artist.jpg
├── banner.jpg
├── logo.png
└── Definitely Maybe (Chasing the Sun Edition) (1994)/
    ├── album.json                  44 entries across 3 discs
    ├── cover.jpg
    ├── Disc 1 - Remastered Album/
    │   ├── 01 Rock 'n' Roll Star.mp3
    │   ├── 01 Rock 'n' Roll Star.json
    │   └── ...
    ├── Disc 2 - B-Sides & Extra Tracks/
    └── Disc 3 - Unreleased Demos, Out-Takes & Live Recordings/
```

Only three of the forty-four `<track>.json` files are here — the first track of
each disc, enough to show the shape.

Alongside them:

- **`tagmap.json`** — every document key and what it is called in ID3v2, MP4 and
  Vorbis. Generated from `TagMap` in `:core:model`, so it cannot drift from what
  Roam actually reads and writes. This is the file a tag editor wants; the
  documents themselves do not repeat the mapping, because it belongs to the
  schema rather than to any one instance.
- **`PLAYLISTS/`** — one smart playlist and one manual, showing both shapes of
  the same format.

## What this album demonstrates

**The album folder is worked out, not configured.** These tracks live in three
disc subfolders. The album folder is the longest path prefix they share, taken
segment by segment, so `Disc 1`, `Disc 2` and `Disc 3` all resolve up to
`Definitely Maybe (Chasing the Sun Edition) (1994)` and the index goes there.

**`file` is the identity.** Every entry names its audio file relative to the
album folder, disc subfolder included:
`Disc 2 - B-Sides & Extra Tracks/08 Supersonic (Live).mp3`. Matching is on that,
never on a title or a track number — those are what an edit changes.

**`artist` is absent from every entry.** It is only written when it differs from
`album_artist`, which on a single-artist album is never. On a compilation each
entry would carry its own.

**`lyrics` appears exactly twice**, on the two tracks that have a `.lrc` beside
them. It is never invented: Roam cannot see whether a lyric file exists without
asking, and a path naming nothing is worse than no path. Where the field is
absent a `.lrc` sharing the audio file's basename is still found.

**`cover_art` is `cover.jpg`** at album level. The `folder.jpg` and `Front.jpg`
inside each disc folder are left alone — Roam looks for the album's cover in the
album's folder.

**`previous_artwork` is absent** because nothing has been replaced yet. It
appears, directly beneath `cover_art`, the first time a cover is swapped: the
outgoing `cover.jpg` becomes `cover1.jpg` and gets named there.

## Two decisions worth checking

**The year.** This is the 2014 Chasing the Sun reissue of a 1994 record, so
`year` is 2014 and `original_year` is 1994. That split matters: a "nineties"
smart playlist reads `original_year`, so the album still counts as a 1994 one.
Set them the other way round if you would rather the album sorted as 1994
throughout — but then nothing records that this pressing is a reissue.

**`album_title` carries no year.** The folder is
`Definitely Maybe (Chasing the Sun Edition) (1994)`; the title is
`Definitely Maybe (Chasing the Sun Edition)`. The year lives in its own field
and in the folder name, not in the title twice.

Also worth knowing: the folder beside this one is named *Standing on the
Shoulders of Giants*, and the record is *Standing on the Shoulder of Giants*.
Once that album has an `album.json`, the document is what Roam shows and the
folder name stops mattering. That is the whole point of these files.

## Catalogue ids

`artist.json` carries its MusicBrainz and Discogs artist ids, `album.json` its
release ids, and the first track its recording id. All optional, and only the
first track here has one -- the other two are the shape, not a claim.

They are here because a name is not an identity and a folder name is less than
that. This album's folder could as easily have been written `Definitely Maybe
(Chasing The Sun Edition)`, and `D:Ream` cannot be a folder at all. What makes
the ids useful is that the crawl already reads every `album.json`, so sync can
index release id to folder as it goes and the downloader can ask an exact
question instead of comparing punctuation.

Note that the release id is a RELEASE, not a release group: this edition and the
1994 original are different records with different track counts, and telling
them apart is the entire point. The recording id is the opposite -- shared with
every other release carrying the same recording, which is what makes it answer
"do I already own this?" on a compilation.

See `docs/ALBUM_JSON.md` for how a wrong id is caught.

## `modified`

Every document carries one, immediately after `schema`, in UTC to the second.
Whoever writes the file sets it. It exists so two copies can be compared without
relying on filesystem mtime, which not every transport preserves — and by the
time it has been lost, the wrong copy has already won.

A patch counts as a write: replacing a cover restamps `album.json`.

## Where the numbers come from

`duration_seconds` is read from the audio file, not typed. The 323 on
*Rock 'n' Roll Star* is real; the other two are placeholders for the shape.

`lyrics_checked` records that somebody looked, whatever they found — a null
`lyrics_file` with a date means "there are none", which is a different thing
from "nobody has asked". Without it a track with no lyrics is looked up again
on every play, and again from scratch after a reinstall.
