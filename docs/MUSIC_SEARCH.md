# Music Search

One page for finding music you do not own yet, and one queue for getting it.

Replaces the current downloader screen, whose search is a yt-dlp query against
YouTube with the catalogues bolted on beside it. That is backwards. The
catalogues are faster (plain REST against an index, versus starting a Python
interpreter to scrape a search page), they know what a release actually
*contains*, and they are the only things that can tell an original from a
remaster. YouTube is where the audio comes from, not where the answer comes
from.

Library search — finding music you already own — is a different question and
lives elsewhere. See "Not in this spec".

## The shape

**Entry point.** A download icon, not a magnifying glass. The distinction it is
drawing is "music you do not have" against "music you do", and a second
magnifying glass would say the opposite.

**One page.** A query field, a filter row, results, and the Add to Library
queue reachable from the same screen. Today's split between a track search, an
album sheet and an artist sheet becomes one list with a filter that decides
what kind of row appears in it.

## The query is a value, not a parameter list

`ReleaseSource.searchReleases(query, limit, compilationsOnly)` grows six more
filters under this spec. Six more parameters across two implementations and
every call site is the exact bug that broke the last build: `applyUserEdit`
went to sixteen parameters and one call site kept passing nine, silently, until
CI said so.

So the filters travel as one object:

```kotlin
data class ReleaseQuery(
    val text: String,
    val kind: ResultKind = ResultKind.ANYTHING,
    val compilationsOnly: Boolean = false,
    val genre: String? = null,
    val decade: Int? = null,          // 1990 means 1990..1999
    val format: ReleaseFormat? = null,
    val sources: Set<MetadataSource> = MetadataSource.entries.toSet(),
    val sort: ResultSort = ResultSort.RELEVANCE,
    val limit: Int = 25,
)

enum class ResultKind { ANYTHING, RELEASES, TRACKS, ARTISTS }
enum class ReleaseFormat { CD, VINYL, CASSETTE, DIGITAL }
enum class ResultSort { RELEVANCE, POPULARITY, NEWEST, OLDEST, ALPHABETICAL }
```

Adding a filter later is then a field with a default and no call site changes.
`check-deps` cannot catch a missed call site on a *defaulted* parameter, which
is the point: there is nothing to miss.

## What each source can actually honour

This is uneven and the UI must not pretend otherwise. Exact parameter names
need checking against current API docs when this is built; the asymmetry itself
is the stable part.

| Filter | MusicBrainz | Discogs |
| --- | --- | --- |
| Text | Strong | Strong |
| Compilations | Secondary release-group type | `format_desc=Compilation` |
| Genre | Crowd-sourced tags, patchy | `genre` + `style`, curated and good |
| Decade | Date range query | Single `year`; a decade needs client-side narrowing |
| Format | Medium format | Strong, it is what the site is for |
| Popularity | **None at all** | `community.have` |
| Track search | Recording search, good | **None** — releases only |
| Tracklists | Best available | Good, and knows vinyl positions |

Two consequences worth designing around rather than discovering:

**Popularity only orders Discogs.** MusicBrainz has no popularity data of any
kind, and `null` there means unknown rather than unpopular. A merged list sorted
by popularity therefore puts every MusicBrainz release below every Discogs one
— and MusicBrainz is the better source for tracklists. So **relevance is the
default sort**, popularity is offered, and when it is chosen the list says
which results it could not rank rather than silently sinking them.

**Tracks are a MusicBrainz result type.** Discogs cannot answer a track search.
With `kind = TRACKS` and Discogs selected, Discogs contributes nothing and the
filter row should show that, not return a thin list that looks like a bad
index.

## Results

Three row types, decided by `kind`. `ANYTHING` merges releases and tracks;
`ARTISTS` replaces the list entirely, because an artist row leads somewhere
rather than being addable.

- **Release** — cover, title, artist, year, format, track count, source. Tapping
  opens the tracklist, which is today's album sheet and stays roughly as it is:
  it already marks what you hold via `markHeld` and offers "add what is
  missing".
- **Track** — title, artist, release it came from, duration. Addable directly.
- **Artist** — name, disambiguation, image. Tapping shows their releases
  through `releasesForArtist`, which already exists.

**Render each source as it lands.** MusicBrainz is one request per second
behind a shared mutex; Discogs is sixty a minute. They will not answer together
and waiting for both means the page is as slow as the slower one. This is the
same lesson as the flat-listing fix in the YouTube search: draw what you have.

## The Add to Library queue

Reachable from the search page, holding everything queued this session and
whatever is still running from before.

State is `WorkInfo.State` plus one thing WorkManager does not know — whether
the track is already in the library. Colour follows state:

| State | Colour | Label |
| --- | --- | --- |
| Not queued, not held | none | "Add" |
| `ENQUEUED` / `BLOCKED` | amber | "Queued" |
| `RUNNING` | amber + progress | "Adding" |
| `SUCCEEDED` | green | "Added" |
| Held before the search | green | "In library" |
| `FAILED` | red | "Failed", plus the reason |
| `CANCELLED` | none | "Add" |

Rules the colours are there to express:

- **Green means the audio exists**, not that you asked for it. The current
  green tick appears on *queueing*, which is why a failed download still looks
  like a success. That is the bug this fixes.
- **Retrying returns a red row to amber**, because a retry is a queued job
  again and nothing about it is known yet.
- **Clearing a failed row returns it to "Add".** The row leaving the Failed
  list is what makes the track addable again; nothing else does.
- **Both greens are green.** Whether you already owned it or just fetched it,
  the answer to "can I play this" is yes. Only the label differs.
- **Failure keeps its reason.** Already true and must stay true: a refused
  duration match is a verdict about the recording, and a retry repeats it
  exactly.

## What this reuses

Most of it. `ReleaseSource`, `ReleaseMatch`, `ReleaseDetail`, `ReleaseTrack`,
`ArtistMatch`, `MetadataSource`, `searchArtists`, `releasesForArtist`,
`markHeld`, `DownloadStatus`, `DownloadRequest`, `AlbumPlacement` and the
placement dialog all stand. The new work is:

1. `ReleaseQuery` and widening the two `ReleaseSource` implementations to honour
   it, declining filters they cannot serve rather than ignoring them.
2. `searchTracks` on the interface, MusicBrainz-backed, Discogs returning empty.
3. YouTube behind the same interface as a third source, so it is a filter rather
   than a separate screen. It answers tracks only, and no filter but text.
4. One screen and one ViewModel replacing the search pane, album sheet and
   artist sheet.
5. Queue state and colour as described above.

## Open questions

- **Does YouTube stay in search at all?** It is the only source that can find
  what no catalogue lists — a live set, a session, an obscure upload. It is
  also the one that returns a channel name where an artist belongs. Keeping it
  as an off-by-default filter is the current plan; worth revisiting once
  catalogue search is the primary path and it is clear how often it comes up
  empty.
- **Decade on Discogs** needs either several queries or client-side narrowing.
  Client-side is simpler and biases the result set; decide when the shape of a
  real result page is visible.
- **Genre vocabulary.** Discogs genres and styles are a fixed list; `Genres`
  in `:core:model` already canonicalises the library's own. Whether the filter
  offers the Discogs list, the library's list, or both is unsettled.

## Not in this spec

**Library search.** Titles, artists and albums; results playable; long-press
offering go-to-album, edit metadata, hide, go-to-artist, and view the album in
Music Search to add what is missing. Different page, different data, tracked
separately.

**Artist pages.** `albumsForArtist` matches only `al.artistId`, so an artist
who appears only as a *track* credit on a compilation has no albums and their
page renders empty. Fixed by an "Appears on" query, not by mirror rows —
content-derived ids mean a duplicate row is either a collision or a track
rediscovered as new. Plus hiding artists with no visible tracks.

**Barcode scanning.** Produces a `ReleaseMatch` and pushes it into the Add to
Library queue, so it depends on this being settled first.
