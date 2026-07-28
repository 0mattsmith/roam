package app.roam.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.roam.data.catalog.artwork.ArtworkProvider
import coil.compose.AsyncImage

/**
 * What the artwork sheet needs to know, so artists and albums share one
 * implementation rather than two that drift apart.
 */
data class ArtworkTarget(
    val title: String,
    val subtitle: String?,
    val artworkId: String?,
    /** "photo" or "cover" -- the sheet reads as English either way. */
    val noun: String,
)

/**
 * Long-press menu for an artist or an album.
 *
 * View and save are hidden rather than disabled when there is no image: a dead
 * row invites tapping it to find out why. Adding one is always offered, since
 * that is exactly what an entry with no artwork needs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkSheet(
    target: ArtworkTarget,
    onDismiss: () -> Unit,
    onView: () -> Unit,
    onSave: () -> Unit,
    onPicked: (Uri) -> Unit,
) {
    // The system photo picker. No storage permission, and it only ever hands
    // back the single item the user chose.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onPicked(uri) }

    val artworkId = target.artworkId

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                target.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
            target.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
            }

            if (artworkId != null) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onView),
                    headlineContent = { Text("View ${target.noun}") },
                    leadingContent = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                )
                ListItem(
                    modifier = Modifier.clickable(onClick = onSave),
                    headlineContent = { Text("Save to Photos") },
                    supportingContent = { Text("Copies it to Pictures/Roam") },
                    leadingContent = { Icon(Icons.Filled.Download, contentDescription = null) },
                )
            }

            ListItem(
                modifier = Modifier.clickable {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                headlineContent = {
                    Text(if (artworkId == null) "Add a ${target.noun}" else "Replace ${target.noun}")
                },
                supportingContent = { Text("Also saved to Drive, so it sticks") },
                leadingContent = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Full-screen look at the stored image.
 *
 * Reads the full-size file rather than the 320px thumb the lists use -- this is
 * the one place the master actually gets shown.
 */
@Composable
fun ArtworkViewer(target: ArtworkTarget, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val artworkId = target.artworkId ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = ArtworkProvider.uri(ctx, artworkId),
                contentDescription = target.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .padding(16.dp)
                    .clip(MaterialTheme.shapes.large),
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
