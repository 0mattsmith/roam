package app.roam.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import app.roam.core.designsystem.Amber
import app.roam.data.acquire.DownloadStatus
import kotlinx.coroutines.launch

/**
 * Everything adding opens over the library: the placement question, the
 * album and artist sheets, a failure's reason and the Adding list.
 *
 * Drawn by the route rather than by the search page, so it is there whatever
 * is on screen underneath -- a share that arrives while an album is open
 * still gets its question asked.
 */
@Composable
internal fun AddMusicHost(
    addVm: AddMusicViewModel,
    /** A track you have, played in its album from that track. */
    onPlayTrack: (Long) -> Unit,
    /** A track you have, opened in its album, with the editor up if asked. */
    onOpenTrack: (Long, Boolean) -> Unit,
) {
    val pending by addVm.pending.collectAsStateWithLifecycle()
    val album by addVm.album.collectAsStateWithLifecycle()
    val artist by addVm.artist.collectAsStateWithLifecycle()
    val ledger by addVm.ledger.collectAsStateWithLifecycle()
    val downloads by addVm.downloads.collectAsStateWithLifecycle()
    val addingOpen by addVm.addingOpen.collectAsStateWithLifecycle()
    val failure by addVm.failure.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The tick: one more look, then the album it is in, or the reason not.
    val onFound: (List<DownloadStatus>) -> Unit = { jobs ->
        scope.launch {
            val id = addVm.findAdded(jobs) ?: return@launch
            addVm.closeAlbum()
            addVm.closeArtist()
            onOpenTrack(id, false)
        }
    }

    // The discography's place, out here rather than in the sheet: the sheet
    // leaves the composition while an album opened from it is up, and Back
    // from the album should land where you were.
    //
    // Started over for a new order, a new filter or a new artist. The rows
    // are keyed, and a keyed LazyColumn keeps its FIRST VISIBLE row in view
    // through a reorder -- so flipping Newest first to Oldest first used to
    // follow the newest release down to the bottom of the list. Saveable, so
    // a rotation keeps the place all the same.
    val discographyList = rememberSaveable(
        artist?.name,
        artist?.source,
        artist?.sort,
        artist?.includeCompilations,
        saver = LazyListState.Saver,
    ) { LazyListState() }

    // The artist sheet waits under an album opened from it, and comes back
    // when the album is put away: Back returns, here as on the page.
    val discography = artist
    if (discography != null && album == null) {
        ArtistSheet(
            artist = discography,
            onDismiss = addVm::closeArtist,
            onOpenRelease = addVm::openArtistRelease,
            onSort = addVm::setReleaseSort,
            onIncludeCompilations = addVm::setIncludeCompilations,
            listState = discographyList,
        )
    }

    album?.let {
        AlbumSheet(
            album = it,
            ledger = ledger,
            onDismiss = addVm::closeAlbum,
            onAddMissing = addVm::addMissing,
            onAddTrack = addVm::addTrack,
            onSelectRelease = addVm::selectRelease,
            onSelectSource = addVm::selectSource,
            urlFor = addVm::searchUrlFor,
            onPlayTrack = onPlayTrack,
            onFound = onFound,
            onFailed = addVm::showFailure,
        )
    }

    if (addingOpen) {
        AddingSheet(
            downloads = downloads,
            onDismiss = addVm::hideAdding,
            onClearFinished = addVm::clearFinished,
            onRetry = { addVm.retry(listOf(it)) },
            onClear = { addVm.clear(listOf(it)) },
            onCancel = addVm::cancel,
            onRestart = addVm::restart,
            onOpenTrack = { download, edit ->
                scope.launch {
                    val request = download.request ?: return@launch
                    val id = addVm.resolveTrackId(request)
                    if (id == null) {
                        addVm.reportNotInLibrary()
                    } else {
                        addVm.hideAdding()
                        onOpenTrack(id, edit)
                    }
                }
            },
        )
    }

    // Asked before anything is written, because the worker cannot ask later:
    // all it has by then is a search result, and a channel name is not an
    // album artist. Last, so it opens over whichever sheet asked for it.
    pending?.let { waiting ->
        PlacementDialog(
            initial = waiting.placement,
            trackCount = waiting.trackCount,
            trackTitle = waiting.requests.singleOrNull()?.title,
            note = waiting.note,
            onDismiss = addVm::cancelPlacement,
            onConfirm = addVm::confirmPlacement,
        )
    }

    failure?.let { failed ->
        FailedDialog(
            failure = failed,
            onRetry = {
                addVm.dismissFailure()
                addVm.retry(failed.jobs)
            },
            onClear = {
                addVm.dismissFailure()
                addVm.clear(failed.jobs)
            },
            onDismiss = addVm::dismissFailure,
        )
    }
}

/**
 * The Adding list: everything on its way to the library, and what became of it.
 *
 * Read straight from WorkManager, so it is still right after the app has been
 * killed and reopened -- which for a phone adding over mobile data is the
 * normal case rather than an unusual one.
 *
 * Its colours are the search page's: amber on its way, teal yours, red needs
 * you. A row here and the row it came from should never disagree about what
 * is happening.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddingSheet(
    downloads: List<DownloadStatus>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onRetry: (DownloadStatus) -> Unit,
    onClear: (DownloadStatus) -> Unit,
    onCancel: (DownloadStatus) -> Unit,
    onRestart: (DownloadStatus) -> Unit,
    onOpenTrack: (DownloadStatus, Boolean) -> Unit,
) {
    // Three lists, not three filters of one: which tab you want depends on why
    // you opened the sheet. Watching progress, chasing what broke, and checking
    // what landed are different errands, and a single list interleaves them
    // exactly when there is most to read.
    val adding = downloads.filterNot { it.finished }
    val failed = downloads.filter { it.failed }
    val added = downloads.filter { it.succeeded }

    // Failed and Added are hidden while empty. A permanent "Failed 0" is an
    // invitation to worry about nothing, and a tab you can never usefully open
    // is just a smaller screen.
    val tabs = buildList {
        add(Triple("Adding", Icons.Filled.Schedule, adding))
        if (failed.isNotEmpty()) add(Triple("Failed", Icons.Filled.Close, failed))
        if (added.isNotEmpty()) add(Triple("Added", Icons.Filled.Check, added))
    }

    var tab by remember { mutableIntStateOf(0) }
    // Clamped, because a tab can VANISH under you -- retrying the last failure
    // empties that list while you are looking at it, and an index left pointing
    // past the end is a crash rather than a cosmetic problem.
    val selected = tab.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Adding to library",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.any { it.finished }) {
                    TextButton(onClick = onClearFinished) { Text("Clear finished") }
                }
            }

            if (downloads.isEmpty()) {
                Text(
                    "Nothing being added. The + on a search result adds it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                // Only worth a tab row when there is somewhere else to go.
                if (tabs.size > 1) {
                    PrimaryTabRow(selectedTabIndex = selected) {
                        tabs.forEachIndexed { index, (label, icon, rows) ->
                            Tab(
                                selected = index == selected,
                                onClick = { tab = index },
                                text = { Text("$label ${rows.size}") },
                                icon = { Icon(icon, contentDescription = null) },
                            )
                        }
                    }
                }

                val rows = tabs.getOrNull(selected)?.third.orEmpty()

                if (rows.isEmpty()) {
                    Text(
                        "Nothing on its way",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }

                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(rows.size, key = { rows[it].id }) { index ->
                        val download = rows[index]
                        AddingRow(
                            download = download,
                            onRetry = { onRetry(download) },
                            onClear = { onClear(download) },
                            onCancel = { onCancel(download) },
                            onRestart = { onRestart(download) },
                            onOpenTrack = { onOpenTrack(download, false) },
                            onEditTrack = { onOpenTrack(download, true) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** One add, waiting, running or finished. */
@Composable
private fun AddingRow(
    download: DownloadStatus,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onRestart: () -> Unit,
    onOpenTrack: () -> Unit,
    onEditTrack: () -> Unit,
) {
    val teal = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error

    ListItem(
        headlineContent = {
            Text(download.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        download.artist.ifBlank { null },
                        // "Waiting", never "Queued": the queue is what plays
                        // next (docs/SEARCH.md, "Words").
                        when (download.state) {
                            WorkInfo.State.ENQUEUED -> "Waiting"
                            WorkInfo.State.RUNNING -> "Adding"
                            WorkInfo.State.SUCCEEDED -> "Added to library"
                            WorkInfo.State.FAILED -> "Failed"
                            WorkInfo.State.BLOCKED -> "Waiting"
                            WorkInfo.State.CANCELLED -> "Cancelled"
                        },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // "Failed" on its own invites a retry that will fail the same
                // way. A refused duration match is a statement about the search
                // itself, so it is worth the second line.
                download.error?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = red,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // The bar is gone entirely once it is done -- a full bar and a
                // tick say the same thing twice.
                if (download.running) {
                    Spacer(Modifier.height(6.dp))
                    if (download.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { download.progress },
                            color = Amber,
                            trackColor = Amber.copy(alpha = 0.22f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // yt-dlp reports nothing until bytes start arriving;
                        // indeterminate is honest about not knowing yet.
                        LinearProgressIndicator(
                            color = Amber,
                            trackColor = Amber.copy(alpha = 0.22f),
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
                        color = red,
                        trackColor = red.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        leadingContent = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    // Done: the useful actions are about the track now, not
                    // about the add.
                    download.succeeded -> {
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Added", tint = teal)
                        IconButton(onClick = onOpenTrack) {
                            Icon(Icons.Filled.Folder, contentDescription = "Show in library")
                        }
                        IconButton(onClick = onEditTrack) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit details")
                        }
                    }

                    download.failed -> {
                        Icon(Icons.Filled.Cancel, contentDescription = "Failed", tint = red)
                        IconButton(onClick = onRetry, enabled = download.request != null) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Retry")
                        }
                    }

                    // Still going: stopping it is the only thing worth
                    // offering. Restart lives in the overflow, because tapping
                    // it by accident on a half-finished add costs the bytes
                    // already spent.
                    else -> {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Filled.Stop, contentDescription = "Cancel")
                        }
                    }
                }

                // Only when there is something in it: a request that could not
                // be read cannot be restarted, and a finished success has
                // nothing to cancel or clear.
                if (download.request != null || !download.finished || download.failed) {
                    RowMenu(download, onRestart, onCancel, onClear)
                }
            }
        },
    )
}

@Composable
private fun RowMenu(
    download: DownloadStatus,
    onRestart: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    var rowMenu by remember(download.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { rowMenu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(rowMenu, onDismissRequest = { rowMenu = false }) {
            if (download.request != null) {
                DropdownMenuItem(
                    text = { Text("Restart") },
                    onClick = { rowMenu = false; onRestart() },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                )
            }
            if (!download.finished) {
                DropdownMenuItem(
                    text = { Text("Cancel") },
                    onClick = { rowMenu = false; onCancel() },
                    leadingIcon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                )
            }
            // One failure dismissed, where Clear finished takes
            // every finished row, the successes with them.
            if (download.failed) {
                DropdownMenuItem(
                    text = { Text("Clear") },
                    onClick = { rowMenu = false; onClear() },
                    leadingIcon = { Icon(Icons.Filled.Clear, contentDescription = null) },
                )
            }
        }
    }
}
