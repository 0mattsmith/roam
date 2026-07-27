package app.roam.player.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.roam.feature.library.LibraryRoute
import app.roam.feature.nowplaying.NowPlayingRoute
import app.roam.feature.downloader.DownloaderRoute
import app.roam.feature.settings.SettingsRoute

object Routes {
    const val LIBRARY = "library"
    const val NOW_PLAYING = "now_playing"
    const val DOWNLOADER = "downloader"
    const val SETTINGS = "settings"
}

@Composable
fun RoamNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryRoute(
                onOpenPlayer = { nav.navigate(Routes.NOW_PLAYING) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                onOpenDownloader = { nav.navigate(Routes.DOWNLOADER) },
            )
        }
        composable(Routes.NOW_PLAYING) { NowPlayingRoute(onCollapse = { nav.popBackStack() }) }
        composable(Routes.DOWNLOADER)  { DownloaderRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS)    { SettingsRoute(onBack = { nav.popBackStack() }) }
    }
}
