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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.text.KeyboardOptions
import app.roam.core.database.TrackListItem
import app.roam.data.catalog.TrackEdits

/**
 * Long-press menu for a track.
 *
 * Revert is only offered once something has actually been overridden -- on an
 * untouched track it would be a button that does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionSheet(
    track: TrackListItem,
    edited: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onArtworkPicked: (Uri) -> Unit,
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onArtworkPicked(uri) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
            )
            Text(
                "${track.artistName} · ${track.albumTitle}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )

            ListItem(
                modifier = Modifier.clickable(onClick = onEdit),
                headlineContent = { Text("Edit details") },
                leadingContent = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )

            ListItem(
                modifier = Modifier.clickable {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                headlineContent = { Text("Change artwork") },
                supportingContent = { Text("This track only, stored on the phone") },
                leadingContent = { Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null) },
            )

            if (edited) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onRevert),
                    headlineContent = { Text("Undo my edits") },
                    supportingContent = { Text("Read the tags from the file again") },
                    leadingContent = { Icon(Icons.Filled.Restore, contentDescription = null) },
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * The edit form.
 *
 * Nothing here touches the file on Drive -- the change lives in Roam's database
 * and is marked so neither sync nor the tag pass overwrites it. Numeric fields
 * parse leniently: a blank box means "no value", not zero.
 */
@Composable
fun TrackEditDialog(
    initial: TrackEdits,
    artworkId: String?,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrackEdits) -> Unit,
    onStep: (TrackEdits, Int) -> Unit,
    onCoverSave: () -> Unit,
    onCoverRemove: () -> Unit,
    onCoverPicked: (Uri) -> Unit,
    onCoverMessage: (String) -> Unit,
    pastCovers: List<LibraryViewModel.PastCover>,
    onLoadPastCovers: () -> Unit,
    onSavePastCover: (LibraryViewModel.PastCover) -> Unit,
    onRestorePastCover: (LibraryViewModel.PastCover) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var artist by remember { mutableStateOf(initial.artist) }
    var album by remember { mutableStateOf(initial.album) }
    var albumArtist by remember { mutableStateOf(initial.albumArtist.orEmpty()) }
    var trackNo by remember { mutableStateOf(initial.trackNo?.toString().orEmpty()) }
    var discNo by remember { mutableStateOf(initial.discNo?.toString().orEmpty()) }
    var year by remember { mutableStateOf(initial.year?.toString().orEmpty()) }
    var genre by remember { mutableStateOf(initial.genre.orEmpty()) }
    var compilation by remember { mutableStateOf(initial.compilation) }

    fun collect() = TrackEdits(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist.ifBlank { null },
        trackNo = trackNo.toIntOrNull(),
        discNo = discNo.toIntOrNull(),
        year = year.toIntOrNull(),
        genre = genre.ifBlank { null },
        compilation = compilation,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit track") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Field(title, "Title") { title = it }
                Field(artist, "Artist") { artist = it }
                Field(album, "Album") { album = it }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { compilation = !compilation },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = compilation, onCheckedChange = { compilation = it })
                    Column {
                        Text("Compilation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Groups by album artist, so each track keeps its own",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Field(albumArtist, "Album artist", help = "Blank means same as artist") {
                    albumArtist = it
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(trackNo, "Track", Modifier.weight(1f)) { trackNo = it }
                    NumberField(discNo, "Disc", Modifier.weight(1f)) { discNo = it }
                    NumberField(year, "Year", Modifier.weight(1.2f)) { year = it }
                }
                Field(genre, "Genre") { genre = it }

                AlbumArtBlock(
                    artworkId = artworkId,
                    past = pastCovers,
                    onLoadPast = onLoadPastCovers,
                    onSavePast = onSavePastCover,
                    onRestorePast = onRestorePastCover,
                    onSave = onCoverSave,
                    onRemove = onCoverRemove,
                    onPicked = onCoverPicked,
                    onPasteFailed = onCoverMessage,
                )

                // Stepping saves first, so working down an album never silently
                // drops the edit you just made.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = { onStep(collect(), -1) },
                        enabled = canGoPrevious,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Save and previous track")
                    }
                    IconButton(
                        onClick = { onStep(collect(), 1) },
                        enabled = canGoNext,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Save and next track")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(collect()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Field(
    value: String,
    label: String,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        // Filtered rather than validated: a non-digit simply cannot be typed,
        // which beats an error message appearing after the fact.
        onValueChange = { entered -> onChange(entered.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
