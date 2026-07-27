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
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (!state.connected) {
                        Button(onClick = { vm.connect() }) { Text("Connect") }
                    }
                },
            )

            if (state.folderId != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.syncNow() }, enabled = !state.syncing) {
                        Text(if (state.syncing) "Scanning…" else "Sync now")
                    }
                    Spacer(Modifier.width(16.dp))
                    state.tracksFound?.let { n ->
                        Text(
                            if (state.syncing) "$n tracks so far…" else "$n tracks found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
