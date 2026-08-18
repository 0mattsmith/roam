package app.roam.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import app.roam.core.designsystem.RoamTheme
import app.roam.feature.downloader.YoutubeLink
import app.roam.player.ui.RoamNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Instantiating it runs the launch-time sync and update check. Activity
    // scoped, so a rotation does not re-trigger either.
    private val startup: StartupViewModel by viewModels()

    /**
     * A video id shared in from another app, waiting to be queued.
     *
     * Held as Compose state rather than read from the intent inside the
     * composable: the intent is not observable, so a share arriving while Roam
     * is already open would never recompose anything.
     */
    private var sharedVideoId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startup   // touch it so the ViewModel is created
        requestNotificationPermission()
        sharedVideoId = YoutubeLink.videoId(sharedText(intent))

        setContent {
            RoamTheme {
                RoamNavHost(
                    sharedVideoId = sharedVideoId,
                    onSharedHandled = { sharedVideoId = null },
                )
            }
        }
    }

    /**
     * The activity is singleTop, so a share while Roam is ALREADY open reuses
     * this instance and onCreate never runs again. Without this the second
     * share of the session is silently dropped -- and the first one is the only
     * one anybody tests.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        YoutubeLink.videoId(sharedText(intent))?.let { sharedVideoId = it }
    }

    /**
     * YouTube shares a link as EXTRA_TEXT, sometimes with the title in front of
     * it. EXTRA_SUBJECT holds the title alone on some apps, which is no use
     * here, so only the text is read.
     */
    private fun sharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null

    /**
     * Media playback runs as a foreground service, which needs a notification.
     * Without this permission on Android 13+ the notification is suppressed and
     * the transport controls never appear on the lock screen.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }
}
