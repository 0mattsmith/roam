package app.roam.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.roam.core.database.AlbumListItem
import app.roam.core.database.ArtistListItem
import app.roam.core.database.TrackListItem
import app.roam.core.model.AlbumSort
import app.roam.core.model.ArtistSort
import app.roam.core.model.LibraryTab
import app.roam.core.model.TrackSort
import app.roam.data.catalog.artwork.ArtworkProvider
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloader: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val photoMessage by vm.photoMessage.collectAsStateWithLifecycle()

    // Back closes a drill-down before it leaves the screen. enabled = false when
    // there is nothing to close, so the system handles it normally.
    BackHandler(enabled = state.drillTitle != null) { vm.closeDrill() }

    // Hoisted deliberately. Drilling into an artist swaps ArtistList out of
    // composition entirely, which takes any state remembered inside it -- so
    // coming back landed you at the top. The route itself stays composed, so
    // holding the positions here is what makes them survive the round trip.
    val artistsState = rememberLazyListState()
    val albumsState = rememberLazyListState()
    val tracksState = rememberLazyListState()

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(photoMessage) {
        photoMessage?.let {
            snackbars.showSnackbar(it)
            vm.clearPhotoMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text(state.drillTitle ?: "Roam") },
                navigationIcon = {
                    if (state.drillTitle != null) {
                        IconButton(onClick = { vm.closeDrill() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    SortMenu(state, vm)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            if (nowPlaying.hasItem) {
                MiniPlayer(
                    title = nowPlaying.title,
                    artist = nowPlaying.artist,
                    isPlaying = nowPlaying.isPlaying,
                    onToggle = vm::togglePlayPause,
                    onNext = vm::next,
                    onExpand = onOpenPlayer,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Tabs are hidden inside a drill-down: the list is no longer "all
            // tracks", so a highlighted Tracks tab would be a lie.
            if (state.drillTitle == null) {
                PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                    LibraryTab.entries.forEach { tab ->
                        Tab(
                            selected = state.tab == tab,
                            onClick = { vm.selectTab(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }
            }

            when {
                // Keyed on the drill target so each one starts at its own top:
                // opening an album should show its first track, not wherever
                // the previously opened album happened to be scrolled to.
                state.drillTitle != null ->
                    key(state.drillTitle) { TrackList(vm, rememberLazyListState()) }
                state.tab == LibraryTab.TRACKS -> TrackList(vm, tracksState)
                state.tab == LibraryTab.ARTISTS -> ArtistList(vm, artistsState)
                state.tab == LibraryTab.ALBUMS -> AlbumList(vm, albumsState)
                else -> PlaylistList(vm)
            }
        }
    }
}

@Composable
private fun SortMenu(state: LibraryUiState, vm: LibraryViewModel) {
    var open by remember { mutableStateOf(false) }

    // Playlists is a fixed list of one; nothing to order.
    if (state.tab == LibraryTab.PLAYLISTS && state.drillTitle == null) return

    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.Sort, contentDescription = "Sort")
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        val showTrackSorts = state.drillTitle != null || state.tab == LibraryTab.TRACKS
        when {
            showTrackSorts -> TrackSort.entries.forEach { sort ->
                SortItem(sort.label, state.trackSort == sort) { vm.setTrackSort(sort); open = false }
            }
            state.tab == LibraryTab.ARTISTS -> ArtistSort.entries.forEach { sort ->
                SortItem(sort.label, state.artistSort == sort) { vm.setArtistSort(sort); open = false }
            }
            state.tab == LibraryTab.ALBUMS -> AlbumSort.entries.forEach { sort ->
                SortItem(sort.label, state.albumSort == sort) { vm.setAlbumSort(sort); open = false }
            }
        }
    }
}

@Composable
private fun SortItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            RadioButton(selected = selected, onClick = null)
        },
    )
}

@Composable
private fun TrackList(vm: LibraryViewModel, listState: LazyListState) {
    val tracks = vm.pagedTracks.collectAsLazyPagingItems()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()

    if (tracks.itemCount == 0) {
        EmptyState("No tracks here yet")
        return
    }

    var sheetFor by remember { mutableStateOf<TrackListItem?>(null) }
    var sheetEdited by remember { mutableStateOf(false) }
    var headerSheetFor by remember { mutableStateOf<TrackListItem?>(null) }
    var bulkCount by remember { mutableIntStateOf(0) }
    val editing by vm.editing.collectAsStateWithLifecycle()
    val bulkEditing by vm.bulkEditing.collectAsStateWithLifecycle()
    val pastCovers by vm.pastCovers.collectAsStateWithLifecycle()

    // Album-major orderings get one big cover per album instead of the same
    // thumbnail repeated down every row.
    val grouped = state.trackSort == TrackSort.ALBUM || state.drillTitle != null

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(count = tracks.itemCount, key = tracks.itemKey { it.id }) { index ->
            tracks[index]?.let { track ->
                if (grouped) {
                    // peek, not get: reading the previous row with the indexed
                    // accessor would tell Paging that row is in view and drag
                    // the load window backwards as you scroll.
                    val previous = if (index == 0) null else tracks.peek(index - 1)
                    val newAlbum = previous?.albumId != track.albumId
                    if (newAlbum) {
                        AlbumHeader(
                            track = track,
                            onPlay = { vm.playFrom(track) },
                            onOpen = { vm.openAlbum(track.albumId, track.albumTitle) },
                            onLongPress = { headerSheetFor = track },
                        )
                    }
                    // Only for genuine multi-disc sets, and only where the disc
                    // actually changes -- a single-disc album labelled "Disc 1"
                    // is noise. The list is already ordered by disc, so a
                    // boundary here is a real one.
                    if (track.albumDiscTotal > 1) {
                        val disc = track.discNo ?: 1
                        if (newAlbum || previous?.discNo != track.discNo) {
                            DiscHeader(disc)
                        }
                    }
                }
                TrackRow(
                    track = track,
                    isCurrent = track.title == nowPlaying.title,
                    showArtwork = !grouped,
                    onClick = { vm.playFrom(track) },
                    onLongClick = { sheetFor = track },
                    onToggleLoved = { vm.toggleLoved(track) },
                )
            }
        }
    }

    // Asked once when the sheet opens: whether to offer "undo my edits" is the
    // only thing that needs it, and querying per row would be a read per item.
    LaunchedEffect(sheetFor) {
        sheetEdited = sheetFor?.let { vm.isEdited(it.id) } ?: false
    }

    sheetFor?.let { track ->
        TrackActionSheet(
            track = track,
            edited = sheetEdited,
            onDismiss = { sheetFor = null },
            onEdit = { sheetFor = null; vm.openTrackEditor(track) },
            onRevert = { sheetFor = null; vm.revertTrackEdits(track.id) },
            onArtworkPicked = { uri -> sheetFor = null; vm.setTrackArtwork(track, uri) },
        )
    }

    editing?.let { (track, initial) ->
        // Asked once per target rather than per recomposition; an arrow that
        // does nothing is worse than one that is visibly disabled.
        var canPrev by remember(track.id) { mutableStateOf(false) }
        var canNext by remember(track.id) { mutableStateOf(false) }
        LaunchedEffect(track.id) {
            canPrev = vm.hasSiblingTrack(track, -1)
            canNext = vm.hasSiblingTrack(track, 1)
            vm.clearPastCovers()
        }

        TrackEditDialog(
            initial = initial,
            artworkId = track.albumArtworkId,
            canGoPrevious = canPrev,
            canGoNext = canNext,
            onDismiss = vm::closeTrackEditor,
            onSave = { edits -> vm.saveTrackEdits(track.id, edits) },
            onStep = { edits, delta -> vm.stepTrackEditor(track, edits, delta) },
            onCoverSave = { vm.saveAlbumCoverFor(track) },
            onCoverRemove = { vm.removeAlbumCover(track) },
            onCoverPicked = { uri -> vm.setAlbumArtwork(track, uri) },
            onCoverMessage = vm::reportEditorMessage,
            pastCovers = pastCovers,
            onLoadPastCovers = { vm.loadPastCovers(track) },
            onSavePastCover = vm::savePastCover,
            onRestorePastCover = { cover -> vm.restorePastCover(track, cover) },
        )
    }

    headerSheetFor?.let { track ->
        AlbumHeaderSheet(
            track = track,
            onDismiss = { headerSheetFor = null },
            onBulkEdit = { headerSheetFor = null; vm.openAlbumBulkEditor(track) },
            onArtworkPicked = { uri -> headerSheetFor = null; vm.setAlbumArtwork(track, uri) },
        )
    }

    // Counted once when the form opens rather than per recomposition: it is
    // only there to say "Edit 12 tracks" honestly.
    LaunchedEffect(bulkEditing) {
        bulkCount = bulkEditing?.let { vm.albumTrackCount(it.albumId) } ?: 0
    }

    bulkEditing?.let { track ->
        var canPrev by remember(track.albumId) { mutableStateOf(false) }
        var canNext by remember(track.albumId) { mutableStateOf(false) }
        LaunchedEffect(track.albumId) {
            canPrev = vm.hasSiblingAlbum(track, -1)
            canNext = vm.hasSiblingAlbum(track, 1)
            vm.clearPastCovers()
        }

        AlbumBulkEditDialog(
            albumTitle = track.albumTitle,
            trackCount = bulkCount,
            initialArtist = track.albumArtistName,
            artworkId = track.albumArtworkId,
            canGoPrevious = canPrev,
            canGoNext = canNext,
            onDismiss = vm::closeAlbumBulkEditor,
            onSave = { edits -> vm.applyAlbumEdits(track.albumId, edits) },
            onStep = { edits, delta -> vm.stepAlbumEditor(track, edits, delta) },
            onCoverSave = { vm.saveAlbumCoverFor(track) },
            onCoverRemove = { vm.removeAlbumCover(track) },
            onCoverPicked = { uri -> vm.setAlbumArtwork(track, uri) },
            onCoverMessage = vm::reportEditorMessage,
            pastCovers = pastCovers,
            onLoadPastCovers = { vm.loadPastCovers(track) },
            onSavePastCover = vm::savePastCover,
            onRestorePastCover = { cover -> vm.restorePastCover(track, cover) },
        )
    }
}

@Composable
private fun ArtistList(vm: LibraryViewModel, listState: LazyListState) {
    val artists = vm.pagedArtists.collectAsLazyPagingItems()

    // Long-press target. Held here rather than in the ViewModel because it is
    // pure view state -- it should not survive a rotation mid-gesture.
    var sheetFor by remember { mutableStateOf<ArtistListItem?>(null) }
    var viewing by remember { mutableStateOf<ArtistListItem?>(null) }

    if (artists.itemCount == 0) { EmptyState("No artists yet"); return }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(count = artists.itemCount, key = artists.itemKey { it.id }) { index ->
            artists[index]?.let { artist ->
                ArtistRow(
                    artist = artist,
                    onClick = { vm.openArtist(artist.id, artist.name) },
                    onLongClick = { sheetFor = artist },
                )
            }
        }
    }

    sheetFor?.let { artist ->
        ArtworkSheet(
            target = artist.asTarget(),
            onDismiss = { sheetFor = null },
            onView = { sheetFor = null; viewing = artist },
            onSave = { sheetFor = null; vm.saveArtistPhoto(artist) },
            onPicked = { uri -> sheetFor = null; vm.setArtistPhoto(artist, uri) },
            onToggleAlternate = { useLogo ->
                sheetFor = null
                vm.setArtistPreferLogo(artist, useLogo)
            },
        )
    }

    viewing?.let { artist ->
        ArtworkViewer(target = artist.asTarget(), onDismiss = { viewing = null })
    }
}

@Composable
private fun AlbumList(vm: LibraryViewModel, listState: LazyListState) {
    val albums = vm.pagedAlbums.collectAsLazyPagingItems()

    var sheetFor by remember { mutableStateOf<AlbumListItem?>(null) }
    var viewing by remember { mutableStateOf<AlbumListItem?>(null) }

    if (albums.itemCount == 0) { EmptyState("No albums yet"); return }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(count = albums.itemCount, key = albums.itemKey { it.id }) { index ->
            albums[index]?.let { album ->
                AlbumRow(
                    album = album,
                    onClick = { vm.openAlbum(album.id, album.title) },
                    onLongClick = { sheetFor = album },
                )
            }
        }
    }

    sheetFor?.let { album ->
        ArtworkSheet(
            target = album.asTarget(),
            onDismiss = { sheetFor = null },
            onView = { sheetFor = null; viewing = album },
            onSave = { sheetFor = null; vm.saveAlbumCover(album) },
            onPicked = { uri -> sheetFor = null; vm.setAlbumCover(album, uri) },
        )
    }

    viewing?.let { album ->
        ArtworkViewer(target = album.asTarget(), onDismiss = { viewing = null })
    }
}

private fun ArtistListItem.asTarget(): ArtworkTarget {
    // Captured locally: Kotlin will not smart-cast a property declared in
    // another module, because it cannot prove the getter is stable.
    val logo = logoArtworkId
    val photo = artworkId
    val showingLogo = preferLogo && logo != null
    return ArtworkTarget(
        title = name,
        subtitle = null,
        artworkId = if (showingLogo) logo else photo,
        noun = if (showingLogo) "logo" else "photo",
        alternate = if (logo != null && photo != null) {
            ArtworkTarget.Alternate(usingLogo = showingLogo)
        } else null,
    )
}

/** What the row actually draws: the chosen image, falling back to the other. */
private fun ArtistListItem.displayArtworkId(): String? =
    if (preferLogo) logoArtworkId ?: artworkId else artworkId ?: logoArtworkId

private fun AlbumListItem.asTarget() =
    ArtworkTarget(title = title, subtitle = artistName, artworkId = artworkId, noun = "cover")

@Composable
private fun PlaylistList(vm: LibraryViewModel) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ListItem(
                modifier = Modifier.clickable { vm.openLoved() },
                headlineContent = { Text("Loved") },
                supportingContent = { Text("Tracks you have hearted") },
                leadingContent = {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
        item {
            ListItem(
                headlineContent = {
                    Text("Custom playlists", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                supportingContent = { Text("Coming in a later phase") },
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TrackRow(
    track: TrackListItem,
    isCurrent: Boolean,
    showArtwork: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleLoved: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = {
            Text(
                track.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Text(
                "${track.artistName} · ${track.albumTitle}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (showArtwork) {
                Artwork(track.artworkId)
            } else {
                // The track number takes the artwork's place, which is what
                // makes a grouped album read as a track listing.
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Text(
                        track.trackNo?.toString().orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onToggleLoved) {
                Icon(
                    if (track.loved) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (track.loved) "Remove from Loved" else "Add to Loved",
                    tint = if (track.loved) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ArtistRow(
    artist: ArtistListItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${artist.albumCount} albums · ${artist.trackCount} tracks")
        },
        leadingContent = { Artwork(artist.displayArtworkId(), circular = true) },
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun AlbumRow(
    album: AlbumListItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val year = album.year?.let { " · $it" }.orEmpty()
            Text("${album.artistName}$year · ${album.trackCount} tracks",
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Artwork(album.artworkId) },
    )
}

@Composable
private fun Artwork(artworkId: String?, circular: Boolean = false) {
    val ctx = LocalContext.current
    val shape = if (circular) MaterialTheme.shapes.extraLarge else MaterialTheme.shapes.small

    // Captured locally: Kotlin will not smart-cast a property declared in
    // another module, because it cannot prove the getter is stable.
    val id = artworkId
    if (id != null) {
        AsyncImage(
            // content:// rather than a bitmap. The same URI the car will use --
            // Android Auto cannot take bitmaps, so both surfaces share one path.
            model = ArtworkProvider.uri(ctx, id, size = 320),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(shape),
        )
    } else {
        Surface(Modifier.size(44.dp), shape = shape, color = MaterialTheme.colorScheme.surfaceVariant) {}
    }
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.titleMedium)
                Text(artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect Google Drive in settings, then refresh your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
