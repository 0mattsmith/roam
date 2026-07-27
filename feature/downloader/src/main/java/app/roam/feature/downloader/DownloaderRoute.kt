package app.roam.feature.downloader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


/**
 * yt-dlp search across YouTube Music (better metadata) and YouTube, then
 * FFmpeg transcode, then the MusicBrainz -> Cover Art Archive -> TheAudioDB
 * -> Deezer -> iTunes enrichment cascade, then the review sheet.
 *
 * MusicBrainz: 1 request/second, hard, and a real User-Agent. Both are
 * enforced by their infrastructure, not by convention.
 */
@Composable
fun DownloaderRoute(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Download", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
}

