package app.roam.feature.downloader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.roam.data.catalog.metadata.ReleaseMatch
import coil.compose.AsyncImage

/**
 * Everything a catalogue lists under one artist.
 *
 * Deliberately the catalogue's list rather than YouTube's: it includes records
 * you do not have and records YouTube does not carry, which is the point of
 * looking an artist up rather than scrolling search results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistSheet(
    artist: ArtistUiState,
    onDismiss: () -> Unit,
    onOpenRelease: (ReleaseMatch) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val image = artist.imageUrl
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(CircleShape),
                    )
                } else {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            artist.detail,
                            artist.releases.size.takeIf { it > 0 }?.let { "$it releases" },
                            artist.source.label,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (artist.loading) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            artist.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(artist.releases.size, key = { artist.releases[it].id }) { index ->
                    val release = artist.releases[index]
                    ListItem(
                        modifier = Modifier.clickable { onOpenRelease(release) },
                        headlineContent = {
                            Text(
                                release.year?.let { "${release.title} ($it)" } ?: release.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    release.format,
                                    release.trackCount.takeIf { it > 0 }?.let { "$it tracks" },
                                ).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            val cover = release.coverUrl
                            if (cover != null) {
                                AsyncImage(
                                    model = cover,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                                )
                            } else {
                                Icon(Icons.Filled.MusicNote, contentDescription = null)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}
