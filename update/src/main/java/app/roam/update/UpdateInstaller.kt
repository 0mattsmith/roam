package app.roam.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

class ChecksumMismatch(msg: String) : SecurityException(msg)

/**
 * Sideloaded self-update.
 *
 * The signing key must NEVER change. Android refuses to install an APK signed
 * with a different key over an existing install -- recovering means uninstall,
 * which takes the database with it. Back up roam-release.jks and its passwords.
 */
class UpdateInstaller @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(android.net.Uri.parse("package:${ctx.packageName}"))

    /**
     * The only thing standing between you and a corrupted or tampered APK.
     * push.ps1 uploads a .sha256 sidecar next to every artifact.
     */
    fun verify(apk: File, expectedSha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedSha256.trim().take(64), ignoreCase = true)) {
            throw ChecksumMismatch("APK checksum mismatch: expected $expectedSha256, got $actual")
        }
    }

    /** PackageInstaller, not the deprecated ACTION_INSTALL_PACKAGE intent. */
    fun install(apk: File, statusIntentSender: android.content.IntentSender) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("roam", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            session.commit(statusIntentSender)
        }
    }

    /** Roam ships arm64-v8a and armeabi-v7a splits; pick the device's own. */
    fun preferredAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
