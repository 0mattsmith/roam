package app.roam.feature.settings

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
 * Tracks whose metadata is marked as hand-typed, on files Roam has never read.
 *
 * A page rather than a silent repair. Roam cannot tell a title somebody typed
 * from one the step arrows froze by accident -- the flag records that an edit
 * happened, never why -- so guessing either way is wrong. It shows the list and
 * lets the person look, which is the only honest answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrozenTracksRoute(
    onBack: () -> Unit,
    vm: FrozenTracksViewModel = hiltViewModel(),
) {
    val frozen by vm.frozen.collectAsStateWithLifecycle()
    var confirmAll by remember { mutableStateOf(false) }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Read the tags for all ${frozen.size}?") },
            text = {
                Text(
                    "Roam will read each file and use whatever its tags say. Any " +
                        "title, artist or album you typed for these tracks will be " +
                        "replaced. Files with no tags will keep the name they have now.\n\n" +
                        "Nothing on Drive is touched."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmAll = false; vm.releaseAll() }) {
                    Text("Read the tags")
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
                    "Every track marked as edited by hand sits on a file whose tags " +
                        "Roam has read, so nothing here is running on a guess.",
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
                    "These tracks are marked as edited by hand, but Roam has never " +
                        "managed to read their files' own tags — so what you see is " +
                        "worked out from the filename, and nothing will replace it.\n\n" +
                        "Some of these you probably did type yourself. The rest were " +
                        "marked by the up and down arrows in the editor, which used to " +
                        "save on every press even when nothing had changed. That is " +
                        "fixed, but Roam cannot tell the two apart now.\n\n" +
                        "\"Read the tags\" throws away what is stored for that track " +
                        "and uses the file instead. The file on Drive is never touched.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            items(frozen.size, key = { frozen[it].id }) { index ->
                val track = frozen[index]
                ListItem(
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
                        TextButton(onClick = { vm.release(track.id) }) { Text("Read tags") }
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
