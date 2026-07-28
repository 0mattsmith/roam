package app.roam.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.sync.SyncWorker
import app.roam.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Work that runs once when the app opens.
 *
 * Both tasks are opt-out in Settings and neither blocks the UI: the sync is
 * handed to WorkManager, and the update check only records what it found in
 * UpdateRepository, which the banner observes. Nothing here interrupts the
 * user.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    app: Application,
    private val settings: SettingsRepository,
    private val updates: UpdateRepository,
) : AndroidViewModel(app) {

    init {
        runStartupTasks()
    }

    private fun runStartupTasks() = viewModelScope.launch {
        val saved = settings.settings.first()
        val ctx = getApplication<Application>()

        // Captured locally because Kotlin will not smart-cast a property
        // declared in another module -- it cannot prove the getter is stable.
        val folderId = saved.driveFolderId

        // A refresh is cheap once the catalogue exists -- unchanged files are a
        // hash-map lookup, so this is usually a few seconds of folder listing.
        if (saved.syncOnLaunch && folderId != null) {
            SyncWorker.enqueue(ctx, folderId)
        }

        // Result lands in UpdateRepository, which the banner observes.
        if (saved.autoCheckUpdates) updates.checkQuietly()
    }
}
