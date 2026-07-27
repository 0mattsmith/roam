package app.roam.feature.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


/**
 * Tabs: Artists / Albums / Tracks / Loved, each backed by a Room PagingSource.
 * A persistent "Shuffle all" FAB sits above the mini-player.
 *
 * Never load 10,000 rows into memory -- LazyColumn over PagingData only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryRoute(
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloader: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Roam") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { /* TODO(phase3): weighted shuffle of the whole library */ },
                text = { Text("Shuffle all") },
                icon = {},
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Library", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Phase 1: connect Google Drive in Settings, run a sync, then " +
                    "artists appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenSettings) { Text("Settings") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenDownloader) { Text("Download") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenPlayer) { Text("Now playing") }
        }
    }
}

