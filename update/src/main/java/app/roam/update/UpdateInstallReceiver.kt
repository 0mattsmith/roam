package app.roam.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * PackageInstaller reports asynchronously. STATUS_PENDING_USER_ACTION is the
 * normal first response when Android wants the user to confirm, and it hands
 * back an Intent to show. Once Roam is the installer of record for itself the
 * update is usually silent and the next thing heard is the result.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val confirm = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) confirmation(intent) else null

        // The repository first, so the button that started this knows whether
        // it is over. Guarded, because the toast below must happen regardless.
        runCatching {
            EntryPointAccessors
                .fromApplication(context.applicationContext, UpdateEntryPoint::class.java)
                .updates()
                .onInstallStatus(status, message, confirm)
        }

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                confirm?.let { context.startActivity(Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit   // the process is replaced with the APK
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Toast.makeText(context, "Update cancelled", Toast.LENGTH_LONG).show()
            else ->
                Toast.makeText(context, "Update failed: ${message ?: "unknown"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }

    companion object {
        fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx,
            0,
            Intent(ctx, UpdateInstallReceiver::class.java).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/**
 * How the receiver reaches the repository. Android creates receivers, not
 * Hilt, so it asks the graph rather than having anything injected.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdateEntryPoint {
    fun updates(): UpdateRepository
}
