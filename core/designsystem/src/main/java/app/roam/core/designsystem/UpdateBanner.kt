package app.roam.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Teal bar along the bottom announcing a new build.
 *
 * Deliberately not a dialog: an update is not urgent enough to interrupt what
 * someone is doing, and a modal that appears on launch is the fastest way to
 * train people to dismiss things without reading them.
 */
@Composable
fun UpdateBanner(
    versionName: String,
    downloadPercent: Int?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = Teal, contentColor = Ink, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Roam $versionName available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (downloadPercent != null) {
                        Text(
                            "Downloading $downloadPercent%",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.copy(alpha = 0.75f),
                        )
                    }
                }

                if (downloadPercent == null) {
                    TextButton(
                        onClick = onInstall,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Ink,
                        ),
                    ) {
                        Text("Install", fontWeight = FontWeight.Bold)
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Ink)
                }
            }

            if (downloadPercent != null) {
                LinearProgressIndicator(
                    progress = { downloadPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Ink,
                    trackColor = Ink.copy(alpha = 0.2f),
                )
            }
        }
    }
}
