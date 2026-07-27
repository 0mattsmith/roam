package app.roam.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * PackageInstaller reports asynchronously. STATUS_PENDING_USER_ACTION is the
 * normal first response: the system wants the user to confirm, and hands back
 * an Intent to show.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // the app is about to restart
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Update failed: ${msg ?: "unknown"}", Toast.LENGTH_LONG).show()
            }
        }
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
