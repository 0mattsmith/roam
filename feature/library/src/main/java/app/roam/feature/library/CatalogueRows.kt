package app.roam.feature.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.core.designsystem.Amber
import app.roam.data.acquire.AddState
import app.roam.data.catalog.metadata.ArtistMatch
import app.roam.data.acquire.YoutubeResult
import coil.compose.AsyncImage
import kotlin.math.roundToInt

/*
 * The rows of the search page's second group, and the one icon each ends in.
 *
 * Laid out like the library's own rows -- 44dp artwork, two lines, one thing
 * at the end -- so the two groups read as one list that changes meaning half
 * way down, not as two screens stacked on top of each other.
 */

/**
 * Does what a row's icon does. The row and its icon are one target with one
 * meaning, so a near miss on the icon is not a different action.
 */
internal fun act(
    state: AddState,
    onAdd: () -> Unit,
    onPlay: (Long) -> Unit,
    onFound: () -> Unit,
    onFailed: (AddState.Failed) -> Unit,
) {
    when (state) {
        AddState.NotAdded -> onAdd()
        is AddState.Ready -> onPlay(state.trackId)
        AddState.NotFound -> onFound()
        is AddState.Failed -> onFailed(state)
        // On its way: nothing to offer a tap. The Adding list is where an add
        // is cancelled, because a ring that cancels when brushed is a trap.
        AddState.Queued, is AddState.Adding, AddState.Syncing -> Unit
    }
}

/**
 * The icon a row of music you do not have ends in, which is its state
 * (docs/SEARCH.md). Amber is on its way, teal is yours, red needs you -- and
 * every state has its own shape as well, so none is told apart by colour
 * alone: a +, a dashed ring, a ring filling, a ring spinning, a play
 * triangle, a tick, a warning sign.
 */
@Composable
internal fun AddStateButton(
    state: AddState,
    onAdd: () -> Unit,
    onPlay: (Long) -> Unit,
    onFound: () -> Unit,
    onFailed: (AddState.Failed) -> Unit,
) {
    val teal = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    val label = when (state) {
        AddState.NotAdded -> "Add to library"
        AddState.Queued -> "Waiting to be added"
        is AddState.Adding ->
            if (state.progress > 0f) "Adding, ${(state.progress * 100).roundToInt()}%" else "Adding"
        AddState.Syncing -> "On Drive, waiting for a sync"
        is AddState.Ready -> "Play"
        AddState.NotFound -> "Added. Find it in your library"
        is AddState.Failed -> "Could not add. Show why"
    }
    val tappable = when (state) {
        AddState.Queued, is AddState.Adding, AddState.Syncing -> false
        else -> true
    }

    IconButton(
        onClick = { act(state, onAdd, onPlay, onFound, onFailed) },
        enabled = tappable,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .semantics(mergeDescendants = true) { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                AddState.NotAdded -> StateBadge(
                    Icons.Filled.Add,
                    tint = MaterialTheme.colorScheme.onSurface,
                    wash = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f),
                )
                AddState.Queued -> DashedRing(Amber)
                // Determinate from the start, at nothing filled until yt-dlp
                // reports, which it does late. A spinner there would be the
                // Syncing ring in another colour, and colour alone is the one
                // thing this icon must not rely on.
                is AddState.Adding -> CircularProgressIndicator(
                    progress = { state.progress },
                    color = Amber,
                    trackColor = Amber.copy(alpha = 0.22f),
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(RING),
                )
                AddState.Syncing -> CircularProgressIndicator(
                    color = teal,
                    trackColor = Color.Transparent,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(RING),
                )
                is AddState.Ready -> StateBadge(Icons.Filled.PlayArrow, tint = teal, wash = teal.copy(alpha = 0.14f))
                AddState.NotFound -> StateBadge(Icons.Filled.Check, tint = teal, wash = teal.copy(alpha = 0.14f))
                is AddState.Failed -> StateBadge(Icons.Filled.Warning, tint = red, wash = red.copy(alpha = 0.14f))
            }
        }
    }
}

@Composable
private fun StateBadge(icon: ImageVector, tint: Color, wash: Color) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(wash),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DashedRing(color: Color) {
    Canvas(Modifier.size(RING)) {
        val stroke = 2.5.dp.toPx()
        drawCircle(
            color = color,
            radius = (size.minDimension - stroke) / 2,
            style = Stroke(
                width = stroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            ),
        )
    }
}

private val RING = 26.dp

/**
 * A song from YouTube, drawn as the track it is: title, then artist and
 * length, then album and year when YouTube knows them -- the album is what
 * tells you whether this is the recording you meant, since the same song
 * appears a dozen times across singles, reissues and compilations.
 *
 * A tap does what the icon does. View album and View artist wait behind a
 * long press, because every row ends in one icon and one only.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SongRow(
    result: YoutubeResult,
    state: AddState,
    onAdd: () -> Unit,
    onPlay: (Long) -> Unit,
    onFound: () -> Unit,
    onFailed: (AddState.Failed) -> Unit,
    onViewAlbum: () -> Unit,
    onViewArtist: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // The row and its menu share a Box so the menu opens at the row, not at
    // the top of the list (CLAUDE.md, "A menu opens miles from the button").
    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = { act(state, onAdd, onPlay, onFound, onFailed) },
                onLongClick = { menuOpen = true },
            ),
            headlineContent = {
                Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(
                        listOfNotNull(
                            result.artist.ifBlank { null },
                            result.durationSec?.let { "%d:%02d".format(it / 60, it % 60) },
                        ).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Absent rather than blank when YouTube had no album: an
                    // empty line would leave the rows uneven for no information.
                    result.albumLine?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            leadingContent = {
                RemoteArtwork(result.thumbnailUrl, Icons.Filled.MusicNote, circular = false)
            },
            trailingContent = { AddStateButton(state, onAdd, onPlay, onFound, onFailed) },
        )

        DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("View album") },
                enabled = result.album != null,
                onClick = { menuOpen = false; onViewAlbum() },
                leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text("View artist") },
                enabled = result.artist.isNotBlank(),
                onClick = { menuOpen = false; onViewArtist() },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            )
        }
    }
}

/**
 * An album from the catalogues. A tap opens its tracklist; the icon adds what
 * is missing from it, which is all of it unless the row says otherwise.
 */
@Composable
internal fun ReleaseRow(
    group: ReleaseGroup,
    state: AddState,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
    onPlay: (Long) -> Unit,
    onFound: () -> Unit,
    onFailed: (AddState.Failed) -> Unit,
) {
    val match = group.shown
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = {
            Text(
                albumTitleWithYear(match.title, match.year),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(
                        match.artist.ifBlank { null },
                        match.trackCount.takeIf { it > 0 }?.let { "$it tracks" },
                        // How many pressings the row stands for, when it stands
                        // for more than one: the sheet lists them to choose from.
                        if (group.releases.size > 1) "${group.releases.size} releases" else match.source.label,
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // "4 of 12 in your library" is the sentence a part-owned album
                // is shown for. Teal, because that part is yours.
                if (group.held > 0) {
                    Text(
                        if (match.trackCount > 0) {
                            "${group.held} of ${match.trackCount} in your library"
                        } else {
                            "${group.held} in your library"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        },
        leadingContent = { RemoteArtwork(match.coverUrl, Icons.Filled.Album, circular = false) },
        trailingContent = { AddStateButton(state, onAdd, onPlay, onFound, onFailed) },
    )
}

/**
 * An artist from a catalogue, with the disambiguation that tells three bands
 * called Nirvana apart. A tap opens their discography; there is nothing to
 * add about an artist as such, so the row ends in the same chevron as the
 * library's own artists do.
 */
@Composable
internal fun CatalogueArtistRow(match: ArtistMatch, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(match.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(match.detail, match.source.label).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { RemoteArtwork(match.imageUrl, Icons.Filled.Person, circular = true) },
        trailingContent = { Chevron() },
    )
}

/**
 * Artwork from the web, at the size the library's rows use. A URL is fine
 * here: this is the phone, not the car, and none of it is in Room to point at.
 */
@Composable
private fun RemoteArtwork(url: String?, fallback: ImageVector, circular: Boolean) {
    val shape = if (circular) CircleShape else MaterialTheme.shapes.small
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Square, so a 16:9 video still is cropped to match the covers
            // rather than left letterboxed among them.
            modifier = Modifier.size(44.dp).clip(shape),
        )
    } else {
        Box(
            Modifier.size(44.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fallback, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Why a red row is red, with the two ways out of it.
 *
 * A failure keeps its reason because the reason decides which way: a refused
 * duration match is a verdict on the recording and a retry repeats it, where
 * a dropped connection is worth another go.
 */
@Composable
internal fun FailedDialog(
    failure: AddState.Failed,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val jobs = failure.jobs
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = {
            Text(
                if (jobs.size == 1) "Could not add ${jobs.first().title}"
                else "${jobs.size} tracks could not be added"
            )
        },
        text = {
            Column {
                if (jobs.size == 1) {
                    Text(failure.reason ?: "It stopped without saying why.")
                } else {
                    jobs.take(SHOWN_FAILURES).forEach { job ->
                        Text(
                            "${job.title}: ${job.error ?: "no reason given"}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (jobs.size > SHOWN_FAILURES) {
                        Text("and ${jobs.size - SHOWN_FAILURES} more")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Clear takes it off the Adding list and puts the + back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry, enabled = jobs.any { it.request != null }) { Text("Retry") }
        },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear") } },
    )
}

private const val SHOWN_FAILURES = 4
