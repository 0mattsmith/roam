# Roam — working notes for Claude Code

A personal streaming music player. The library lives on Google Drive (and later
SMB/WebDAV); Roam streams it to Android Auto. Sideloaded, single user, no Play
Store. Full design: `docs/SPEC.md`. Visual reference: `docs/mockups.html`.

## Build

```
./gradlew assembleDebug          # build
./gradlew installDebug           # to a connected device
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
   `skipCount`, `lastPlayedAt` belong to the user. Sync owns file-derived
   columns only. Getting this wrong wipes someone's loved list on a re-scan.

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
`BrowseTree` proper (Home / Artists / Albums / Loved), content styles,
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
| A third of the AAC library untagged | `moov` atom at end of file; needs the tail-range fallback |
| Blank covers on the head unit | PNG `APIC` — re-encode all covers to JPEG |
| Roam created stray folders in the music library | `resolveFolder(create = true)` where the artist tag name did not match a folder. The photo pass resolves with `create = false` and skips when absent |
| A hand-placed artist photo keeps getting replaced | Precedence inverted — `artist.jpg` on the source must beat Deezer, and an existing file is never overwritten |
| Artist avatars blank | Tags carry no artist photo — `ArtistPhotoWorker` pulls them from Deezer (`api.deezer.com/search/artist`, no key). Only a `Ids.normalise`-exact name match is accepted; a wrong face is worse than none |
| An artist is re-searched every launch | `artworkAttemptedAt` not stamped — it must be written on failure too, not just success |
| Downloader silently stops working | Stale yt-dlp; call `YoutubeDL.updateYoutubeDL()` |
| `[ksp] not a valid name: <x>` | A `@Provides`/`@Binds` function named after a **Java** reserved word — Dagger mirrors it into a generated Java factory. Rename it (`default` → `defaultDispatcher`) |
| `Cannot access class X. Check your module classpath` | A public signature in a dependency module exposes a type from one of ITS `implementation` deps — declare an explicit return type, or promote to `api` |
| Artist photo saves to Photos do nothing | MediaStore `RELATIVE_PATH`/`IS_PENDING` are API 29+; the version check must *wrap* the call, not early-throw, or lint's NewApi fails `lintVitalRelease` |
| A replaced album cover reverts after a re-tag | `TagWorker` must only ever call `setArtworkIfMissing`; the unconditional `setArtwork` is for user picks alone |
| An edited track reverts to the filename after a sync | `userEdited` not honoured — sync's `refreshFromPath` and `TagWorker.pendingTags` both filter on it |
| A renamed artist makes tracks disappear | Artist and album ids are content-derived (invariant 7), so renaming moves a track to a *different* row. `TrackEditor` must `insertIgnore` the new parent before pointing at it, or the inner joins drop the track |
| Loved flags or artwork vanish after replacing a file | Something reintroduced `@Upsert` in the sync path — see invariant 3a |
| MusicBrainz starts 503-ing | Exceeded 1 req/sec, or missing a real User-Agent |
| Update never installs | Version compared as a string, or the signing key changed |
| Two releases with the same versionCode | Updater ignores the newer one | `versionCode` is the commit count; never hand-edit it in CI |
| Update invisible to devices | Release marked pre-release or draft — `/releases/latest` skips both |
| CI release job fails at signing | `KEYSTORE_BASE64` missing or stale — re-run `./setup-secrets.ps1` |
