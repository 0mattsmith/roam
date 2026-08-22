package app.roam.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.roam.core.database.TrackDao
import app.roam.core.designsystem.NoticeBanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NeedsLookViewModel @Inject constructor(
    tracks: TrackDao,
) : ViewModel() {
    val frozenCount: StateFlow<Int> = tracks.frozenOnGuessCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/**
 * Says so when Roam's own catalogue has gone wrong in a way it cannot fix alone.
 *
 * Right now that means one thing: tracks whose files Roam read and found no
 * usable tags in, so their titles come from their filenames and no automatic
 * pass will ever improve them. It counts those and offers the page, because the
 * alternative to saying so is a library that is quietly wrong forever.
 *
 * Dismissal is per session, not remembered. This is a real problem with a real
 * fix, and a permanent "no" would leave a library quietly wrong with nothing
 * ever mentioning it again -- unlike an update, which will simply be offered
 * afresh tomorrow.
 */
@Composable
fun NeedsLookBannerHost(
    onOpen: () -> Unit,
    vm: NeedsLookViewModel = hiltViewModel(),
) {
    val count by vm.frozenCount.collectAsStateWithLifecycle()
    var dismissed by remember { mutableStateOf(false) }

    // Raised again each time the app comes back to the front. The count is a
    // live query, so fixing tracks makes this disappear on its own.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { dismissed = false }

    if (count == 0 || dismissed) return

    NoticeBanner(
        headline = if (count == 1) "1 track needs a look" else "$count tracks need a look",
        detail = "No usable tags in the file, so the title is its filename",
        actionLabel = "Review",
        onAction = { dismissed = true; onOpen() },
        onDismiss = { dismissed = true },
    )
}
