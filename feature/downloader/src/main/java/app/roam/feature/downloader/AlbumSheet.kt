package app.roam.feature.downloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.data.catalog.metadata.MetadataSource
import app.roam.data.catalog.metadata.ReleaseTrack
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage

/**
 * An album as it really is, not as YouTube happens to hold it.
 *
 * Laid out like the library's own album view on purpose -- large cover, title
 * and year, then the tracks in order with disc headings. Someone looking at
 * this and at their own library should not have to work out which is which.
 *
 * The tracklist is MusicBrainz's, so tracks Roam does not have still appear.
 * That is the point: an album you own nine tenths of should look like an album
 * with one track missing, not like a complete album that is nine tracks long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSheet(
    album: AlbumUiState,
    onDismiss: () -> Unit,
    onDownloadMissing: () -> Unit,
    onDownloadTrack: (ReleaseTrack) -> Unit,
    onSelectRelease: (String) -> Unit,
    onSelectSource: (MetadataSource) -> Unit,
    /** Download URLs already queued, so a tapped row can show a tick. */
    queuedUrls: Set<String>,
    urlFor: (ReleaseTrack, String) -> String,
) {
    var pickingRelease by remember(album.selectedId) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cover = album.coverUrl
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(MaterialTheme.shapes.medium),
                    )
                } else {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        album.year?.let { "${album.title} ($it)" } ?: album.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (album.tracks.isNotEmpty()) {
                        Text(
                            // "9 of 12 in your library" is the sentence someone
                            // opened this screen to read.
                            "${album.heldCount} of ${album.tracks.size} in your library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Which catalogue answered. Only offered when there is a choice --
            // Discogs is absent until a token has been pasted into Settings.
            if (album.sources.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    album.sources.forEach { option ->
                        FilterChip(
                            selected = option == album.source,
                            onClick = { onSelectSource(option) },
                            label = { Text(option.label) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Which pressing this is, and a way to say it is the wrong one.
            // Track counts differ between an original and a deluxe edition, so
            // getting this wrong makes every row below it wrong too.
            if (album.candidates.size > 1) {
                val selected = album.candidates.firstOrNull { it.id == album.selectedId }
                ListItem(
                    modifier = Modifier.clickable { pickingRelease = !pickingRelease },
                    headlineContent = {
                        Text(
                            selected?.let { listOfNotNull(it.format, "${it.trackCount} tracks").joinToString(" · ") }
                                ?: "Choose a release",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = { Text("${album.candidates.size} releases found") },
                    trailingContent = {
                        TextButton(onClick = { pickingRelease = !pickingRelease }) {
                            Text(if (pickingRelease) "Hide" else "Change")
                        }
                    },
                )

                if (pickingRelease) {
                    album.candidates.forEach { candidate ->
                        ListItem(
                            modifier = Modifier.clickable { onSelectRelease(candidate.id) },
                            headlineContent = {
                                Text(
                                    candidate.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(candidate.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = candidate.id == album.selectedId,
                                    onClick = { onSelectRelease(candidate.id) },
                                )
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            if (album.tracks.isNotEmpty()) {
                // Counts what is neither owned nor already queued, so pressing
                // it twice does not offer to fetch the same tracks again.
                val missing = album.missing.count { urlFor(it, album.artist) !in queuedUrls }
                Button(
                    onClick = onDownloadMissing,
                    enabled = missing > 0,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            missing > 0 -> "Download $missing missing"
                            album.missing.isNotEmpty() -> "All queued"
                            else -> "Nothing missing"
                        }
                    )
                }
            }

            album.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            if (album.loading) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(
                    album.tracks.size,
                    key = { "${album.tracks[it].track.discNo}/${album.tracks[it].track.position}" },
                ) { index ->
                    val row = album.tracks[index]
                    val track = row.track
                    val previous = album.tracks.getOrNull(index - 1)?.track

                    // Only on a genuine multi-disc set, and only at the
                    // boundary -- same rule as the library's own list.
                    val multiDisc = album.tracks.any { it.track.discNo > 1 }
                    if (multiDisc && previous?.discNo != track.discNo) {
                        Text(
                            "Disc ${track.discNo}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp,
                            ),
                        )
                    }

                    ListItem(
                        headlineContent = {
                            Text(
                                track.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                // Dimmed, not struck through: you own it, which
                                // is a good thing, not a cancelled one.
                                color = if (row.inLibrary) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    if (row.inLibrary) "In your library" else null,
                                    track.artist.takeIf { it != album.artist && it.isNotBlank() },
                                    track.durationMs?.let {
                                        val s = it / 1000
                                        "%d:%02d".format(s / 60, s % 60)
                                    },
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Text(
                                "${track.position}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            // A tick where the button would be, so the rows
                            // stay aligned and the difference is the icon
                            // rather than a gap. Two different ticks: grey for
                            // "you already own this", green for "queued just
                            // now" -- they mean different things and the
                            // second is the one you are waiting to see.
                            val queued = urlFor(track, album.artist) in queuedUrls
                            when {
                                row.inLibrary -> Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Already in your library",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                queued -> Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Queued",
                                    tint = QUEUED_GREEN,
                                )
                                else -> IconButton(onClick = { onDownloadTrack(track) }) {
                                    Icon(Icons.Filled.Download, contentDescription = "Download track")
                                }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Matches the download manager's green, so a tick means one thing. */
private val QUEUED_GREEN = Color(0xFF2E7D32)
