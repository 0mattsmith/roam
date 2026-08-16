package app.roam.feature.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.data.catalog.metadata.LrcLib
import app.roam.data.catalog.metadata.TrackLyrics

/**
 * The one line being sung, with the whole song a tap away.
 *
 * Deliberately not a scrolling area embedded in the player. Now Playing is a
 * panel you dismiss by dragging DOWN, and a vertical scroll inside a vertical
 * drag is a gesture fight the user always loses -- half their swipes would
 * close the player instead of moving the words. A strip plus a sheet keeps both
 * gestures unambiguous.
 */
@Composable
internal fun LyricsStrip(
    lyrics: TrackLyrics,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    // Nothing at all until a lookup has happened and found something. An empty
    // "Lyrics" button on every instrumental would be worse than silence.
    if (!lyrics.hasAny) return

    var open by remember { mutableStateOf(false) }

    val active = remember(lyrics.lines, positionMs) {
        LrcLib.activeLineIndex(lyrics.lines, positionMs)
    }

    val preview = when {
        lyrics.instrumental -> "Instrumental"
        // Before the first line there is nothing being sung yet, so the label
        // says what tapping does instead of showing a line early.
        lyrics.synced && active >= 0 -> lyrics.lines[active].text
        else -> "Lyrics"
    }

    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = !lyrics.instrumental) { open = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Lyrics,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            preview.ifBlank { "Lyrics" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (lyrics.synced && active >= 0) FontWeight.Medium else FontWeight.Normal,
            color = if (lyrics.synced && active >= 0) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (open) {
        LyricsSheet(
            lyrics = lyrics,
            activeIndex = active,
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsSheet(
    lyrics: TrackLyrics,
    activeIndex: Int,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (lyrics.synced) {
            val listState = rememberLazyListState()

            // Follows the song. Scrolled to a third of the way down rather than
            // to the top, so the lines about to be sung are on screen too --
            // pinning the active line to the very top means always reading
            // ahead into blank space.
            LaunchedEffect(activeIndex) {
                if (activeIndex >= 0) {
                    listState.animateScrollToItem(
                        index = (activeIndex - LOOK_AHEAD).coerceAtLeast(0),
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                itemsIndexed(lyrics.lines) { index, line ->
                    val isActive = index == activeIndex
                    Text(
                        // A blank line in an LRC is a real pause; a zero-height
                        // Text would silently collapse it and pull the next
                        // line up out of time.
                        line.text.ifBlank { " " },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Start,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
        } else {
            Text(
                lyrics.plain.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Lines kept above the active one, so the next words are already visible. */
private const val LOOK_AHEAD = 2
