# Roam — Technical Specification

**A personal streaming music player for the car. Your library lives on Google Drive and your NAS. Roam streams it to Android Auto.**

Version 1.0 · Target: Android 9 (API 28) → Android 16 · Kotlin / Jetpack Compose / Media3
Distribution: sideloaded personal build (not Play Store — see §17)

---

## 1. Name and identity

**Roam**

Chosen because it's one syllable, spells and speaks cleanly ("open Roam"), and carries the exact product idea: your own library roams with you, off your own drives, wherever the car goes. It also plays on data *roaming* — the app is a streamer, not a locker.

Runners-up, if you want alternatives: **Ferry** (carries your library across), **Wavelength** (works but long), **Sonar** (good but implies search/discovery more than playback).

**Package ID:** `app.roam.player`
**Display name in Android Auto:** Roam

### Icon

A white play blade with two teal signal arcs projecting forward — "play" plus "streaming/broadcast", on a near-black squircle. Teal is the accent, not the field, per your brief. It stays legible at 48 px (verified).

| File | Use |
| --- | --- |
| `roam-icon.svg` / `roam-icon-512.png` | master, store listing |
| `roam-icon-adaptive-foreground.svg` | `ic_launcher_foreground` — mark scaled to the 66 % adaptive safe zone |
| `roam-icon-adaptive-background.svg` | `ic_launcher_background` |
| `roam-icon-mono.svg` | Android 13+ themed icon (`android:monochrome`) and notification small icon |
| `roam-icon-192/96/48.png` | xxxhdpi → mdpi launcher fallbacks |
| `roam-icon-preview.png` | size-legibility contact sheet |

Android Auto also needs a **flat white 44 × 44 dp** version of the mark for the browse-tab app badge — derive from `roam-icon-mono.svg`.

---

## 2. Scope

**In scope (v1):**

- Read-only sync of a Google Drive folder tree into a local catalogue (metadata + artwork only, no bulk audio download)
- SMB/WebDAV network-drive sources, same catalogue
- Streaming playback through Media3 with a bounded, user-configured cache
- Android Auto media browse + playback (Google's template — see §3)
- Phone app: library browse, now playing, settings, downloader
- Loved tracks and loved-weighted shuffle
- yt-dlp downloader with FFmpeg transcode, MusicBrainz/TheAudioDB tagging, optional Drive upload

**Explicitly out of scope (v1):** multi-user, Chromecast, gapless crossfade, lyrics, ReplayGain analysis, Android Automotive OS (AAOS) native build, Play Store distribution.

---

## 3. Android Auto: what you can and cannot control

This constrains the whole design, so it comes first.

An Android Auto **media app** does not draw its own UI in the car. It exposes a content tree and a playback session; **Android Auto renders the interface**. The Spotify screenshot you sent *is* that template — every media app gets the same one. That's why Spotify, Symfonium, and Roam will all look broadly alike in the car, and it's a feature: it's the familiarity you asked for, for free.

**What you control:**

| Surface | Control |
| --- | --- |
| App badge + name | Your icon and label, top-left of the browse screen |
| Root tabs | Up to N browsable roots, N supplied by the head unit (see below) — each with its own icon and title |
| Item presentation | Per-node: list vs. grid, category-list vs. category-grid, group titles, progress bars, "explicit"/"downloaded" badges |
| Artwork | Any square bitmap you can serve from a `content://` URI |
| Playback controls | Play/pause, prev, next, seek bar (standard) + **custom actions** (this is where ❤️ and shuffle live) |
| Accent colour | A single theme colour via manifest metadata |
| Voice | "Play <x> on Roam" via `MediaSession` search callbacks |

**What you do not control:** layout, typography, button size and position, artwork geometry, the queue screen's design, the overall colour scheme (the head unit's day/night theme wins), animations, or anything resembling the large-artwork Symfonium layout.

**Therefore:** the Symfonium-inspired big-artwork player you want is built as the **phone** now-playing screen (§12.3). In the car you get the standard template with a heart and a shuffle button in the custom-action slots.

**Root tab count.** Do not hardcode 4. Android Auto passes `MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT` in the root hints of `onGetLibraryRoot`. Read it, clamp your root list to it, default to 4 if absent. Older/simpler head units advertise fewer.

**Custom action count.** Also head-unit dependent, and the number of *visible* slots varies with screen width — extras fall into an overflow menu. Order your `CommandButton` list by priority: **love, shuffle**, then anything else. Use `MediaConstants.EXTRAS_KEY_SLOT_RESERVATION_SEEK_TO_PREV` / `_NEXT` in the session extras so prev/next always keep their slots.

**Driver distraction.** No free-text entry in the car, list lengths are truncated by the platform while moving, and you must paginate rather than return 5,000 children. Assume ~50–100 items per browse level are actually reachable while driving.

---

## 4. Architecture

Single Gradle project, multi-module, offline-first. The car surface and the phone UI are two thin clients over one repository layer.

```
:app                       Application, DI graph, navigation, manifest
:core:model                Pure Kotlin domain types (Track, Album, Artist, Source)
:core:database             Room: entities, DAOs, migrations, FTS
:core:datastore            Proto DataStore: settings
:core:designsystem         Theme tokens, Compose components, icons
:core:common               Dispatchers, Result, coroutine utils

:data:source-api           SourceProvider interface — the sync/stream contract
:data:source-drive         Google Drive: OAuth, files.list, changes.list, ranged reads
:data:source-smb           SMB2/3 via smbj + a seekable DataSource
:data:source-webdav        WebDAV via OkHttp (Range comes free)
:data:catalog              SyncEngine, TagExtractor, ArtworkStore, repositories

:feature:player            MediaLibraryService, browse tree, queue, ShuffleEngine, CacheManager
:feature:library           Phone browse UI (artists / albums / tracks / loved)
:feature:nowplaying        Phone player UI
:feature:downloader        yt-dlp + FFmpeg + metadata enrichment + Drive upload
:feature:settings          Settings UI
```

**Stack:** Kotlin 2.x · Compose BOM · Hilt · Room · Proto DataStore · Media3 (ExoPlayer + `MediaLibraryService`) · WorkManager · Coil · Retrofit/OkHttp · kotlinx-serialization.

**Non-negotiable rule:** `MediaLibraryService` reads only from Room and the `ArtworkStore`. It never blocks on the network to build a browse response. The car must respond instantly even with no signal; if the catalogue is synced, browsing works offline and only the audio stream needs connectivity.

---

## 5. Data model (Room)

```kotlin
@Entity(tableName = "sources")
data class SourceEntity(
  @PrimaryKey val id: String,          // uuid
  val type: SourceType,                // DRIVE | SMB | WEBDAV
  val displayName: String,
  val rootPath: String,                // Drive folderId, or smb://host/share/Music
  val credentialAlias: String?,        // key into EncryptedSharedPreferences / Keystore
  val deltaToken: String?,             // Drive startPageToken; mtime watermark for SMB
  val lastSyncAt: Long?,
  val enabled: Boolean = true
)

@Entity(tableName = "artists", indices = [Index(value=["sortName"])])
data class ArtistEntity(
  @PrimaryKey val id: Long,            // stable hash of normalised name
  val name: String,
  val sortName: String,                // "Beatles, The" — strips leading articles
  val artworkId: String?,              // borrowed from a representative album
  val albumCount: Int, val trackCount: Int
)

@Entity(tableName = "albums", indices = [Index(value=["artistId"]), Index(value=["sortTitle"])])
data class AlbumEntity(
  @PrimaryKey val id: Long,            // hash(albumArtist + album + year)
  val title: String, val sortTitle: String,
  val artistId: Long,                  // ALBUM artist, not track artist
  val year: Int?, val discTotal: Int, val trackCount: Int,
  val artworkId: String?,
  val durationMs: Long,
  val addedAt: Long
)

@Entity(tableName = "tracks",
  indices = [Index("albumId"), Index("artistId"), Index("sourceId"),
             Index(value=["sourceId","remoteId"], unique = true)])
data class TrackEntity(
  @PrimaryKey val id: Long,
  val sourceId: String,
  val remoteId: String,                // Drive fileId | SMB path | WebDAV href
  val remoteRevision: String?,         // Drive md5Checksum / version; SMB mtime+size
  val title: String,
  val artistId: Long,                  // track artist
  val albumId: Long,
  val albumArtist: String?,
  val trackNo: Int?, val trackTotal: Int?,
  val discNo: Int?, val discTotal: Int?,
  val year: Int?, val genre: String?,
  val durationMs: Long,
  val bitrate: Int?, val sampleRate: Int?, val mimeType: String,
  val sizeBytes: Long,
  val artworkId: String?,              // per-track art wins over album art
  val loved: Boolean = false,
  val lovedAt: Long? = null,
  val playCount: Int = 0,
  val skipCount: Int = 0,
  val lastPlayedAt: Long? = null,
  val addedAt: Long,
  val tagState: TagState              // PENDING | OK | FAILED | PATH_INFERRED
)

@Entity(tableName = "artwork")
data class ArtworkEntity(
  @PrimaryKey val id: String,          // sha-256 of the image bytes → automatic dedupe
  val width: Int, val height: Int,
  val bytes: Int,
  val sourceKind: ArtworkSource        // EMBEDDED | FOLDER_JPG | COVER_ART_ARCHIVE | AUDIODB | DEEZER | ITUNES
)

@Fts4(contentEntity = TrackEntity::class)
@Entity(tableName = "tracks_fts")
data class TrackFts(val title: String, val albumArtist: String?)
```

Notes:

- **IDs are content-derived, not autoincrement.** `id = xxHash64(normalise(albumArtist) + " " + normalise(album))`. A file that moves in Drive keeps its identity; a re-sync is idempotent; loved flags survive re-organisation.
- `remoteRevision` is the change-detection key. Drive gives you `md5Checksum` free on `files.list`; if it's unchanged, skip re-tagging entirely.
- Artwork is stored on disk (`filesDir/artwork/<id>.jpg`), never as a Room BLOB. Deduplicating by content hash means a 14-track album stores one image, not fourteen.
- Playback stats (`playCount`, `loved`, …) live only in `tracks` and are **never** overwritten by sync. Sync touches file-derived columns only.

---

## 6. Source layer

One interface, three implementations. Adding a source type later means one new module.

```kotlin
interface SourceProvider {
  suspend fun listAll(root: String): Flow<RemoteFile>              // full crawl
  suspend fun listChanges(token: String?): ChangeSet               // delta, if supported
  fun dataSourceFactory(): DataSource.Factory                       // for ExoPlayer
  suspend fun readRange(id: String, offset: Long, len: Long): ByteArray
  suspend fun write(path: List<String>, name: String, file: File): String  // upload, optional
  val capabilities: Set<Capability>   // DELTA_SYNC, RANDOM_ACCESS, WRITE
}
```

### 6.1 Google Drive

- **Auth:** Play Services `AuthorizationClient.authorize(AuthorizationRequest)` with Drive scopes. (`GoogleSignIn` is deprecated; Credential Manager handles identity, `AuthorizationClient` handles scopes.)
- **Scopes:** `https://www.googleapis.com/auth/drive` — full. You need it because `drive.file` only sees files *your app created*, so it cannot enumerate your existing `Music/` tree, and it cannot create `Music/Artist/Album/` folders inside it. This is a **restricted** scope; see §17 for what that means.
- **Full crawl:** recursive `files.list`, `q = '<folderId>' in parents and trashed = false`, `pageSize = 1000`, and a tight field mask:
  `fields=nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,parents)`.
  Breadth-first, ~6 concurrent requests. Cache folder-id → path so you can reconstruct `Artist/Album` without extra calls.
- **Incremental sync:** the **Changes API**. `changes.getStartPageToken` once, store it on the source row, then `changes.list(pageToken=…, fields=…)` on every subsequent sync. This returns only what actually changed — adds, edits, moves, trashes — instead of re-walking 10,000 files. This is the single biggest performance decision in the app.
- **Streaming:** `GET /drive/v3/files/{id}?alt=media` with `Authorization: Bearer …`. Drive honours HTTP `Range`, so ExoPlayer can seek natively. Wrap it in a `ResolvingDataSource` that stamps a fresh token on **every** request (including each range request) — tokens expire mid-track otherwise.
- **Quota:** Drive enforces per-user rate limits and a daily download ceiling. Back off exponentially on 403 `userRateLimitExceeded` / 429. Prefer a small number of large ranged reads over many small ones.

### 6.2 SMB / network drives

- `com.hierynomus:smbj` (SMB 2/3, maintained; jcifs-ng is the fallback for SMB1-only kit).
- No delta API — sync compares `(path, size, lastModified)` against the last watermark.
- ExoPlayer needs a custom `DataSource` wrapping smbj's `File.read(buffer, fileOffset, …)` so seeking works. Roughly 80 lines: `open()` positions, `read()` advances, `close()` releases the `DiskShare`.
- Credentials in `EncryptedSharedPreferences` backed by the Android Keystore. Never in DataStore plaintext.
- Discovery: don't bother with mDNS, it's unreliable on Android. Manual host / share / user / password, with a "Test connection" button.

### 6.3 WebDAV

OkHttp plus `PROPFIND`. `Range` is native, so `DefaultHttpDataSource` works with an auth interceptor and nothing else. This is the cheapest source type to support — worth including for Nextcloud users.

---

## 7. Sync engine

A `CoroutineWorker` chain under WorkManager, constrained to unmetered network by default (user-overridable).

```
Discover → Diff → ExtractTags → ResolveArtwork → Reconcile → Index
```

1. **Discover** — full crawl on first run, `changes.list` thereafter. Emits `RemoteFile(id, name, path, size, revision)`. Filters to audio extensions: `mp3 flac m4a aac ogg opus wav wma aiff`.
2. **Diff** — left-join against `tracks` on `(sourceId, remoteId)`. Three buckets: new, changed (`remoteRevision` differs), gone. Unchanged files cost zero further work.
3. **ExtractTags** — §8.
4. **ResolveArtwork** — §9.
5. **Reconcile** — upsert tracks; recompute album/artist rollups; delete orphans; **preserve** loved/playCount/lastPlayed.
6. **Index** — rebuild FTS, then emit a single `notifyChildrenChanged(rootId)` so a connected head unit refreshes.

**Progress and cancellation.** Foreground-service worker with a notification showing `n/total`. Fully resumable — the worker checkpoints its position, so killing the app mid-scan doesn't restart a 10,000-file crawl.

**Schedule.** On app launch (debounced 30 min), on manual pull-to-refresh, and every 6 h via `PeriodicWorkRequest`. Optionally on Android Auto connect, gated on Wi-Fi. Note that a proper push (`changes.watch`) needs a public webhook endpoint, so polling is correct here.

---

## 8. Metadata extraction — the important part

You must not download whole files to read tags. A 10,000-track library at 8 MB average is 80 GB.

**Primary path — ranged read + Media3 extractors.**

ID3v2 tags (including the `APIC` embedded-art frame) sit at the **start** of an MP3. FLAC's `VORBIS_COMMENT` and `PICTURE` metadata blocks are likewise at the head of the file. So:

1. Ranged-read the first **1 MB** through the source's `DataSource`. That covers the tag plus a typical 200–600 KB embedded cover.
2. Feed those bytes to Media3's `MetadataRetriever` (or drive `Mp3Extractor` / `FlacExtractor` directly over an in-memory `DataSource`). You get back `Metadata` entries:

| Field | ID3v2 frame | Media3 type |
| --- | --- | --- |
| Title | `TIT2` | `TextInformationFrame` |
| Artist | `TPE1` | `TextInformationFrame` |
| Album | `TALB` | `TextInformationFrame` |
| Album artist | `TPE2` | `TextInformationFrame` |
| Year | `TDRC` / `TYER` | `TextInformationFrame` |
| Track no. / total | `TRCK` ("5/12") | `TextInformationFrame` |
| Disc no. / total | `TPOS` ("1/2") | `TextInformationFrame` |
| Genre | `TCON` | `TextInformationFrame` |
| **Embedded artwork** | `APIC` | **`ApicFrame.pictureData`** |

3. If the tag block is truncated at 1 MB (rare, huge cover), retry once at 4 MB.

**M4A/MP4 caveat.** The `moov` atom carrying metadata may be at the *end* of a non-faststart file. Detect a missing `moov` in the head range and fall back to reading the **last** 512 KB. Handle this or a third of an AAC library will come back untagged.

**Fallback A — path inference.** Your convention is `Music / Artist / Album / 01 track - artist.mp3`. When tags are missing, parse it:

```
regex: ^(?<track>\d{1,3})[\s._-]+(?<title>.+?)(?:\s+-\s+(?<artist>.+))?$
artist ← parent-of-parent folder
album  ← parent folder
```

Mark those rows `tagState = PATH_INFERRED` and surface a "Fix metadata" chip in the phone app so you can see what guessed.

**Fallback B — folder art.** If no `APIC`, look for `cover.jpg | folder.jpg | front.jpg | album.jpg | *.jpg` in the same Drive/SMB folder.

**Fallback C — online.** Only for tracks the user explicitly asks to enrich (§13.4). Never auto-fetch across a whole library; it's thousands of rate-limited calls.

**Throughput.** 8-way parallel, semaphore-bounded. Expect ~1–1.5 s per new track on the first sync, dominated by the ranged HTTP read. A 5,000-track library takes roughly 15–25 minutes once, then seconds per delta sync forever after.

---

## 9. Artwork pipeline

Album art is the thing most home-built car players get wrong. This is the path:

```
APIC bytes / folder.jpg
   → decode, downscale to 1000 px max edge, JPEG q88
   → sha-256 the *encoded* bytes → artworkId
   → filesDir/artwork/<id>.jpg   (skip write if it exists — dedupe)
   → also emit a 320 px thumb: filesDir/artwork/<id>_320.jpg
```

**Serving to Android Auto.** Android Auto and AAOS require artwork as a **local `content://` or `android.resource://` URI**, not a bitmap — `setIconBitmap` isn't supported on AAOS at all, and stuffing bitmaps into browse results blows the 1 MB Binder limit and hangs the app.

So: a `ContentProvider` at authority `app.roam.player.artwork`, exposing `content://app.roam.player.artwork/art/<artworkId>?size=320`. Its `openFile()` returns a `ParcelFileDescriptor` on the cached file, generating the requested size on demand. Return the URI **immediately** even if the file isn't ready — Android Auto shows its own loading state and will re-request.

Set it on:
- browse items → `MediaMetadata.Builder().setArtworkUri(...)`
- the playing item → `METADATA_KEY_ALBUM_ART_URI` / `_DISPLAY_ICON_URI`

**Sizes.** Serve 320 px for browse grids, 640 px for the player. Some head units render artwork very large; 320 alone looks soft on a 1200 px screen.

**Placeholder.** A teal-on-charcoal Roam glyph vector, served via `android.resource://`, never a null URI.

---

## 10. Playback and caching

### 10.1 Player

`ExoPlayer` inside a `MediaLibraryService`, exposed via `MediaSession`. Foreground service with `foregroundServiceType="mediaPlayback"` (mandatory from API 34). Audio focus, becoming-noisy, and media buttons come from Media3 defaults — don't hand-roll them.

### 10.2 The cache — two tiers over one store

You asked for two modes; they're the same mechanism with a different bound.

```kotlin
val cache = SimpleCache(
  File(context.filesDir, "media"),
  LeastRecentlyUsedCacheEvictor(budgetBytes),   // slider value
  StandaloneDatabaseProvider(context)
)

val factory = CacheDataSource.Factory()
  .setCache(cache)
  .setUpstreamDataSourceFactory(sourceProvider.dataSourceFactory())
  .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(4 * 1024 * 1024))
  .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)   // network hiccup → serve from cache, don't fail
```

**Mode A — "next N tracks" (default N = 10).** A `PrefetchCoordinator` observes the timeline. Whenever the queue position changes it computes the window `[current+1 … current+N]`, and for each item not yet fully cached runs a Media3 `CacheWriter` on a bounded IO dispatcher, sequentially, lowest index first. Items that fall *out* of the window are simply released to LRU pressure. Budget is implicit: `N × ~10 MB`, so 10 tracks ≈ 100 MB, and the evictor is set to `max(N × 12 MB, 250 MB)`.

**Mode B — "storage budget" (slider, 250 MB → 20 GB, log scale, snapping at 500 MB / 1 / 2 / 5 / 10 GB).** Identical machinery; the evictor gets the slider value and the prefetch window grows to fill it: `N = clamp(budget / averageTrackBytes, 3, 50)`. Show live "3.2 GB used of 5 GB · about 640 tracks" under the slider.

**Skip behaviour, per your brief.** Skipping past a prefetched track wastes it but does *not* purge it — LRU will reclaim it naturally, and you may skip back. On a skip, the coordinator recomputes the window and cancels in-flight writes outside it (`CacheWriter.cancel()`), so a rapid-skip burst doesn't queue up ten redundant downloads.

**Offline degradation.** On connectivity loss the player keeps going through the cached window. When it reaches an uncached item it does *not* stop and it does *not* throw — the `OfflineQueueFilter` reorders the remaining queue to prefer cached items, shows a "Playing offline · 6 tracks cached" chip in the phone UI, and only pauses when the cache is genuinely exhausted. This is what you asked for: music keeps playing out of range unless you've been out too long or skipped too much.

**Cache is not download.** It's `filesDir`, opaque, and evictable — nothing is "kept". The Settings screen shows usage and a Clear button.

---

## 11. Loved tracks and shuffle

### 11.1 Loved

`loved: Boolean` + `lovedAt` on `tracks`. Toggled from three places: the AA custom action, the phone player, and long-press in any list. Toggling writes to Room, and the DB flow drives every surface — the heart in the car updates because the row changed, not because the car UI called back.

**"Loved" browse node** — a root tab in Android Auto and a tab on the phone, listing loved tracks newest-first, with **Shuffle loved** as the first playable child.

### 11.2 Weighted shuffle

Loved tracks should come up more often, without becoming the only thing you hear. Use **Efraimidis–Spirakis A-Res weighted sampling without replacement** — one pass, no bias, no repeats:

```kotlin
fun weightedShuffle(tracks: List<Track>, prefs: ShufflePrefs): List<Track> {
  val now = System.currentTimeMillis()
  return tracks.map { t ->
    var w = 1.0
    if (t.loved) w *= prefs.lovedMultiplier            // default 3.0, slider 1.0–5.0
    if (t.skipCount > 3) w *= 0.5                       // you keep skipping it
    t.lastPlayedAt?.let { last ->                       // recency damping
      val days = (now - last) / 86_400_000.0
      if (days < 14) w *= 0.35 + 0.65 * (days / 14.0)
    }
    t to Math.pow(Random.nextDouble(), 1.0 / w)         // key = U^(1/w)
  }.sortedByDescending { it.second }.map { it.first }
}
```

With `lovedMultiplier = 3.0`, a loved track is ~3× as likely to appear early. It's a smooth preference, not a hard filter.

### 11.3 Two shuffle entry points, as specified

| Where | Scope |
| --- | --- |
| Player custom action (car) / player screen (phone) | Reorder the **current queue** from the next index onward. Never re-shuffles what's already played, never interrupts the current track. |
| Library main screen "Shuffle all" | Build a fresh queue from the **entire library** (weighted), replace the timeline, play index 0. |
| Artist row → Shuffle | Weighted shuffle of all that artist's tracks |
| Album → Shuffle | Weighted shuffle of that album |
| Loved → Shuffle | Weighted shuffle of loved only |

Queues over ~2,000 items should be windowed — materialise 500 into the ExoPlayer timeline and extend as you approach the end, or the `MediaSession` queue blows the Binder limit.

---

## 12. UI

### 12.1 Theme tokens

```kotlin
// Surfaces
val Ink          = Color(0xFF070B0D)  // scrim / behind-artwork
val Surface      = Color(0xFF0C1215)  // app background
val SurfaceRaise = Color(0xFF141C20)  // cards, sheets
val SurfaceHigh  = Color(0xFF1C262A)  // pressed, selected
val Outline      = Color(0xFF24333A)

// Teal ramp
val TealBright   = Color(0xFF5EEAD4)  // icons on dark, focus rings
val Teal         = Color(0xFF2DD4BF)  // PRIMARY — active states, hearts, sliders
val TealCore     = Color(0xFF14B8A6)  // filled buttons
val TealDeep     = Color(0xFF0D9488)  // pressed
val TealWash     = Color(0x1A14B8A6)  // 10% tint chips

// Text
val TextHi       = Color(0xFFE8EFF0)
val TextMid      = Color(0xFF9AAAB0)
val TextLow      = Color(0xFF5F7178)
```

Fixed dark scheme — Material You dynamic colour is **off**. Type: Inter or Google Sans Text. Touch targets ≥ 56 dp on the player (car-adjacent use), ≥ 48 dp elsewhere. Corner radius 16 dp cards, 12 dp artwork, full-round buttons.

### 12.2 Library (main screen)

Symfonium's tab layout, tightened. A top bar with the Roam mark, a search icon, and a settings gear; below it four scrollable tabs:

**Artists** · **Albums** · **Tracks** · **Loved**

- A persistent **Shuffle all** pill floats bottom-right above the mini-player — one tap, entire library, weighted.
- **Artists**: 2-column grid, circular art, name + "n albums · m tracks". Fast-scroll A–Z rail on the right edge.
- **Artist detail**: header with a large blurred-art backdrop, artist name, and two buttons — **Shuffle** (all their tracks) and **Play**. Below, albums as cards; below that, a "Top tracks" section.
- **Album detail**: square art at 40 % height, title/artist/year/track-count, **Shuffle** + **Play**, then the track list with disc-number dividers when `discTotal > 1`. Each row: track no., title, duration, heart, overflow.
- **Albums**: 2-column grid, sortable by title / artist / year / recently added.
- **Tracks**: flat list, FTS-backed search.
- **Loved**: same as Tracks, filtered, with **Shuffle loved** pinned at the top.
- **Mini-player** docked above the nav: 48 dp art, title, artist, play/pause, next. Tap or swipe up expands.

Every list is a `LazyColumn`/`LazyVerticalGrid` over a Room `PagingSource`. Never load 10,000 rows into memory.

### 12.3 Now playing (the Symfonium-inspired screen)

Layout mirrors the reference you sent:

```
┌────────────────────────────────────────────────┐
│  ⌄                          ⟳   🔊  ⌇  ⋮       │   chevron collapses; right: cast, volume, EQ, menu
│                                                │
│   ┌──────────────┐        Dilnawaz             │   title 30sp, 1–2 lines
│   │              │        The Local Train      │   artist 18sp, TextMid
│   │  ALBUM ART   │        ─────────────        │   album 15sp, TextLow
│   │   1:1, 16dp  │                             │
│   │   radius     │        ☰♪      ♥      ＋    │   queue · LOVE · add-to-playlist
│   │              │                             │
│   └──────────────┘        ●━━━━━━━━━━━━━━━     │   teal scrubber
│                           0:15          3:27   │
│                                                │
│      ⤨        ⏮        ⏸        ⏭        ⇥     │   shuffle · prev · PLAY · next · repeat
└────────────────────────────────────────────────┘
```

- Landscape/wide: art left, controls right (exactly as your reference). Portrait: art on top, controls stacked below.
- Background: vertical gradient from `Ink` into a heavily blurred, desaturated sample of the album art — that's where the teal-to-navy depth in the reference comes from. Cheap to do with a `RenderEffect` blur on a 32 px downsample.
- Play/pause is a **72 dp** filled teal circle; prev/next 56 dp; secondary row 48 dp. Big and obvious, per your brief.
- Heart animates: outline → filled teal with a spring scale bounce.
- Chevron top-left collapses to the mini-player and returns you to Library.

### 12.4 Android Auto browse tree

Four roots (clamped to the head unit's advertised limit), each with a white 44 dp icon:

```
root
├── Home            [CATEGORY_LIST]
│    ├── ▶ Shuffle everything          (playable, teal shuffle icon)
│    ├── ♥ Shuffle loved               (playable)
│    ├── Recently added        [GRID]  → albums, 24 most recent
│    └── Recently played       [GRID]  → albums
├── Artists         [LIST, A–Z]
│    └── <Artist>   [CATEGORY_LIST]
│         ├── ▶ Shuffle all by <Artist>   (playable)
│         └── <Album>  [GRID]  → tracks [LIST]
├── Albums          [GRID, paginated 100/page]
│    └── <Album>    → ▶ Shuffle album + tracks [LIST]
└── Loved           [LIST]
     └── ▶ Shuffle loved + tracks
```

Content styles via `MediaConstants` extras on each `MediaItem`:
`DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE` = `…_GRID_ITEM` (2) for albums/artists, `…_LIST_ITEM` (1) for tracks; `…_CATEGORY_LIST_ITEM` (3) for the action rows on Home.

Also: mark the last-played item with `EXTRA_PLAYBACK_STATUS_KEY` so the car shows a resume affordance, and honour `EXTRA_RECENT` in root hints so tapping Roam from the car's recents resumes rather than reopening the tree.

**Voice.** Implement `MediaSession.Callback.onSetMediaItems` / `onSearch` so "Hey Google, play The Local Train on Roam" resolves through the FTS index. This is the only search you get in the car — there's no keyboard while driving.

**Player custom actions.** In priority order:

1. `roam.LOVE` — heart outline / heart filled, state from the current track
2. `roam.SHUFFLE_QUEUE` — shuffle glyph, toggles the queue shuffle
3. `roam.REPEAT` — repeat off/all/one

Declared as `CommandButton`s in `setCustomLayout`, handled in `onCustomCommand`.

---

## 13. Downloader (yt-dlp)

### 13.1 Reality check, stated plainly

Bundling yt-dlp breaks YouTube's Terms of Service and **cannot be published on Google Play**. Every app that does this — Seal, NewPipe, Tubular — is sideloaded or F-Droid only. For a personal build on your own phone that's your call to make; I'm flagging it so it isn't a surprise later. It also rules out ever putting Roam on the Play Store as a single app, which is one more reason §17 recommends the sideload route.

### 13.2 Runtime

```gradle
implementation "io.github.junkfood02.youtubedl-android:library:0.18.x"
implementation "io.github.junkfood02.youtubedl-android:ffmpeg:0.18.x"
implementation "io.github.junkfood02.youtubedl-android:aria2c:0.18.x"   // optional, faster
```

This is the maintained fork of `yausername/youtubedl-android` (the one Seal uses). It ships a Python 3.8 runtime, a lazy-extractors build of yt-dlp, and an FFmpeg binary as AARs, for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

**Consequences:** ~90–120 MB APK. Use ABI splits and ship only `arm64-v8a` for your own device (~45 MB). Also add a self-update path — `YoutubeDL.updateYoutubeDL()` — because YouTube breaks extractors regularly and a stale yt-dlp is the #1 failure mode of apps like this.

### 13.3 Search and fetch

- **YouTube Music:** run yt-dlp against `https://music.youtube.com/search?q=<query>` and parse the playlist entries — YT Music results carry proper artist/album/release-year fields, which is exactly what you want.
- **YouTube:** `ytsearch25:<query>`.
- Results merge into one list, YT Music first (better metadata), each row showing thumbnail, title, uploader/artist, duration, source badge.
- **Also accepted:** a pasted video URL, a playlist URL, or an album/`OLAK5uy_` URL. Playlists resolve with `--flat-playlist` first for a fast track listing, then download per item.

Format selection:

```
-f "bestaudio[ext=m4a]/bestaudio/best"
```

**On "highest possible quality".** YouTube serves Opus (~160 kbps) or AAC (~128 kbps). Transcoding either to 320 kbps MP3 makes the file bigger and the sound *slightly worse* — it's lossy→lossy. So Roam offers two output modes:

- **MP3 320** (default) — maximum compatibility, tags and embedded art work everywhere
- **Keep source (M4A/Opus)** — genuinely better fidelity, still fully taggable, still plays in Android Auto

Transcode:

```
ffmpeg -i in.webm -vn -c:a libmp3lame -b:a 320k -ar 44100 -id3v2_version 3 out.mp3
```

### 13.4 Metadata enrichment (user-assisted)

A cascade, first hit wins, with a review sheet before anything is written:

1. **MusicBrainz** — `https://musicbrainz.org/ws/2/recording/?query=recording:"<title>" AND artist:"<artist>"&fmt=json&limit=5`
   Hard rules: **1 request per second**, and a descriptive `User-Agent: Roam/1.0 ( your@email )`. Violating either gets you blocked. Use a token-bucket interceptor in OkHttp.
   Take the best-scoring recording → its release → **release MBID**.
2. **Cover Art Archive** — `https://coverartarchive.org/release/<mbid>/front-1200` (falls back to `front-500`, then `front`). This is MusicBrainz's art partner and should be tier 1 for covers.
3. **TheAudioDB** — `https://theaudiodb.com/api/v1/json/<key>/searchtrack.php?s=<artist>&t=<title>`, plus `searchalbum.php` for `strAlbumThumb`. The free/test key rotates — check their current docs; a Patreon key is the stable option. Good for genre and for obscure releases MusicBrainz misses.
4. **Deezer** — `https://api.deezer.com/search?q=<artist> <title>` — no key, no signup, returns `cover_xl` at 1000×1000. In practice the highest-yield art source; worth having.
5. **iTunes Search** — `https://itunes.apple.com/search?term=…&entity=song` — no key; take `artworkUrl100` and rewrite `100x100bb` → `1200x1200bb` for a large cover.

**Optional upgrade worth doing later:** AcoustID fingerprinting (Chromaprint) matches the actual audio rather than the title string, which is dramatically more accurate for YouTube rips with messy titles ("Song Name (Official Video) [HQ]"). It needs a native `fpcalc`/libchromaprint build, so it's a phase-4 item, not v1.

**Review sheet.** Before writing: cover thumbnail, every field editable, a "candidates" carousel if several releases matched, and per-field source badges (MB / CAA / Deezer / inferred). One tap to accept, or fix it yourself. This is the user-assisted step you asked for.

### 13.5 Tag write

```
ffmpeg -i audio.mp3 -i cover.jpg \
  -map 0:a -map 1:v -c:a copy -c:v mjpeg \
  -disposition:v attached_pic \
  -metadata title="…"        -metadata artist="…" \
  -metadata album="…"        -metadata album_artist="…" \
  -metadata date="2019"      -metadata track="5/12" \
  -metadata disc="1/1"       -metadata genre="…" \
  -metadata:s:v title="Album cover" \
  -metadata:s:v comment="Cover (front)" \
  -id3v2_version 3 out.mp3
```

Cover must be **JPEG** (not PNG — some car head units won't decode PNG `APIC`), square, ≤ 1000 px, ≤ 500 KB. Use ID3v2.3, not 2.4: it's what car stereos and older software actually parse reliably.

### 13.6 Upload to Drive

Optional per download, on by default.

1. Resolve `Music` → `<AlbumArtist>` → `<Album>` with `files.list` at each level, creating any missing folder (`mimeType: application/vnd.google-apps.folder`). **Cache folder IDs in Room** — this is 3 round-trips per download otherwise, and you'll hit rate limits on an album.
2. Filename from a template setting, defaulting to your convention: `{track:02d} {title} - {artist}.{ext}`.
3. **Resumable upload** (`uploadType=resumable`) for anything over 5 MB, with retry on 5xx and a progress notification.
4. Insert directly into the local catalogue on success — don't wait for the next Drive sync to see your own download.

Concurrency: 2 at a time, queued, resumable across app death via WorkManager.

---

## 14. Auto-update from GitHub Releases

Because Roam is sideloaded, there's no Play Store to push updates. Roam self-updates from your own GitHub repo.

### 14.1 Update check

No extra backend, no manifest file to maintain — the GitHub Releases API *is* the update feed:

```
GET https://api.github.com/repos/<owner>/roam/releases/latest
Accept: application/vnd.github+json
```

```kotlin
@Serializable data class GhRelease(
  @SerialName("tag_name")     val tag: String,        // "v1.4.2"
  val name: String,
  val body: String,                                   // release notes → shown in the dialog
  val prerelease: Boolean,          // always false -- see 14.3
  @SerialName("published_at") val publishedAt: String,
  val assets: List<GhAsset>
)
@Serializable data class GhAsset(
  val name: String,
  val size: Long,
  @SerialName("browser_download_url") val url: String
)
```

- **One endpoint, always.** `/releases/latest` excludes drafts and pre-releases,
  and `release.yml` publishes every build with `--latest --prerelease=false`. So
  the updater needs no pagination, no filtering, and no "include pre-releases"
  setting — every release reaches every device.
- **Version comparison** on `versionCode`, not on the tag string. The release body carries a machine-readable trailer that `push.ps1` writes automatically: `<!-- roam:versionCode=142 -->`. Parse it; compare against `BuildConfig.VERSION_CODE`. String-comparing "v1.10.0" against "v1.9.0" is a classic way to ship an update that never installs.
- **Asset selection:** with ABI splits you get several APKs. Pick by `Build.SUPPORTED_ABIS[0]` — `roam-1.4.2-arm64-v8a.apk`. Fall back to a `-universal.apk` if the exact ABI isn't present.
- **Schedule:** a `PeriodicWorkRequest` every 24 h on unmetered network, plus a manual "Check for updates" in Settings → About. Unauthenticated GitHub API is 60 req/hour per IP — daily checks are nowhere near it.
- **Private repo?** Then you need a PAT. Store it in `EncryptedSharedPreferences` and send `Authorization: Bearer <pat>`. A public repo avoids this entirely and is simpler — the APK isn't a secret.

### 14.2 Download and install

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
```

1. Download the asset to `cacheDir/updates/` with a `DownloadWorker` (progress notification, resumable via `Range`).
2. **Verify SHA-256** against the `.sha256` sidecar asset that `push.ps1` uploads alongside each APK. Refuse to install on mismatch. This is the only thing standing between you and a corrupted or tampered APK, so don't skip it.
3. Install via `PackageInstaller` — do not use the deprecated `ACTION_INSTALL_PACKAGE` intent:

```kotlin
val installer = context.packageManager.packageInstaller
val params = PackageInstaller.SessionParams(MODE_FULL_INSTALL).apply {
    setAppPackageName(context.packageName)
    if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
}
val sessionId = installer.createSession(params)
installer.openSession(sessionId).use { session ->
    session.openWrite("roam", 0, apk.length()).use { out ->
        apk.inputStream().copyTo(out); session.fsync(out)
    }
    session.commit(pendingIntentFor(UpdateReceiver::class).intentSender)
}
```

4. The system shows its own confirmation dialog. First run also prompts for "Allow from this source" — send the user to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` if `packageManager.canRequestPackageInstalls()` is false.

**Signing key must never change.** Android refuses to update an APK signed with a different key — you'd have to uninstall and lose your database. Back up the keystore and its passwords somewhere you won't lose them. This is the single most important operational detail in the whole project.

**UI:** a dismissible card at the top of Settings and a one-time in-app banner — "Roam 1.4.2 available · 46 MB", the release notes rendered as markdown, **Update** / **Later** / **Skip this version**. Never auto-install without confirmation.

### 14.3 Release pipeline — `push.ps1` + GitHub Actions

The build runs in CI, not on the release machine. That means no local keystore,
no local Android SDK, and a release that is reproducible from the tag alone.

**`push.ps1`** — version, tag, push, watch.

```powershell
./push.ps1 "Fixes album art on FLAC, adds shuffle to artist rows"
./push.ps1 -Bump minor "Network drive support"
./push.ps1 -Verify "Risky refactor"   # local assembleDebug first
./push.ps1 -DryRun                    # print the plan, change nothing
```

| Parameter | Default | Purpose |
| --- | --- | --- |
| `[0] Notes` | auto from git log | Tag annotation, and therefore the release body |
| `-Bump` | `patch` | `patch` \| `minor` \| `major` |
| `-SetVersion` | — | Explicit version, overrides `-Bump` |
| `-Verify` | off | Local `assembleDebug` before bumping anything |
| `-NoWatch` | off | Push and exit rather than following the run |
| `-DryRun` | off | Plan only |

Steps: preflight (`gh` auth, clean tree, **the four signing secrets exist**) →
read and bump `versionCode`/`versionName` → commit → **annotated tag whose
message is the release notes** → push → poll for the workflow run → `gh run
watch` → print the release URL. Any failure before the push restores the version
file and deletes the local tag.

Putting the notes in the tag annotation keeps them in git, versioned alongside
the code they describe, and gives the workflow something to read back without a
second source of truth.

**`.github/workflows/release.yml`** — triggered by `push: tags: ['v*']`, plus a
`workflow_dispatch` input so a failed build can be re-run against the same tag
without burning a version number.

1. Checkout with `fetch-depth: 0` (tag annotations are needed)
2. Read `versionCode` from `app/build.gradle.kts`
3. JDK 17 + `gradle/actions/setup-gradle` cache
4. Decode `KEYSTORE_BASE64` → `roam-release.jks`, write `keystore.properties`
   from the other three secrets
5. `./gradlew assembleRelease` — ABI splits
6. Rename to `roam-<version>-<abi>.apk`, `sha256sum` each into a `.sha256`
   sidecar (this is what `UpdateInstaller.verify` checks)
7. Notes = tag annotation + `<!-- roam:versionCode=N -->` trailer
8. `gh release create … --latest`, or `gh release edit … --latest
   --prerelease=false` when re-running an existing tag
9. Delete the keystore and properties file, always

**Every release is Latest. None is ever a pre-release.** The updater polls
`/repos/:owner/:repo/releases/latest`, which excludes drafts and pre-releases —
so a build marked pre-release is invisible to every installed copy of Roam.
Publishing with `--latest` unconditionally is what makes the auto-updater a
single API call with no pagination and no filtering.

**`.github/workflows/ci.yml`** — `assembleDebug` + `lint` on every push to main
and every PR. No secrets required, since debug builds use the auto-generated
debug key.

**`setup-secrets.ps1`** — one-time. `-Create` generates the keystore via
`keytool`, writes `keystore.properties`, then base64-encodes the `.jks` and
uploads all four secrets with `gh secret set`. It verifies the keystore actually
opens with the given alias and password first, so a typo surfaces here rather
than as a wall of Gradle output in CI.

---

## 15. Settings

**Sources**
- Google Drive: connect/disconnect, folder picker, folder path, last sync, "Sync now"
- Network drives: add/edit/remove SMB & WebDAV, test connection
- Sync on Wi-Fi only *(default on)* · Sync frequency *(6 h)* · Sync when Android Auto connects

**Playback & cache**
- Cache mode: **Next N tracks** (slider 3–30, default 10) | **Storage budget** (slider 250 MB–20 GB, log scale, snap points at 0.5/1/2/5/10 GB)
- Live usage readout: "3.2 GB of 5 GB · ~640 tracks" · Clear cache
- Prefetch on mobile data *(default off)*
- Audio focus behaviour: pause / duck
- Resume on Bluetooth connect · Auto-play on Android Auto connect

**Library**
- Loved weighting multiplier (1.0–5.0, default 3.0)
- Recency damping on/off
- Ignore articles when sorting *(The/A/An)*
- Group by album artist vs. track artist
- Rescan metadata · Rebuild artwork cache

**Downloader**
- Output format: MP3 320 / Keep source
- Auto-upload to Drive *(on)* · Filename template
- Metadata sources: reorderable MusicBrainz / CAA / TheAudioDB / Deezer / iTunes toggles
- Always show review sheet *(on)*
- Update yt-dlp · shows current version

**Updates**
- Auto-check for updates *(daily, Wi-Fi only)* · Check now
- Current version + build, latest available, changelog

**About** — version, library counts, licences, yt-dlp version, storage breakdown.

---

## 16. Manifest essentials

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

<application …>
  <meta-data android:name="com.google.android.gms.car.application"
             android:resource="@xml/automotive_app_desc"/>
  <meta-data android:name="com.google.android.gms.car.application.theme"
             android:resource="@style/CarTheme"/>
  <meta-data android:name="com.google.android.gms.car.notification.SmallIcon"
             android:resource="@drawable/ic_roam_mono"/>

  <service android:name=".player.RoamLibraryService"
           android:foregroundServiceType="mediaPlayback"
           android:exported="true">
    <intent-filter>
      <action android:name="androidx.media3.session.MediaLibraryService"/>
      <action android:name="android.media.browse.MediaBrowserService"/>
    </intent-filter>
  </service>

  <provider android:name=".artwork.ArtworkProvider"
            android:authorities="app.roam.player.artwork"
            android:exported="true"
            android:grantUriPermissions="true"/>
</application>
```

`res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
  <uses name="media"/>
</automotiveApp>
```

The `MediaBrowserService` action **must** be in the intent filter alongside the Media3 one, or Android Auto won't discover the app at all. It's the most common reason a media app doesn't show up in the car.

---

## 17. Google Cloud / OAuth setup

You need full `drive` scope, which Google classifies as **restricted**. Three routes, and only one is sensible for you:

| Route | Refresh token | Cost | Verdict |
| --- | --- | --- | --- |
| OAuth client, **Testing** status | **Expires after 7 days** — you'd re-authorise weekly | free | ✗ unusable |
| OAuth client, **Production**, unverified | Persistent | free | ✓ **do this** |
| Verified + published | Persistent | CASA Tier-2 security audit, annual, paid | ✗ overkill |

Steps:

1. Google Cloud Console → new project → enable the **Google Drive API**
2. OAuth consent screen → **External** → fill in app name and support email
3. Add scope `https://www.googleapis.com/auth/drive`
4. **Set publishing status to "In production."** Do *not* submit for verification. You'll see a "Google hasn't verified this app" interstitial on first sign-in — Advanced → Continue. That's it, once, forever.
5. Credentials → OAuth client ID → **Android**, with your package name and the SHA-1 of your signing key (debug *and* release — they differ, and this catches everyone)

The unverified-production route is capped at 100 users, which for a personal app is 99 more than needed.

---

## 18. Risks and gotchas

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Drive access token expires mid-track | Playback stalls at a chapter boundary | `ResolvingDataSource` refreshes on every request, not per-track |
| M4A `moov` atom at end of file | Untagged AAC library | Head-range read, detect, fall back to tail-range |
| Binder 1 MB limit on browse results | Car UI hangs or silently truncates | Artwork by URI only; paginate ≥ 100 items |
| yt-dlp extractor rot | Downloader silently stops working | In-app `updateYoutubeDL()`, surface version in Settings |
| MusicBrainz rate-limit ban | Enrichment dies | 1 req/s token bucket + real User-Agent, non-negotiable |
| APK size from Python + FFmpeg | 90–120 MB | ABI splits; ship arm64-v8a only |
| First sync on a large library | 20+ min, looks broken | Foreground progress notification, resumable, browsable as it fills |
| Drive daily download quota | Playback 403s | Exponential backoff; cache reduces repeat fetches substantially |
| PNG cover art in `APIC` | Blank art on some head units | Always re-encode covers to JPEG |
| **Lost or changed signing keystore** | **Updates permanently impossible — uninstall/reinstall loses the DB** | Back up `keystore.jks` + passwords off-device; `push.ps1` refuses to build without it |
| Version compared as a string | Update never installs, or installs backwards | Compare `versionCode` from the release-body trailer |
| A release published as a pre-release or draft | Invisible to every installed copy — `/releases/latest` skips both | `release.yml` always passes `--latest --prerelease=false`; there is no opt-out |
| `KEYSTORE_BASE64` secret missing or stale | CI release job fails at the signing step | `push.ps1` checks all four secrets before it tags anything |
| Head unit ignores custom actions | No heart button in car | Keep love reachable from the phone and the notification too |

---

## 19. Build order

**Phase 0 — Repo and release plumbing (≈half a day).** `gh repo create`, `./setup-secrets.ps1 -Create` to generate the keystore and push it into Actions secrets, then cut a `v0.0.1` release from an empty app. CI compiles every push from here on, and `./push.ps1` ships to your phone in one command. Doing this *first* means you never discover a signing problem at v1.0 with a database you care about.

**Phase 1 — Skeleton that plays (≈2 weeks).** Modules, theme, Room, Drive OAuth, full crawl, tag extraction, artwork store + ContentProvider, ExoPlayer with `CacheDataSource`. Plus the in-app updater — it's an afternoon, and from then on testing is `./push.ps1` then tap Update on the phone. Success: play a Drive track on the phone with correct title and cover.

**Phase 2 — The car (≈1.5 weeks).** `MediaLibraryService`, browse tree, content styles, custom actions, voice search, manifest plumbing. Test on the **Desktop Head Unit** before ever plugging into the car. Success: browse Artists → Album → track and see your album art on the head unit.

**Phase 3 — Make it good (≈2 weeks).** Changes-API delta sync, prefetch coordinator + both cache modes, weighted shuffle, loved everywhere, the full phone UI including the now-playing screen, settings.

**Phase 4 — Downloader (≈2 weeks).** yt-dlp integration, search across YT + YT Music, FFmpeg transcode, the enrichment cascade, review sheet, Drive upload with folder resolution.

**Phase 5 — Everything else.** SMB and WebDAV sources, playlists, AcoustID fingerprinting, Android Automotive OS build, widget.

Roughly 8–10 focused weeks solo. Phases 1–2 alone give you a working car player — everything after that is refinement.

---

## 20. Reference implementations worth reading

- **Universal Android Music Player (UAMP)** — Google's own sample; the `AlbumArtContentProvider` is directly reusable
- **Symfonium** — closed source, but the best-in-class reference for browse-tree structure in the car
- **Seal** — the cleanest example of `youtubedl-android` wired into a modern Compose app
- **Media3 `MediaLibraryService` docs** — `developer.android.com/media/media3/session/serve-content`
- **Android for Cars media docs** — `developer.android.com/training/cars/media`
