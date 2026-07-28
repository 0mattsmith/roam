package app.roam.update

import android.content.Context
import android.content.Intent
import android.os.Build
import app.roam.core.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "is there a new build, and can we install it".
 *
 * Settings, the launch-time check and the banner all read this. Duplicating the
 * check/download/install sequence per screen is how two surfaces end up
 * disagreeing about whether an update exists.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
    private val settings: SettingsRepository,
) {
    private val _available = MutableStateFlow<AvailableUpdate?>(null)
    val available: StateFlow<AvailableUpdate?> = _available.asStateFlow()

    private val _downloadPercent = MutableStateFlow<Int?>(null)
    val downloadPercent: StateFlow<Int?> = _downloadPercent.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** In memory only: dismissing hides the banner for this run, not forever. */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    val installedVersionName: String
        get() = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

    private val installedVersionCode: Int
        get() = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }

    /** Returns null when already current. Throws only for genuine failures. */
    suspend fun check(): AvailableUpdate? {
        val found = checker.check(installedVersionCode, installer.preferredAbi())
        _available.value = found
        if (found != null) _dismissed.value = false      // a new build un-dismisses
        settings.setUpdateAvailable(found?.versionName)
        return found
    }

    /** Silent variant for launch: a missing connection is not an error to show. */
    suspend fun checkQuietly() {
        runCatching { check() }
    }

    fun dismiss() {
        _dismissed.value = true
    }

    fun clearMessage() {
        _message.value = null
    }

    suspend fun install() {
        val update = _available.value ?: return

        if (!installer.canInstall()) {
            ctx.startActivity(installer.unknownSourcesIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _message.value = "Allow installs from Roam, then tap Install again."
            return
        }

        _downloadPercent.value = 0
        runCatching {
            val apk = downloader.download(update) { _downloadPercent.value = it }
            installer.install(apk, UpdateInstallReceiver.pendingIntent(ctx).intentSender)
        }.onSuccess {
            _downloadPercent.value = null
            _message.value = "Confirm the install when prompted."
        }.onFailure { e ->
            _downloadPercent.value = null
            _message.value = "Update failed: ${e.message}"
        }
    }
}
