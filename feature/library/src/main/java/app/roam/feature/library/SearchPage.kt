package app.roam.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.roam.data.acquire.AddLedger
import app.roam.data.acquire.AddState
import app.roam.data.acquire.DownloadStatus
import app.roam.data.acquire.YoutubeResult
import app.roam.data.catalog.metadata.ArtistMatch
import kotlinx.coroutines.launch

/*
 * The search page (docs/SEARCH.md): your own music, then music you do not have,
 * found from the magnifying glass on the library screen. Every row says which
 * by the icon it ends in -- a play for yours, a + for the rest.
 *
 * It is a LAYER of the library rather than a screen of its own, the way the
 * drills are. An artist or album opened from a result is a drill on top of it,
 * so Back comes home to the results with the query and the scroll position as
 * they were -- nothing has to be rebuilt, because nothing was taken down.
 */

/**
 * The search box, standing in for the app bar while the page is open, with the
 * Adding list beside it -- badged with how many adds are still on their way --
 * and an overflow holding Update yt-dlp.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    /** Adds not yet finished. */
    adding: Int,
    onOpenAdding: () -> Unit,
    onUpdateYtDlp: () -> Unit,
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
                // Undecorated: it stands in for a title, and a filled box
                // with an underline reads as a stray form field.
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
            // BadgedBox wraps the BUTTON, not the icon inside it. Anchored to
            // the icon it is boxed in by the 48dp touch target, and a
            // three-digit count runs out of room and loses its last digit.
            BadgedBox(
                badge = {
                    if (adding > 0) {
                        // Capped: past a hundred the exact number is not
                        // information anyone acts on, and it is the width
                        // that causes the trouble.
                        Badge { Text(if (adding > BADGE_MAX) "$BADGE_MAX+" else "$adding") }
                    }
                }
            ) {
                IconButton(onClick = onOpenAdding) {
                    Icon(Icons.Filled.LibraryAdd, contentDescription = "Adding to library")
                }
            }

            // Button and menu share a Box so the menu anchors to the button
            // rather than to the whole app bar.
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                    // yt-dlp goes stale and quietly stops finding songs when it
                    // does, so the fix sits where the symptom shows.
                    DropdownMenuItem(
                        text = { Text("Update yt-dlp") },
                        onClick = { menuOpen = false; onUpdateYtDlp() },
                    )
                }
            }
        },
    )
}

/**
 * Highest count the badge prints before falling back to "99+".
 *
 * The badge grows with its text; three digits pushed it past the edge and the
 * last one was clipped away, which is worse than rounding because it reads as
 * a smaller number rather than as a big one.
 */
private const val BADGE_MAX = 99

/**
 * The results, with the sheets their rows open.
 *
 * A long-press opens exactly what the same row opens on its own tab, so a
 * result does what that row does and there is no second copy to drift.
 */
@Composable
internal fun SearchHost(
    vm: LibraryViewModel,
    addVm: AddMusicViewModel,
    listState: LazyListState,
) {
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val elsewhere by addVm.results.collectAsStateWithLifecycle()
    val ledger by addVm.ledger.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The first few albums, then all of them on asking. Per term, so a new
    // search starts short again; saveable, so a rotation does not.
    var allReleases by rememberSaveable(elsewhere.query) { mutableStateOf(false) }

    val actions = CatalogueActions(
        onOpenArtist = addVm::openCatalogueArtist,
        onOpenRelease = addVm::openRelease,
        onAddRelease = addVm::addRelease,
        onAddSong = addVm::add,
        onSongAlbum = addVm::viewAlbum,
        onSongArtist = { addVm.viewArtist(it.artist) },
        // A song of yours plays in its album from that song, as the album
        // sheet plays it; a whole album, from its first track.
        onPlayTrack = { vm.playAlbumFrom(it) },
        onPlayAlbum = { vm.playAlbumFrom(it, fromStart = true) },
        onFound = { jobs ->
            scope.launch { addVm.findAdded(jobs)?.let { vm.revealTrack(it, openEditor = false) } }
        },
        onFailed = addVm::showFailure,
        onShowMore = addVm::showMore,
        onCompilationsOnly = addVm::setCompilationsOnly,
    )

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
        elsewhere = {
            catalogueItems(
                results = elsewhere,
                ledger = ledger,
                actions = actions,
                allReleases = allReleases,
                onShowAllReleases = { allReleases = true },
            )
        },
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
            onPlayNext = { trackSheet = null; vm.playNext(track) },
            onAddToQueue = { trackSheet = null; vm.addToQueue(track) },
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
 * In your library first, in the order the mockup sets out: artists, albums,
 * songs, the first few of each and then See all. Then whatever [elsewhere]
 * adds below it -- the second group, music you do not have.
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
    /** The second group, drawn below the first. */
    elsewhere: LazyListScope.() -> Unit,
) {
    if (query.isBlank()) {
        SearchMessage("Search your library, and the catalogues and YouTube for music to add")
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

        elsewhere()
    }
}

@Composable
internal fun GroupLabel(text: String) {
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
internal fun Chevron() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchMessage(message: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

// ---- the second group -------------------------------------------------------

/** What the second group's rows can ask for, gathered so the page does not take a dozen lambdas. */
internal class CatalogueActions(
    val onOpenArtist: (ArtistMatch) -> Unit,
    val onOpenRelease: (ReleaseGroup) -> Unit,
    val onAddRelease: (ReleaseGroup) -> Unit,
    val onAddSong: (YoutubeResult) -> Unit,
    val onSongAlbum: (YoutubeResult) -> Unit,
    val onSongArtist: (YoutubeResult) -> Unit,
    /** A track of yours, by id. */
    val onPlayTrack: (Long) -> Unit,
    /** An album of yours, from its first track, by any track of it. */
    val onPlayAlbum: (Long) -> Unit,
    val onFound: (List<DownloadStatus>) -> Unit,
    val onFailed: (AddState.Failed) -> Unit,
    val onShowMore: () -> Unit,
    val onCompilationsOnly: (Boolean) -> Unit,
)

/**
 * Not in your library: artists, albums and songs from the catalogues and
 * YouTube, in the first group's order, so the page reads the same way twice.
 *
 * Nothing appears for a single letter, and nothing is asked until the typing
 * stops (docs/SEARCH.md). Each section draws as its source answers, and a
 * source that could not be reached says so in its own place -- the first
 * group needs no signal and goes on working.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun LazyListScope.catalogueItems(
    results: CatalogueResults,
    ledger: AddLedger,
    actions: CatalogueActions,
    allReleases: Boolean,
    onShowAllReleases: () -> Unit,
) {
    if (results.query.isEmpty()) return

    item(key = "elsewhere") {
        Column {
            HorizontalDivider(Modifier.padding(top = 12.dp))
            GroupLabel("Not in your library")
            // One bar for the group while anything in it is still being
            // asked, outside every section, so no section changing can hide it.
            if (results.searching) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    val songsError = results.songsError
    if (!results.searching && results.isEmpty && results.cataloguesUnreachable && songsError != null) {
        item(key = "elsewhere-unreachable") {
            CatalogueNote("Could not reach the catalogues or YouTube. Your own music above needs no signal.")
        }
        return
    }

    if (results.artists.isNotEmpty()) {
        item(key = "elsewhere-artists") { CatalogueHeading("Artists") }
        items(results.artists, key = { "remote-artist-${it.source}-${it.id}" }) { match ->
            CatalogueArtistRow(match, onOpen = { actions.onOpenArtist(match) })
        }
    }

    // Always headed once the albums have been asked for, so the filter is
    // there to turn off again when it found nothing.
    item(key = "elsewhere-albums") {
        CatalogueHeading("Albums") {
            FilterChip(
                selected = results.compilationsOnly,
                onClick = { actions.onCompilationsOnly(!results.compilationsOnly) },
                label = { Text("Compilations only") },
            )
        }
    }
    when {
        results.releases.isNotEmpty() -> {
            val shown = if (allReleases) results.releases else results.releases.take(RELEASES_SHOWN)
            items(shown, key = { "remote-album-${it.key}" }) { group ->
                val placement = group.placement
                val state = ledger.forAlbum(placement.album, placement.filingArtist)
                ReleaseRow(
                    group = group,
                    state = state,
                    onOpen = { actions.onOpenRelease(group) },
                    onAdd = { actions.onAddRelease(group) },
                    onPlay = actions.onPlayAlbum,
                    onFound = {
                        actions.onFound(ledger.jobsForAlbum(placement.album, placement.filingArtist))
                    },
                    onFailed = actions.onFailed,
                )
            }
            if (!allReleases && results.releases.size > RELEASES_SHOWN) {
                item(key = "elsewhere-albums-all") {
                    TextButton(
                        onClick = onShowAllReleases,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text("Show all ${results.releases.size} albums") }
                }
            }
        }
        // The bar above says it is coming.
        results.searchingReleases -> Unit
        results.cataloguesUnreachable -> item(key = "elsewhere-albums-none") {
            CatalogueNote("Could not reach Discogs or MusicBrainz")
        }
        else -> item(key = "elsewhere-albums-none") {
            CatalogueNote(
                if (results.compilationsOnly) "No compilations found. Try turning the filter off."
                else "No albums found that you do not have"
            )
        }
    }

    if (results.songs.isNotEmpty() || songsError != null || !results.searchingSongs) {
        item(key = "elsewhere-songs") { CatalogueHeading("Songs") }
    }
    when {
        results.songs.isNotEmpty() -> {
            items(results.songs, key = { "remote-song-${it.videoId}" }) { song ->
                val url = song.url
                val held = results.heldSongs[song.videoId]
                SongRow(
                    result = song,
                    state = if (held != null) AddState.Ready(held) else ledger.forUrl(url),
                    onAdd = { actions.onAddSong(song) },
                    onPlay = actions.onPlayTrack,
                    onFound = { actions.onFound(ledger.jobsForUrl(url)) },
                    onFailed = actions.onFailed,
                    onViewAlbum = { actions.onSongAlbum(song) },
                    onViewArtist = { actions.onSongArtist(song) },
                )
            }
            if (results.hasMore) {
                item(key = "elsewhere-songs-more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        if (results.loadingMore) CircularProgressIndicator()
                        else OutlinedButton(onClick = actions.onShowMore) { Text("Show more") }
                    }
                }
            }
        }
        songsError != null -> item(key = "elsewhere-songs-none") {
            // yt-dlp's own words, then what usually fixes them.
            CatalogueNote("$songsError\n\nIf this keeps happening, update yt-dlp from the menu.")
        }
        results.searchingSongs -> Unit
        else -> item(key = "elsewhere-songs-none") {
            CatalogueNote("No songs found that you do not have")
        }
    }
}

/** Albums shown before "Show all": the first few, as the first group shows. */
private const val RELEASES_SHOWN = 4

@Composable
private fun CatalogueHeading(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun CatalogueNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
