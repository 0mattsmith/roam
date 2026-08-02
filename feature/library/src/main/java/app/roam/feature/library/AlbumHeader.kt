package app.roam.feature.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
@Composable
fun AlbumHeader(
    track: TrackListItem,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
) {
    val ctx = LocalContext.current

    // Captured locally: Kotlin will not smart-cast a property declared in
    // another module, because it cannot prove the getter is stable.
    val artworkId = track.albumArtworkId ?: track.artworkId

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(
                Modifier
                    .clickable(onClick = onOpen)
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
                        track.albumTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    track.albumYear?.takeIf { it > 0 }?.let { year ->
                        Text(
                            year.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }

                FilledTonalIconButton(onClick = onPlay) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play album")
                }
            }
            HorizontalDivider()
        }
    }
}
