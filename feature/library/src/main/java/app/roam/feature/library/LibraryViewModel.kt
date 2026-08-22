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
import android.content.Context
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.metadata.DocWriter
import app.roam.data.catalog.metadata.LyricsRepository
import app.roam.data.catalog.metadata.LyricsWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import android.net.Uri
import app.roam.core.database.ArtistListItem
import app.roam.data.catalog.LibraryQueries
import app.roam.data.catalog.AlbumBulkEdits
import app.roam.data.catalog.TrackEditState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which list is showing, how it is sorted, and what has been drilled into. */
data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.TRACKS,
    val trackSort: TrackSort = TrackSort.ARTIST,
    val albumSort: AlbumSort = AlbumSort.ARTIST,
    val artistSort: ArtistSort = ArtistSort.NAME,
    val artistViewMode: ViewMode = ViewMode.GRID_3,
    val artistAlbumViewMode: ViewMode = ViewMode.GRID_3,
    /** Non-null when viewing one artist's or album's tracks, or the loved list. */
    val drillTitle: String? = null,
    /**
     * The album currently open, if any.
     *
     * Exists because the route used to decide between the artist page and a
     * track list by comparing drillTitle against the artist's NAME. An album
     * called the same thing as its artist -- Royal Blood by Royal Blood, and
     * every other self-titled debut ever made -- then matched the artist page
     * branch and could not be opened at all. Identity, never a display string.
     */
    val openAlbumId: Long? = null,
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
    private val docWriter: DocWriter,
    private val settings: SettingsRepository,
    private val lyrics: LyricsRepository,
    @ApplicationContext private val ctx: Context,
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
     *
     * distinctUntilChanged IS THE WHOLE POINT and must not be dropped.
     *
     * Without it every emission of `_state` builds a brand new Pager, and a
     * new Pager is a new PagingSource, which reloads from the first page and
     * throws the reader back to the top of the list. `_state` carries the
     * collapsed-album set, the view modes and the tab, so collapsing one album
     * -- or anything else that touches state -- silently reset the scroll
     * position of a list the person was reading. Only the sort and the drill
     * target actually change what is being QUERIED; everything else is
     * presentation and must not disturb the pages already loaded.
     */
    val pagedTracks = combine(_state, drill) { s, d -> s.trackSort to d }
        .distinctUntilChanged()
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

    val pagedArtists = _state.map { it.artistSort }
        .distinctUntilChanged()
        .flatMapLatest { sort ->
            Pager(pagingConfig()) { artists.pagedListItemsRaw(LibraryQueries.artists(sort)) }.flow
        }.cachedIn(viewModelScope)

    val pagedAlbums = _state.map { it.albumSort }
        .distinctUntilChanged()
        .flatMapLatest { sort ->
            Pager(pagingConfig()) { albums.pagedListItemsRaw(LibraryQueries.albums(sort)) }.flow
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

    /**
     * PLACEHOLDERS ON, and this is load-bearing.
     *
     * Any write to tracks invalidates the PagingSource -- hearting a track,
     * saving an edit -- and Paging answers by reloading. Without placeholders
     * the presented list collapses from everything scrolled through to the one
     * page around the anchor, so a scroll index of 250 suddenly exceeds a
     * 60-item list, gets clamped, and lands the reader somewhere else entirely.
     * That is the "it jumped to the top after I hearted something" bug.
     *
     * With placeholders the count is the FULL count from the start, unloaded
     * rows are null, indices never move, and a refresh leaves the scroll
     * position exactly where it was. The cost is that every list has to render
     * something for a null row, which is why they all have a placeholder branch
     * rather than skipping nulls -- skipping gives a zero-height row and hands
     * the geometry problem straight back.
     */
    private fun pagingConfig() =
        PagingConfig(pageSize = 60, prefetchDistance = 30, enablePlaceholders = true)

    // ---- navigation ---------------------------------------------------------

    fun selectTab(tab: LibraryTab) {
        closeOpenForms()
        _artistPage.value = null
        drill.value = null
        _state.update {
            it.copy(
                tab = tab,
                drillTitle = null,
                openAlbumId = null,
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
        closeOpenForms()
        loadArtistPage(id)
        drill.value = Drill.Artist(id, name)
        // Album order, so the drill-down groups by album the way a discography
        // reads rather than as one flat alphabetical run of songs.
        _state.update {
            it.copy(
                drillTitle = name,
                openAlbumId = null,
                showingLoved = false,
                trackSort = TrackSort.ALBUM,
                toggledAlbums = emptySet(),
            )
        }
    }

    /**
     * Shuts every open sheet and dialog.
     *
     * Navigating away has to do this explicitly. The editor is a dialog held in
     * ViewModel state, not in the composition of the screen behind it, so
     * leaving a drill-down does not dismiss it -- open an album, edit a track,
     * go back, open a DIFFERENT album, and the previous track's form is still
     * sitting there over the top of it, ready to save changes to a track you
     * are no longer looking at.
     */
    private fun closeOpenForms() {
        _editing.value = null
        _bulkEditing.value = null
    }

    fun openAlbum(id: Long, title: String) {
        // Leaves the artist page loaded: opening one of their albums and
        // pressing Back should land you where you were, not at the top level.
        closeOpenForms()
        drill.value = Drill.Album(id, title)
        // One album is never an index of itself.
        _state.update {
            it.copy(
                drillTitle = title,
                openAlbumId = id,
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
        closeOpenForms()
        drill.value = Drill.Loved
        _state.update {
            it.copy(
                drillTitle = "Loved",
                openAlbumId = null,
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
        closeOpenForms()
        val current = drill.value
        if (current is Drill.Album && _artistPage.value != null) {
            drill.value = null
            _state.update {
                it.copy(drillTitle = _artistPage.value?.first?.name, openAlbumId = null)
            }
            return true
        }
        if (current == null && _artistPage.value != null) {
            _artistPage.value = null
            _state.update { it.copy(drillTitle = null, openAlbumId = null, showingLoved = false) }
            return true
        }
        if (current == null) return false

        // Leaving the drill entirely, so the loaded artist page goes with it.
        // Left behind it is state describing a screen nobody is on, and the
        // route would happily keep rendering it -- which is what made Back
        // appear to do nothing here and then quit the app on the second press.
        drill.value = null
        _artistPage.value = null
        _state.update { it.copy(drillTitle = null, openAlbumId = null, showingLoved = false) }
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

    fun setArtistViewMode(mode: ViewMode) {
        _state.update { it.copy(artistViewMode = mode) }
        viewModelScope.launch { settings.setArtistViewMode(mode) }
    }

    fun setArtistAlbumViewMode(mode: ViewMode) {
        _state.update { it.copy(artistAlbumViewMode = mode) }
        viewModelScope.launch { settings.setArtistAlbumViewMode(mode) }
    }

    /**
     * The album in a random order.
     *
     * A plain shuffle, not the weighted engine. Weighting exists to stop a
     * library-wide shuffle circling the same forty songs; inside one album
     * there is nothing to correct for, and skewing a twelve-track record
     * towards the loved ones would just be wrong.
     */
    fun shuffleAlbum(albumId: Long) = viewModelScope.launch {
        val queue = tracks.listItemsRaw(LibraryQueries.tracksForAlbum(albumId))
        if (queue.isEmpty()) return@launch
        player.play(queue.shuffled(), 0)
    }

    // ---- removing from the library ------------------------------------------
    //
    // The row is kept and flagged, never deleted. Deleting it would mean the
    // next sync rediscovers the file as new and puts it straight back -- and it
    // would take the loved flag and play count with it.

    fun removeTrack(track: TrackListItem) = viewModelScope.launch {
        tracks.setHidden(track.id, hidden = true)
        recount()
        _photoMessage.value = "Removed ${track.title}"
    }

    fun removeAlbum(albumId: Long, title: String) = viewModelScope.launch {
        tracks.setHiddenForAlbum(albumId, hidden = true)
        recount()
        _photoMessage.value = "Removed $title"
    }

    /** Counts live on the parent rows, so hiding a track has to update them. */
    private suspend fun recount() {
        albums.recomputeRollups()
        artists.recomputeRollups()
    }

    fun toggleAlbumCollapsed(albumId: Long) = _state.update {
        it.copy(
            toggledAlbums =
                if (albumId in it.toggledAlbums) it.toggledAlbums - albumId
                else it.toggledAlbums + albumId,
        )
    }

    /**
     * Collapses or expands every album at once.
     *
     * Flips the DEFAULT and clears the exceptions, rather than walking the list
     * and collapsing each album in turn. That matters because the list is
     * paged: only the albums scrolled past are loaded, so "collapse each one I
     * can see" would leave everything below it expanded and the button would
     * appear not to have worked properly. Flipping the default applies to
     * albums that have not been fetched yet and costs one boolean.
     */
    fun setAllAlbumsCollapsed(collapsed: Boolean) = _state.update {
        it.copy(collapseAlbumsByDefault = collapsed, toggledAlbums = emptySet())
    }

    /**
     * True when collapsing all would actually change something.
     *
     * With no exceptions set this is just the default inverted; with some, the
     * button should still offer to tidy them up, so the label follows the
     * default rather than trying to guess a majority.
     */
    val allAlbumsCollapsed: Boolean get() = _state.value.collapseAlbumsByDefault

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
    private val _editing = MutableStateFlow<Pair<TrackListItem, TrackEditState>?>(null)
    val editing: StateFlow<Pair<TrackListItem, TrackEditState>?> = _editing.asStateFlow()

    /**
     * Opens the album a track sits in, and optionally its editor.
     *
     * The entry point from the download manager, where the only handle on a
     * finished download is a track id. Opening the ALBUM rather than a bare
     * track is deliberate -- landing on one row with no context reads as a
     * broken search result.
     */
    fun revealTrack(trackId: Long, openEditor: Boolean) = viewModelScope.launch {
        val track = tracks.listItemsRaw(LibraryQueries.tracksForTrack(trackId)).firstOrNull()
            ?: return@launch
        openAlbum(track.albumId, track.albumTitle)
        if (openEditor) openTrackEditor(track)
    }

    fun openTrackEditor(track: TrackListItem) = viewModelScope.launch {
        val current = trackEditor.current(track.id) ?: return@launch
        _editing.value = track to current
    }

    fun closeTrackEditor() {
        _editing.value = null
    }

    fun saveTrackEdits(
        trackId: Long,
        edits: TrackEdits,
        createDocs: Boolean = false,
    ) = viewModelScope.launch {
        val albumId = _editing.value?.first?.albumId
        _editing.value = null
        // Lists redraw themselves: every PagingSource here observes the tables
        // the edit touches, including the artist and album it may have moved to.
        val saved = trackEditor.apply(trackId, edits)
            .fold({ "Track updated" }, { "Could not save: ${it.message}" })

        // After the edit, never before: the files are built from the catalogue,
        // so writing them first would put the OLD values on the source and then
        // have the reader hand them straight back on the next sync.
        _photoMessage.value = if (createDocs && albumId != null) {
            writeMetadataFiles(albumId)
        } else {
            saved
        }
    }

    /**
     * Writes an album's metadata files, and says what happened.
     *
     * The message is the whole feedback: this touches someone's Drive, and a
     * silent success there is indistinguishable from a silent failure.
     */
    private suspend fun writeMetadataFiles(albumId: Long): String =
        docWriter.writeAlbum(albumId).fold(
            { report ->
                when {
                    report.wroteNothing -> "Nothing to write yet"
                    report.skippedUnread > 0 ->
                        "Wrote ${report.filesWritten} files, skipped " +
                            "${report.skippedUnread} with unread tags"
                    else -> "Wrote ${report.filesWritten} files to Drive"
                }
            },
            { "Could not write: ${it.message}" },
        )

    /** From the album's long-press sheet, where doing this to a whole album belongs. */
    fun writeAlbumMetadataFiles(albumId: Long) = viewModelScope.launch {
        _photoMessage.value = writeMetadataFiles(albumId)
    }

    /**
     * Lyrics for every track on an album that has none.
     *
     * A worker rather than a coroutine here: an album is up to a couple of
     * dozen lookups, and locking the phone half way through should not abandon
     * them. The message is fire-and-forget -- the words appear on each track as
     * they land, and a progress bar for something nobody is waiting on is
     * clutter.
     */
    fun fetchLyricsForAlbum(albumId: Long, albumTitle: String) = viewModelScope.launch {
        LyricsWorker.enqueue(
            ctx = ctx,
            wifiOnly = settings.settings.first().wifiOnlyForLargeTransfers,
            albumId = albumId,
        )
        _photoMessage.value = "Looking up lyrics for $albumTitle"
    }

    /** The Fetch button beside the lyrics field. Forced: it was asked for. */
    suspend fun fetchLyricsNow(trackId: Long): String? {
        val found = lyrics.ensureFetched(trackId, force = true)
        return if (found) tracks.lyricsForOnce(trackId)?.plain else null
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
     *
     * Null [edits] mean nothing was typed. Writing anyway would mark the track
     * hand-edited, which permanently takes it out of the tag pass and out of
     * album.json's reach -- so paging through an album to read it would quietly
     * freeze every track in it.
     */
    fun stepTrackEditor(current: TrackListItem, edits: TrackEdits?, delta: Int) =
        viewModelScope.launch {
            edits?.let {
                trackEditor.apply(current.id, it)
                    .onFailure { e -> _photoMessage.value = "Could not save: ${e.message}" }
            }

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

    /**
     * How many tracks an album holds.
     *
     * Two callers, one question: what a bulk edit would touch, and how many
     * files "Create album.json" would write.
     */
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
