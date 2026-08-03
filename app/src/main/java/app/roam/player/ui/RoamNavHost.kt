package app.roam.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.roam.feature.library.LibraryRoute
import app.roam.feature.nowplaying.NowPlayingRoute
import app.roam.feature.downloader.DownloaderRoute
import app.roam.feature.settings.SettingsRoute

object Routes {
    const val LIBRARY = "library"
    const val DOWNLOADER = "downloader"
    const val SETTINGS = "settings"
}

/**
 * The hardware/gesture Back button is handled by NavHost, which pops this back
 * stack. The on-screen arrows call the same `back` lambda, so both routes
 * through the app behave identically -- important for anyone using the
 * three-button navigation bar rather than gestures.
 *
 * `launchSingleTop` stops a screen stacking on itself if a button is
 * double-tapped, which would otherwise need two presses of Back to escape.
 */
@Composable
fun RoamNavHost() {
    val nav = rememberNavController()

    // Pop only if this destination is still the current one. Without the guard
    // a fast double-tap on a Back arrow pops twice and skips a screen.
    val back: () -> Unit = { if (!nav.popBackStack()) Unit }

    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }

    // A Column rather than an overlay: the banner sits below the content so it
    // cannot cover the mini-player or a screen's own bottom bar.
    //
    // navigationBarsPadding applies once, here, so whatever happens to be
    // bottom-most clears the system bar. Putting it on the mini-player and the
    // banner separately would double up whenever both are visible.
    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        NavHost(
            navController = nav,
            startDestination = Routes.LIBRARY,
            modifier = Modifier.weight(1f),
        ) {
            composable(Routes.LIBRARY) {
                // Now Playing is an overlay ON the library, not a destination of
                // its own. As a route the NavHost disposes the library while it
                // is showing, so dragging the panel down would reveal the window
                // background rather than the list you came from -- and revealing
                // what is behind is the whole point of the gesture.
                var playerExpanded by rememberSaveable { mutableStateOf(false) }

                Box(Modifier.fillMaxSize()) {
                    LibraryRoute(
                        onOpenPlayer = { playerExpanded = true },
                        onOpenSettings = { go(Routes.SETTINGS) },
                        onOpenDownloader = { go(Routes.DOWNLOADER) },
                    )

                    if (playerExpanded) {
                        // Back collapses the panel before it leaves the screen,
                        // matching what the drag and the chevron do.
                        BackHandler { playerExpanded = false }
                        NowPlayingRoute(onCollapse = { playerExpanded = false })
                    }
                }
            }
            composable(Routes.DOWNLOADER)  { DownloaderRoute(onBack = back) }
            composable(Routes.SETTINGS)    { SettingsRoute(onBack = back) }
        }

        UpdateBannerHost()
    }
}
