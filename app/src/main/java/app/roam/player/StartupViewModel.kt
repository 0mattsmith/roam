package app.roam.player

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.sync.SyncWorker
import app.roam.update.UpdateChecker
import app.roam.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Work that runs once when the app opens.
 *
 * Both tasks are opt-out in Settings and neither blocks the UI: the sync is
 * handed to WorkManager, and the update check only records what it found so
 * Settings can show it. Nothing here interrupts the user.
 */
@HiltViewModel
class StartupViewModel @Inject constructor(
    app: Application,
    private val settings: SettingsRepository,
    private val updateChecker: UpdateChecker,
    private val updateInstaller: UpdateInstaller,
) : AndroidViewModel(app) {

    init {
        runStartupTasks()
    }

    private fun runStartupTasks() = viewModelScope.launch {
        val saved = settings.settings.first()
        val ctx = getApplication<Application>()

        // A refresh is cheap once the catalogue exists -- unchanged files are a
        // hash-map lookup, so this is usually a few seconds of folder listing.
        if (saved.syncOnLaunch && saved.driveFolderId != null) {
            SyncWorker.enqueue(ctx, saved.driveFolderId)
        }

        if (saved.autoCheckUpdates) {
            val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
            // Failure here is silent by design: no connection on launch should
            // not produce an error the user has to dismiss.
            val found = runCatching {
                updateChecker.check(code, updateInstaller.preferredAbi())
            }.getOrNull()
            settings.setUpdateAvailable(found?.versionName)
        }
    }
}
