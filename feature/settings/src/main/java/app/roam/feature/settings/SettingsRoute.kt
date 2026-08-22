package app.roam.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onOpenRemoved: () -> Unit,
    onOpenFrozen: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onConsentResult(result.data) }

    // Google returned a PendingIntent asking the user to approve the Drive
    // scope; hand it to the activity-result launcher.
    LaunchedEffect(consent) {
        consent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
            vm.consentHandled()
        }
    }

    if (state.confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { vm.askDisconnect(false) },
            title = { Text("Disconnect Google Drive?") },
            text = {
                Text(
                    "Roam will forget the folder and delete its local catalogue. " +
                        "Nothing on Drive is touched.\n\n" +
                        "To revoke Roam's access to your account entirely, remove it " +
                        "under Google Account \u2192 Data & privacy \u2192 Third-party access."
                )
            },
            confirmButton = { TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") } },
            dismissButton = { TextButton(onClick = { vm.askDisconnect(false) }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // AutoMirrored flips for right-to-left locales.
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            SectionHeader("Sources")

            ListItem(
                headlineContent = { Text("Google Drive") },
                supportingContent = {
                    Text(
                        when {
                            !state.connected -> "Not connected"
                            state.folderName != null -> "/${state.folderName} · connected"
                            else -> "Connected"
                        }
                    )
                },
                trailingContent = {
                    when {
                        state.busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        !state.connected -> Button(onClick = { vm.connect() }) { Text("Connect") }
                        else -> TextButton(onClick = { vm.askDisconnect(true) }) { Text("Disconnect") }
                    }
                },
            )

            if (state.connected) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.refreshLibrary() },
                        enabled = !state.syncing && state.folderId != null,
                    ) {
                        Text(if (state.syncing) "Checking…" else "Refresh library")
                    }
                    Spacer(Modifier.width(16.dp))
                    state.tracksFound?.let { n ->
                        Text(
                            if (state.syncing) "$n tracks so far…" else "$n tracks",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SwitchRow(
                    title = "Check for new music on launch",
                    subtitle = "Only changed files are re-read, so this is quick",
                    checked = state.syncOnLaunch,
                    onChange = vm::setSyncOnLaunch,
                )

                SwitchRow(
                    title = "Save artwork to Drive",
                    subtitle = "Writes artist.jpg and cover.jpg beside your music so " +
                        "images survive a reinstall. Automatic ones never replace a " +
                        "file you added yourself.",
                    checked = state.saveArtistPhotos,
                    onChange = vm::setSaveArtistPhotos,
                )
            }

            state.message?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Cache")
            Text(
                "Coming in phase 3 - next-N-tracks and storage-budget modes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Now playing")

            val showLyrics by vm.showLyrics.collectAsStateWithLifecycle()
            SwitchRow(
                title = "Show lyrics",
                subtitle = "Looked up once per track from LRCLIB and kept, so a " +
                    "song you have played before needs no connection. A few " +
                    "kilobytes each, and only for what you actually play.",
                checked = showLyrics,
                onChange = vm::setShowLyrics,
            )

            val saveLyrics by vm.saveLyricsToDrive.collectAsStateWithLifecycle()
            SwitchRow(
                title = "Save lyrics beside the music",
                subtitle = "Writes a .lrc or .txt next to each track on Drive, so " +
                    "they survive a reinstall and any other player can read them. " +
                    "Never replaces a file that is already there.",
                checked = saveLyrics,
                onChange = vm::setSaveLyricsToDrive,
            )

            val sweep by vm.lyricsSweep.collectAsStateWithLifecycle()

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { vm.syncLyrics(force = false) },
                    enabled = sweep?.running != true,
                ) { Text("Sync lyrics") }

                OutlinedButton(
                    onClick = { vm.syncLyrics(force = true) },
                    enabled = sweep?.running != true,
                ) { Text("Re-check all") }
            }

            Text(
                // Being straight about the limit. Roam has nothing to compare a
                // lyric against, so it cannot find a wrong one -- "Re-check all"
                // is the manual answer to that, not a smarter algorithm.
                "\"Sync lyrics\" fills in tracks with none. \"Re-check all\" asks " +
                    "again about every track, including ones that came back empty " +
                    "or came back wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            sweep?.let { progress ->
                if (progress.running && progress.total > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.done} of ${progress.total} - ${progress.found} found",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (progress.finished && progress.total > 0) {
                    Text(
                        "Found lyrics for ${progress.found} of ${progress.total} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionHeader("Data usage")

            val wifiOnly by vm.wifiOnlyLargeTransfers.collectAsStateWithLifecycle()
            SwitchRow(
                title = "Use Wi-Fi only for large transfers",
                subtitle = "Adding music, reading tags and covers from your files, and " +
                    "fetching artist images all wait for an unmetered network. " +
                    "Looking for new music is not held back - that is a few " +
                    "folder listings, so your library still updates anywhere.",
                checked = wifiOnly,
                onChange = vm::setWifiOnlyLargeTransfers,
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Discogs")

            val discogsToken by vm.discogsToken.collectAsStateWithLifecycle()
            OutlinedTextField(
                value = discogsToken,
                onValueChange = vm::setDiscogsToken,
                label = { Text("Personal access token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                // Said plainly because there is no way around it: Discogs
                // refuses searches outright without a token, and the album
                // screen simply will not offer it until one is here.
                "Discogs will not answer searches without one. Generate a token at " +
                    "discogs.com/settings/developers and paste it here. Leave blank to use " +
                    "MusicBrainz only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Library")

            val tagMessage by vm.tagMessage.collectAsStateWithLifecycle()
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { vm.readTagsAgain(everything = false) }) { Text("Read tags") }
                OutlinedButton(onClick = { vm.readTagsAgain(everything = true) }) {
                    Text("Re-read all")
                }
                tagMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                // The same shape as the lyrics pair above it, and for the same
                // reason: one button fills gaps, the other is the way back from
                // an answer Roam has already recorded and would never revisit.
                "\"Read tags\" tries files Roam gave up on. \"Re-read all\" reads " +
                    "every file again, for when a title is wrong rather than missing. " +
                    "Neither replaces anything you typed yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            val frozenCount by vm.frozenCount.collectAsStateWithLifecycle()
            if (frozenCount > 0) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenFrozen),
                    headlineContent = { Text("Needs a look") },
                    supportingContent = {
                        Text(
                            if (frozenCount == 1) "1 track is stuck on a guessed title"
                            else "$frozenCount tracks are stuck on a guessed title"
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                        )
                    },
                )
            }

            val hiddenCount by vm.hiddenCount.collectAsStateWithLifecycle()
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenRemoved),
                headlineContent = { Text("Removed from library") },
                supportingContent = {
                    Text(
                        if (hiddenCount == 0) "Nothing removed"
                        else "$hiddenCount hidden, still on Drive"
                    )
                },
                leadingContent = {
                    Icon(Icons.Filled.VisibilityOff, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
            )

            Spacer(Modifier.height(24.dp))
            SectionHeader("Updates")

            ListItem(
                headlineContent = { Text("Roam ${state.installedVersion}") },
                supportingContent = {
                    Text(
                        state.update?.let { "Version ${it.versionName} available" }
                            ?: "Installed version"
                    )
                },
                trailingContent = {
                    when {
                        state.checkingUpdate ->
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        state.downloadPercent != null ->
                            Text("${state.downloadPercent}%", style = MaterialTheme.typography.labelMedium)
                        state.update != null ->
                            Button(onClick = { vm.installUpdate() }) { Text("Update") }
                        else ->
                            OutlinedButton(onClick = { vm.checkForUpdate() }) { Text("Check") }
                    }
                },
            )

            state.downloadPercent?.let { pct ->
                LinearProgressIndicator(
                    progress = { pct / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }

            SwitchRow(
                title = "Check for updates on launch",
                subtitle = null,
                checked = state.autoCheckUpdates,
                onChange = vm::setAutoCheckUpdates,
            )

            state.update?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        notes.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            state.updateMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}
