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

    // Whether the artist field has anything to decide. Read it as "is this an
    // album", because that is the only difference that changes the answers.
    val many = trackCount > 1

    fun collect() = AlbumPlacement(
        artist = artist,
        album = album,
        // The album artist is what names the folder, so it must never be
        // empty. Copied from the performer when it is, which is the right
        // answer for an ordinary album by one act -- and the "Saving to" line
        // above reflects it immediately, so the folder shown is the folder used
        // rather than something worked out later behind the dialog's back.
        albumArtist = albumArtist.ifBlank { artist },
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

                // Ticking OFFERS Various Artists rather than imposing it. Most
                // compilations are various-artists ones, so filling the field
                // in saves the typing -- but a greatest-hits is a compilation
                // with a real album artist, and the folder follows that field
                // now rather than the flag. Untick and the suggestion is taken
                // back, so the box stays something you can change your mind
                // about.
                fun setCompilation(on: Boolean) {
                    compilation = on
                    if (on && albumArtist.isBlank()) {
                        albumArtist = FolderNames.VARIOUS_ARTISTS
                    } else if (!on && albumArtist.trim() == FolderNames.VARIOUS_ARTISTS) {
                        albumArtist = artist.trim()
                    }
                }

                Row(
                    Modifier.fillMaxWidth().clickable { setCompilation(!compilation) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = compilation, onCheckedChange = { setCompilation(it) })
                    Column {
                        Text("Compilation", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Tags it as one, and suggests ${FolderNames.VARIOUS_ARTISTS} " +
                                "as the album artist",
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
                            when {
                                // Keyed on where the album actually files, not
                                // on the compilation flag -- a greatest-hits is
                                // a compilation that files under its own artist,
                                // and telling someone to leave the field blank
                                // there would be wrong.
                                //
                                // The advice also INVERTS on a single track, and
                                // getting that backwards is how a track lands
                                // credited to a YouTube channel. In a batch the
                                // field has nothing to decide, because every
                                // track already carries its own credit. Alone,
                                // it is the only place that credit can come from.
                                placement.filesUnderVariousArtists && many ->
                                    "Leave blank — each track keeps its own artist, and the " +
                                        "folder is ${FolderNames.VARIOUS_ARTISTS}"
                                placement.filesUnderVariousArtists ->
                                    "Who performed this one. The folder is still " +
                                        "${FolderNames.VARIOUS_ARTISTS} — blank keeps the " +
                                        "credit it arrived with, which is often a channel name"
                                else -> "The performer"
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
                        // This is the field that names the folder, so it says
                        // so. It used to be decided by the compilation box,
                        // which meant the one value that mattered was the one
                        // nothing explained.
                        supportingText = { Text("Names the folder") },
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
