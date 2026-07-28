package app.roam.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.feature.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tracks: TrackDao,
    private val player: PlayerController,
) : ViewModel() {

    /**
     * Paged straight out of Room. A library of several thousand tracks must
     * never be materialised into a list -- invariant 8.
     */
    val pagedTracks = Pager(
        PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)
    ) { tracks.pagedListItems() }.flow.cachedIn(viewModelScope)

    val nowPlaying = player.nowPlaying

    init {
        player.connect()
    }

    /**
     * Builds the queue around the tapped track.
     *
     * Capped rather than unbounded: a MediaSession queue is passed over Binder,
     * and a few thousand items would blow the 1 MB limit. Phase 3 windows this
     * properly, extending the timeline as playback approaches the end.
     */
    fun playFrom(track: TrackListItem) = viewModelScope.launch {
        val queue = tracks.listItems(QUEUE_LIMIT)
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.play(queue, index)
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()

    private companion object { const val QUEUE_LIMIT = 500 }
}
