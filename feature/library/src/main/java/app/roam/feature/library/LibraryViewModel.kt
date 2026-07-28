package app.roam.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import app.roam.core.database.AlbumDao
import app.roam.core.database.ArtistDao
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.AlbumSort
import app.roam.core.model.ArtistSort
import app.roam.core.model.LibraryTab
import app.roam.core.model.TrackSort
import app.roam.data.catalog.LibraryQueries
import app.roam.feature.player.PlayerController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which list is showing, how it is sorted, and what has been drilled into. */
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.TRACKS,
    val trackSort: TrackSort = TrackSort.ARTIST,
    val albumSort: AlbumSort = AlbumSort.ARTIST,
    val artistSort: ArtistSort = ArtistSort.NAME,
    /** Non-null when viewing one artist's or album's tracks, or the loved list. */
    val drillTitle: String? = null,
    val showingLoved: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
    private val player: PlayerController,
) : ViewModel() {

    private sealed interface Drill {
        data class Artist(val id: Long, val name: String) : Drill
        data class Album(val id: Long, val title: String) : Drill
        data object Loved : Drill
    }

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /** Set when drilled into an artist, album or the loved list. */
    private val drill = MutableStateFlow<Drill?>(null)

    val nowPlaying = player.nowPlaying

    /**
     * Re-queries whenever the sort or the drill-down changes. flatMapLatest
     * cancels the previous Pager, so flicking between sort orders does not
     * leave stale pages loading behind the new one.
     */
    val pagedTracks = combine(_state, drill) { s, d -> s.trackSort to d }
        .flatMapLatest { (sort, current) ->
            Pager(pagingConfig()) {
                tracks.pagedListItemsRaw(
                    when (current) {
                        is Drill.Artist -> LibraryQueries.tracksForArtist(current.id, sort)
                        is Drill.Album -> LibraryQueries.tracksForAlbum(current.id)
                        Drill.Loved -> LibraryQueries.lovedTracks(sort)
                        null -> LibraryQueries.tracks(sort)
                    }
                )
            }.flow
        }.cachedIn(viewModelScope)

    val pagedArtists = _state.flatMapLatest { s ->
        Pager(pagingConfig()) { artists.pagedListItemsRaw(LibraryQueries.artists(s.artistSort)) }.flow
    }.cachedIn(viewModelScope)

    val pagedAlbums = _state.flatMapLatest { s ->
        Pager(pagingConfig()) { albums.pagedListItemsRaw(LibraryQueries.albums(s.albumSort)) }.flow
    }.cachedIn(viewModelScope)

    init {
        player.connect()
    }

    private fun pagingConfig() =
        PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)

    // ---- navigation ---------------------------------------------------------

    fun selectTab(tab: LibraryTab) {
        drill.value = null
        _state.update { it.copy(tab = tab, drillTitle = null, showingLoved = false) }
    }

    fun openArtist(id: Long, name: String) {
        drill.value = Drill.Artist(id, name)
        _state.update { it.copy(drillTitle = name, showingLoved = false) }
    }

    fun openAlbum(id: Long, title: String) {
        drill.value = Drill.Album(id, title)
        _state.update { it.copy(drillTitle = title, showingLoved = false) }
    }

    fun openLoved() {
        drill.value = Drill.Loved
        _state.update { it.copy(drillTitle = "Loved", showingLoved = true) }
    }

    /** True when it consumed a back press, so the screen knows not to exit. */
    fun closeDrill(): Boolean {
        if (drill.value == null) return false
        drill.value = null
        _state.update { it.copy(drillTitle = null, showingLoved = false) }
        return true
    }

    // ---- sorting ------------------------------------------------------------

    fun setTrackSort(sort: TrackSort) = _state.update { it.copy(trackSort = sort) }
    fun setAlbumSort(sort: AlbumSort) = _state.update { it.copy(albumSort = sort) }
    fun setArtistSort(sort: ArtistSort) = _state.update { it.copy(artistSort = sort) }

    // ---- playback -----------------------------------------------------------

    /**
     * The queue mirrors whatever the list is currently showing, so playing from
     * a sorted or filtered view continues in that order rather than jumping
     * back to the whole library.
     */
    fun playFrom(track: TrackListItem) = viewModelScope.launch {
        val sort = _state.value.trackSort
        val queue = tracks.listItemsRaw(
            when (val current = drill.value) {
                is Drill.Artist -> LibraryQueries.tracksForArtist(current.id, sort)
                is Drill.Album -> LibraryQueries.tracksForAlbum(current.id)
                Drill.Loved -> LibraryQueries.lovedTracksLimited(sort, QUEUE_LIMIT)
                null -> LibraryQueries.tracksLimited(sort, QUEUE_LIMIT)
            }
        )
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.play(queue, index)
    }

    fun toggleLoved(track: TrackListItem) = viewModelScope.launch {
        tracks.setLoved(
            id = track.id,
            loved = !track.loved,
            at = if (track.loved) null else System.currentTimeMillis(),
        )
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()

    private companion object {
        /**
         * A MediaSession queue crosses Binder, and a few thousand items would
         * blow the 1 MB limit. Phase 3 windows this properly.
         */
        const val QUEUE_LIMIT = 500
    }
}
