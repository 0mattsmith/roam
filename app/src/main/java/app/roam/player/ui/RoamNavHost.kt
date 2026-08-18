package app.roam.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.roam.feature.library.LibraryRoute
import app.roam.feature.nowplaying.NowPlayingRoute
import app.roam.feature.downloader.DownloaderRoute
import app.roam.feature.settings.RemovedTracksRoute
import app.roam.feature.settings.SettingsRoute

object Routes {
    const val LIBRARY = "library"
    /** The library, told to jump to one track. Both arguments optional. */
    const val LIBRARY_AT = "library?track={track}&edit={edit}"
    fun libraryAt(trackId: Long, edit: Boolean) = "library?track=$trackId&edit=$edit"
    const val DOWNLOADER = "downloader"
    const val SETTINGS = "settings"
    const val REMOVED = "settings/removed"
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
fun RoamNavHost(
    /** Set when another app shared a YouTube link into Roam. */
    sharedVideoId: String? = null,
    onSharedHandled: () -> Unit = {},
) {
    val nav = rememberNavController()

    // A share jumps straight to the downloader, wherever the app happened to
    // be. launchSingleTop so sharing twice does not stack the screen on itself.
    LaunchedEffect(sharedVideoId) {
        if (sharedVideoId != null) {
            nav.navigate(Routes.DOWNLOADER) { launchSingleTop = true }
        }
    }

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
            startDestination = Routes.LIBRARY_AT,
            modifier = Modifier.weight(1f),
        ) {
            composable(
                Routes.LIBRARY_AT,
                arguments = listOf(
                    navArgument("track") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("edit") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                // Now Playing is an overlay ON the library, not a destination of
                // its own. As a route the NavHost disposes the library while it
                // is showing, so dragging the panel down would reveal the window
                // background rather than the list you came from -- and revealing
                // what is behind is the whole point of the gesture.
                var playerExpanded by rememberSaveable { mutableStateOf(false) }

                Box(Modifier.fillMaxSize()) {
                    LibraryRoute(
                        revealTrackId = entry.arguments?.getString("track")?.toLongOrNull(),
                        revealForEditing = entry.arguments?.getBoolean("edit") == true,
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
            composable(Routes.DOWNLOADER) {
                DownloaderRoute(
                    onBack = back,
                    sharedVideoId = sharedVideoId,
                    onSharedHandled = onSharedHandled,
                    // popUpTo so the library is not stacked twice: this is a
                    // jump back to where you already were, not a new screen.
                    onOpenTrack = { id, edit ->
                        nav.navigate(Routes.libraryAt(id, edit)) {
                            popUpTo(Routes.LIBRARY_AT) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(onBack = back, onOpenRemoved = { go(Routes.REMOVED) })
            }
            composable(Routes.REMOVED)     { RemovedTracksRoute(onBack = back) }
        }

        UpdateBannerHost()
    }
}
