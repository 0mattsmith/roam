package app.roam.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
        setContent {
            RoamTheme { RoamNavHost() }
        }
    }
}
