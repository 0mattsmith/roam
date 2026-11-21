package app.roam.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.roam.core.model.AlbumSort
import app.roam.core.model.ArtistSort
import app.roam.core.model.CachePolicy
import app.roam.core.model.TrackSort
import app.roam.core.model.ViewMode
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("roam_settings")

data class RoamSettings(
    // Drive source. Lives here rather than the `sources` table until phase 3
    // brings multi-source sync -- a schema migration to hold one folder id
    // would be ceremony for no benefit.
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
    val lastTrackCount: Int? = null,
    val lastSyncAt: Long? = null,

    val cachePolicy: CachePolicy = CachePolicy.NextTracks(10),
    val prefetchOnMobile: Boolean = false,
    val lovedMultiplier: Float = 3.0f,
    val recencyDamping: Boolean = true,
    val ignoreArticles: Boolean = true,
    val downloadFormatMp3: Boolean = true,
    val autoUploadToDrive: Boolean = true,
    val filenameTemplate: String = "{track} {title} - {artist}",
    val autoCheckUpdates: Boolean = true,
    /** Look for new music on the source each time the app opens. */
    val syncOnLaunch: Boolean = true,
    /**
     * Write artist photos back to the source as artist.jpg, so they survive a
     * reinstall and can be overridden by hand. Roam never overwrites a file
     * that is already there, but this is still Roam writing into the user's
     * own library folder, so it stays visible and reversible.
     */
    val saveArtistPhotosToDrive: Boolean = true,
    /** Version found by the last launch-time check, if any. */
    val updateAvailable: String? = null,

    /**
     * Personal access token for Discogs. Null until someone pastes one in.
     *
     * Discogs refuses /database/search outright without it, so the downloader
     * treats that source as unavailable rather than failing every lookup.
     */
    val discogsToken: String? = null,

    /**
     * Jamendo's API key ("client id"), free from devportal.jamendo.com. Null
     * until someone pastes one in, and until then the downloader simply does
     * not look there -- it is the last of its places, not one it relies on.
     */
    val jamendoClientId: String? = null,

    /**
     * Show lyrics under the now playing screen. On by default, because the
     * lookup only happens for the song being played and costs a few kilobytes.
     */
    val showLyrics: Boolean = true,

    /**
     * Even out volume: every track brought to one level (docs/VOLUME.md), so
     * a quiet old master is not drowned out by the loud new one after it.
     *
     * On by default. It is the reason the setting exists, it changes nothing
     * until a track has been measured, and switching it off puts everything
     * back exactly -- the measurements are kept either way.
     */
    val levelVolume: Boolean = true,

    /**
     * Write found lyrics back beside the track as .lrc / .txt.
     *
     * Same bargain as saveArtistPhotosToDrive: it makes the words survive a
     * reinstall and reach anything else reading the folder, but it IS Roam
     * writing into the user's own library, so it stays visible and off-able.
     * Never overwrites a file that is already there.
     */
    val saveLyricsToDrive: Boolean = true,

    /**
     * Hold LARGE transfers until an unmetered network is available.
     *
     * Covers downloads, the tag pass (a 1 MB ranged read per new track) and
     * artist images. Deliberately NOT the library crawl -- that is a handful of
     * folder listings, and making it wait would mean opening Roam away from
     * home and seeing none of your new music.
     *
     * On by default: a queued album is hundreds of megabytes, and the surprise
     * is far worse in that direction than the wait is in the other.
     */
    val wifiOnlyForLargeTransfers: Boolean = true,

    /**
     * Serve the library to this wifi network for editing from a browser.
     *
     * Off by default, and the only setting in Roam that opens a port. Until
     * this there was no credential and no server anywhere in the app, which is
     * why the PIN below exists at all.
     */
    val webServerEnabled: Boolean = false,

    /**
     * Four digits, generated once and shown beside the address.
     *
     * Null until the server is first switched on. Not a secret worth much --
     * it stops "I opened it on my phone by accident and renamed an album",
     * which is a likelier Tuesday than an attack from inside the house.
     */
    val webPin: String? = null,

    /**
     * The port the server last bound. Null until it has started once.
     *
     * Written by the server rather than chosen here: it takes the first free
     * one of `WebAddress.PORTS`, and Settings must print the one that answers.
     */
    val webPort: Int? = null,

    /**
     * Why the server last failed to start, in its own words.
     *
     * The switch turns itself back off when the start fails, and a switch
     * that flicks back with nothing said is indistinguishable from a broken
     * one. Cleared by the next attempt.
     */
    val webError: String? = null,

    // How the library is laid out and ordered. These live here rather than in
    // the ViewModel's UI state because they are preferences, not screen state:
    // picking "grid" once should still mean grid tomorrow morning in the car.
    val artistViewMode: ViewMode = ViewMode.GRID_3,
    /** Albums on an artist's landing page, which is a separate choice. */
    val artistAlbumViewMode: ViewMode = ViewMode.GRID_3,
    val trackSort: TrackSort = TrackSort.ARTIST,
    val albumSort: AlbumSort = AlbumSort.ARTIST,
    val artistSort: ArtistSort = ArtistSort.NAME,
)

/**
 * Enums are stored by name, not ordinal. An ordinal silently means something
 * else the moment a constant is inserted in the middle -- and TrackSort has
 * already had one added mid-list once.
 */
private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

/** Four digits, zero-padded, from a source worth trusting for the purpose. */
private fun newPin(): String = "%04d".format(java.security.SecureRandom().nextInt(10_000))

/**
 * Every public function here declares an explicit return type. DataStore's
 * `edit {}` returns Preferences, and an expression body would make that the
 * inferred return type -- putting an androidx.datastore class in this module's
 * public API and forcing every consumer to depend on DataStore.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object K {
        val DRIVE_FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val DRIVE_FOLDER_NAME = stringPreferencesKey("drive_folder_name")
        val LAST_TRACK_COUNT = intPreferencesKey("last_track_count")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")

        val CACHE_MODE = stringPreferencesKey("cache_mode")        // "tracks" | "bytes"
        val CACHE_VALUE = longPreferencesKey("cache_value")
        val PREFETCH_MOBILE = booleanPreferencesKey("prefetch_mobile")
        val LOVED_MULT = floatPreferencesKey("loved_multiplier")
        val RECENCY = booleanPreferencesKey("recency_damping")
        val ARTICLES = booleanPreferencesKey("ignore_articles")
        val FMT_MP3 = booleanPreferencesKey("download_mp3")
        val UPLOAD = booleanPreferencesKey("auto_upload")
        val TEMPLATE = stringPreferencesKey("filename_template")
        val AUTO_UPDATE = booleanPreferencesKey("auto_check_updates")
        val SYNC_ON_LAUNCH = booleanPreferencesKey("sync_on_launch")
        val SAVE_ARTIST_PHOTOS = booleanPreferencesKey("save_artist_photos_to_drive")
        val UPDATE_AVAILABLE = stringPreferencesKey("update_available")
        val DISCOGS_TOKEN = stringPreferencesKey("discogs_token")
        val JAMENDO_CLIENT_ID = stringPreferencesKey("jamendo_client_id")
        val SHOW_LYRICS = booleanPreferencesKey("show_lyrics")
        val LEVEL_VOLUME = booleanPreferencesKey("level_volume")
        val SAVE_LYRICS = booleanPreferencesKey("save_lyrics_to_drive")
        val WIFI_ONLY_LARGE = booleanPreferencesKey("wifi_only_large_transfers")
        val WEB_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_PIN = stringPreferencesKey("web_pin")
        val WEB_PORT = intPreferencesKey("web_port")
        val WEB_ERROR = stringPreferencesKey("web_error")

        val ARTIST_VIEW = stringPreferencesKey("artist_view_mode")
        val ARTIST_ALBUM_VIEW = stringPreferencesKey("artist_album_view_mode")
        val TRACK_SORT = stringPreferencesKey("track_sort")
        val ALBUM_SORT = stringPreferencesKey("album_sort")
        val ARTIST_SORT = stringPreferencesKey("artist_sort")
    }

    val settings: Flow<RoamSettings> = ctx.dataStore.data.map { p ->
        val mode = p[K.CACHE_MODE] ?: "tracks"
        val value = p[K.CACHE_VALUE] ?: 10L
        RoamSettings(
            driveFolderId = p[K.DRIVE_FOLDER_ID],
            driveFolderName = p[K.DRIVE_FOLDER_NAME],
            lastTrackCount = p[K.LAST_TRACK_COUNT],
            lastSyncAt = p[K.LAST_SYNC_AT],
            cachePolicy = if (mode == "bytes") CachePolicy.StorageBudget(value)
                          else CachePolicy.NextTracks(value.toInt()),
            prefetchOnMobile = p[K.PREFETCH_MOBILE] ?: false,
            lovedMultiplier = p[K.LOVED_MULT] ?: 3.0f,
            recencyDamping = p[K.RECENCY] ?: true,
            ignoreArticles = p[K.ARTICLES] ?: true,
            downloadFormatMp3 = p[K.FMT_MP3] ?: true,
            autoUploadToDrive = p[K.UPLOAD] ?: true,
            filenameTemplate = p[K.TEMPLATE] ?: "{track} {title} - {artist}",
            autoCheckUpdates = p[K.AUTO_UPDATE] ?: true,
            syncOnLaunch = p[K.SYNC_ON_LAUNCH] ?: true,
            saveArtistPhotosToDrive = p[K.SAVE_ARTIST_PHOTOS] ?: true,
            updateAvailable = p[K.UPDATE_AVAILABLE],
            discogsToken = p[K.DISCOGS_TOKEN],
            jamendoClientId = p[K.JAMENDO_CLIENT_ID],
            showLyrics = p[K.SHOW_LYRICS] ?: true,
            levelVolume = p[K.LEVEL_VOLUME] ?: true,
            saveLyricsToDrive = p[K.SAVE_LYRICS] ?: true,
            wifiOnlyForLargeTransfers = p[K.WIFI_ONLY_LARGE] ?: true,
            webServerEnabled = p[K.WEB_ENABLED] ?: false,
            webPin = p[K.WEB_PIN],
            webPort = p[K.WEB_PORT],
            webError = p[K.WEB_ERROR],
            artistViewMode = p[K.ARTIST_VIEW].toEnum(ViewMode.GRID_3),
            artistAlbumViewMode = p[K.ARTIST_ALBUM_VIEW].toEnum(ViewMode.GRID_3),
            trackSort = p[K.TRACK_SORT].toEnum(TrackSort.ARTIST),
            albumSort = p[K.ALBUM_SORT].toEnum(AlbumSort.ARTIST),
            artistSort = p[K.ARTIST_SORT].toEnum(ArtistSort.NAME),
        )
    }

    // Block bodies, not expression bodies. `dataStore.edit {}` returns
    // Preferences; an expression body would make that the inferred return type
    // and leak androidx.datastore into this module's public API. Note that
    // `: Unit = <expression>` does NOT work -- Kotlin requires an expression
    // body to match the declared type rather than discarding it.
    suspend fun setCachePolicy(policy: CachePolicy) {
        ctx.dataStore.edit { p ->
            when (policy) {
                is CachePolicy.NextTracks -> { p[K.CACHE_MODE] = "tracks"; p[K.CACHE_VALUE] = policy.count.toLong() }
                is CachePolicy.StorageBudget -> { p[K.CACHE_MODE] = "bytes"; p[K.CACHE_VALUE] = policy.bytes }
            }
        }
    }

    suspend fun setDriveFolder(id: String, name: String) {
        ctx.dataStore.edit {
            it[K.DRIVE_FOLDER_ID] = id
            it[K.DRIVE_FOLDER_NAME] = name
        }
    }

    suspend fun setSyncResult(trackCount: Int, at: Long = System.currentTimeMillis()) {
        ctx.dataStore.edit {
            it[K.LAST_TRACK_COUNT] = trackCount
            it[K.LAST_SYNC_AT] = at
        }
    }

    suspend fun clearDriveFolder() {
        ctx.dataStore.edit {
            it.remove(K.DRIVE_FOLDER_ID); it.remove(K.DRIVE_FOLDER_NAME)
            it.remove(K.LAST_TRACK_COUNT); it.remove(K.LAST_SYNC_AT)
        }
    }

    suspend fun setSyncOnLaunch(v: Boolean) {
        ctx.dataStore.edit { it[K.SYNC_ON_LAUNCH] = v }
    }

    suspend fun setUpdateAvailable(version: String?) {
        ctx.dataStore.edit {
            if (version == null) it.remove(K.UPDATE_AVAILABLE) else it[K.UPDATE_AVAILABLE] = version
        }
    }

    suspend fun setLovedMultiplier(v: Float) {
        ctx.dataStore.edit { it[K.LOVED_MULT] = v }
    }

    suspend fun setAutoCheckUpdates(v: Boolean) {
        ctx.dataStore.edit { it[K.AUTO_UPDATE] = v }
    }

    suspend fun setSaveArtistPhotosToDrive(v: Boolean) {
        ctx.dataStore.edit { it[K.SAVE_ARTIST_PHOTOS] = v }
    }

    suspend fun setWifiOnlyForLargeTransfers(v: Boolean) {
        ctx.dataStore.edit { it[K.WIFI_ONLY_LARGE] = v }
    }

    suspend fun setShowLyrics(v: Boolean) {
        ctx.dataStore.edit { it[K.SHOW_LYRICS] = v }
    }

    suspend fun setLevelVolume(v: Boolean) {
        ctx.dataStore.edit { it[K.LEVEL_VOLUME] = v }
    }

    suspend fun setSaveLyricsToDrive(v: Boolean) {
        ctx.dataStore.edit { it[K.SAVE_LYRICS] = v }
    }

    /**
     * Turns the server on or off, minting a PIN the first time.
     *
     * The PIN is generated HERE rather than by the server, because the switch
     * has to be able to show it in the same breath as the address -- needing
     * both and being shown one is the shape of a feature that feels broken.
     * `SecureRandom`, not `Random`: four digits is small enough that a
     * predictable sequence would be worth guessing.
     */
    suspend fun setWebServerEnabled(v: Boolean) {
        ctx.dataStore.edit {
            it[K.WEB_ENABLED] = v
            // A fresh attempt, or the person giving up: either way the last
            // failure is no longer the news.
            it.remove(K.WEB_ERROR)
            if (v && it[K.WEB_PIN].isNullOrBlank()) it[K.WEB_PIN] = newPin()
        }
    }

    /** The server is up, and this is the port it got. */
    suspend fun webServerStarted(port: Int) {
        ctx.dataStore.edit {
            it[K.WEB_PORT] = port
            it.remove(K.WEB_ERROR)
        }
    }

    /**
     * The server could not start: the switch goes back off, with the reason.
     *
     * One edit for both, so nothing ever sees the switch off without the
     * explanation, or the explanation beside a switch that is still on.
     */
    suspend fun webServerFailed(reason: String) {
        ctx.dataStore.edit {
            it[K.WEB_ENABLED] = false
            it[K.WEB_ERROR] = reason
        }
    }

    /** Signs every browser out, because the cookie carries the old PIN. */
    suspend fun rotateWebPin() {
        ctx.dataStore.edit { it[K.WEB_PIN] = newPin() }
    }

    suspend fun setDiscogsToken(token: String?) {
        ctx.dataStore.edit {
            val trimmed = token?.trim().orEmpty()
            if (trimmed.isBlank()) it.remove(K.DISCOGS_TOKEN) else it[K.DISCOGS_TOKEN] = trimmed
        }
    }

    suspend fun setJamendoClientId(id: String?) {
        ctx.dataStore.edit {
            val trimmed = id?.trim().orEmpty()
            if (trimmed.isBlank()) it.remove(K.JAMENDO_CLIENT_ID) else it[K.JAMENDO_CLIENT_ID] = trimmed
        }
    }

    suspend fun setArtistViewMode(v: ViewMode) {
        ctx.dataStore.edit { it[K.ARTIST_VIEW] = v.name }
    }

    suspend fun setArtistAlbumViewMode(v: ViewMode) {
        ctx.dataStore.edit { it[K.ARTIST_ALBUM_VIEW] = v.name }
    }

    suspend fun setTrackSort(v: TrackSort) {
        ctx.dataStore.edit { it[K.TRACK_SORT] = v.name }
    }

    suspend fun setAlbumSort(v: AlbumSort) {
        ctx.dataStore.edit { it[K.ALBUM_SORT] = v.name }
    }

    suspend fun setArtistSort(v: ArtistSort) {
        ctx.dataStore.edit { it[K.ARTIST_SORT] = v.name }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule
