package app.roam.feature.nowplaying

import androidx.lifecycle.ViewModel
import app.roam.feature.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val player: PlayerController,
) : ViewModel() {

    val nowPlaying = player.nowPlaying

    init {
        // Idempotent, and the screen can be reached without passing through the
        // library first (a notification tap, for instance).
        player.connect()
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun refreshPosition() = player.refreshPosition()
}
