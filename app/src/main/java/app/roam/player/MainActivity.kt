package app.roam.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import app.roam.core.designsystem.RoamTheme
import app.roam.player.ui.RoamNavHost
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Instantiating it runs the launch-time sync and update check. Activity
    // scoped, so a rotation does not re-trigger either.
    private val startup: StartupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startup   // touch it so the ViewModel is created
        requestNotificationPermission()
        setContent {
            RoamTheme { RoamNavHost() }
        }
    }

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
