package app.roam.feature.webui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.roam.core.common.WebAddress
import app.roam.core.database.QueueDao
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.artwork.ArtworkStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Owns the server's lifetime, because Android will not let anything else.
 *
 * A plain object holding a socket is killed within minutes of the screen going
 * off -- so the foreground service and its notification are not ceremony, they
 * are the feature working at all. That is the honest trade and the Settings
 * subtitle says so rather than hiding it.
 *
 * STAGE ONE EXISTS TO ANSWER ONE QUESTION: does this survive the screen going
 * off, on a phone with aggressive battery management? Everything else in the
 * spec depends on the answer, and nothing should be built on top of it until
 * someone has left it running and come back.
 */
@AndroidEntryPoint
class WebService : LifecycleService() {

    @Inject lateinit var queue: QueueDao
    @Inject lateinit var artwork: ArtworkStore
    @Inject lateinit var settings: SettingsRepository

    private var server: RoamWebServer? = null

    /**
     * The live PIN, read by the server on every request.
     *
     * Atomic because two threads genuinely touch it: this service's coroutine
     * writes it when Settings rotates the PIN, and a NanoHTTPD worker reads it
     * while answering a request.
     */
    private val pin = AtomicReference<String?>(null)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification(WebAddress.current()))

        lifecycleScope.launch {
            settings.settings
                .map { it.webPin to it.webServerEnabled }
                .distinctUntilChanged()
                .collect { (current, enabled) ->
                    pin.set(current)
                    // Switched off while running: stop, rather than linger with
                    // a notification for something that is no longer serving.
                    // The setting is the single source of truth for whether
                    // this should exist -- not the intent that started it.
                    if (!enabled) stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (server == null) {
            val fresh = RoamWebServer(assets, queue, artwork) { pin.get() }
            // daemon = false. A daemon thread dies with the process at a moment
            // nothing chose; this one is stopped in onDestroy, which is the only
            // place that knows the serving is meant to be over.
            if (runCatching { fresh.start(SOCKET_TIMEOUT_MS, false) }.isSuccess) {
                server = fresh
            } else {
                // Almost always the port already being in use. Turning the
                // SETTING back off is what makes that visible: stopping
                // quietly would leave the switch on with nothing behind it,
                // which looks like the server working and refusing to answer.
                // The collector above then stops this service for us.
                lifecycleScope.launch { settings.setWebServerEnabled(false) }
            }
        }
        // START_STICKY: if Android kills the process for memory, the server is
        // meant to come back -- the user left it on. The settings collector
        // above stops it again immediately if they have since turned it off.
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun notification(address: String?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Web interface",
                    // LOW, so leaving it on does not mean a sound or a peek
                    // every time the process is restarted.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) }
            )
        }
        val where = address?.let { WebAddress.url(it) } ?: "not on a network"
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Roam is serving your library")
            .setContentText(where)
            // The address IS the useful content, and it is longer than one
            // line on a narrow phone.
            .setStyle(NotificationCompat.BigTextStyle().bigText(where))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open())
            .build()
    }

    /** Tapping it goes to Roam, which is where the switch to stop it is. */
    private fun open(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val CHANNEL = "roam_web"
        private const val NOTIFICATION_ID = 42
        /** NanoHTTPD's own default. Long enough for a slow laptop, short enough to free a thread. */
        private const val SOCKET_TIMEOUT_MS = 5000

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WebService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WebService::class.java))
        }
    }
}
