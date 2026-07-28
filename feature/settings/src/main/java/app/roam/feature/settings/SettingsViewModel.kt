package app.roam.feature.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.roam.data.catalog.sync.SyncWorker
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.data.source.drive.DriveAuth
import app.roam.data.source.drive.DriveSourceProvider
import app.roam.data.source.drive.DriveSourceProvider.Companion.SOURCE_ID
import app.roam.update.AvailableUpdate
import app.roam.update.UpdateChecker
import app.roam.update.UpdateDownloader
import app.roam.update.UpdateInstallReceiver
import app.roam.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val connected: Boolean = false,
    val folderName: String? = null,
    val folderId: String? = null,
    val syncing: Boolean = false,
    val tracksFound: Int? = null,
    val message: String? = null,
    val busy: Boolean = false,

    val installedVersion: String = "",
    val checkingUpdate: Boolean = false,
    val update: AvailableUpdate? = null,
    val downloadPercent: Int? = null,
    val updateMessage: String? = null,

    val syncOnLaunch: Boolean = true,
    val autoCheckUpdates: Boolean = true,
    val saveArtistPhotos: Boolean = true,
    val confirmDisconnect: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    app: Application,
    private val auth: DriveAuth,
    private val drive: DriveSourceProvider,
    private val updateChecker: UpdateChecker,
    private val updateDownloader: UpdateDownloader,
    private val updateInstaller: UpdateInstaller,
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

    fun checkForUpdate() = viewModelScope.launch {
        _state.update { it.copy(checkingUpdate = true, updateMessage = null, update = null) }
        val ctx = getApplication<Application>()
        val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }
        runCatching { updateChecker.check(code, updateInstaller.preferredAbi()) }
            .onSuccess { found ->
                _state.update {
                    it.copy(
                        checkingUpdate = false,
                        update = found,
                        updateMessage = if (found == null) "You're on the latest version." else null,
                    )
                }
            }
            .onFailure { e ->
                _state.update { it.copy(checkingUpdate = false, updateMessage = "Check failed: ${e.message}") }
            }
    }

    fun installUpdate() = viewModelScope.launch {
        val update = _state.value.update ?: return@launch
        val ctx = getApplication<Application>()

        if (!updateInstaller.canInstall()) {
            ctx.startActivity(updateInstaller.unknownSourcesIntent().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            _state.update { it.copy(updateMessage = "Allow installs from Roam, then tap Update again.") }
            return@launch
        }

        _state.update { it.copy(downloadPercent = 0, updateMessage = null) }
        runCatching {
            val apk = updateDownloader.download(update) { pct ->
                _state.update { it.copy(downloadPercent = pct) }
            }
            updateInstaller.install(apk, UpdateInstallReceiver.pendingIntent(ctx).intentSender)
        }.onSuccess {
            _state.update { it.copy(downloadPercent = null, updateMessage = "Confirm the install when prompted.") }
        }.onFailure { e ->
            _state.update { it.copy(downloadPercent = null, updateMessage = "Update failed: ${e.message}") }
        }
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
