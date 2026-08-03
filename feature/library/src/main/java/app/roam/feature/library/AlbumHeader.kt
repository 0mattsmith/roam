package app.roam.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.core.database.TrackListItem
import app.roam.data.catalog.artwork.ArtworkProvider
import coil.compose.AsyncImage

/**
 * The band above each album's tracks.
 *
 * Laid out sideways rather than as the desktop's full-height left column: on a
 * phone a tall art column would push the track list off the screen. The cover
 * is 96dp, big enough to be the anchor without costing a screenful.
 *
 * Reads the ALBUM's artwork, not the track's. Those usually agree, but a track
 * with its own picture would otherwise change the header as you scrolled past.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumHeader(
    track: TrackListItem,
    /** Null while the answer is still being fetched, so the icon does not flicker. */
    loved: Boolean?,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleLoved: () -> Unit,
) {
    val ctx = LocalContext.current

    // Captured locally: Kotlin will not smart-cast a property declared in
    // another module, because it cannot prove the getter is stable.
    val artworkId = track.albumArtworkId ?: track.artworkId

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(
                Modifier
                    .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (artworkId != null) {
                    AsyncImage(
                        model = ArtworkProvider.uri(ctx, artworkId, size = 320),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(96.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                } else {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        albumTitleWithYear(track.albumTitle, track.albumYear),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The ALBUM's artist, so a compilation reads "Various
                    // Artists" rather than whichever guest happens to open it.
                    Text(
                        track.albumArtistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onToggleLoved, enabled = loved != null) {
                    Icon(
                        if (loved == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription =
                            if (loved == true) "Remove album from Loved" else "Add album to Loved",
                        tint = if (loved == true) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FilledTonalIconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play album")
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * A thin rule above each disc of a multi-disc set.
 *
 * Shown for every disc including the first, so a two-disc album reads as two
 * labelled halves rather than one unlabelled run followed by "Disc 2" -- which
 * leaves you inferring what the tracks above it belonged to.
 */
@Composable
fun DiscHeader(discNo: Int) {
    Text(
        "Disc $discNo",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * "Rumours (1977)", or just "Rumours" when there is no year.
 *
 * Zero counts as absent: an untagged year reads as 0 through the Int column,
 * and "(0)" is worse than nothing.
 */
fun albumTitleWithYear(title: String, year: Int?): String =
    year?.takeIf { it > 0 }?.let { "$title ($it)" } ?: title
