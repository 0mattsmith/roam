# Roam — working notes for Claude Code

A personal streaming music player. The library lives on Google Drive (and later
SMB/WebDAV); Roam streams it to Android Auto. Sideloaded, single user, no Play
Store. Full design: `docs/SPEC.md`. Visual reference: `docs/mockups.html`.

## Build

```
./gradlew assembleDebug          # build
./gradlew installDebug           # to a connected device
./gradlew testDebugUnitTest      # TagParser — the only provable part
./gradlew lint                   # before committing
./commit.ps1 "what changed"      # stage + push + watch CI  <- day to day
./commit.ps1 -Amend "fix"        # fold into HEAD, force-push with lease
./push.ps1 "release notes"       # bump + tag + push; CI publishes a signed APK
./push.ps1 -DryRun               # show the plan, change nothing
./setup-secrets.ps1              # one-time: signing key into Actions secrets
```

**Every green push to main publishes a signed release marked Latest**, with
APKs attached, via the `release` job in `ci.yml`. `versionCode` is
`git rev-list --count HEAD`, injected through `ROAM_VERSION_CODE` -- monotonic,
and nothing has to commit back to the repo. `versionName` is the `major.minor`
from `app/build.gradle.kts` plus that count, so bumping the base in gradle is
how you mark a milestone.

The job **skips** (does not fail) when `KEYSTORE_BASE64` is absent, and never
falls back to debug signing -- switching keys would make every installed copy
refuse to update.

`commit.ps1` is the development loop; `push.ps1` cuts a hand-versioned release. Don't reach for
`push.ps1` to test a fix — it burns a version number.

Releases are built by **GitHub Actions**, not locally -- `push.ps1` only bumps
the version and pushes an annotated tag. `.github/workflows/ci.yml` compiles and
lints every push to main and every PR, so a broken build is caught before it
reaches a tag.

Android Studio's **Desktop Head Unit** (Tools → Android → Run DHU) is how you
test the car surface. Do not wait until you are in the car — the DHU catches
manifest and browse-tree problems in seconds.

## Architecture

Multi-module, offline-first. `:app` wires everything; nothing else depends on it.

```
:core:model         pure Kotlin domain types + content-derived ID hashing
:core:common        dispatchers, qualifiers
:core:database      Room — entities, DAOs, converters
:core:datastore     Preferences DataStore — settings
:core:designsystem  theme tokens, shared Compose components
:data:source-api    SourceProvider — the sync/stream contract
:data:source-drive  Drive OAuth, files.list, changes.list, ranged reads
:data:catalog       SyncWorker, TagExtractor, ArtworkStore, ArtworkProvider
:feature:player     MediaLibraryService, browse tree, shuffle, prefetch
:feature:library    phone browse UI
:feature:nowplaying phone player UI
:feature:downloader yt-dlp + FFmpeg + metadata enrichment
:feature:settings   settings UI
:update             GitHub Releases self-updater
```

Dependency direction is downward: `feature → data → core`. UI features may
depend on `:feature:player` (it hosts the media session) but never on each
other — route between them through `:app`.

## Invariants — do not break these

1. **`RoamLibraryService` never touches the network to build a browse response.**
   It reads Room and `ArtworkStore` only. With the catalogue synced, browsing
   works with no signal; only the audio stream needs data.

2. **Artwork goes to Android Auto as a `content://` URI, never a bitmap.**
   `setIconBitmap` is unsupported on AAOS, and bitmaps in browse results blow
   the 1 MB Binder limit — the symptom is the car UI hanging. Use
   `ArtworkProvider.uri(...)`.

3. **Sync never writes user state.** `loved`, `lovedAt`, `playCount`,
   `skipCount`, `lastPlayedAt`, `hidden` belong to the user. Sync owns
   file-derived columns only. Getting this wrong wipes someone's loved list on
   a re-scan.

3c. **"Remove from library" hides, it never deletes.** The row stays with
   `hidden = 1`, so the next sync finds the track already known and leaves it
   alone. Deleting the row would have the crawl rediscover the file as new and
   put it straight back, taking the loved flag and play count with it. The
   filter lives in `TRACK_COLUMNS` itself, not at each call site, so a new
   query cannot forget it -- which is why every caller appends `AND`, never
   `WHERE`. Settings lists what is hidden; without that they would be gone for
   good, which is the one thing this must not mean.

3a. **Sync inserts, it does not upsert.** Room's `@Upsert` writes *every*
   column of the entity you hand it, so a freshly built row's defaults land on
   top of real data -- this silently reset `loved`/`playCount` and nulled album
   and artist `artworkId` for any file whose revision changed. Parents use
   `insertIgnore` (their ids are content-derived, so a rename is a new row);
   tracks use `insertIgnore` plus explicit updates of file-derived columns only.

3b. **Room migrations are additive; never destructive.** Same reasoning as 3 --
   `fallbackToDestructiveMigration()` would drop loved flags and play counts on
   every schema bump. Bump `version`, write a `MIGRATION_n_m`, add it to
   `addMigrations(...)`. Only the *downgrade* fallback is allowed.

4. **Never download a whole file to read tags.** Ranged-read the first 1 MB.
   For M4A, if no `moov` atom is in the head, re-read the *last* 512 KB — on a
   non-faststart file the metadata is at the end.

5. **Drive auth is stamped per request, not per track.** Use
   `ResolvingDataSource`; a token that expires mid-song otherwise kills playback.

6. **Compare updates on `versionCode`, never the tag string.** `"v1.10.0"` sorts
   below `"v1.9.0"` lexically. `release.yml` appends a
   `<!-- roam:versionCode=N -->` trailer for exactly this reason.

6b. **Every release is published with `--latest` and never as a pre-release.**
   The updater polls the single `/releases/latest` endpoint, which excludes
   drafts and pre-releases. Marking one build as a pre-release makes it
   invisible to every installed copy of Roam. Do not add a prerelease path
   back into `UpdateChecker`.

6c. **Artist photos: source first, Deezer second, local always.** Order is
   `artist.jpg`/`folder.jpg` in the artist's folder, then Deezer, whose result
   is written back as `artist.jpg` so the lookup happens once per artist ever.
   The render path is always the local `ArtworkStore` -- reading artwork from
   the source at browse time would break invariant 1. Roam writes into the
   user's library folder here, so it never overwrites and it never creates a
   folder, and the whole behaviour is a Settings switch. The *manual* path
   (`ArtistPhotoEditor`, long-press an artist) does replace an existing
   `artist.jpg`, in place via `overwrite` rather than by uploading a second
   one -- Drive allows duplicate names in a folder, so adding would make which
   photo wins a matter of luck. That asymmetry is the point: automatic work is
   cautious, work the user asked for is not. Album covers work the same way
   via `cover.jpg` in the album folder -- replacing one does **not** rewrite the
   `APIC` frame in every track, because a folder cover is the convention and
   rewriting would mean a full download and re-upload of the whole album.

6d. **Roam never destroys anything on the source.** No delete, ever, and no
   overwrite of an image. Replacing a cover, photo or logo *numbers the outgoing
   file* -- `cover.jpg` becomes `cover1.jpg`, then `cover2.jpg` -- and writes the
   new one as plain `cover.jpg`. The live image therefore always has the same
   name, so nothing pointing at it ever has to change, and every version the
   folder has held is still there. Numbered names deliberately fall outside
   `ArtworkFiles`' candidate lists, so exactly one file answers to `cover.jpg` /
   `artist.jpg` / `logo.png`. "Remove cover" clears Roam's row only.
   `SourceProvider.overwrite` exists for the phase 4 tag writer and must not be
   reintroduced into an artwork path.

7. **IDs are content-derived** (`Ids.album`, `Ids.track` in `:core:model`), not
   autoincrement. A file that moves in Drive keeps its identity and its loved
   flag. Re-sync must be idempotent.

8. **Never load a full library into memory.** Lists are Room `PagingSource` →
   `LazyColumn`. Shuffle uses `shuffleCandidates()`, which selects four columns.

9. **The signing keystore must never change.** Android refuses to update an APK
   signed with a different key; recovery means uninstall, which takes the
   database. `keystore.properties` is gitignored — do not commit it.

10. **Do not hardcode 4 root tabs.** The head unit advertises its limit in the
    root hints (`CarConstants.ROOT_HINT_CHILDREN_LIMIT`). Read it and clamp.

10a. **The root is four browsable tabs and nothing else.** Library, Artists,
    Albums, Loved -- taken from the FRONT, because the unit shrinks its limit
    when maps takes most of the screen and whatever sits last falls off.
    Nothing playable goes at the root: every list leads with its own shuffle
    row instead, which no head unit can refuse (some accept only browsable
    root children), shuffles the thing you are looking at rather than always
    the whole library, and still lands "Shuffle all" at the top of the screen
    because Library is the first tab.

11. **Playback state is restored, never resumed by index alone.** The saved
    queue is re-found by the *current track's id*; a sync between sessions can
    drop a track ahead of it and shift every index after. Restore also refuses
    to run once `player.mediaItemCount > 0` -- a tap in the car beats the Room
    query, and the person's choice wins. Nothing ever persists an empty queue,
    because the player is briefly empty while the service starts and letting
    that land would wipe the state the restore is about to read.

## Phase plan

Work in order. Each phase ends somewhere testable.

**Phase 0 — done.** Repo, modules, theme, manifest, CI + release workflows.
Remaining: `gradle wrapper --gradle-version 8.9`, `./setup-secrets.ps1 -Create`,
set `UpdateChecker.OWNER`, create the Cloud Console OAuth client.

**Phase 1 — skeleton that plays.**
`DriveAuth.refresh()` via Play Services `AuthorizationClient`; `SyncWorker`
full crawl; `TagExtractor` head-range parse with Media3 extractors; artwork
store + provider; ExoPlayer with `CacheDataSource`.
*Done when:* a Drive track plays on the phone with correct title and cover.

**Phase 2 — the car.**
`BrowseTree` proper (Library / Artists / Albums / Loved), content styles,
`MediaLibrarySession` with custom actions (`ACTION_LOVE`, `ACTION_SHUFFLE_QUEUE`),
voice search over FTS.
*Done when:* the DHU browses Artists → Album → track with artwork.

**Phase 3 — make it good.**
Drive `changes.list` delta sync; `PrefetchCoordinator` and both cache modes;
weighted shuffle everywhere; the full phone UI including the now-playing screen.

**Phase 4 — downloader.**
yt-dlp search across YouTube + YT Music, FFmpeg transcode, the MusicBrainz →
Cover Art Archive → TheAudioDB → Deezer → iTunes cascade, review sheet,
resumable Drive upload with cached folder IDs.

**Phase 5 —** SMB/WebDAV sources, playlists, AcoustID fingerprinting, AAOS.

*Playlists parking lot.* Ideas land here as they come up, rather than being
argued about mid-flight:

- The car's fourth tab is called **Loved** today and becomes **Playlists**,
  with Loved as its first entry. `MediaId.Loved` carries a `TODO(phase5)`.
- A **plus icon on the album header**, beside the heart: "New playlist..." or
  an existing one.
- Loved stays a column on `tracks`, not a playlist row -- the heart in the car
  has to be one write, and every browse query already reads it.

## Conventions

- Kotlin, Compose, coroutines. No RxJava, no LiveData, no `runBlocking` off the
  main thread except inside `ResolvingDataSource` (documented there).
- Hilt for DI. `@HiltWorker` for workers. Modules named `<Thing>Module`.
- One public composable per file, named `<Screen>Route` for nav entry points.
- `TODO(phaseN)` markers mean "not yet, and here is when". Grep for them.
- **Public functions in `:core` and `:data` modules declare explicit return
  types.** An expression body infers its type from the last call, which can drag
  a library class into the module's public API and force every consumer to
  depend on it. `dataStore.edit {}` returning `Preferences` was exactly this.
  `tools/check-deps.py` catches leaked *supertypes* but not leaked *return
  types* -- the convention is the guard. Use a **block body**, not
  `: Unit = expr`: Kotlin requires an expression body to match the declared
  type, so that form is a compile error rather than a discard.
- Comments explain *why*, not what. If the code needs a "what" comment, rewrite
  the code.
- Versions are pinned in `gradle/libs.versions.toml` to a known-good set. Run
  the AGP Upgrade Assistant before starting, then confirm the build.

## Things that will bite

| Symptom | Cause |
| --- | --- |
| App absent from Android Auto entirely | Missing `android.media.browse.MediaBrowserService` action in the service intent-filter, or missing `automotive_app_desc.xml` |
| Car UI hangs while browsing | Bitmaps in browse results — use artwork URIs |
| Playback dies partway through a track | Auth stamped once instead of per request |
| Play in the car starts silence, or does nothing at all | `androidx.media3.session.MediaButtonReceiver` missing from the manifest. Media3 never receives `ACTION_MEDIA_BUTTON`, so `onPlaybackResumption` is never asked what "play" means |
| Resumption resumes nothing and the log says the app misbehaved | `onPlaybackResumption` returned an empty list. The contract is to FAIL the future when there is nothing to resume |
| Roam resumes a few tracks off after a sync | The stored index was trusted. Re-find the row by `currentTrackId` |
| The saved queue is empty every launch | A snapshot taken while the player was still starting got written. `snapshot` returns null on an empty player and `save` refuses an empty list -- keep both |
| Position always resumes up to ten seconds early | By design: position emits no events, so it is polled. Pausing writes exactly |
| "Shuffle all" is not the first row in a list | The `page == 0` guard was dropped or reordered. It must be first in Library, Artists, Albums and each album, and only on the first page or it repeats down the list |
| A tab is missing in the car | Expected below a 4-tab limit -- `rootTabs` is taken from the front, so Loved goes first, then Albums |
| A third of the AAC library untagged | `moov` atom at end of file; needs the tail-range fallback. `TagParser.needsTailRead` decides, `TagExtractor.readTags` does it — one implementation, shared by sync and the tag pass |
| Tags read as empty on an M4A | `meta` is a FullBox: four bytes of version and flags before its children. Walk it as a plain container and `ilst` is never found |
| An embedded cover is rejected as corrupt | The APIC description is terminated in the frame's OWN encoding, so a UTF-16 one ends in TWO zero bytes. Reading one leaves the image starting a byte late |
| Genre shows as "(17)" | A numeric ID3v1 index reached the UI. `TagParser.id3Genre` maps it; MP4's `gnre` is the same list but one-based |
| Blank covers on the head unit | PNG `APIC` — re-encode all covers to JPEG |
| Roam created stray folders in the music library | `resolveFolder(create = true)` where the artist tag name did not match a folder. The photo pass resolves with `create = false` and skips when absent |
| A hand-placed artist photo keeps getting replaced | Precedence inverted — `artist.jpg` on the source must beat Deezer, and an existing file is never overwritten |
| Artist avatars blank | Tags carry no artist photo — `ArtistPhotoWorker` pulls them from Deezer (`api.deezer.com/search/artist`, no key). Only a `Ids.normalise`-exact name match is accepted; a wrong face is worse than none |
| A band logo renders as a black rectangle | Something sent it down the JPEG path. Logos are transparent PNGs — `ArtworkStore.put(keepAlpha = true)`, and `ArtworkProvider.getType` must report `image/png` for them |
| Logos never appear | TheAudioDB's shared test key is public and capped at 30 req/min for everyone using it. A failure is shrugged off, not retried; `logoAttemptedAt` stops it being re-asked forever |
| An artist is re-searched every launch | `artworkAttemptedAt` not stamped — it must be written on failure too, not just success |
| Artist banners always blank | The banner lookup was made a passenger on the logo pass, which runs once per artist ever. It needs its own `bannerAttemptedAt` and its own query, or every artist stamped before the column existed is excluded forever |
| A collapsed list reveals five albums per scroll | Collapse is only offered inside an artist. Album boundaries are only visible once the rows are paged in, so collapsing the whole library hides everything Paging has not fetched yet |
| The album header opens the album instead of collapsing | Tap toggles; "Open album" is in the long-press sheet |
| Grid and list disagree about scroll position | Expected — they hold separate state objects, because a row index does not translate to a cell index |
| A menu opens miles from the button that owns it | `DropdownMenu` anchors to its PARENT layout node, not to the button beside it. Wrap the button and the menu in a small Box and align THAT — aligning only the button leaves the menu at the big parent's origin |
| Downloader silently stops working | Stale yt-dlp; call `YoutubeDL.updateYoutubeDL()` |
| Every search AND the update fail together | `YoutubeDL.init()` threw. Almost always `useLegacyPackaging = true` missing from `:app` — the library unzips a Python runtime out of its .so files, and modern AGP leaves native libs unextracted so there is nothing to unzip |
| "class X is not a concrete class" from a search | R8 shrank a class that is only ever built reflectively. commons-compress needs `-keep`, not just `-dontwarn`; `-dontobfuscate` alone does not help because this is shrinking, not renaming |
| A release-only failure with a two-letter class name | Obfuscation. Release builds set `-dontobfuscate` for exactly this reason — debug builds do not minify, so this class of bug never appears in testing |
| Searching produces an error while typing | Two yt-dlp processes at once. `execute` blocks rather than suspends and cancelling the coroutine does not kill it, so calls are serialised behind `runLock` |
| A YouTube Music search returns nothing | The results are shelves, not videos — walk `entries` recursively, and treat any id that is not 11 characters as a browse id rather than a track |
| `[ksp] not a valid name: <x>` | A `@Provides`/`@Binds` function named after a **Java** reserved word — Dagger mirrors it into a generated Java factory. Rename it (`default` → `defaultDispatcher`) |
| `Cannot access class X. Check your module classpath` | A public signature in a dependency module exposes a type from one of ITS `implementation` deps — declare an explicit return type, or promote to `api` |
| Artist photo saves to Photos do nothing | MediaStore `RELATIVE_PATH`/`IS_PENDING` are API 29+; the version check must *wrap* the call, not early-throw, or lint's NewApi fails `lintVitalRelease` |
| A replaced cover wiped the previous image on Drive | Something called `overwrite` instead of `rename`-then-`write` — see invariant 6d |
| Two cover.jpg files in one folder | A numbered name matched a candidate list. `cover1.jpg` must not appear in `ArtworkFiles.ALBUM_NAMES` |
| Archive numbering restarts at 1 and collides | `nextArchiveName` must scan the folder for the highest existing number, not count how many replacements this session made |
| A replaced album cover reverts after a re-tag | `TagWorker` must only ever call `setArtworkIfMissing`; the unconditional `setArtwork` is for user picks alone |
| The edit form shows the previous track after tapping next | `remember { mutableStateOf(initial.x) }` with no key. remember survives recomposition, so a new `initial` is ignored — key every form field on the id of what is being edited |
| An edited track reverts to the filename after a sync | `userEdited` not honoured — sync's `refreshFromPath` and `TagWorker.pendingTags` both filter on it |
| A removed track comes back after a sync | Its row was deleted instead of flagged, so the crawl found the file as new |
| A removed track still shows in one list | That query used `WHERE` after `TRACK_COLUMNS` instead of `AND`, dropping the hidden filter — or it needs brackets, since `AND` binds tighter than `OR` |
| An album says 12 tracks and lists 10 | `recomputeRollups` counted hidden rows |
| A whole compilation vanishes after an edit | `artists.pruneOrphans` deleted the album artist. Nothing carries "Various Artists" as a *track* artist, so the prune must also spare artists referenced by `albums.artistId` — the `aar` join is inner |
| An artist page is empty despite having albums | `tracksForArtist` filtered on `t.artistId` alone. It must also match `al.artistId`, or an album-artist-only credit shows nothing |
| A grouped alias still shows as its own entry | The Artists list must filter `groupArtistId IS NULL`; grouping only hides the row, the tracks are found through the parent's query |
| Grouping an artist made their albums vanish | `tracksForArtist` and `albumsForArtist` must also match artists whose `groupArtistId` is the one being opened, or the records belong to nobody |
| An alias will not sort with the main artist | `sortAs` is the stored override but `sortName` is what every ORDER BY reads — `setSortAs` must write both |
| A compilation scatters across every guest artist | Album-major sorting keyed on the *track* artist. `TRACK_COLUMNS` joins `artists aar` on the album's own artistId, and `TrackSort.ARTIST` orders by `aar.sortName` |
| Marking a compilation splits it up | The album artist must not fall back to the track artist when `compilation` is set — it defaults to "Various Artists", and that name is half the album's content-derived id |
| Half an album ends up under a different album | A bulk edit renamed the album outside a transaction. Renaming moves every track to a new content-derived id at once, so `applyToAlbum` wraps the loop in `withTransaction` |
| A renamed album loses its cover | The new `AlbumEntity` must inherit `artworkId` from the old row — `insertIgnore` creates a fresh row, and a fresh row's default is null |
| A renamed artist makes tracks disappear | Artist and album ids are content-derived (invariant 7), so renaming moves a track to a *different* row. `TrackEditor` must `insertIgnore` the new parent before pointing at it, or the inner joins drop the track |
| Loved flags or artwork vanish after replacing a file | Something reintroduced `@Upsert` in the sync path — see invariant 3a |
| MusicBrainz starts 503-ing | Exceeded 1 req/sec, or missing a real User-Agent. `MusicBrainz.get` serialises every call behind one mutex so the limit holds across callers |
| A download reports success but saves nothing | The output was found by parsing an id out of the URL. An album-page download is a `ytsearch1:` query with no id in it — take whatever landed in an empty per-job directory instead |
| Two downloads pick up each other's file | Shared staging directory. It is keyed on the WorkManager job id for exactly this reason |
| Update never installs | Version compared as a string, or the signing key changed |
| Two releases with the same versionCode | Updater ignores the newer one | `versionCode` is the commit count; never hand-edit it in CI |
| Update invisible to devices | Release marked pre-release or draft — `/releases/latest` skips both |
| CI release job fails at signing | `KEYSTORE_BASE64` missing or stale — re-run `./setup-secrets.ps1` |
