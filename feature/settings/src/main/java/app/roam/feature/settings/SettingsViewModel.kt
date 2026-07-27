package app.roam.feature.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.roam.data.catalog.sync.SyncWorker
import app.roam.data.source.drive.DriveAuth
import app.roam.data.source.drive.DriveSourceProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    app: Application,
    private val auth: DriveAuth,
    private val drive: DriveSourceProvider,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    /** Emitted when Google needs the user to approve the scope. */
    private val _consent = MutableStateFlow<PendingIntent?>(null)
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

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

    /** Locate the library root. Phase 3 replaces this with a proper picker. */
    private suspend fun onConnected() {
        _state.update { it.copy(connected = true, busy = true, message = "Looking for /Music…") }
        val folder = runCatching { drive.findFolder("Music") }.getOrNull()
        _state.update {
            if (folder == null) {
                it.copy(busy = false, message = "No folder named 'Music' at the root of your Drive.")
            } else {
                it.copy(busy = false, folderName = folder.name, folderId = folder.id, message = null)
            }
        }
    }

    fun syncNow() {
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
                    WorkInfo.State.SUCCEEDED -> _state.update {
                        it.copy(syncing = false, tracksFound = info.outputData.getInt(SyncWorker.KEY_FOUND, 0))
                    }
                    WorkInfo.State.FAILED -> _state.update {
                        it.copy(syncing = false, message = info.outputData.getString(SyncWorker.KEY_ERROR) ?: "Sync failed")
                    }
                    else -> Unit
                }
            }
    }
}
