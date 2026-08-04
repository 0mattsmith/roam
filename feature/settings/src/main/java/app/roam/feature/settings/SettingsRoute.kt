package app.roam.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

            // Only when there is something in it. An empty "Removed" section
            // is a permanent reminder of a feature nobody used.
            val hidden by vm.hiddenTracks.collectAsStateWithLifecycle()
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Removed from library")
                Text(
                    "Still on Drive, just hidden. ${hidden.size} " +
                        if (hidden.size == 1) "track." else "tracks.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                hidden.forEach { track ->
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
                            TextButton(onClick = { vm.restoreTrack(track.id) }) { Text("Restore") }
                        },
                    )
                }
            }

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
