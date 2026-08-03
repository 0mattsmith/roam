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
import app.roam.core.model.Ids
import app.roam.core.model.TrackSort
import app.roam.core.model.ViewMode
import app.roam.core.datastore.SettingsRepository
import kotlinx.coroutines.flow.first
import android.net.Uri
import app.roam.core.database.ArtistListItem
import app.roam.data.catalog.LibraryQueries
import app.roam.data.catalog.AlbumBulkEdits
import app.roam.data.catalog.TrackEditor
import app.roam.data.catalog.TrackEdits
import app.roam.core.database.AlbumListItem
import app.roam.data.catalog.artwork.ArtworkEditor
import app.roam.data.catalog.artwork.ArtworkFiles
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
    val artistViewMode: ViewMode = ViewMode.GRID,
    val artistAlbumViewMode: ViewMode = ViewMode.GRID,
    /** Non-null when viewing one artist's or album's tracks, or the loved list. */
    val drillTitle: String? = null,
    val showingLoved: Boolean = false,
    /**
     * Whether a discography this long reads better as an index of albums.
     *
     * Only ever true inside an artist, never on the full track list. Collapsed
     * rows still have to be paged in to know where the album boundaries ARE, so
     * a collapsed view of the whole library would reveal five albums per page
     * load -- fine for one artist, useless for ten thousand tracks.
     */
    val collapseAlbumsByDefault: Boolean = false,
    /** Albums the user has flipped away from whatever the default is. */
    val toggledAlbums: Set<Long> = emptySet(),
) {
    /** Default XOR flipped: one set, and changing the default costs nothing. */
    fun albumCollapsed(albumId: Long): Boolean =
        collapseAlbumsByDefault != (albumId in toggledAlbums)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
    private val player: PlayerController,
    private val photos: ArtworkEditor,
    private val trackEditor: TrackEditor,
    private val settings: SettingsRepository,
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

        // Read once, not collected. A live collection would fight openArtist,
        // which deliberately forces TrackSort.ALBUM for the drill-down and
        // would have it reset from under itself on the next emission.
        viewModelScope.launch {
            val saved = settings.settings.first()
            _state.update {
                it.copy(
                    trackSort = saved.trackSort,
                    albumSort = saved.albumSort,
                    artistSort = saved.artistSort,
                    artistViewMode = saved.artistViewMode,
                    artistAlbumViewMode = saved.artistAlbumViewMode,
                )
            }
        }
    }

    private fun pagingConfig() =
        PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = false)

    // ---- navigation ---------------------------------------------------------

    fun selectTab(tab: LibraryTab) {
        _artistPage.value = null
        drill.value = null
        _state.update {
            it.copy(
                tab = tab,
                drillTitle = null,
                showingLoved = false,
                collapseAlbumsByDefault = false,
                toggledAlbums = emptySet(),
            )
        }
    }

    /**
     * The artist landing page: who they are, plus their records.
     *
     * Null when not on an artist. Loaded rather than paged -- an artist has
     * tens of albums, and a Pager for that is machinery without a payoff.
     */
    private val _artistPage = MutableStateFlow<Pair<ArtistDetail, List<AlbumListItem>>?>(null)
    val artistPage: StateFlow<Pair<ArtistDetail, List<AlbumListItem>>?> = _artistPage.asStateFlow()

    private fun loadArtistPage(id: Long) = viewModelScope.launch {
        val row = artists.byId(id) ?: return@launch
        // Captured locally: Kotlin will not smart-cast a property declared in
        // another module.
        val logo = row.logoArtworkId
        val photo = row.artworkId
        _artistPage.value = ArtistDetail(
            id = row.id,
            name = row.name,
            avatarArtworkId = if (row.preferLogo) logo ?: photo else photo ?: logo,
            bannerArtworkId = row.bannerArtworkId,
            albumCount = row.albumCount,
            trackCount = row.trackCount,
        ) to albums.listItemsRaw(LibraryQueries.albumsForArtist(id))

        // Three albums is a screen and a half and you still know where you are.
        // At four the tracks stop being a list you read and start being one you
        // scroll past, so the headers become the index instead.
        _state.update { it.copy(collapseAlbumsByDefault = row.albumCount >= COLLAPSE_FROM) }
    }

    fun openArtist(id: Long, name: String) {
        loadArtistPage(id)
        drill.value = Drill.Artist(id, name)
        // Album order, so the drill-down groups by album the way a discography
        // reads rather than as one flat alphabetical run of songs.
        _state.update {
            it.copy(
                drillTitle = name,
                showingLoved = false,
                trackSort = TrackSort.ALBUM,
                toggledAlbums = emptySet(),
            )
        }
    }

    fun openAlbum(id: Long, title: String) {
        // Leaves the artist page loaded: opening one of their albums and
        // pressing Back should land you where you were, not at the top level.
        drill.value = Drill.Album(id, title)
        // One album is never an index of itself.
        _state.update {
            it.copy(
                drillTitle = title,
                showingLoved = false,
                collapseAlbumsByDefault = false,
                toggledAlbums = emptySet(),
            )
        }
    }

    /** Everything by the artist whose page is open. */
    fun playArtist(shuffled: Boolean) = viewModelScope.launch {
        val id = _artistPage.value?.first?.id ?: return@launch
        val queue = tracks.listItemsRaw(
            LibraryQueries.tracksForArtistLimited(id, TrackSort.ALBUM, QUEUE_LIMIT)
        )
        player.play(if (shuffled) queue.shuffled() else queue, 0)
    }

    /**
     * Opens an artist by name, which is what a track row actually carries.
     *
     * Follows the grouping: a track credited to Makaveli opens 2Pac's page if
     * that is where Makaveli has been folded, rather than a page the Artists
     * list does not even show.
     */
    fun openArtistByName(name: String) = viewModelScope.launch {
        val id = Ids.artist(name)
        val row = artists.byId(id) ?: return@launch
        val target = row.groupArtistId?.let { artists.byId(it) } ?: row
        openArtist(target.id, target.name)
    }

    fun openLoved() {
        drill.value = Drill.Loved
        _state.update {
            it.copy(
                drillTitle = "Loved",
                showingLoved = true,
                collapseAlbumsByDefault = false,
                toggledAlbums = emptySet(),
            )
        }
    }

    // ---- the artist's banner ------------------------------------------------
    //
    // Reloaded by hand after each change. The artist page is a one-shot read
    // rather than a Flow, so unlike the paged lists it does not notice the row
    // moving underneath it.

    fun setArtistBanner(picked: Uri) = viewModelScope.launch {
        val detail = _artistPage.value?.first ?: return@launch
        _photoMessage.value = photos.setArtistBanner(detail.id, detail.name, picked)
            .fold({ it }, { "Could not update: ${it.message}" })
        loadArtistPage(detail.id)
    }

    fun saveBannerToDevice() = viewModelScope.launch {
        val detail = _artistPage.value?.first ?: return@launch
        val artworkId = detail.bannerArtworkId ?: return@launch
        _photoMessage.value = photos.saveToGallery(artworkId, "${detail.name} banner")
            .fold({ it }, { "Could not save: ${it.message}" })
    }

    fun clearArtistBanner() = viewModelScope.launch {
        val detail = _artistPage.value?.first ?: return@launch
        _photoMessage.value = photos.clearArtistBanner(detail.id)
            .fold({ it }, { "Could not remove: ${it.message}" })
        loadArtistPage(detail.id)
    }

    /**
     * True when it consumed a back press, so the screen knows not to exit.
     *
     * Two levels: an album opened from an artist page goes back to that page,
     * and only then does a further press leave for the list.
     */
    fun closeDrill(): Boolean {
        val current = drill.value
        if (current is Drill.Album && _artistPage.value != null) {
            drill.value = null
            _state.update { it.copy(drillTitle = _artistPage.value?.first?.name) }
            return true
        }
        if (current == null && _artistPage.value != null) {
            _artistPage.value = null
            _state.update { it.copy(drillTitle = null, showingLoved = false) }
            return true
        }
        if (current == null) return false
        drill.value = null
        _state.update { it.copy(drillTitle = null, showingLoved = false) }
        return true
    }

    // ---- sorting ------------------------------------------------------------

    // Applied at once and written through, so the list reorders on the tap
    // rather than after a round trip to disk.
    fun setTrackSort(sort: TrackSort) {
        _state.update { it.copy(trackSort = sort) }
        viewModelScope.launch { settings.setTrackSort(sort) }
    }

    fun setAlbumSort(sort: AlbumSort) {
        _state.update { it.copy(albumSort = sort) }
        viewModelScope.launch { settings.setAlbumSort(sort) }
    }

    fun setArtistSort(sort: ArtistSort) {
        _state.update { it.copy(artistSort = sort) }
        viewModelScope.launch { settings.setArtistSort(sort) }
    }

    // ---- layout -------------------------------------------------------------

    fun toggleArtistViewMode() {
        val next = _state.value.artistViewMode.toggled()
        _state.update { it.copy(artistViewMode = next) }
        viewModelScope.launch { settings.setArtistViewMode(next) }
    }

    fun toggleArtistAlbumViewMode() {
        val next = _state.value.artistAlbumViewMode.toggled()
        _state.update { it.copy(artistAlbumViewMode = next) }
        viewModelScope.launch { settings.setArtistAlbumViewMode(next) }
    }

    fun toggleAlbumCollapsed(albumId: Long) = _state.update {
        it.copy(
            toggledAlbums =
                if (albumId in it.toggledAlbums) it.toggledAlbums - albumId
                else it.toggledAlbums + albumId,
        )
    }

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

    /** True only when every track on the album is loved. */
    suspend fun isAlbumLoved(albumId: Long): Boolean =
        tracks.unlovedCountForAlbum(albumId) == 0

    /**
     * All-or-nothing, which is what the single heart on a header can honestly
     * represent: a half-loved album shows as unloved, and tapping loves the
     * rest rather than toggling to some third state nobody asked for.
     */
    fun toggleAlbumLoved(albumId: Long, nowLoved: Boolean) = viewModelScope.launch {
        tracks.setLovedForAlbum(
            albumId = albumId,
            loved = nowLoved,
            at = if (nowLoved) System.currentTimeMillis() else null,
        )
    }

    // ---- artist photos ------------------------------------------------------

    /**
     * One-shot feedback for the long-press actions. Cleared by the screen once
     * shown, so rotating does not replay the snackbar.
     */
    private val _photoMessage = MutableStateFlow<String?>(null)
    val photoMessage: StateFlow<String?> = _photoMessage.asStateFlow()

    fun saveArtistPhoto(artist: ArtistListItem) = viewModelScope.launch {
        // Captured locally: Kotlin will not smart-cast a property declared in
        // another module, because it cannot prove the getter is stable.
        val artworkId = artist.artworkId ?: return@launch
        _photoMessage.value = photos.saveToGallery(artworkId, artist.name)
            .fold({ it }, { "Could not save: ${it.message}" })
    }

    fun setArtistPreferLogo(artist: ArtistListItem, preferLogo: Boolean) = viewModelScope.launch {
        _photoMessage.value = photos.setPreferLogo(artist.id, preferLogo)
            .fold({ it }, { "Could not switch: ${it.message}" })
    }

    fun setArtistPhoto(artist: ArtistListItem, picked: Uri) = viewModelScope.launch {
        // The list redraws itself: the artists PagingSource observes the table.
        _photoMessage.value = photos.setArtistPhoto(artist.id, artist.name, picked)
            .fold({ it }, { "Could not update: ${it.message}" })
    }

    fun saveAlbumCover(album: AlbumListItem) = viewModelScope.launch {
        val artworkId = album.artworkId ?: return@launch
        _photoMessage.value = photos.saveToGallery(artworkId, "${album.artistName} - ${album.title}")
            .fold({ it }, { "Could not save: ${it.message}" })
    }

    fun setAlbumCover(album: AlbumListItem, picked: Uri) = viewModelScope.launch {
        _photoMessage.value = photos.setAlbumCover(album.id, album.artistName, album.title, picked)
            .fold({ it }, { "Could not update: ${it.message}" })
    }

    fun clearPhotoMessage() {
        _photoMessage.value = null
    }

    // ---- track editing ------------------------------------------------------

    /** Non-null while the edit form is open, holding the values it started with. */
    private val _editing = MutableStateFlow<Pair<TrackListItem, TrackEdits>?>(null)
    val editing: StateFlow<Pair<TrackListItem, TrackEdits>?> = _editing.asStateFlow()

    fun openTrackEditor(track: TrackListItem) = viewModelScope.launch {
        val current = trackEditor.current(track.id) ?: return@launch
        _editing.value = track to current
    }

    fun closeTrackEditor() {
        _editing.value = null
    }

    fun saveTrackEdits(trackId: Long, edits: TrackEdits) = viewModelScope.launch {
        _editing.value = null
        // Lists redraw themselves: every PagingSource here observes the tables
        // the edit touches, including the artist and album it may have moved to.
        _photoMessage.value = trackEditor.apply(trackId, edits)
            .fold({ "Track updated" }, { "Could not save: ${it.message}" })
    }

    fun revertTrackEdits(trackId: Long) = viewModelScope.launch {
        _photoMessage.value = trackEditor.revert(trackId)
            .fold({ "Reverted - tags will be re-read" }, { "Could not revert: ${it.message}" })
    }

    fun setTrackArtwork(track: TrackListItem, picked: Uri) = viewModelScope.launch {
        _photoMessage.value = photos.setTrackArtwork(track.id, picked)
            .fold({ it }, { "Could not update: ${it.message}" })
    }

    // ---- bulk album editing -------------------------------------------------

    /** Non-null while the bulk form is open, holding the album it applies to. */
    private val _bulkEditing = MutableStateFlow<TrackListItem?>(null)
    val bulkEditing: StateFlow<TrackListItem?> = _bulkEditing.asStateFlow()

    fun openAlbumBulkEditor(track: TrackListItem) {
        _bulkEditing.value = track
    }

    fun closeAlbumBulkEditor() {
        _bulkEditing.value = null
    }

    /**
     * Applies and stays put. Bulk editing is iterative -- set the year, look at
     * it, then set the genre -- so closing the form after each pass would mean
     * reopening it to do the next thing.
     */
    fun applyAlbumEdits(track: TrackListItem, edits: AlbumBulkEdits) = viewModelScope.launch {
        _photoMessage.value = trackEditor.applyToAlbum(track.albumId, edits)
            .fold(
                { count -> if (count == 0) "Nothing to change" else "Updated $count tracks" },
                { "Could not save: ${it.message}" },
            )

        // Re-resolve the row the dialog is driven by. Renaming the album moved
        // every track to a new content-derived id, so the carrier is now stale
        // and the arrows and cover buttons would target an album that no longer
        // exists. Track ids are stable, so this finds it wherever it landed.
        tracks.listItemsRaw(LibraryQueries.tracksForTrack(track.id)).firstOrNull()
            ?.let { _bulkEditing.value = it }
    }

    fun setAlbumArtwork(track: TrackListItem, picked: Uri) = viewModelScope.launch {
        _photoMessage.value = photos.setAlbumArtworkEverywhere(
            albumId = track.albumId,
            albumArtist = track.artistName,
            albumTitle = track.albumTitle,
            picked = picked,
        ).fold({ it }, { "Could not update: ${it.message}" })
    }

    // ---- stepping between editor targets ------------------------------------
    //
    // Scoped to the album for tracks, and to the whole album list for albums.
    // Following whatever list happens to be on screen would mean loading an
    // arbitrarily long ordered set just to find one neighbour; an album is a
    // few dozen rows and is where sequential tag-fixing actually happens.

    /**
     * Saves, then opens the neighbour. Discarding what was just typed is the
     * one behaviour nobody wants from an arrow key.
     */
    fun stepTrackEditor(current: TrackListItem, edits: TrackEdits, delta: Int) =
        viewModelScope.launch {
            trackEditor.apply(current.id, edits)
                .onFailure { _photoMessage.value = "Could not save: ${it.message}" }

            val siblings = tracks.listItemsRaw(LibraryQueries.tracksForAlbum(current.albumId))
            // Re-found by id: saving may have renamed or re-parented this track,
            // so the position it held before the write is not to be trusted.
            val index = siblings.indexOfFirst { it.id == current.id }
            val next = siblings.getOrNull(index + delta)
            if (next == null) {
                _editing.value = null
                return@launch
            }
            _editing.value = trackEditor.current(next.id)?.let { next to it }
        }

    /** Whether an arrow should be live, so a dead button is never offered. */
    suspend fun hasSiblingTrack(current: TrackListItem, delta: Int): Boolean {
        val siblings = tracks.listItemsRaw(LibraryQueries.tracksForAlbum(current.albumId))
        val index = siblings.indexOfFirst { it.id == current.id }
        return index >= 0 && siblings.getOrNull(index + delta) != null
    }

    fun stepAlbumEditor(current: TrackListItem, edits: AlbumBulkEdits, delta: Int) =
        viewModelScope.launch {
            if (!edits.isEmpty) {
                trackEditor.applyToAlbum(current.albumId, edits)
                    .onFailure { _photoMessage.value = "Could not save: ${it.message}" }
            }

            val all = albums.listItemsRaw(LibraryQueries.albums(_state.value.albumSort))
            val index = all.indexOfFirst { it.id == current.albumId }
            val next = all.getOrNull(index + delta)
            if (next == null) {
                _bulkEditing.value = null
                return@launch
            }
            // The dialog is driven by a track row, so borrow the neighbouring
            // album's first track as the carrier.
            _bulkEditing.value =
                tracks.listItemsRaw(LibraryQueries.tracksForAlbum(next.id)).firstOrNull()
        }

    suspend fun hasSiblingAlbum(current: TrackListItem, delta: Int): Boolean {
        val all = albums.listItemsRaw(LibraryQueries.albums(_state.value.albumSort))
        val index = all.indexOfFirst { it.id == current.albumId }
        return index >= 0 && all.getOrNull(index + delta) != null
    }

    // ---- cover art from inside the editors -----------------------------------

    fun saveAlbumCoverFor(track: TrackListItem) = viewModelScope.launch {
        val artworkId = track.albumArtworkId ?: return@launch
        _photoMessage.value = photos
            .saveToGallery(artworkId, "${track.albumArtistName} - ${track.albumTitle}")
            .fold({ it }, { "Could not save: ${it.message}" })
    }

    fun removeAlbumCover(track: TrackListItem) = viewModelScope.launch {
        _photoMessage.value = photos.clearAlbumArtwork(track.albumId)
            .fold({ it }, { "Could not remove: ${it.message}" })
    }

    /** A retired cover, resolved far enough to draw. */
    data class PastCover(val remoteId: String, val name: String, val artworkId: String?)

    private val _pastCovers = MutableStateFlow<List<PastCover>>(emptyList())
    val pastCovers: StateFlow<List<PastCover>> = _pastCovers.asStateFlow()

    /** Loaded on demand: opening the history is what pays for the downloads. */
    fun loadPastCovers(track: TrackListItem) = viewModelScope.launch {
        val past = photos.previousImages(
            listOf(track.albumArtistName, track.albumTitle),
            ArtworkFiles.ALBUM_UPLOAD_NAME,
        )
        _pastCovers.value = past.map { PastCover(it.remoteId, it.name, null) }
        // Then fill in the thumbnails one at a time, so the row appears
        // immediately and populates rather than blocking on the whole set.
        _pastCovers.value = past.map {
            PastCover(it.remoteId, it.name, photos.cachePastImage(it.remoteId))
        }
    }

    fun clearPastCovers() {
        _pastCovers.value = emptyList()
    }

    fun savePastCover(cover: PastCover) = viewModelScope.launch {
        _photoMessage.value = photos.savePastImage(cover.remoteId, cover.name.substringBeforeLast('.'))
            .fold({ it }, { "Could not save: ${it.message}" })
    }

    fun restorePastCover(track: TrackListItem, cover: PastCover) = viewModelScope.launch {
        _photoMessage.value = photos.restoreAlbumCover(
            albumId = track.albumId,
            albumArtist = track.albumArtistName,
            albumTitle = track.albumTitle,
            remoteId = cover.remoteId,
        ).fold({ it }, { "Could not restore: ${it.message}" })
        loadPastCovers(track)
    }

    fun reportEditorMessage(message: String) {
        _photoMessage.value = message
    }

    /** How many tracks a bulk edit would touch, for the dialog's title. */
    suspend fun albumTrackCount(albumId: Long): Int =
        tracks.listItemsRaw(LibraryQueries.tracksForAlbum(albumId)).size

    /** Whether this track carries hand-typed tags, for the revert entry. */
    suspend fun isEdited(trackId: Long): Boolean = tracks.byId(trackId)?.userEdited == true

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()

    private companion object {
        /**
         * A MediaSession queue crosses Binder, and a few thousand items would
         * blow the 1 MB limit. Phase 3 windows this properly.
         */
        const val QUEUE_LIMIT = 500

        /** Albums in a discography before its headers become the index. */
        const val COLLAPSE_FROM = 4
    }
}
