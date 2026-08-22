package app.roam.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.database.HiddenTrackRow
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.data.catalog.tags.TagWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tracks frozen on a guessed title, and the way out.
 *
 * Its own ViewModel for the same reason RemovedTracksViewModel is: this needs
 * one DAO and a worker, and building the Drive auth and updater to show a list
 * would be ceremony.
 */
@HiltViewModel
class FrozenTracksViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracks: TrackDao,
    private val settings: SettingsRepository,
) : ViewModel() {

    val frozen: StateFlow<List<HiddenTrackRow>> = tracks.frozenOnGuess()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Hands one track back to its file.
     *
     * clearUserEdit does both halves: it drops the flag AND sets tagState back
     * to PENDING, so the pass below actually has something to pick up. Dropping
     * the flag alone would leave a FAILED row that nothing ever revisits.
     */
    fun release(id: Long) = viewModelScope.launch {
        tracks.clearUserEdit(id)
        readTags()
    }

    fun releaseAll() = viewModelScope.launch {
        tracks.releaseFrozenOnGuess()
        readTags()
    }

    private suspend fun readTags() {
        TagWorker.enqueue(ctx, settings.settings.first().wifiOnlyForLargeTransfers)
    }
}
