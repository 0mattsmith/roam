package app.roam.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.core.database.TrackListItem
import app.roam.data.catalog.AlbumBulkEdits

/** Long-press menu for an album header inside a grouped track list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumHeaderSheet(
    track: TrackListItem,
    onDismiss: () -> Unit,
    onBulkEdit: () -> Unit,
    onArtworkPicked: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onArtworkPicked(uri) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                track.albumTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
            Text(
                track.artistName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )

            ListItem(
                modifier = Modifier.clickable {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                headlineContent = { Text("Change artwork for the whole album") },
                supportingContent = { Text("Applies to every track, and saves cover.jpg to Drive") },
                leadingContent = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
            )

            ListItem(
                modifier = Modifier.clickable(onClick = onBulkEdit),
                headlineContent = { Text("Edit details for all tracks") },
                supportingContent = { Text("Pick which fields to change") },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * Bulk edit across an album.
 *
 * Each field is opt-in. An untouched box leaves that column exactly as it is on
 * every track, which matters because these tracks legitimately differ -- a
 * compilation has a different artist per track, and blanket-writing one value
 * would destroy that. Ticking a box and clearing the text clears the field.
 *
 * Title and track number are absent by design: they are what make the tracks
 * distinct.
 */
@Composable
fun AlbumBulkEditDialog(
    albumTitle: String,
    trackCount: Int,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSave: (AlbumBulkEdits) -> Unit,
) {
    var artistOn by remember { mutableStateOf(false) }
    var albumOn by remember { mutableStateOf(false) }
    var albumArtistOn by remember { mutableStateOf(false) }
    var yearOn by remember { mutableStateOf(false) }
    var genreOn by remember { mutableStateOf(false) }
    var discOn by remember { mutableStateOf(false) }

    var artist by remember { mutableStateOf(initialArtist) }
    var album by remember { mutableStateOf(albumTitle) }
    var albumArtist by remember { mutableStateOf(initialArtist) }
    var year by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var disc by remember { mutableStateOf("") }

    val anyChecked = artistOn || albumOn || albumArtistOn || yearOn || genreOn || discOn

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $trackCount tracks") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Tick a field to change it. Anything left unticked stays as it is.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CheckedField(artistOn, { artistOn = it }, artist, "Artist") { artist = it }
                CheckedField(albumOn, { albumOn = it }, album, "Album") { album = it }
                CheckedField(albumArtistOn, { albumArtistOn = it }, albumArtist, "Album artist") {
                    albumArtist = it
                }
                CheckedField(genreOn, { genreOn = it }, genre, "Genre") { genre = it }
                CheckedField(yearOn, { yearOn = it }, year, "Year", numeric = true) { year = it }
                CheckedField(discOn, { discOn = it }, disc, "Disc number", numeric = true) {
                    disc = it
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = anyChecked,
                onClick = {
                    onSave(
                        AlbumBulkEdits(
                            artist = artist.takeIf { artistOn },
                            album = album.takeIf { albumOn },
                            albumArtist = albumArtist.takeIf { albumArtistOn },
                            year = if (yearOn) year.toIntOrNull() else null,
                            genre = genre.takeIf { genreOn },
                            discNo = if (discOn) disc.toIntOrNull() else null,
                        )
                    )
                },
            ) { Text("Apply to all") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * A field that only becomes editable once its box is ticked. Disabled rather
 * than hidden, so the whole set of what *could* be changed stays visible.
 */
@Composable
private fun CheckedField(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    value: String,
    label: String,
    numeric: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { entered ->
                onValueChange(if (numeric) entered.filter { it.isDigit() }.take(4) else entered)
            },
            label = { Text(label) },
            enabled = checked,
            singleLine = true,
            keyboardOptions = if (numeric) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
