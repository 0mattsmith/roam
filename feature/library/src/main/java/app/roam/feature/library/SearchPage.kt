package app.roam.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.roam.core.database.AlbumListItem
import app.roam.core.database.ArtistListItem
import app.roam.core.database.TrackListItem

/*
 * The search page, stage 1 of docs/SEARCH.md: your own music, found from the
 * magnifying glass on the library screen.
 *
 * It is a LAYER of the library rather than a screen of its own, the way the
 * drills are. An artist or album opened from a result is a drill on top of it,
 * so Back comes home to the results with the query and the scroll position as
 * they were -- nothing has to be rebuilt, because nothing was taken down.
 */

/**
 * The search box, standing in for the app bar while the page is open.
 *
 * Styled like the downloader's, because until stage 4 brings music you do not
 * have onto this page the two are one search split across two screens, and
 * the button on the right is the way across.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onFindElsewhere: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // Up for a fresh search, not when Back returns to one: someone coming back
    // from an album came for the results, and a keyboard would cover them.
    LaunchedEffect(Unit) {
        if (query.isBlank()) runCatching { focus.requestFocus() }
    }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text("Artists, albums or songs") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                        }
                    }
                },
                // Results arrive as you type, so the key only has to get the
                // keyboard out of their way.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                // Undecorated, as in the downloader: it stands in for a title,
                // and a filled box with an underline reads as a stray form field.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the library")
            }
        },
        actions = {
            IconButton(onClick = onFindElsewhere) {
                Icon(Icons.Filled.LibraryAdd, contentDescription = "Find music to add")
            }
        },
    )
}

/**
 * The results, with the sheets their rows open.
 *
 * A long-press opens exactly what the same row opens on its own tab, so a
 * result does what that row does and there is no second copy to drift.
 */
@Composable
internal fun SearchHost(
    vm: LibraryViewModel,
    listState: LazyListState,
    onFindElsewhere: () -> Unit,
) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

    // Long-press targets. Here rather than in the ViewModel because they are
    // pure view state, like the tabs' own.
    var trackSheet by remember { mutableStateOf<TrackListItem?>(null) }
    var trackEdited by remember { mutableStateOf(false) }
    var artistSheet by remember { mutableStateOf<ArtistListItem?>(null) }
    var albumSheet by remember { mutableStateOf<AlbumListItem?>(null) }
    var viewingArtist by remember { mutableStateOf<ArtistListItem?>(null) }
    var viewingAlbum by remember { mutableStateOf<AlbumListItem?>(null) }

    SearchPage(
        query = query,
        results = results,
        playingTrackId = nowPlaying.trackId,
        playing = nowPlaying.isPlaying,
        listState = listState,
        onPlay = { vm.playSearchResult(it) },
        onTrackLongPress = { trackSheet = it },
        onOpenArtist = { vm.openArtist(it.id, it.name) },
        onArtistLongPress = { artistSheet = it },
        onOpenAlbum = { vm.openAlbum(it.id, it.title) },
        onAlbumLongPress = { albumSheet = it },
        onSeeAll = { vm.openSearchList(it) },
        onFindElsewhere = onFindElsewhere,
    )

    // Asked once when the sheet opens, as the track list does: whether to
    // offer "undo my edits" is the only thing that needs it.
    LaunchedEffect(trackSheet) {
        trackEdited = trackSheet?.let { vm.isEdited(it.id) } ?: false
    }

    trackSheet?.let { track ->
        TrackActionSheet(
            track = track,
            edited = trackEdited,
            onDismiss = { trackSheet = null },
            // The row's trailing slot holds the play button here rather than
            // the heart, so the heart moves into the sheet.
            onToggleLoved = { trackSheet = null; vm.toggleLoved(track) },
            onGoToAlbum = { trackSheet = null; vm.openAlbum(track.albumId, track.albumTitle) },
            onGoToArtist = { trackSheet = null; vm.openArtistByName(track.artistName) },
            onEdit = { trackSheet = null; vm.editFromSearch(track) },
            onRevert = { trackSheet = null; vm.revertTrackEdits(track.id) },
            onRemove = { trackSheet = null; vm.removeTrack(track) },
            onArtworkPicked = { uri -> trackSheet = null; vm.setTrackArtwork(track, uri) },
        )
    }

    artistSheet?.let { artist ->
        ArtworkSheet(
            target = artist.asTarget(),
            onDismiss = { artistSheet = null },
            onView = { artistSheet = null; viewingArtist = artist },
            onSave = { artistSheet = null; vm.saveArtistPhoto(artist) },
            onPicked = { uri -> artistSheet = null; vm.setArtistPhoto(artist, uri) },
            onToggleAlternate = { useLogo ->
                artistSheet = null
                vm.setArtistPreferLogo(artist, useLogo)
            },
        )
    }

    albumSheet?.let { album ->
        ArtworkSheet(
            target = album.asTarget(),
            onDismiss = { albumSheet = null },
            onView = { albumSheet = null; viewingAlbum = album },
            onSave = { albumSheet = null; vm.saveAlbumCover(album) },
            onPicked = { uri -> albumSheet = null; vm.setAlbumCover(album, uri) },
        )
    }

    viewingArtist?.let { artist ->
        ArtworkViewer(target = artist.asTarget(), onDismiss = { viewingArtist = null })
    }
    viewingAlbum?.let { album ->
        ArtworkViewer(target = album.asTarget(), onDismiss = { viewingAlbum = null })
    }
}

/**
 * The page itself, with no state of its own.
 *
 * One group for now -- In your library -- in the order the mockup sets out:
 * artists, albums, songs, the first few of each and then See all. Below it the
 * place where stage 4's second group will be, which for now hands the term to
 * the downloader rather than making someone type it twice.
 */
@Composable
internal fun SearchPage(
    query: String,
    results: SearchResults,
    playingTrackId: Long?,
    playing: Boolean,
    listState: LazyListState,
    onPlay: (TrackListItem) -> Unit,
    onTrackLongPress: (TrackListItem) -> Unit,
    onOpenArtist: (ArtistListItem) -> Unit,
    onArtistLongPress: (ArtistListItem) -> Unit,
    onOpenAlbum: (AlbumListItem) -> Unit,
    onAlbumLongPress: (AlbumListItem) -> Unit,
    onSeeAll: (SearchKind) -> Unit,
    onFindElsewhere: () -> Unit,
) {
    if (query.isBlank()) {
        SearchMessage(
            "Search your artists, albums and songs",
            action = "Find music you don't have",
            onAction = onFindElsewhere,
        )
        return
    }
    // The first term is still settling. Said, rather than a flash of "nothing
    // matches" that then changes its mind.
    if (results.query.isEmpty()) {
        SearchMessage("Searching…")
        return
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "mine") { GroupLabel("In your library") }

        if (results.isEmpty) {
            item(key = "none") {
                Text(
                    "Nothing in your library matches “${results.query}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        if (results.artists.isNotEmpty()) {
            item(key = "artists") {
                SectionHeading(SearchKind.ARTISTS, results.artistCount, results.artists.size, onSeeAll)
            }
            items(results.artists, key = { "artist-${it.id}" }) { artist ->
                ArtistRow(
                    artist = artist,
                    onClick = { onOpenArtist(artist) },
                    onLongClick = { onArtistLongPress(artist) },
                    trailing = { Chevron() },
                )
            }
        }

        if (results.albums.isNotEmpty()) {
            item(key = "albums") {
                SectionHeading(SearchKind.ALBUMS, results.albumCount, results.albums.size, onSeeAll)
            }
            items(results.albums, key = { "album-${it.id}" }) { album ->
                AlbumRow(
                    album = album,
                    onClick = { onOpenAlbum(album) },
                    onLongClick = { onAlbumLongPress(album) },
                    trailing = { Chevron() },
                )
            }
        }

        if (results.songs.isNotEmpty()) {
            item(key = "songs") {
                SectionHeading(SearchKind.SONGS, results.songCount, results.songs.size, onSeeAll)
            }
            items(results.songs, key = { "song-${it.id}" }) { track ->
                val current = track.id == playingTrackId
                TrackRow(
                    track = track,
                    isCurrent = current,
                    showArtwork = true,
                    onClick = { onPlay(track) },
                    onLongClick = { onTrackLongPress(track) },
                    // Unused: the trailing slot below replaces the heart.
                    onToggleLoved = {},
                    trailing = { PlayState(sounding = current && playing, onClick = { onPlay(track) }) },
                )
            }
        }

        item(key = "elsewhere") {
            Column {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                GroupLabel("Not in your library")
                ListItem(
                    modifier = Modifier.clickable(onClick = onFindElsewhere),
                    headlineContent = { Text("Search the catalogues and YouTube") },
                    supportingContent = {
                        Text("For “${results.query}”, to add to your library")
                    },
                    leadingContent = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionHeading(
    kind: SearchKind,
    total: Int,
    shown: Int,
    onSeeAll: (SearchKind) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            kind.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Only when there is more to see. A See all that shows the same three
        // rows again is a button that lies.
        if (total > shown) {
            TextButton(onClick = { onSeeAll(kind) }) { Text("See all $total") }
        }
    }
}

/** Where a song you have can be played from, and says so while it is. */
@Composable
private fun PlayState(sounding: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (sounding) Icons.Filled.GraphicEq else Icons.Filled.PlayArrow,
                contentDescription = if (sounding) "Playing" else "Play",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
