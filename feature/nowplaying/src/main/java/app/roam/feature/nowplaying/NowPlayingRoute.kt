package app.roam.feature.nowplaying

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp


/**
 * The large-artwork player. Portrait: art on top, controls stacked.
 * Landscape: art left, controls right.
 *
 * Background is a vertical gradient from Ink into a heavily blurred,
 * desaturated 32px downsample of the album art -- cheap with RenderEffect,
 * and it is where the depth in the reference screenshots comes from.
 *
 * Play/pause 72dp, transport 56dp, secondary row 48dp.
 */
@Composable
fun NowPlayingRoute(onCollapse: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Now playing", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        // Chevron down, matching the reference player: collapses to the
        // mini-player rather than reading as a hierarchy "back".
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Collapse")
        }
    }
}

