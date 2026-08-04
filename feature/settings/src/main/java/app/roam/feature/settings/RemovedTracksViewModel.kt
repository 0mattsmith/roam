package app.roam.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.database.AlbumDao
import app.roam.core.database.ArtistDao
import app.roam.core.database.HiddenTrackRow
import app.roam.core.database.TrackDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Its own ViewModel rather than a corner of SettingsViewModel.
 *
 * That one carries Drive auth, the updater and a sync observer; a list of
 * removed tracks needs three DAOs and nothing else, and constructing the rest
 * to show it would be pure ceremony.
 */
@HiltViewModel
class RemovedTracksViewModel @Inject constructor(
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
) : ViewModel() {

    val removed: StateFlow<List<HiddenTrackRow>> = tracks.hiddenTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: Long) = viewModelScope.launch {
        tracks.setHidden(id, hidden = false)
        recount()
    }

    fun restoreAll() = viewModelScope.launch {
        tracks.restoreAllHidden()
        recount()
    }

    /**
     * Counts live on the album and artist rows, so putting a track back has to
     * put it back into the totals too -- otherwise an album reads as ten tracks
     * and lists eleven.
     */
    private suspend fun recount() {
        albums.recomputeRollups()
        artists.recomputeRollups()
    }
}
