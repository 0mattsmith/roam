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
     * Asks the tag pass to open one of these files again.
     *
     * clearUserEdit rather than a bare tagState reset: these rows carry no user
     * edit by definition, so the flag half is a no-op, and the PENDING half is
     * what gives the pass something to pick up. A FAILED row is otherwise never
     * revisited -- that is the point of FAILED.
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
