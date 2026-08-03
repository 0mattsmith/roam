package app.roam.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.core.database.AlbumListItem
import app.roam.core.model.ViewMode
import app.roam.data.catalog.artwork.ArtworkProvider
import coil.compose.AsyncImage

/** Everything the landing page draws that is not an album row. */
data class ArtistDetail(
    val id: Long,
    val name: String,
    val avatarArtworkId: String?,
    val bannerArtworkId: String?,
    val albumCount: Int,
    val trackCount: Int,
)

/**
 * An artist's landing page: banner, avatar, then the records.
 *
 * The albums are a plain list rather than a Pager. An artist has tens of
 * albums, not thousands, and paging a list that short costs more in machinery
 * than it saves.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistPage(
    detail: ArtistDetail,
    albums: List<AlbumListItem>,
    viewMode: ViewMode,
    listState: LazyListState,
    gridState: LazyGridState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenAlbum: (AlbumListItem) -> Unit,
    onAlbumLongPress: (AlbumListItem) -> Unit,
    onBannerPicked: (Uri) -> Unit,
    onBannerSave: () -> Unit,
    onBannerClear: () -> Unit,
) {
    // The header is identical either way, so it is built once and handed to
    // whichever list is drawing. Duplicating it into both branches is how the
    // two silently drift apart.
    val header: @Composable () -> Unit = {
        Column {
            ArtistBanner(detail, onBannerPicked, onBannerSave, onBannerClear)
            ArtistActions(onPlay, onShuffle)
        }
    }

    if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            // No horizontal contentPadding: the banner is a full-span row and
            // has to bleed to both edges. The gutters live on the cells
            // instead, which also survives the column count changing.
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }

            if (albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { NothingYet() }
            }

            items(albums.size, key = { albums[it].id }) { index ->
                val album = albums[index]
                AlbumCell(
                    album = album,
                    onClick = { onOpenAlbum(album) },
                    onLongClick = { onAlbumLongPress(album) },
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    } else {
        ArtistPageList(
            albums = albums,
            listState = listState,
            header = header,
            onOpenAlbum = onOpenAlbum,
            onAlbumLongPress = onAlbumLongPress,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistPageList(
    albums: List<AlbumListItem>,
    listState: LazyListState,
    header: @Composable () -> Unit,
    onOpenAlbum: (AlbumListItem) -> Unit,
    onAlbumLongPress: (AlbumListItem) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item { header() }

        if (albums.isEmpty()) {
            item { NothingYet() }
        }

        items(albums.size, key = { albums[it].id }) { index ->
            val album = albums[index]
            ArtistAlbumRow(
                album = album,
                onClick = { onOpenAlbum(album) },
                onLongClick = { onAlbumLongPress(album) },
            )
        }
    }
}

@Composable
private fun NothingYet() {
    Text(
        "Nothing by this artist yet",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

/** Banner, avatar, name and counts -- and the pencil that replaces the banner. */
@Composable
private fun ArtistBanner(
    detail: ArtistDetail,
    onPicked: (Uri) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val ctx = LocalContext.current
    var menuOpen by remember(detail.id) { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPicked(uri) }

    Box(Modifier.fillMaxWidth()) {
        // Captured locally: Kotlin will not smart-cast a property
        // declared in another module.
        val banner = detail.bannerArtworkId
        val avatar = detail.avatarArtworkId

        if (banner != null) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, banner),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
            )
        } else {
            // No banner is common -- TheAudioDB has them for well-known
            // acts and little else. A tinted block keeps the layout
            // stable rather than the avatar jumping up the screen.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }

        // Scrim under the avatar and name, so light banners do not
        // leave white text on white.
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                    )
                )
        )

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (avatar != null) {
                AsyncImage(
                    model = ArtworkProvider.uri(ctx, avatar),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape),
                )
            } else {
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column {
                Text(
                    detail.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${detail.albumCount} albums · ${detail.trackCount} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }

        // Top right, over the banner. A filled circle rather than a bare icon
        // because it sits on an unpredictable photo -- a plain pencil vanishes
        // on anything pale.
        FilledTonalIconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Change banner")
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Choose from device") },
                onClick = {
                    menuOpen = false
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                leadingIcon = { Icon(Icons.Filled.PhotoLibrary, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Save to Photos") },
                enabled = detail.bannerArtworkId != null,
                onClick = { menuOpen = false; onSave() },
                leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("Remove banner") },
                enabled = detail.bannerArtworkId != null,
                onClick = { menuOpen = false; onClear() },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun ArtistActions(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Play")
        }
        OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Shuffle")
        }
    }
}

/** One album as a large square cover with its title beneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCell(
    album: AlbumListItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val artworkId = album.artworkId
        if (artworkId != null) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, artworkId, size = 640),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            albumTitleWithYear(album.title, album.year),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${album.trackCount} tracks",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistAlbumRow(
    album: AlbumListItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val ctx = LocalContext.current
    Row(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artworkId = album.artworkId
        if (artworkId != null) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, artworkId, size = 320),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                albumTitleWithYear(album.title, album.year),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${album.trackCount} tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
