package app.roam.feature.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import app.roam.core.database.TrackListItem
import app.roam.core.model.TagFormat
import app.roam.core.model.TagMap
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
    /** The file's own type, so a tag label names the right frame or atom. */
    mimeType: String?,
    /** Every genre already used in this library, for the chip suggestions. */
    knownGenres: List<String>,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onDismiss: () -> Unit,
    onSave: (TrackEdits) -> Unit,
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
    var trackTotal by rememberSaveable(trackId) { mutableStateOf(initial.trackTotal?.toString().orEmpty()) }
    var discTotal by rememberSaveable(trackId) { mutableStateOf(initial.discTotal?.toString().orEmpty()) }
    var year by rememberSaveable(trackId) { mutableStateOf(initial.year?.toString().orEmpty()) }
    var originalYear by rememberSaveable(trackId) { mutableStateOf(initial.originalYear?.toString().orEmpty()) }
    // The list itself, not a joined string: the chips ARE the model, and
    // round-tripping through text would lose a genre containing a comma.
    var genres by rememberSaveable(trackId) { mutableStateOf(initial.genres) }
    var compilation by rememberSaveable(trackId) { mutableStateOf(initial.compilation) }
    var composer by rememberSaveable(trackId) { mutableStateOf(initial.composer.orEmpty()) }
    var grouping by rememberSaveable(trackId) { mutableStateOf(initial.grouping.orEmpty()) }
    var titleSort by rememberSaveable(trackId) { mutableStateOf(initial.titleSort.orEmpty()) }
    var sortArtist by rememberSaveable(trackId) { mutableStateOf(initial.sortArtist.orEmpty()) }
    var albumSort by rememberSaveable(trackId) { mutableStateOf(initial.albumSort.orEmpty()) }
    var albumArtistSort by rememberSaveable(trackId) { mutableStateOf(initial.albumArtistSort.orEmpty()) }
    var composerSort by rememberSaveable(trackId) { mutableStateOf(initial.composerSort.orEmpty()) }
    var groupArtist by rememberSaveable(trackId) { mutableStateOf(initial.groupArtist.orEmpty()) }
    // NOT keyed on trackId: someone who turned tag names on wants them on for
    // the next track too, and the whole point of stepping is not re-setting up.
    var showTagNames by rememberSaveable { mutableStateOf(false) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var startAt by rememberSaveable(trackId) { mutableStateOf(formatClip(initial.startMs)) }
    var endAt by rememberSaveable(trackId) { mutableStateOf(formatClip(initial.endMs)) }
    var lyrics by rememberSaveable(trackId) { mutableStateOf(initial.lyrics.orEmpty()) }
    var fetchingLyrics by remember(trackId) { mutableStateOf(false) }
    var lyricNote by rememberSaveable(trackId) { mutableStateOf<String?>(null) }
    val lyricScope = rememberCoroutineScope()

    // Which dialect this file speaks, so a label names TIT2 over an MP3 and
    // TITLE over a FLAC. Null for anything unrecognised -- naming the wrong
    // frame would be worse than naming none.
    val tagFormat = remember(mimeType) { TagMap.formatOf(mimeType) }.takeIf { showTagNames }

    fun collect() = TrackEdits(
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist.ifBlank { null },
        trackNo = trackNo.toIntOrNull(),
        discNo = discNo.toIntOrNull(),
        year = year.toIntOrNull(),
        originalYear = originalYear.toIntOrNull(),
        genres = genres,
        composer = composer.ifBlank { null },
        grouping = grouping.ifBlank { null },
        trackTotal = trackTotal.toIntOrNull(),
        discTotal = discTotal.toIntOrNull(),
        compilation = compilation,
        sortArtist = sortArtist.ifBlank { null },
        titleSort = titleSort.ifBlank { null },
        albumSort = albumSort.ifBlank { null },
        albumArtistSort = albumArtistSort.ifBlank { null },
        composerSort = composerSort.ifBlank { null },
        groupArtist = groupArtist.ifBlank { null },
        startMs = parseClip(startAt),
        endMs = parseClip(endAt),
        lyrics = lyrics,
    )

    // Nothing typed means nothing to save, and the button says so rather than
    // offering a write with a permanent consequence: applyUserEdit takes a
    // track out of the tag pass for good, which is a strange thing to get from
    // pressing Save to close a dialog you only opened to look at. TrackEditor
    // guards the other half -- a change to only the lyrics or the trim points
    // does not freeze the tags either.
    val dirty = collect().comparable() != initial.comparable()

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
                Field(albumArtist, "Album artist", "album_artist", tagFormat,
                    help = "Blank means same as artist") { albumArtist = it }

                // "3 of 12" is how TRCK is actually written, so the pair sits
                // together rather than as two unrelated boxes.
                PairRow("Track", trackNo, trackTotal, "track_number", tagFormat,
                    onFirst = { trackNo = it }, onSecond = { trackTotal = it })
                PairRow("Disc", discNo, discTotal, "disc_number", tagFormat,
                    onFirst = { discNo = it }, onSecond = { discTotal = it })

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField(year, "Year", Modifier.weight(1f), "year", tagFormat) { year = it }
                    NumberField(
                        originalYear, "Original year", Modifier.weight(1f),
                        "original_year", tagFormat,
                    ) { originalYear = it }
                }
                Text(
                    // The two-year split is the thing people get wrong, and a
                    // decade rule reads the original -- so a 2014 reissue of a
                    // 1994 record still counts as a nineties one.
                    "Original year is when the music first came out. Leave it blank " +
                        "unless this is a reissue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                GenreChips(
                    genres = genres,
                    known = knownGenres,
                    tagFormat = tagFormat,
                    onChange = { genres = it },
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

                AdvancedOptions(
                    open = advancedOpen,
                    onToggle = { advancedOpen = !advancedOpen },
                    showTagNames = showTagNames,
                    onShowTagNames = { showTagNames = it },
                    tagFormat = tagFormat,
                    composer = composer, onComposer = { composer = it },
                    grouping = grouping, onGrouping = { grouping = it },
                    titleSort = titleSort, onTitleSort = { titleSort = it },
                    sortArtist = sortArtist, onSortArtist = { sortArtist = it },
                    albumSort = albumSort, onAlbumSort = { albumSort = it },
                    albumArtistSort = albumArtistSort, onAlbumArtistSort = { albumArtistSort = it },
                    composerSort = composerSort, onComposerSort = { composerSort = it },
                    groupArtist = groupArtist, onGroupArtist = { groupArtist = it },
                    startAt = startAt, onStartAt = { startAt = it },
                    endAt = endAt, onEndAt = { endAt = it },
                )

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
            TextButton(enabled = dirty, onClick = { onSave(collect()) }) { Text("Save") }
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
 * Genres as chips, because that is what they are.
 *
 * The array is the model and the text box only ever holds what is being typed
 * right now. Storing them as one string and splitting on save would lose a
 * genre containing a comma, and would make "the field is a list" something the
 * person has to remember rather than see.
 *
 * Backspace on an empty box removes the last chip, which is the behaviour every
 * address field has trained people to expect -- it makes a chip feel like one
 * character rather than an object needing a separate gesture.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun GenreChips(
    genres: List<String>,
    known: List<String>,
    tagFormat: TagFormat?,
    onChange: (List<String>) -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }

    fun commit(value: String) {
        val genre = value.trim()
        // Case-insensitive, so a library does not end up holding Britpop and
        // britpop as two different things.
        if (genre.isNotEmpty() && genres.none { it.equals(genre, ignoreCase = true) }) {
            onChange(genres + genre)
        }
        typed = ""
    }

    // What the library already uses, narrowed by what is being typed. Its own
    // spellings first is the point: it is what stops one library holding
    // "Britpop", "britpop" and "Brit-Pop".
    val suggestions = remember(typed, known, genres) {
        val query = typed.trim()
        known.asSequence()
            .filter { candidate -> genres.none { it.equals(candidate, ignoreCase = true) } }
            .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
            .take(6)
            .toList()
    }

    Column {
        Text("Genres", style = MaterialTheme.typography.labelMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            genres.forEach { genre ->
                InputChip(
                    selected = false,
                    onClick = { onChange(genres - genre) },
                    label = { Text(genre) },
                    trailingIcon = {
                        Icon(Icons.Filled.Close, contentDescription = "Remove $genre")
                    },
                )
            }
        }

        OutlinedTextField(
            value = typed,
            onValueChange = { entered ->
                // A separator finishes a chip, so pasting "Rock, Pop" works and
                // so does typing a comma out of habit.
                if (entered.any { it in ";,/" }) {
                    entered.split(';', ',', '/').forEach { commit(it) }
                } else {
                    typed = entered
                }
            },
            placeholder = { Text("Add a genre") },
            singleLine = true,
            supportingText = tagName("genres", tagFormat)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit(typed) }),
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    val backspaceOnEmpty = event.type == KeyEventType.KeyDown &&
                        event.key == Key.Backspace && typed.isEmpty() && genres.isNotEmpty()
                    if (backspaceOnEmpty) {
                        onChange(genres.dropLast(1))
                        true
                    } else {
                        false
                    }
                },
        )

        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { commit(suggestion) },
                        label = { Text(suggestion) },
                    )
                }
            }
        }
    }
}

/**
 * The fields most people never touch, one tap away.
 *
 * Sorting orders, the composer credits, the Roam-only playback window and the
 * artist grouping. Everything here is real and editable -- it is simply not
 * what somebody opening the form to fix a title came for, and a form is easier
 * to read when its first screen is the common case.
 */
@Composable
private fun AdvancedOptions(
    open: Boolean,
    onToggle: () -> Unit,
    showTagNames: Boolean,
    onShowTagNames: (Boolean) -> Unit,
    tagFormat: TagFormat?,
    composer: String, onComposer: (String) -> Unit,
    grouping: String, onGrouping: (String) -> Unit,
    titleSort: String, onTitleSort: (String) -> Unit,
    sortArtist: String, onSortArtist: (String) -> Unit,
    albumSort: String, onAlbumSort: (String) -> Unit,
    albumArtistSort: String, onAlbumArtistSort: (String) -> Unit,
    composerSort: String, onComposerSort: (String) -> Unit,
    groupArtist: String, onGroupArtist: (String) -> Unit,
    startAt: String, onStartAt: (String) -> Unit,
    endAt: String, onEndAt: (String) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Advanced options",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Hide advanced options" else "Show advanced options",
            )
        }

        if (!open) return@Column

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Show tag names", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Labels every field with its name in this file's format",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = showTagNames, onCheckedChange = onShowTagNames)
            }

            Field(composer, "Composer", "composer", tagFormat) { onComposer(it) }
            Field(
                grouping, "Grouping", "grouping", tagFormat,
                help = "The work this belongs to, e.g. a symphony",
            ) { onGrouping(it) }

            Text("Sorting", style = MaterialTheme.typography.labelLarge)
            Field(titleSort, "Title sort", "title_sort", tagFormat) { onTitleSort(it) }
            Field(
                sortArtist, "Artist sort", "artist_sort", tagFormat,
                help = "File under another name, e.g. Bowie, David",
            ) { onSortArtist(it) }
            Field(albumSort, "Album sort", "album_sort", tagFormat) { onAlbumSort(it) }
            Field(
                albumArtistSort, "Album artist sort", "album_artist_sort", tagFormat,
            ) { onAlbumArtistSort(it) }
            Field(composerSort, "Composer sort", "composer_sort", tagFormat) { onComposerSort(it) }

            Text("Roam only", style = MaterialTheme.typography.labelLarge)
            Text(
                // Worth saying plainly: these do not travel. Somebody relying on
                // a trim point should know no other player will honour it.
                "No tag format has a name for these, so they live in Roam and in " +
                    "the metadata files, and no other player will see them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                groupArtist, "Group artist", "group_artist", tagFormat,
                help = "Show this artist's albums under another, e.g. Makaveli inside 2Pac",
            ) { onGroupArtist(it) }

            // Playback window, not a trim. Nothing is cut from the file -- the
            // player is simply told where the song really begins and ends, so
            // clearing these puts everything back.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ClipField(startAt, "Start at", Modifier.weight(1f)) { onStartAt(it) }
                ClipField(endAt, "End at", Modifier.weight(1f)) { onEndAt(it) }
            }
            Text(
                "m:ss, for skipping silence or an intro. Leave blank for the whole track.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
    genres = genres.map { it.trim() }.filter { it.isNotEmpty() },
    composer = composer?.trim()?.ifBlank { null },
    grouping = grouping?.trim()?.ifBlank { null },
    sortArtist = sortArtist?.trim()?.ifBlank { null },
    titleSort = titleSort?.trim()?.ifBlank { null },
    albumSort = albumSort?.trim()?.ifBlank { null },
    albumArtistSort = albumArtistSort?.trim()?.ifBlank { null },
    composerSort = composerSort?.trim()?.ifBlank { null },
    groupArtist = groupArtist?.trim()?.ifBlank { null },
    startMs = parseClip(formatClip(startMs)),
    endMs = parseClip(formatClip(endMs)),
    lyrics = lyrics?.trim()?.ifBlank { null },
)

@Composable
private fun Field(
    value: String,
    label: String,
    jsonKey: String? = null,
    tagFormat: TagFormat? = null,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    val note = listOfNotNull(help, tagName(jsonKey, tagFormat)).joinToString(" · ")
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = note.takeIf { it.isNotEmpty() }?.let { { Text(it) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * What this field is called in the file, or null when it should not be shown.
 *
 * Reads TagMap rather than a table of its own -- the whole reason that exists
 * in :core:model is that the reader, the writer and this label must agree.
 */
private fun tagName(jsonKey: String?, tagFormat: TagFormat?): String? {
    val mapping = jsonKey?.let { TagMap.of(it) } ?: return null
    if (mapping.roamOnly) return "Roam only — no tag equivalent"
    val format = tagFormat ?: return null
    return mapping.name(format)
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
    jsonKey: String? = null,
    tagFormat: TagFormat? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        // Filtered rather than validated: a non-digit simply cannot be typed,
        // which beats an error message appearing after the fact.
        onValueChange = { entered -> onChange(entered.filter { it.isDigit() }.take(4)) },
        label = { Text(label) },
        supportingText = tagName(jsonKey, tagFormat)?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** "Track [3] of [12]" -- both halves of one frame, laid out as one. */
@Composable
private fun PairRow(
    label: String,
    first: String,
    second: String,
    jsonKey: String,
    tagFormat: TagFormat?,
    onFirst: (String) -> Unit,
    onSecond: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberField(first, label, Modifier.weight(1f), jsonKey, tagFormat, onFirst)
        Text("of", style = MaterialTheme.typography.bodyMedium)
        NumberField(second, "", Modifier.weight(1f), onChange = onSecond)
    }
}
