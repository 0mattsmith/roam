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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import app.roam.core.database.TrackListItem
import app.roam.core.model.TagState
import app.roam.data.catalog.MetadataSource
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
    onGoToAlbum: () -> Unit,
    onGoToArtist: () -> Unit,
    onEdit: () -> Unit,
    onRevert: () -> Unit,
    onRemove: () -> Unit,
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
                modifier = Modifier.clickable(onClick = onGoToAlbum),
                headlineContent = { Text("Go to album") },
                supportingContent = { Text(track.albumTitle) },
                leadingContent = { Icon(Icons.Filled.Album, contentDescription = null) },
            )

            ListItem(
                modifier = Modifier.clickable(onClick = onGoToArtist),
                headlineContent = { Text("Go to artist") },
                supportingContent = { Text(track.artistName) },
                leadingContent = { Icon(Icons.Filled.Person, contentDescription = null) },
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

            ListItem(
                modifier = Modifier.clickable(onClick = onRemove),
                headlineContent = { Text("Remove from library") },
                // Says what it does NOT do, because that is the part people
                // hesitate over. The file is untouched; Settings puts it back.
                supportingContent = { Text("Hides it. The file stays on Drive") },
                leadingContent = {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                },
            )

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
    /**
     * Identifies what is being edited. Every remember below is keyed on it --
     * without that, stepping to the next track leaves the form showing the
     * previous one's values, because remember survives recomposition and the
     * new `initial` is simply ignored.
     */
    trackId: Long,
    initial: TrackEdits,
    /** Whose values these are. Shown, because it decides what an edit will do. */
    source: MetadataSource,
    /** Whether the file itself has been read yet. */
    tagState: TagState,
    artworkId: String?,
    /** How many files ticking "Create album.json" would write. */
    albumTrackCount: Int,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onDismiss: () -> Unit,
    /** Second argument: also write this album's metadata files to the source. */
    onSave: (TrackEdits, Boolean) -> Unit,
    /** Looks the words up now, returning them, or null when nothing was found. */
    onFetchLyrics: suspend () -> String?,
    /**
     * Null edits mean nothing was typed, so the neighbour opens without a write.
     * Stepping used to save regardless, which marked every track someone had
     * merely LOOKED at as hand-edited -- and a hand-edited track is out of the
     * tag pass and out of album.json's reach for good.
     */
    onStep: (TrackEdits?, Int) -> Unit,
    onCoverSave: () -> Unit,
    onCoverRemove: () -> Unit,
    onCoverPicked: (Uri) -> Unit,
    onCoverMessage: (String) -> Unit,
    pastCovers: List<LibraryViewModel.PastCover>,
    onLoadPastCovers: () -> Unit,
    onSavePastCover: (LibraryViewModel.PastCover) -> Unit,
    onRestorePastCover: (LibraryViewModel.PastCover) -> Unit,
) {
    // rememberSaveable, not remember. Rotating the phone RECREATES the activity,
    // and plain remember is scoped to the composition -- so every field emptied
    // back to the stored values and whatever had been typed was gone. The
    // ViewModel keeps the dialog OPEN across the rotation, which made the loss
    // worse: the form came back looking untouched rather than closing, so there
    // was nothing to suggest anything had been lost.
    var title by rememberSaveable(trackId) { mutableStateOf(initial.title) }
    var artist by rememberSaveable(trackId) { mutableStateOf(initial.artist) }
    var album by rememberSaveable(trackId) { mutableStateOf(initial.album) }
    var albumArtist by rememberSaveable(trackId) { mutableStateOf(initial.albumArtist.orEmpty()) }
    var trackNo by rememberSaveable(trackId) { mutableStateOf(initial.trackNo?.toString().orEmpty()) }
    var discNo by rememberSaveable(trackId) { mutableStateOf(initial.discNo?.toString().orEmpty()) }
    var year by rememberSaveable(trackId) { mutableStateOf(initial.year?.toString().orEmpty()) }
    var genre by rememberSaveable(trackId) { mutableStateOf(initial.genre.orEmpty()) }
    var compilation by rememberSaveable(trackId) { mutableStateOf(initial.compilation) }
    var sortArtist by rememberSaveable(trackId) { mutableStateOf(initial.sortArtist.orEmpty()) }
    var groupArtist by rememberSaveable(trackId) { mutableStateOf(initial.groupArtist.orEmpty()) }
    var startAt by rememberSaveable(trackId) { mutableStateOf(formatClip(initial.startMs)) }
    var endAt by rememberSaveable(trackId) { mutableStateOf(formatClip(initial.endMs)) }
    var lyrics by rememberSaveable(trackId) { mutableStateOf(initial.lyrics.orEmpty()) }
    var fetchingLyrics by remember(trackId) { mutableStateOf(false) }
    var lyricNote by rememberSaveable(trackId) { mutableStateOf<String?>(null) }
    val lyricScope = rememberCoroutineScope()

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
        sortArtist = sortArtist.ifBlank { null },
        groupArtist = groupArtist.ifBlank { null },
        startMs = parseClip(startAt),
        endMs = parseClip(endAt),
        lyrics = lyrics,
    )

    // Nothing typed means nothing to save, and the button says so rather than
    // offering a write with a permanent consequence: applyUserEdit takes a
    // track out of the tag pass and out of album.json's reach for good, which
    // is a strange thing to get from pressing Save to close a dialog you only
    // opened to look at. TrackEditor guards the other half -- a change to only
    // the lyrics or the trim points does not freeze the tags either.
    val dirty = collect().comparable() != initial.comparable()

    // Absent once the values already come from a document, so its presence is
    // itself the answer to "is this album described yet".
    val canCreateDocs = source != MetadataSource.DOCUMENT
    var createDocs by rememberSaveable(trackId) { mutableStateOf(false) }
    var showDocHelp by rememberSaveable(trackId) { mutableStateOf(false) }

    if (showDocHelp) DocHelpDialog { showDocHelp = false }

    AlertDialog(
        // A tap outside must NOT discard a form full of typing. Back still
        // works and still means cancel -- that is a deliberate gesture, and
        // trapping someone in a dialog is worse than the thing being fixed.
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text("Edit track") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SourceNote(source, tagState)

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
                Field(
                    sortArtist,
                    "Sorting artist",
                    help = "File under another name, e.g. Makaveli under 2Pac",
                ) { sortArtist = it }
                Field(
                    groupArtist,
                    "Group artist",
                    help = "Show this artist's albums under another, e.g. Makaveli inside 2Pac",
                ) { groupArtist = it }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(trackNo, "Track", Modifier.weight(1f)) { trackNo = it }
                    NumberField(discNo, "Disc", Modifier.weight(1f)) { discNo = it }
                    NumberField(year, "Year", Modifier.weight(1.2f)) { year = it }
                }
                Field(genre, "Genre") { genre = it }

                // Playback window, not a trim. Nothing is cut from the file --
                // the player is simply told where the song really begins and
                // ends, so clearing these puts everything back.
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ClipField(startAt, "Start at", Modifier.weight(1f)) { startAt = it }
                    ClipField(endAt, "End at", Modifier.weight(1f)) { endAt = it }
                }
                Text(
                    "m:ss, for skipping silence or an intro. Leave blank for the whole track.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Multi-line and roomy: this field gets PASTED into far more
                // often than typed, and a one-line box for a whole song is a
                // reliable way to make someone think it did not take.
                OutlinedTextField(
                    value = lyrics,
                    onValueChange = { lyrics = it },
                    label = { Text("Lyrics") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !fetchingLyrics,
                        onClick = {
                            fetchingLyrics = true
                            lyricScope.launch {
                                val found = onFetchLyrics()
                                // Only replaces the box when something came
                                // back. Blanking what someone typed because a
                                // lookup missed would be its own bug.
                                if (found != null) lyrics = found
                                lyricNote = if (found != null) "Found" else "Nothing found"
                                fetchingLyrics = false
                            }
                        },
                    ) { Text(if (fetchingLyrics) "Looking..." else "Fetch lyrics") }

                    lyricNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    "Found automatically while a track plays, and saved beside the " +
                        "track on Drive. Typing here replaces them, and nothing " +
                        "will overwrite what you wrote.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (canCreateDocs) {
                    CreateDocsRow(
                        checked = createDocs,
                        albumTrackCount = albumTrackCount,
                        tagState = tagState,
                        source = source,
                        onChange = { createDocs = it },
                        onHelp = { showDocHelp = true },
                    )
                }

                AlbumArtBlock(
                    targetKey = trackId,
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
                // drops the edit you just made -- but only when there IS one.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = { onStep(collect().takeIf { dirty }, -1) },
                        enabled = canGoPrevious,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous track")
                    }
                    IconButton(
                        onClick = { onStep(collect().takeIf { dirty }, 1) },
                        enabled = canGoNext,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next track")
                    }
                }
            }
        },
        confirmButton = {
            // Ticking the box is itself a change worth saving, even when no
            // field moved -- creating the files IS the action in that case.
            TextButton(
                enabled = dirty || createDocs,
                onClick = { onSave(collect(), createDocs) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Where these values came from.
 *
 * A statement about the VALUES ON SCREEN and nothing else. An earlier version
 * also announced whether the file's tags had been read, which read as "these
 * are a guess" over a title somebody had typed themselves -- the tags being
 * unread says nothing about a value that did not come from them. Where it does
 * matter is when the tags have never been read AND nothing better exists, which
 * is the PATH case, and that is said there.
 */
@Composable
private fun SourceNote(source: MetadataSource, tagState: TagState) {
    val (headline, detail) = when (source) {
        MetadataSource.USER ->
            "Your edits" to "Nothing overwrites these. Undo them from the long-press menu."
        MetadataSource.DOCUMENT ->
            "From album.json" to "Stored beside the music, so it survives a reinstall."
        MetadataSource.TAGS ->
            "From the file's tags" to "Read out of the file itself."
        MetadataSource.PATH -> "From the file and folder names" to when (tagState) {
            TagState.FAILED -> "Roam could not read this file's own tags."
            else -> "Roam has not read this file's own tags yet."
        }
    }

    Column {
        Text(
            headline,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The offer to make these corrections durable.
 *
 * It says how many files it will write, because `album.json` describes the
 * whole album -- ticking this in one track's editor and then finding
 * forty-four new files on your Drive would be a surprise, and a surprise on
 * someone's own storage is the kind that stops them trusting the app.
 */
@Composable
private fun CreateDocsRow(
    checked: Boolean,
    albumTrackCount: Int,
    tagState: TagState,
    source: MetadataSource,
    onChange: (Boolean) -> Unit,
    onHelp: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Column(Modifier.weight(1f)) {
                Text("Create album.json", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (albumTrackCount > 1) {
                        "Writes the index for this album and a file for each of " +
                            "its $albumTrackCount tracks"
                    } else {
                        "Writes the index for this album and a file for its track"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onHelp) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "Why create album.json?")
            }
        }

        // Informative, not a refusal. What must not be written down is a
        // filename GUESS -- recording one makes json outrank the tags, so the
        // real ones are never consulted again and nothing looks wrong
        // afterwards. Values somebody typed are the opposite of a guess, and
        // refusing THOSE left the tracks people had just carefully fixed as the
        // only ones that could not be made durable.
        if (tagState != TagState.OK && source != MetadataSource.USER) {
            Text(
                "Roam has not read this file's own tags, so what is above came " +
                    "from its name. Check it before writing, or fetch them from " +
                    "Settings → Read tags.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
    }
}

/** Verbatim from docs/ALBUM_JSON.md. The last line is the part that matters. */
@Composable
private fun DocHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why create album.json?") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Roam keeps your corrections in its own database, which is " +
                        "deleted if you ever reinstall the app. An album.json file " +
                        "sits beside the music on your Drive, so your edits survive " +
                        "a reinstall and any other player or device can read them."
                )
                Text(
                    "It is also faster. Roam reads one small file per album instead " +
                        "of opening every track to check its tags."
                )
                Text("Nothing is written into your audio files, and nothing is deleted.")
                Text(
                    "Once the file exists, Roam trusts it over the tags — and you " +
                        "edit it here, the same as now."
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

/**
 * The form's values, flattened so two of them can be compared for equality.
 *
 * Blank and absent mean the same thing to every field here, but they are
 * different values -- so a form nobody touched would compare unequal to what it
 * opened with and the Save button would light up for no reason. The clip times
 * are round-tripped through the same formatting the boxes use, because a value
 * that is not a whole number of seconds comes back slightly different and would
 * do exactly the same thing.
 */
private fun TrackEdits.comparable(): TrackEdits = copy(
    title = title.trim(),
    artist = artist.trim(),
    album = album.trim(),
    albumArtist = albumArtist?.trim()?.ifBlank { null },
    genre = genre?.trim()?.ifBlank { null },
    sortArtist = sortArtist?.trim()?.ifBlank { null },
    groupArtist = groupArtist?.trim()?.ifBlank { null },
    startMs = parseClip(formatClip(startMs)),
    endMs = parseClip(formatClip(endMs)),
    lyrics = lyrics?.trim()?.ifBlank { null },
)

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

/**
 * A time in m:ss.
 *
 * Digits and one colon only, so "1:23" can be typed but "1:2:3" cannot. Not
 * validated on submit -- a field that accepts nonsense and complains afterwards
 * is worse than one that never accepts it.
 */
@Composable
private fun ClipField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { entered ->
            val cleaned = entered.filter { it.isDigit() || it == ':' }
            if (cleaned.count { it == ':' } <= 1) onChange(cleaned.take(7))
        },
        label = { Text(label) },
        placeholder = { Text("0:00") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** "1:23" and a bare "83" both mean 83 seconds. Blank means unset. */
internal fun parseClip(raw: String): Long? {
    val text = raw.trim().ifBlank { return null }
    val parts = text.split(':')
    val seconds = when (parts.size) {
        1 -> parts[0].toLongOrNull()
        2 -> {
            val m = parts[0].toLongOrNull() ?: return null
            val s = parts[1].toLongOrNull() ?: return null
            m * 60 + s
        }
        else -> null
    } ?: return null
    return (seconds * 1000).takeIf { it >= 0 }
}

internal fun formatClip(ms: Long?): String {
    if (ms == null) return ""
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
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
