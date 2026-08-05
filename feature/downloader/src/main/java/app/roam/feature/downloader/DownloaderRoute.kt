package app.roam.feature.downloader

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import app.roam.core.database.TrackListItem
import coil.compose.AsyncImage

/**
 * One search box over the library and over YouTube Music.
 *
 * Two tabs rather than one merged list: "do I already have this" and "can I
 * get this" are different questions, and interleaving the answers makes both
 * harder to read. The library tab answers from Room as you type; the YouTube
 * one costs a round trip through a native binary, so it waits longer before
 * asking and says so while it works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderRoute(
    onBack: () -> Unit,
    onPlay: (TrackListItem) -> Unit = {},
    vm: DownloaderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val album by vm.album.collectAsStateWithLifecycle()
    val artist by vm.artist.collectAsStateWithLifecycle()
    val queued by vm.queuedUrls.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbars.showSnackbar(it); vm.clearMessage() }
    }

    // Opens with the keyboard up: nobody navigates here to look at an empty
    // screen, they came to type something.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = vm::onQueryChanged,
                        placeholder = { Text("Search") },
                        singleLine = true,
                        // Undecorated: it is standing in for the title, and a
                        // filled box with an underline inside an app bar reads
                        // as a form field that wandered in.
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedIndicatorColor = MaterialTheme.colorScheme.surface,
                            unfocusedIndicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                        trailingIcon = {
                            if (state.query.isNotEmpty()) {
                                IconButton(onClick = { vm.onQueryChanged("") }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Badged rather than a count in text: the number that
                    // matters is how many are still going, and it should be
                    // readable at a glance while something is downloading.
                    val active = downloads.count { !it.finished }
                    IconButton(onClick = { showDownloads = true }) {
                        BadgedBox(
                            badge = { if (active > 0) Badge { Text("$active") } }
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = "Downloads")
                        }
                    }

                    // Button and menu share a Box so the menu anchors to the
                    // button rather than to the whole app bar.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Update yt-dlp") },
                                onClick = { menuOpen = false; vm.updateYtDlp() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Library") })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text("YouTube Music") })
            }

            // Sits OUTSIDE the when below, deliberately. A spinner that lives
            // in one branch of a when can be hidden by any branch above it
            // changing, which is exactly how the last two attempts at this
            // failed. This bar depends on one boolean and nothing else.
            if (tab == 1 && (state.searchingYoutube || state.loadingMore)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            when {
                state.query.isBlank() ->
                    Hint("Search your library, or YouTube Music for something new")

                tab == 0 -> LibraryResults(state.library, onPlay)

                // Nothing is drawn until the first batch is fully looked up:
                // album and year need a real extraction, and rows that appear
                // bare and then rearrange are worse than a moment's wait.
                state.searchingYoutube -> Column(
                    Modifier.fillMaxSize().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Looking up tracks…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.youtube.isEmpty() ->
                    Hint("No results. If this keeps happening, update yt-dlp from the menu.")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.youtube.size, key = { state.youtube[it].videoId }) { index ->
                        val result = state.youtube[index]
                        YoutubeRow(
                            result = result,
                            queued = result.url in queued,
                            onDownload = { vm.download(result) },
                            onViewAlbum = { vm.viewAlbum(result) },
                            onViewArtist = { vm.viewArtist(result.artist) },
                        )
                    }

                    if (state.hasMore) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (state.loadingMore) CircularProgressIndicator()
                                // Usually instant: the next batch was fetched
                                // while this one was being read.
                                else OutlinedButton(onClick = vm::showMore) { Text("Show more") }
                            }
                        }
                    }
                }
            }
        }
    }

    artist?.let {
        ArtistSheet(
            artist = it,
            onDismiss = vm::closeArtist,
            onOpenRelease = vm::openRelease,
        )
    }

    album?.let {
        AlbumSheet(
            album = it,
            onDismiss = vm::closeAlbum,
            onDownloadMissing = vm::downloadMissing,
            onDownloadTrack = vm::downloadTrack,
            onSelectRelease = vm::selectRelease,
            onSelectSource = vm::selectSource,
            queuedUrls = queued,
            urlFor = vm::searchUrlFor,
        )
    }

    if (showDownloads) {
        DownloadsSheet(
            downloads = downloads,
            onDismiss = { showDownloads = false },
            onClearFinished = vm::clearFinishedDownloads,
            onRetry = vm::retry,
        )
    }
}

/**
 * The download queue.
 *
 * Read straight from WorkManager, so it is still right after the app has been
 * killed and reopened -- which for a phone downloading over mobile data is the
 * normal case rather than an unusual one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsSheet(
    downloads: List<DownloadStatus>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onRetry: (DownloadStatus) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Downloads",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.any { it.finished }) {
                    TextButton(onClick = onClearFinished) { Text("Clear finished") }
                }
            }

            if (downloads.isEmpty()) {
                Text(
                    "Nothing downloading",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(downloads.size, key = { downloads[it].id }) { index ->
                    DownloadRow(downloads[index], onRetry = { onRetry(downloads[index]) })
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * One queued, running or finished download.
 *
 * Colours are stated outright rather than taken from the scheme: green for
 * done and red for failed mean the same thing in every theme, and a "success"
 * that comes out purple because the palette says so communicates nothing.
 */
@Composable
private fun DownloadRow(download: DownloadStatus, onRetry: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(download.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        download.artist.ifBlank { null },
                        when (download.state) {
                            WorkInfo.State.ENQUEUED -> "Waiting"
                            WorkInfo.State.RUNNING -> "Downloading"
                            WorkInfo.State.SUCCEEDED -> "Saved to Drive"
                            WorkInfo.State.FAILED -> "Failed"
                            WorkInfo.State.BLOCKED -> "Queued"
                            WorkInfo.State.CANCELLED -> "Cancelled"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // The bar is gone entirely once it is done -- a full bar and a
                // tick say the same thing twice.
                if (download.running) {
                    Spacer(Modifier.height(6.dp))
                    if (download.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { download.progress },
                            color = PROGRESS_TEAL,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // yt-dlp reports nothing until bytes start arriving;
                        // indeterminate is honest about not knowing yet.
                        LinearProgressIndicator(
                            color = PROGRESS_TEAL,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // Frozen where it stopped, in red. How far it got is the
                // useful part -- a bar that vanishes on failure loses it.
                if (download.failed) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { download.progress },
                        color = FAILED_RED,
                        trackColor = FAILED_RED.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
        trailingContent = {
            when {
                download.succeeded -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Finished",
                    tint = DONE_GREEN,
                )
                download.failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Cancel,
                        contentDescription = "Failed",
                        tint = FAILED_RED,
                    )
                    IconButton(onClick = onRetry, enabled = download.request != null) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                    }
                }
                else -> {}
            }
        },
    )
}

private val PROGRESS_TEAL = Color(0xFF00897B)
private val DONE_GREEN = Color(0xFF2E7D32)
private val FAILED_RED = Color(0xFFC62828)

@Composable
private fun LibraryResults(tracks: List<TrackListItem>, onPlay: (TrackListItem) -> Unit) {
    if (tracks.isEmpty()) {
        Hint("Nothing in your library matches")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks.size, key = { tracks[it].id }) { index ->
            val track = tracks[index]
            ListItem(
                modifier = Modifier.clickable { onPlay(track) },
                headlineContent = {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        "${track.artistName} · ${track.albumTitle}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = { Icon(Icons.Filled.PlayArrow, contentDescription = "Play") },
            )
        }
    }
}

/**
 * A result drawn as the track it is: title, then artist, then album and year.
 *
 * Three lines rather than two because the album is the thing that tells you
 * whether this is the recording you meant -- the same song appears a dozen
 * times across singles, reissues and compilations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun YoutubeRow(
    result: YoutubeResult,
    queued: Boolean,
    onDownload: () -> Unit,
    onViewAlbum: () -> Unit,
    onViewArtist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
        // Long-press for the same actions the overflow carries. The menu stays
        // because a long press is not discoverable; the gesture is there
        // because once you know it, reaching for a 48dp target is slower.
        modifier = Modifier.combinedClickable(
            onClick = { if (!queued) onDownload() },
            onLongClick = { menuOpen = true },
        ),
        headlineContent = { Text(result.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        result.artist.ifBlank { null },
                        result.durationSec?.let { "%d:%02d".format(it / 60, it % 60) },
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Absent rather than blank when YouTube had no album: an empty
                // third line would leave the rows uneven for no information.
                result.albumLine?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        leadingContent = {
            val thumbnail = result.thumbnailUrl
            if (thumbnail != null) {
                AsyncImage(
                    model = thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // 56dp square: this is cover art on a music result, and a
                    // 16:9 still gets cropped to match rather than left letterboxed.
                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                )
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The tick is derived from the download queue, not from having
                // tapped the button, so it survives scrolling, leaving the
                // screen, and the app being killed mid-album.
                if (queued) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Queued",
                        tint = DONE_GREEN,
                    )
                } else {
                    IconButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "Download")
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("View album") },
                            enabled = result.album != null,
                            onClick = { menuOpen = false; onViewAlbum() },
                            leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                        )
                        DropdownMenuItem(
                            text = { Text("View artist") },
                            enabled = result.artist.isNotBlank(),
                            onClick = { menuOpen = false; onViewArtist() },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        )
                        if (!queued) {
                            DropdownMenuItem(
                                text = { Text("Download") },
                                onClick = { menuOpen = false; onDownload() },
                                leadingIcon = {
                                    Icon(Icons.Filled.Download, contentDescription = null)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
