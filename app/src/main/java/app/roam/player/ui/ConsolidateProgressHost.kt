package app.roam.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Context
import app.roam.data.catalog.metadata.ConsolidateWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Just enough to draw a line: what it is doing and how far in. */
data class ConsolidateProgress(val label: String, val fraction: Float?)

@HiltViewModel
class ConsolidateProgressViewModel @Inject constructor(
    @ApplicationContext ctx: Context,
) : ViewModel() {

    val progress: StateFlow<ConsolidateProgress?> =
        WorkManager.getInstance(ctx)
            .getWorkInfosForUniqueWorkFlow(ConsolidateWorker.NAME)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                val running = info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.ENQUEUED
                if (!running) return@map null

                val done = info.progress.getInt(ConsolidateWorker.KEY_DONE, 0)
                val total = info.progress.getInt(ConsolidateWorker.KEY_TOTAL, 0)
                val found = info.progress.getInt(ConsolidateWorker.KEY_FOUND, 0)

                ConsolidateProgress(
                    label = when {
                        total > 0 -> "Writing metadata files · $done of $total albums"
                        found > 0 -> "Reading metadata files · $found found"
                        else -> "Looking through your library…"
                    },
                    // Null while crawling: the total is not known until the
                    // listing finishes, and a bar that jumps from nowhere to
                    // half is worse than one that admits it is indeterminate.
                    fraction = if (total > 0) done.toFloat() / total else null,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/**
 * A thin line along the bottom while Consolidate runs.
 *
 * Deliberately not a dialog and not dismissable. This is work somebody asked
 * for from Settings and then walked away from -- it can take a while on a large
 * library -- so it needs to be visible from wherever they went next without
 * being in the way of it. It disappears on its own when the run ends; the
 * RESULT lives in Settings, which is where they will go to look.
 */
@Composable
fun ConsolidateProgressHost(vm: ConsolidateProgressViewModel = hiltViewModel()) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val showing = progress ?: return

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    showing.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val fraction = showing.fraction
            if (fraction == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
            }
        }
    }
}
