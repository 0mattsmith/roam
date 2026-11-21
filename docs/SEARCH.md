# Search

One page for finding music, whether you own it or not, and every row says
which: a play icon if Roam can play it, a **+** if it can only be added.

Reached from the magnifying glass on the library screen. Replaces the
downloader screen's search box and its three tabs (Library, Albums, YouTube),
and takes in the library search that `docs/MUSIC_SEARCH.md` left out.

## Why one page, not two

`MUSIC_SEARCH.md` split search by ownership: the magnifying glass for music you
have, a download icon for music you do not. That asks the question before the
search can answer it. Whether you own something is often what you searched to
find out, and with two pages a miss on the first means typing it again on the
second.

The distinction it drew is still drawn, per row instead of per page. Everything
else in `MUSIC_SEARCH.md` stands — `ReleaseQuery`, what each catalogue can
honour, drawing each source as it lands, the rules for an add's state — and
this page is where it shows up.

## Why the results did nothing

Two reasons, and the second is the real one.

- The downloader's Library tab called `onPlay`, and `RoamNavHost` never passed
  it to `DownloaderRoute`, so a tap ran the empty default.
- Everything else a result should offer — the track sheet, the editor, Go to
  album, Go to artist — lives in `:feature:library`, and `:feature:downloader`
  may not depend on another feature. Wiring `onPlay` would have fixed playing
  and nothing else.

So the page lives in `:feature:library`, and that tab is gone. See "Where it
lives".

So the page lives in `:feature:library`. See "Where it lives".

## A row says what you can do with it

Every row ends in one icon, and the icon is the row's state:

| State | Icon | Tapping the icon |
| --- | --- | --- |
| In your library | Teal play | Plays it |
| Not in your library | **+** | Asks where it goes, then adds it |
| Queued | Amber ring, dashed | Nothing; the Adding list has Cancel |
| Adding | Amber ring, filling with progress | Nothing |
| On Drive, not yet synced | Teal ring, spinning | Nothing |
| Added, but not found after the sync | Teal tick | Opens it, or says why it cannot |
| Failed | Red warning | Shows the reason, with Retry and Clear |

Amber is on its way, teal is yours, red needs you. Every state also has its own
shape, so none is told apart by colour alone.

- **Play means Roam can play it, not that the file exists.** `DownloadWorker`
  writes nothing to Room: the file lands on Drive, a sync is queued, and only
  that sync makes the track playable. So the ring stays until `resolveTrackId`
  finds the row, then turns into Play. This replaces `MUSIC_SEARCH.md`'s "green
  means the audio exists" — a green row you could not play is the same lie that
  rule was written to stop.
- **The row stays where it is when it flips.** It does not jump up into the
  library group. A list that rearranges itself under a thumb is how the wrong
  row gets tapped; the next search shows it in its proper place.
- **A ring never spins forever.** If the sync that followed the add has
  finished and the track still cannot be matched — its tags named it something
  else — the row shows a tick, and tapping it runs the same lookup the Adding
  list uses, which opens the track or says why it cannot.
- **Retrying turns red back into a ring; clearing turns it back into +.** As in
  `MUSIC_SEARCH.md`. A failure keeps its reason: a refused duration match is a
  verdict on the recording, and a retry repeats it exactly.
- **State is read from WorkManager, keyed by the URL the row would be queued
  under.** `queuedUrls` already does this, which is why a queued row survives
  leaving the screen and the app being killed. Nothing new is stored.

## Owned music shows once

The page has two groups, and a song you own appears in the first and never
again in the second with a +.

- **In your library.** From Room, on every keystroke after a short settle
  (`LOCAL_DEBOUNCE_MS`, 150 ms). Artists, albums and songs — the first few of
  each, then See all.
- **Not in your library.** From the catalogues, once typing stops
  (`REMOTE_DEBOUNCE_MS`, 600 ms). MusicBrainz allows one request a second and
  Discogs sixty a minute, so asking on every keystroke is not an option.
  Releases, songs and artists, each source drawn as it lands. Songs come from
  MusicBrainz recordings once `MUSIC_SEARCH.md`'s `searchTracks` exists, and
  from YouTube until then. With no signal this group says it could not reach
  them, and the first group works as normal.

A result is left out of the second group when you already have it:

- **A song** is held when a library track has the same normalised title *and*
  the same normalised artist or album. Title alone is not enough: plenty of
  albums open with a track called "Intro". (`resolveTrackId` can fall back to
  title alone because it already knows which file it is looking for. This
  check does not.)
- **A release** is held when the library has at least its `trackCount` tracks
  under that normalised album title and artist. With fewer it stays, labelled
  "4 of 12 in your library", and its + adds only what is missing —
  `downloadMissing`, which exists. The count is a Room query against the
  `trackCount` the search result already carries, so drawing the row fetches
  no tracklist.
- **An empty normalised string matches nothing.** `Ids.normalise` keeps only
  a–z and 0–9, so a title written entirely in another script normalises to
  nothing at all, and any two such titles would otherwise match each other.

The known miss: a title that differs by more than normalisation removes — a
" - Remastered" suffix — is not matched, and the song shows a + although you
own it.

## Tapping and long-pressing

| Row | Tap | Long-press |
| --- | --- | --- |
| Your song | Plays it | The track sheet |
| Your album | Opens it | Its sheet, as on the Albums tab |
| Your artist | Opens their page | Their sheet, as on the Artists tab |
| A song you do not have | Adds it, as the + does | View album, View artist |
| A release you do not have | Its tracklist (today's album sheet) | — |
| An artist you do not have | Their discography (today's artist sheet) | — |

- **Your rows are the library's own rows.** `TrackRow`, `AlbumRow` and
  `ArtistRow` with their existing handlers, so a result does exactly what the
  same row does in a tab, and there is no second copy to drift. The one
  difference is the trailing slot, which holds the state icon instead of the
  heart so the column reads the same all the way down. Love moves into the
  track sheet when it is opened from a row without a heart.
- **What a song plays.** The library's rule is that the queue mirrors the list,
  so tapping a song queues every song of yours that matches, in the order
  shown, starting at the one tapped. See all opens that list in full, paged
  like every other list (invariant 8), so a search for "the" does not stop at
  sixty the way today's does.
- **The track sheet gains Play next and Add to queue**, everywhere it appears
  and not only here. See below.
- **Rows for music you do not have keep what they do today.** Tapping a YouTube
  result already adds it, and long-press or its ⋮ already offers View album,
  View artist and Add to library. What changes is the icon: today a green tick
  appears the moment a track is *queued*, the bug `MUSIC_SEARCH.md` describes,
  and here that becomes the ring.

## Back returns to the results

Open an album or an artist from a result, and Back returns to the results with
the query and the scroll position as they were.

Today the library remembers exactly one layer under an open album — the artist
page, through `_artistPage` — and `closeDrill` hand-codes that one case. Search
is a layer under both, so it needs the same care, or Back drops to the top
level and the search is gone. And whatever holds the search must be cleared
when the search closes, for the reason in CLAUDE.md's "Back on an artist page
quits the app": state describing a screen nobody is on still gets rendered.

## Play next and Add to queue

Built (stage 2). On `PlayerController` as `playNext` and `addToQueue`, sent to
`RoamLibraryService` as `ACTION_PLAY_NEXT` and `ACTION_ADD_TO_QUEUE` with the
track ids, and offered in every track sheet and on an album header's sheet for
the whole album. The snackbar says which happened, because into an empty
player both just play. Where the new items go in a shuffled order is
`ShuffleInsert`, which is pure and tested.

- **Shuffle is the hard part.** Roam shuffles with Media3's own
  `shuffleModeEnabled`, and the default shuffle order puts an inserted item at
  a *random* position in the shuffled order. Done naively, Play next with
  shuffle on plays the song at some point, which is not next. So the insert
  runs in `RoamLibraryService` as a custom session command, the way
  `ACTION_LOVE` does, because that is where ExoPlayer's `setShuffleOrder` is —
  a `MediaController` cannot reach it. The order it sets puts the new item
  straight after the current one.
- **Into an empty player, both just play the track.** An empty queue has no
  "next", and queueing silently into one looks like nothing happened.
- **The saved queue needs nothing new.** `SaveOnChange` already listens to
  `onTimelineChanged`, so an added track survives a restart.

## Words

"Queue" means the play queue and nothing else. The + is "Add to library", and
what is in flight is the **Adding** list — never "the queue" in any string.
Same reasoning as the Add to library / Download split in CLAUDE.md: two things
with one name, and the name stops meaning either.

The placement dialog's confirm button says "Download" today. By that same rule
it says **Add**.

## Where it lives

The page belongs to `:feature:library`, because every action on music you own
is already there. The magnifying glass opens it inside `LibraryRoute`, the way
the drills open, instead of navigating to the downloader.

The + is the problem. Adding lives in `:feature:downloader`, which the library
may not depend on, so the acquire path moves down into a new data module,
**`:data:acquire`**:

- `DownloadRequest`, `DownloadWorker` and what it uses, `YoutubeSource`,
  `AlbumPlacement`.
- The held check and `resolveTrackId`. Both are queries against Room, and they
  belong below the UI anyway.

A worker in a data module is not new: `SyncWorker` is in `:data:catalog`. The
classes keep their names until offline pinning lands, per CLAUDE.md's naming
note. The module is new, so it can have the right name from the start.

The downloader's screens move into the search page: the album sheet, the
artist sheet, the placement dialog, the Adding list and the overflow menu
(Update yt-dlp). `MUSIC_SEARCH.md` already planned this — "one screen and one
ViewModel replacing the search pane, album sheet and artist sheet". A YouTube
link shared into Roam then opens the search page with the Adding list up. With
nothing left in it, `:feature:downloader` goes.

## The stages

Ordered so each is worth having alone, and the first is the complaint.

1. **Your music, working. Built.** The page, the first group, the rows and
   their sheets, Back, See all. The magnifying glass opens it. The downloader
   has lost its Library tab and is reached from the search page until stage 4
   -- the button in its bar, the row where the second group will go, or the
   blank page's own button -- and it is handed the term, so nothing is typed
   twice. How it came out, where it differs from the plan above:
   - **The magnifying glass is on the library screen only**, not inside a
     drill. The search is a layer UNDER drills, so opening it from one would
     have to take the drill down first, and Back would not return to it.
   - **Back:** an album or artist opened from the results, or from a See all
     list, returns to where it was opened; the results keep their scroll
     position, and go back to the top only for a new term.
   - **Edit details**, from a song's sheet, opens its album with the editor
     up -- the editor is drawn by the track list, as the Adding list's Edit
     already works -- and Back from the album is the results.
   - **See all songs is one list, not grouped by album.** It is in the
     search's own order, which puts what STARTS with the term first; grouped,
     nearly every row would open an album of its own. The sort menu is hidden
     there for the same reason.
   - **The results are a snapshot**, re-run when something on them may have
     changed: a heart or a removal from the sheet, and every return from a
     drill. They are not a live query.
2. **Play next and Add to queue. Built.** In every track sheet, and on an
   album header's sheet for the whole album. See the section above.
3. **`:data:acquire`. Built.** The move, and no visible change. Its own step,
   so a broken build points at the move and not at a feature. Everything that
   runs without a screen went: the worker and its request, `YoutubeSource`,
   `AlbumPlacement`, the matching and tagging behind them, and `DownloadStatus`.
   The held check and `resolveTrackId` became `Holdings`.
4. **Music you do not have.** The second group, the + and its states, the held
   rules, the sheets moving across. The downloader screen retires.

## Open questions

- **What tapping a song queues.** The library's rule says the matching songs,
  and stage 1 does that, capped at the queue limit. The alternative is the
  song's album, starting from that song, which may be what someone searching
  a title expects. Go to album is one long-press away either way.
- **Does + always ask where it goes?** It does today, because a folder on Drive
  cannot be renamed afterwards without leaving a copy behind (invariant 6d).
  For a song from a catalogue the dialog is nearly always a confirmation, and
  skipping it would make + one tap — at the price of the occasional misfiled
  compilation track, which cannot be undone.
- **How many of each before See all.** Three artists, three albums and five
  songs is a guess until a real page is on a phone.
