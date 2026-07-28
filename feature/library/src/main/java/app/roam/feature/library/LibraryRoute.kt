package app.roam.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import coil.compose.AsyncImage
import app.roam.data.catalog.artwork.ArtworkProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.roam.core.database.TrackListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloader: () -> Unit,
    vm: LibraryViewModel = hiltViewModel(),
) {
    val tracks = vm.pagedTracks.collectAsLazyPagingItems()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Roam") },
                actions = {
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
        if (tracks.itemCount == 0) {
            EmptyLibrary(onOpenSettings, Modifier.padding(padding))
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(
                    count = tracks.itemCount,
                    key = tracks.itemKey { it.id },
                ) { index ->
                    tracks[index]?.let { track ->
                        TrackRow(track, isCurrent = track.title == nowPlaying.title) {
                            vm.playFrom(track)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: TrackListItem, isCurrent: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
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
            val ctx = LocalContext.current
            if (track.artworkId != null) {
                AsyncImage(
                    // content:// rather than a bitmap. Same URI the car will
                    // use -- Android Auto cannot take bitmaps, and this keeps
                    // both surfaces on one path.
                    model = ArtworkProvider.uri(ctx, track.artworkId, size = 320),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
                )
            } else {
                Surface(
                    Modifier.size(44.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {}
            }
        },
    )
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
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 8.dp),
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
private fun EmptyLibrary(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No music yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Connect Google Drive and refresh your library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenSettings) { Text("Open settings") }
    }
}
