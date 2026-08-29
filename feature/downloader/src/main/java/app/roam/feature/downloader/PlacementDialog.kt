package app.roam.feature.downloader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.roam.core.model.FolderNames

/**
 * Where this is about to go, before it goes there.
 *
 * A confirmation rather than a form. Discogs and MusicBrainz already answered
 * all five questions for anything queued from an album page, so the usual
 * interaction is reading one line and pressing Download -- and the fields are
 * there for the times the catalogue is wrong or a single track came from a
 * search with nothing behind it.
 *
 * It exists because the worker cannot ask later. By the time a download runs
 * all it has is a search result, and a YouTube channel name is not an album
 * artist -- which is how a hundred-track compilation used to become a hundred
 * artist folders.
 */
@Composable
fun PlacementDialog(
    initial: AlbumPlacement,
    /** How many tracks this covers, so the button can say what it will do. */
    trackCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (AlbumPlacement) -> Unit,
) {
    var artist by rememberSaveable(initial) { mutableStateOf(initial.artist) }
    var album by rememberSaveable(initial) { mutableStateOf(initial.album) }
    var albumArtist by rememberSaveable(initial) { mutableStateOf(initial.albumArtist) }
    var year by rememberSaveable(initial) { mutableStateOf(initial.year?.toString().orEmpty()) }
    var compilation by rememberSaveable(initial) { mutableStateOf(initial.compilation) }
    var expanded by rememberSaveable { mutableStateOf(false) }

    fun collect() = AlbumPlacement(
        artist = artist,
        album = album,
        albumArtist = albumArtist,
        year = year.toIntOrNull(),
        compilation = compilation,
    )

    val placement = collect()

    AlertDialog(
        // A tap outside must not throw away corrections to something that is
        // about to write files onto somebody's Drive.
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(if (trackCount > 1) "Add $trackCount tracks" else "Add to library") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The answer, spelled out. Everything below is here to correct
                // this line, and most of the time it will already be right.
                Text(
                    "Saving to " + FolderNames.sanitise(placement.filingArtist) + " / " +
                        FolderNames.albumFolder(placement.albumOrSingles, placement.year),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    Modifier.fillMaxWidth().clickable { compilation = !compilation },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = compilation, onCheckedChange = { compilation = it })
                    Column {
                        Text("Compilation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Files under ${FolderNames.VARIOUS_ARTISTS} instead of one " +
                                "folder per artist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    supportingText = {
                        Text(
                            if (compilation) {
                                // Not disabled, because a disabled field looks
                                // broken. Saying why is better than greying out.
                                "Leave blank — each track keeps its own artist, and the " +
                                    "folder is ${FolderNames.VARIOUS_ARTISTS}"
                            } else {
                                "The performer"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    supportingText = { Text("Blank files it under ${AlbumPlacement.SINGLES}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = albumArtist,
                        onValueChange = { albumArtist = it },
                        label = { Text("Album artist") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = { entered ->
                            year = entered.filter { it.isDigit() }.take(4)
                        },
                        label = { Text("Year") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    Modifier.fillMaxWidth().clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Add more information",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                    )
                }

                if (expanded) {
                    Text(
                        // Deliberately a pointer rather than a second form. The
                        // metadata editor already does this properly, with the
                        // track in front of you and the tags read -- and a
                        // download is a bad moment to be typing composer credits.
                        "Everything else — genres, composer, sort orders, the two years — " +
                            "is on the track's own editor once it has landed, where the " +
                            "file's own tags have been read and there is something to " +
                            "correct against.\n\n" +
                            "These five are here only because the downloader has to know " +
                            "which folder to write into, and it cannot ask afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = placement.isComplete,
                onClick = { onConfirm(placement) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
