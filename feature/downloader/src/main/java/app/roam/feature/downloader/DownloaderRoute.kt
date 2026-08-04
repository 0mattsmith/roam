package app.roam.feature.downloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var tab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

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

            when {
                state.query.isBlank() ->
                    Hint("Search your library, or YouTube Music for something new")

                tab == 0 -> LibraryResults(state.library, onPlay)

                // Nothing is drawn until the first batch is fully looked up:
                // album and year need a real extraction, and rows that appear
                // bare and then rearrange are worse than a moment's wait.
                state.searchingYoutube -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.youtube.isEmpty() ->
                    Hint("No results. If this keeps happening, update yt-dlp from the menu.")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.youtube.size, key = { state.youtube[it].videoId }) { index ->
                        val result = state.youtube[index]
                        YoutubeRow(
                            result = result,
                            onDownload = { vm.download(result) },
                            onViewAlbum = { vm.viewAlbum(result) },
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
}

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
@Composable
private fun YoutubeRow(
    result: YoutubeResult,
    onDownload: () -> Unit,
    onViewAlbum: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    ListItem(
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
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download")
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
