package app.roam.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VisibilityOff
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
 * Everything removed from the library, and the way back.
 *
 * A page of its own rather than a section in Settings: every list query filters
 * these out by design, so this is the ONLY place they exist. Buried among the
 * cache options they were easy to miss, and a hundred of them would have pushed
 * the update controls off the bottom of a long scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemovedTracksRoute(
    onBack: () -> Unit,
    vm: RemovedTracksViewModel = hiltViewModel(),
) {
    val removed by vm.removed.collectAsStateWithLifecycle()
    var confirmRestoreAll by remember { mutableStateOf(false) }

    if (confirmRestoreAll) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAll = false },
            title = { Text("Restore everything?") },
            text = {
                Text("All ${removed.size} tracks will reappear in your library.")
            },
            confirmButton = {
                TextButton(onClick = { confirmRestoreAll = false; vm.restoreAll() }) {
                    Text("Restore all")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestoreAll = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Removed from library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (removed.isNotEmpty()) {
                        TextButton(onClick = { confirmRestoreAll = true }) { Text("Restore all") }
                    }
                },
            )
        },
    ) { padding ->
        if (removed.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing removed",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Removing a track hides it from Roam. The file itself is never " +
                        "deleted from Drive, and anything you remove will show up here.",
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
                    // Restated here because this is the page someone lands on
                    // when they are worried they deleted something.
                    "These are hidden from Roam. The files are still on Drive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }

            items(removed.size, key = { removed[it].id }) { index ->
                val track = removed[index]
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
                        TextButton(onClick = { vm.restore(track.id) }) { Text("Restore") }
                    },
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
