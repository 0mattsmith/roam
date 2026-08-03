package app.roam.feature.library

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.roam.data.catalog.artwork.ArtworkProvider
import coil.compose.AsyncImage

/**
 * The album cover, inline in an edit dialog, with everything you can do to it.
 *
 * Duplicates what the long-press sheet offers, deliberately: while correcting
 * an album's details the cover is part of the same job, and closing the form to
 * go and find it elsewhere breaks the flow.
 */
@Composable
fun AlbumArtBlock(
    /** Keys the expanded/collapsed state, so it does not follow you to the next album. */
    targetKey: Any,
    artworkId: String?,
    past: List<LibraryViewModel.PastCover> = emptyList(),
    onLoadPast: () -> Unit = {},
    onSavePast: (LibraryViewModel.PastCover) -> Unit = {},
    onRestorePast: (LibraryViewModel.PastCover) -> Unit = {},
    onSave: () -> Unit,
    onRemove: () -> Unit,
    onPicked: (Uri) -> Unit,
    onPasteFailed: (String) -> Unit,
) {
    val ctx = LocalContext.current
    var showPast by remember(targetKey) { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPicked(uri) }

    // The cover, its actions and the history strip stack vertically.
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
        if (artworkId != null) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, artworkId, size = 320),
                contentDescription = "Album cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
        } else {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }

        Column(Modifier.padding(start = 8.dp)) {
            Text(
                "Cover",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose from device")
                }
                IconButton(
                    onClick = {
                        val pasted = clipboardImage(ctx)
                        if (pasted != null) onPicked(pasted)
                        else onPasteFailed("No image on the clipboard")
                    }
                ) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = "Paste image")
                }
                IconButton(onClick = onSave, enabled = artworkId != null) {
                    Icon(Icons.Filled.Download, contentDescription = "Save to Photos")
                }
                IconButton(onClick = onRemove, enabled = artworkId != null) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove cover")
                }
            }
        }
    }

    // Nothing loads until asked: these live on Drive, and fetching every past
    // cover to draw a strip nobody opened would be rude on mobile data.
    TextButton(
        onClick = {
            showPast = !showPast
            if (showPast) onLoadPast()
        }
    ) {
        Text(if (showPast) "Hide previous covers" else "Previous covers")
    }

    if (showPast) {
        if (past.isEmpty()) {
            Text(
                "No earlier covers for this album",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                past.forEach { cover -> PastCoverCell(cover, onSavePast, onRestorePast) }
            }
        }
    }
    }
}

/** One retired cover: the picture, its filename, and the two things to do with it. */
@Composable
private fun PastCoverCell(
    cover: LibraryViewModel.PastCover,
    onSave: (LibraryViewModel.PastCover) -> Unit,
    onRestore: (LibraryViewModel.PastCover) -> Unit,
) {
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Null while the download is still in flight; the cell appears straight
        // away and fills in rather than the whole strip waiting on the set.
        val id = cover.artworkId
        if (id != null) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, id, size = 320),
                contentDescription = cover.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        } else {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Text(
            cover.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            IconButton(onClick = { onSave(cover) }, enabled = cover.artworkId != null) {
                Icon(Icons.Filled.Download, contentDescription = "Save ${cover.name}")
            }
            IconButton(onClick = { onRestore(cover) }, enabled = cover.artworkId != null) {
                Icon(Icons.Filled.Restore, contentDescription = "Use ${cover.name} again")
            }
        }
    }
}

/**
 * An image on the clipboard, if there is one.
 *
 * Copying a picture puts a content:// URI on the clip rather than the bytes, so
 * this hands back the URI and lets the normal import path read it -- the same
 * route the photo picker uses, permissions and all.
 */
private fun clipboardImage(ctx: Context): Uri? {
    val clipboard = ctx.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null

    val uri = clip.getItemAt(0).uri ?: return null
    val type = ctx.contentResolver.getType(uri) ?: return null
    return uri.takeIf { type.startsWith("image/") }
}
