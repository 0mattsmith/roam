package app.roam.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Tracks whose titles came from their filenames, with nothing better coming.
 *
 * Roam read these files and found no usable tags, nobody has corrected them and
 * no album.json describes them -- so what is on screen is a guess, and no
 * automatic pass will improve it. That is the whole reason they are worth
 * listing: everything else in the library either has an answer or is about to
 * get one.
 *
 * A page rather than a repair, because there is nothing to repair with. Tapping
 * a track opens its editor, which is where a person can supply what the file
 * could not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrozenTracksRoute(
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit,
    vm: FrozenTracksViewModel = hiltViewModel(),
) {
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Try reading all ${frozen.size} again?") },
            text = {
                Text(
                    "Roam will open each file and look for tags a second time. Most " +
                        "of these genuinely have none and will come back unchanged — " +
                        "but a read can also fail on a poor connection, and this is " +
                        "the way back from that.\n\n" +
                        "Nothing you have typed is affected, and nothing on Drive is touched."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; vm.releaseAll() }) {
                    Text("Try again")
                }
            },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Needs a look") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (frozen.isNotEmpty()) {
                        TextButton(onClick = { confirmAll = true }) { Text("All") }
                    }
                },
            )
        },
    ) { padding ->
        if (frozen.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Nothing to look at", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Every track in your library has its title from its own tags, " +
                        "from an album.json, or from you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            return@Scaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    // Says what happened and what it costs, on the page someone
                    // lands on when they are wondering what Roam did to their
                    // library. No jargon: nothing here mentions a flag.
                    "Roam opened these files and found no tags it could use, so " +
                        "their titles are worked out from the filenames. Nothing you " +
                        "have typed is involved, and no automatic pass will improve " +
                        "them.\n\n" +
                        "Tap one to edit it by hand. \"Try again\" re-reads the file, " +
                        "which is worth a go if the first read failed on a poor " +
                        "connection rather than because the tags are missing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            items(frozen.size, key = { frozen[it].id }) { index ->
                val track = frozen[index]
                ListItem(
                    modifier = Modifier.clickable { onOpenTrack(track.id) },
                    headlineContent = {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            "${track.artistName} · ${track.albumTitle}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { vm.release(track.id) }) { Text("Try again") }
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
