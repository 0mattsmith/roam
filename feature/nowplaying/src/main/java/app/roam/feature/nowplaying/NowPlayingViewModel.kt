package app.roam.feature.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.metadata.LyricsRepository
import app.roam.data.catalog.metadata.TrackLyrics
import app.roam.feature.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val player: PlayerController,
    private val lyrics: LyricsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val nowPlaying = player.nowPlaying

    val showLyrics: StateFlow<Boolean> = settings.settings
        .map { it.showLyrics }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Lyrics for whatever is playing.
     *
     * Keyed on the track id alone -- nowPlaying re-emits on every position
     * poll, and flatMapLatest without distinctUntilChanged would tear down and
     * rebuild the Room query several times a second.
     */
    val lyricsState: StateFlow<TrackLyrics> = nowPlaying
        .map { it.trackId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf(TrackLyrics()) else lyrics.lyricsFor(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackLyrics())

    init {
        // Idempotent, and the screen can be reached without passing through the
        // library first (a notification tap, for instance).
        player.connect()

        // One lookup per track, and only while the setting is on. Combining
        // rather than reading the setting inside the collector means turning
        // lyrics on fetches for the song already playing, instead of doing
        // nothing until the next one.
        viewModelScope.launch {
            combine(
                nowPlaying.map { it.trackId }.distinctUntilChanged(),
                settings.settings.map { it.showLyrics }.distinctUntilChanged(),
            ) { id, enabled -> id.takeIf { enabled } }
                .distinctUntilChanged()
                .collect { id -> if (id != null) lyrics.ensureFetched(id) }
        }
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(ms: Long) = player.seekTo(ms)
    fun toggleShuffle() = player.toggleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun refreshPosition() = player.refreshPosition()
}
