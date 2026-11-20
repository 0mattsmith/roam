package app.roam.feature.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.roam.data.catalog.sync.SyncWorker
import app.roam.core.common.WebAddress
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.metadata.ConsolidateWorker
import app.roam.data.catalog.metadata.LyricsWorker
import app.roam.data.catalog.tags.TagWorker
import app.roam.data.source.drive.DriveAuth
import app.roam.data.source.drive.DriveSourceProvider
import app.roam.data.source.drive.DriveSourceProvider.Companion.SOURCE_ID
import app.roam.update.AvailableUpdate
import app.roam.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The web interface as Settings has to show it.
 *
 * The address and the PIN sit in one object because they are shown together:
 * needing both and being given one is the shape of a feature that feels
 * broken. A null url means no network Roam could serve on.
 */
data class WebUiState(
    val enabled: Boolean = false,
    val pin: String? = null,
    val url: String? = null,
    /** Why the last start failed. The switch is off whenever this is set. */
    val error: String? = null,
)

data class SettingsUiState(
    val connected: Boolean = false,
    val folderName: String? = null,
    val folderId: String? = null,
    val syncing: Boolean = false,
    val tracksFound: Int? = null,
    /**
     * What a list would actually show, and what cannot reach one.
     *
     * Kept beside the raw count on purpose: the gap between them is the only
     * thing that distinguishes "the library is gone" from "the library is
     * there and something is dropping it on the way out", and those have
     * nothing in common except how they look.
     */
    val visibleTracks: Int? = null,
    val hiddenTracks: Int = 0,
    val missingTracks: Int = 0,
    val orphanTracks: Int = 0,
    val message: String? = null,
    val busy: Boolean = false,

    val installedVersion: String = "",
    val checkingUpdate: Boolean = false,
    val update: AvailableUpdate? = null,
    val downloadPercent: Int? = null,
    val updateMessage: String? = null,
    /** Handed to Android and not yet answered, with nothing for the person to do. */
    val installingUpdate: Boolean = false,
    /** What the install last said. Shown in place of [updateMessage] while set. */
    val installMessage: String? = null,

    val syncOnLaunch: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val saveArtistPhotos: Boolean = true,
    val confirmDisconnect: Boolean = false,
)

/** What the lyrics sweep row shows. */
data class LyricsSweepUi(
    val running: Boolean,
    val found: Int,
    val done: Int,
    val total: Int,
    val finished: Boolean,
)

/** What Consolidate is doing, or what it did. */
data class ConsolidateUi(
    val running: Boolean,
    val finished: Boolean,
    /** Documents the library holds. Zero is the answer that offers to create. */
    val found: Int,
    val read: Int,
    val applied: Int,
    val unreadable: Int,
    /** Files written, when this run was creating rather than reading. */
    val written: Int,
    val done: Int,
    val total: Int,
    val error: String?,
) {
    /** Only meaningful while creating; reading has no per-item total. */
    val fraction: Float? get() = if (total > 0) done.toFloat() / total else null
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    app: Application,
    private val auth: DriveAuth,
    private val drive: DriveSourceProvider,
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
    private val trackDao: TrackDao,
) : AndroidViewModel(app) {


    // Seeded here rather than in an init block: initializers run in source
    // order, and an init block above this line cannot touch _state.
    // The version comes from PackageManager because library modules have no
    // BuildConfig of their own.
    private val _state = MutableStateFlow(
        SettingsUiState(
            installedVersion = runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
            }.getOrDefault("")
        )
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Emitted when Google needs the user to approve the scope. */
    private val _consent = MutableStateFlow<PendingIntent?>(null)
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    init {
        restore()
        watchUpdates()
    }

    /**
     * The install lives in UpdateRepository, shared with the banner, so there
     * is one of it however many buttons offer it. This screen only mirrors it.
     */
    private fun watchUpdates() {
        viewModelScope.launch {
            updates.available.collect { found -> _state.update { it.copy(update = found) } }
        }
        viewModelScope.launch {
            updates.downloadPercent.collect { pct -> _state.update { it.copy(downloadPercent = pct) } }
        }
        viewModelScope.launch {
            combine(updates.installing, updates.confirming) { installing, confirming -> installing && !confirming }
                .collect { busy -> _state.update { it.copy(installingUpdate = busy) } }
        }
        viewModelScope.launch {
            updates.message.collect { said -> _state.update { it.copy(installMessage = said) } }
        }
    }

    /** Just the number, for the row that leads to the page listing them. */
    val hiddenCount = trackDao.hiddenCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * How a consolidate run is going, or how the last one went.
     *
     * Shaped like lyricsSweep: progress while running, the output data once it
     * has finished, so the result stays on screen rather than vanishing at the
     * moment somebody looks up.
     */
    val consolidate: StateFlow<ConsolidateUi?> =
        WorkManager.getInstance(getApplication())
            .getWorkInfosForUniqueWorkFlow(ConsolidateWorker.NAME)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                val finished = info.state == WorkInfo.State.SUCCEEDED
                val data = if (finished) info.outputData else info.progress
                ConsolidateUi(
                    running = info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.ENQUEUED,
                    finished = finished,
                    found = data.getInt(ConsolidateWorker.KEY_FOUND, 0),
                    read = data.getInt(ConsolidateWorker.KEY_READ, 0),
                    applied = data.getInt(ConsolidateWorker.KEY_APPLIED, 0),
                    unreadable = data.getInt(ConsolidateWorker.KEY_UNREADABLE, 0),
                    written = data.getInt(ConsolidateWorker.KEY_WRITTEN, 0),
                    done = data.getInt(ConsolidateWorker.KEY_DONE, 0),
                    total = data.getInt(ConsolidateWorker.KEY_TOTAL, 0),
                    error = if (info.state == WorkInfo.State.FAILED) {
                        info.outputData.getString(ConsolidateWorker.KEY_ERROR) ?: "Could not finish"
                    } else {
                        null
                    },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun consolidateMetadata(mode: String) = viewModelScope.launch {
        val root = settings.settings.first().driveFolderId
        if (root == null) {
            _tagMessage.value = "Choose a music folder first"
            return@launch
        }
        ConsolidateWorker.enqueue(getApplication(), root, mode)
    }

    /** Tracks marked as hand-edited on files whose tags were never read. */
    val frozenCount = trackDao.frozenOnGuessCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Asks the tag pass to try again.
     *
     * FAILED is terminal by design -- it is what stops a file with no tags
     * being re-read on every sync -- so without this there was no way back from
     * a read that failed on a flaky connection. [everything] widens it to files
     * that were read fine, for when the parser itself has improved.
     */
    fun readTagsAgain(everything: Boolean) = viewModelScope.launch {
        val sourceId = drive.sourceId
        val queued =
            if (everything) trackDao.retryAllTags(sourceId) else trackDao.retryFailedTags(sourceId)
        TagWorker.enqueue(getApplication(), settings.settings.first().wifiOnlyForLargeTransfers)
        _tagMessage.value = when (queued) {
            0 -> "Nothing to re-read"
            1 -> "Reading 1 track"
            else -> "Reading $queued tracks"
        }
    }

    private val _tagMessage = MutableStateFlow<String?>(null)
    val tagMessage: StateFlow<String?> = _tagMessage.asStateFlow()

    fun tagMessageShown() {
        _tagMessage.value = null
    }

    val showLyrics = settings.settings
        .map { it.showLyrics }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setShowLyrics(v: Boolean) = viewModelScope.launch {
        settings.setShowLyrics(v)
    }

    val saveLyricsToDrive = settings.settings
        .map { it.saveLyricsToDrive }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setSaveLyricsToDrive(v: Boolean) = viewModelScope.launch {
        settings.setSaveLyricsToDrive(v)
    }

    /**
     * Progress of a lyrics sweep, straight from WorkManager rather than mirrored
     * in state -- the pass outlives this screen, and a copy here would be wrong
     * the moment someone backed out and came back.
     */
    val lyricsSweep: StateFlow<LyricsSweepUi?> =
        WorkManager.getInstance(getApplication())
            .getWorkInfosForUniqueWorkFlow(LyricsWorker.NAME)
            .map { infos ->
                val info = infos.firstOrNull() ?: return@map null
                val data = if (info.state == WorkInfo.State.SUCCEEDED) info.outputData
                           else info.progress
                LyricsSweepUi(
                    running = info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.ENQUEUED,
                    found = data.getInt(LyricsWorker.KEY_FOUND, 0),
                    done = data.getInt(LyricsWorker.KEY_DONE, 0),
                    total = data.getInt(LyricsWorker.KEY_TOTAL, 0),
                    finished = info.state == WorkInfo.State.SUCCEEDED,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * @param force re-ask about tracks already tried. Roam cannot tell a WRONG
     * lyric from a right one -- there is nothing to compare against -- so this
     * is how someone says "these are not the words".
     */
    fun syncLyrics(force: Boolean) = viewModelScope.launch {
        LyricsWorker.enqueue(
            ctx = getApplication(),
            wifiOnly = settings.settings.first().wifiOnlyForLargeTransfers,
            force = force,
        )
    }

    val wifiOnlyLargeTransfers = settings.settings
        .map { it.wifiOnlyForLargeTransfers }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWifiOnlyLargeTransfers(v: Boolean) = viewModelScope.launch {
        settings.setWifiOnlyForLargeTransfers(v)
    }

    /**
     * The web interface, and the two things needed to actually reach it.
     *
     * The address is worked out on every emission rather than once: a phone
     * that has moved between networks since Settings was last opened would
     * otherwise show an address that cannot load, with nothing on screen to
     * explain why. Toggling the switch re-reads it, which is the moment it
     * matters most.
     */
    val webUi = settings.settings
        .map { s ->
            WebUiState(
                enabled = s.webServerEnabled,
                pin = s.webPin,
                // The port the server last got, which is not always 8080.
                url = WebAddress.current()?.let { WebAddress.url(it, s.webPort ?: WebAddress.PORT) },
                error = s.webError,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebUiState())

    /**
     * Writes the setting and nothing else.
     *
     * Starting the service is `:app`'s job, because a UI feature may not
     * depend on another one -- and because the setting, not an intent, is what
     * decides whether the server should exist. One source of truth survives a
     * process restart; an intent does not.
     */
    fun setWebServerEnabled(v: Boolean) = viewModelScope.launch {
        settings.setWebServerEnabled(v)
    }

    fun rotateWebPin() = viewModelScope.launch { settings.rotateWebPin() }

    /** Kept as typed. Trimming and emptiness are handled on the way to disk. */
    val discogsToken = settings.settings
        .map { it.discogsToken.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setDiscogsToken(token: String) = viewModelScope.launch {
        settings.setDiscogsToken(token)
    }

    /** Kept as typed, like the Discogs token. */
    val jamendoClientId = settings.settings
        .map { it.jamendoClientId.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setJamendoClientId(id: String) = viewModelScope.launch {
        settings.setJamendoClientId(id)
    }

    /**
     * The ViewModel dies when you navigate away from Settings, so everything
     * below has to be recovered rather than assumed. Play Services remembers
     * the grant, so re-authorising is silent -- it only prompts if consent was
     * never given or has been revoked.
     */
    private fun restore() = viewModelScope.launch {
        val saved = settings.settings.first()
        _state.update {
            it.copy(
                folderId = saved.driveFolderId,
                folderName = saved.driveFolderName,
                tracksFound = saved.lastTrackCount,
            )
        }

        // Silent: if this returns NeedsConsent we simply stay disconnected
        // rather than throwing a consent dialog at someone who just opened
        // Settings to change the cache size.
        _state.update {
            it.copy(
                syncOnLaunch = saved.syncOnLaunch,
                autoCheckUpdates = saved.autoCheckUpdates,
                saveArtistPhotos = saved.saveArtistPhotosToDrive,
            )
        }

        if (auth.authorize() is DriveAuth.Outcome.Granted) {
            _state.update { it.copy(connected = true) }
            // Connected but no stored folder: either this account predates
            // folder persistence, or the lookup failed last time. Re-find it
            // rather than leaving the UI with no way to refresh.
            if (saved.driveFolderId == null) locateLibraryFolder()
        }

        // The catalogue is the source of truth once tracks are stored; the
        // DataStore count above is only a fallback for the very first run.
        launch {
            trackDao.count().collect { n ->
                if (!_state.value.syncing) _state.update { it.copy(tracksFound = n) }
            }
        }
        launch { trackDao.visibleCount().collect { n -> _state.update { it.copy(visibleTracks = n) } } }
        launch { trackDao.hiddenCount().collect { n -> _state.update { it.copy(hiddenTracks = n) } } }
        launch { trackDao.missingCount().collect { n -> _state.update { it.copy(missingTracks = n) } } }
        launch { trackDao.orphanCount().collect { n -> _state.update { it.copy(orphanTracks = n) } } }

        // A crawl started earlier may still be running in WorkManager.
        observeSync()
    }

    fun connect() = viewModelScope.launch {
        _state.update { it.copy(busy = true, message = null) }
        when (val outcome = auth.authorize()) {
            is DriveAuth.Outcome.Granted -> onConnected()
            is DriveAuth.Outcome.NeedsConsent -> {
                _consent.value = outcome.pendingIntent
                _state.update { it.copy(busy = false) }
            }
            is DriveAuth.Outcome.Failed ->
                _state.update { it.copy(busy = false, message = "Sign-in failed: ${outcome.cause.message}") }
        }
    }

    fun onConsentResult(data: Intent?) = viewModelScope.launch {
        _consent.value = null
        when (val outcome = auth.completeConsent(data)) {
            is DriveAuth.Outcome.Granted -> onConnected()
            is DriveAuth.Outcome.Failed ->
                _state.update { it.copy(busy = false, message = "Consent failed: ${outcome.cause.message}") }
            else -> _state.update { it.copy(busy = false) }
        }
    }

    fun consentHandled() { _consent.value = null }

    private suspend fun onConnected() {
        _state.update { it.copy(connected = true) }
        locateLibraryFolder()
    }

    /** Locate the library root. Phase 3 replaces this with a proper picker. */
    private suspend fun locateLibraryFolder() {
        _state.update { it.copy(busy = true, message = "Looking for /Music…") }
        val folder = runCatching { drive.findFolder("Music") }.getOrNull()
        _state.update {
            if (folder == null) {
                it.copy(busy = false, message = "No folder named 'Music' at the root of your Drive.")
            } else {
                it.copy(busy = false, folderName = folder.name, folderId = folder.id, message = null)
            }
        }
        folder?.let { settings.setDriveFolder(it.id, it.name) }
    }

    // ---- updates ----------------------------------------------------------

    /**
     * Through the repository, so the banner hears about what this finds. What
     * it found arrives in `update` by way of [watchUpdates].
     */
    fun checkForUpdate() = viewModelScope.launch {
        _state.update { it.copy(checkingUpdate = true, updateMessage = null) }
        updates.clearMessage()
        runCatching { updates.check() }
            .onSuccess { found ->
                _state.update {
                    it.copy(
                        checkingUpdate = false,
                        updateMessage = if (found == null) "You're on the latest version." else null,
                    )
                }
            }
            .onFailure { e ->
                _state.update { it.copy(checkingUpdate = false, updateMessage = "Check failed: ${e.message}") }
            }
    }

    /** Through the repository, so this button and the banner share one install. */
    fun installUpdate() = viewModelScope.launch {
        updates.install(_state.value.update)
    }

    fun setSyncOnLaunch(v: Boolean) = viewModelScope.launch {
        _state.update { it.copy(syncOnLaunch = v) }
        settings.setSyncOnLaunch(v)
    }

    fun setAutoCheckUpdates(v: Boolean) = viewModelScope.launch {
        _state.update { it.copy(autoCheckUpdates = v) }
        settings.setAutoCheckUpdates(v)
    }

    fun setSaveArtistPhotos(v: Boolean) = viewModelScope.launch {
        _state.update { it.copy(saveArtistPhotos = v) }
        settings.setSaveArtistPhotosToDrive(v)
    }

    fun askDisconnect(show: Boolean) {
        _state.update { it.copy(confirmDisconnect = show) }
    }

    /**
     * Forgets the source and its catalogue.
     *
     * Play Services' Identity API has no revoke call, so the account's grant to
     * Roam survives on Google's side -- reconnecting will not re-prompt. To
     * revoke properly the user has to visit their Google account's third-party
     * access page, which the UI says.
     */
    fun disconnect() = viewModelScope.launch {
        _state.update { it.copy(confirmDisconnect = false, busy = true) }
        trackDao.deleteAllForSource(SOURCE_ID)
        settings.clearDriveFolder()
        auth.invalidate()
        _state.update {
            SettingsUiState(
                installedVersion = it.installedVersion,
                // Mirrors of UpdateRepository, which emits only on change, so
                // a reset here would hide an update until the next one.
                update = it.update,
                downloadPercent = it.downloadPercent,
                installingUpdate = it.installingUpdate,
                installMessage = it.installMessage,
                syncOnLaunch = it.syncOnLaunch,
                autoCheckUpdates = it.autoCheckUpdates,
                saveArtistPhotos = it.saveArtistPhotos,
                message = "Disconnected. Library cleared.",
            )
        }
    }

    fun refreshLibrary() {
        val root = _state.value.folderId ?: return
        _state.update { it.copy(syncing = true, tracksFound = null, message = null) }
        val ctx = getApplication<Application>()
        SyncWorker.enqueue(ctx, root)
        observeSync()
    }

    private fun observeSync() = viewModelScope.launch {
        val ctx = getApplication<Application>()
        WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.NAME_ONESHOT)
            .collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val running = info.progress.getInt(SyncWorker.KEY_FOUND, -1)
                when (info.state) {
                    WorkInfo.State.RUNNING -> _state.update {
                        it.copy(syncing = true, tracksFound = running.takeIf { n -> n >= 0 })
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val found = info.outputData.getInt(SyncWorker.KEY_FOUND, 0)
                        _state.update { it.copy(syncing = false, tracksFound = found) }
                        settings.setSyncResult(found)
                    }
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(syncing = false, message = info.outputData.getString(SyncWorker.KEY_ERROR) ?: "Sync failed")
                    }
                    else -> Unit
                }
            }
    }
}
