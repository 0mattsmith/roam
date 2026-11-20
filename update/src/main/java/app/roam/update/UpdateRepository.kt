package app.roam.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import app.roam.core.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for "is there a new build, and can we install it".
 *
 * Settings, the launch-time check and the banner all read this. Duplicating the
 * check/download/install sequence per screen is how two surfaces end up
 * disagreeing about whether an update exists -- and how two of them can each
 * start an install of the same APK.
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

    /**
     * True from the tap until Android answers.
     *
     * One install at a time, whichever button asked. A second session
     * committed while the first is still installing is refused by the system
     * as "Duplicate package ... in pending install requests" -- and a second
     * tap is exactly what the old flow invited: the button came back the
     * moment the APK was handed over, and a silent update shows nothing in
     * the meantime.
     */
    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    /**
     * Android is waiting for the person to confirm. Its screen can be closed
     * without an answer, so the button stays, and puts it back rather than
     * starting another install.
     */
    private val _confirming = MutableStateFlow(false)
    val confirming: StateFlow<Boolean> = _confirming.asStateFlow()

    /** The system's confirmation screen, kept so it can be shown again. */
    private var confirmation: Intent? = null

    /** In memory only: dismissing hides the banner for this run, not forever. */
    private val _dismissed = MutableStateFlow(false)
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    /** Which version the user waved away, so re-checking the same one respects it. */
    private var dismissedVersion: String? = null

    /** Guards against re-checking on every single return to the foreground. */
    private var lastCheckedAt = 0L

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
        lastCheckedAt = System.currentTimeMillis()
        _available.value = found

        // Only a version the user has NOT already dismissed should re-open the
        // banner. Un-dismissing on every check would mean closing it, switching
        // apps, and finding it back again on return.
        if (found != null && found.versionName != dismissedVersion) {
            _dismissed.value = false
        }

        settings.setUpdateAvailable(found?.versionName)
        return found
    }

    /** Silent variant for launch: a missing connection is not an error to show. */
    suspend fun checkQuietly() {
        runCatching { check() }
    }

    /**
     * For returning to the foreground. Throttled because a resume happens every
     * time the user glances at a notification, and the unauthenticated GitHub
     * API allows 60 requests an hour.
     */
    suspend fun checkOnResume() {
        if (!settings.settings.first().autoCheckUpdates) return
        if (System.currentTimeMillis() - lastCheckedAt < RESUME_CHECK_INTERVAL_MS) return
        checkQuietly()
    }

    fun dismiss() {
        _dismissed.value = true
        dismissedVersion = _available.value?.versionName
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * Downloads and installs [update], or the one the last check found.
     *
     * While one is under way this starts nothing. At most it puts the system's
     * confirmation back on screen, for someone who closed it without answering.
     */
    suspend fun install(update: AvailableUpdate? = null) {
        if (_installing.value) {
            confirmation?.let { ctx.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        val target = update ?: _available.value ?: return

        if (!installer.canInstall()) {
            ctx.startActivity(installer.unknownSourcesIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            _message.value = "Allow installs from Roam, then tap Install again."
            return
        }

        // Claimed before the first suspension, on the main thread, so two taps
        // cannot both get past the check above.
        _installing.value = true
        _confirming.value = false
        confirmation = null
        _message.value = null
        _downloadPercent.value = 0
        runCatching {
            val apk = downloader.download(target) { _downloadPercent.value = it }
            // Off the main thread: this copies the whole APK into the session.
            withContext(Dispatchers.IO) {
                installer.install(apk, UpdateInstallReceiver.pendingIntent(ctx).intentSender)
            }
        }.onSuccess {
            _downloadPercent.value = null
            // Android may have answered already. Its word stands over this one.
            if (_installing.value && !_confirming.value) {
                // Usually silent once Roam has installed itself, so this may be
                // the only sign anything is happening.
                _message.value = "Installing. Roam closes when it is done; open it again."
            }
        }.onFailure { e ->
            _downloadPercent.value = null
            _installing.value = false
            _message.value = "Update failed: ${e.message}"
        }
    }

    /**
     * What Android said about the install, relayed by [UpdateInstallReceiver].
     *
     * Success needs nothing here: the process is replaced along with the APK.
     */
    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = confirm
                _confirming.value = true
                _message.value = "Confirm the install when prompted."
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                confirmation = null
                _confirming.value = false
                _installing.value = false
                _message.value =
                    if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "Update cancelled."
                    else "Update failed: ${message ?: "unknown"}"
            }
        }
    }

    private companion object {
        /** Long enough that flicking between apps costs nothing. */
        const val RESUME_CHECK_INTERVAL_MS = 15L * 60 * 1000
    }
}
