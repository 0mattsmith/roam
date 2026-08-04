package app.roam.feature.downloader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.TrackSort
import app.roam.data.catalog.LibraryQueries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val library: List<TrackListItem> = emptyList(),
    val youtube: List<YoutubeResult> = emptyList(),
    val searchingYoutube: Boolean = false,
    val message: String? = null,
)

/**
 * One search box over two very different things.
 *
 * The library answers instantly from Room; YouTube costs a network round trip
 * through a native binary. So the two are searched separately rather than
 * being awaited together -- local results appear as you type and the remote
 * ones arrive when they arrive.
 */
@HiltViewModel
class DownloaderViewModel @Inject constructor(
    private val app: Application,
    private val tracks: TrackDao,
    private val youtube: YoutubeSource,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var localJob: Job? = null
    private var remoteJob: Job? = null

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }

        localJob?.cancel()
        remoteJob?.cancel()

        if (query.isBlank()) {
            _state.update { it.copy(library = emptyList(), youtube = emptyList(), searchingYoutube = false) }
            return
        }

        // Local search is cheap enough to run on every keystroke after a short
        // settle; the remote one is not, so it waits noticeably longer.
        localJob = viewModelScope.launch {
            delay(LOCAL_DEBOUNCE_MS)
            _state.update {
                it.copy(library = tracks.listItemsRaw(LibraryQueries.search(query, TrackSort.ARTIST, LIMIT)))
            }
        }

        remoteJob = viewModelScope.launch {
            delay(REMOTE_DEBOUNCE_MS)
            _state.update { it.copy(searchingYoutube = true) }
            val results = youtube.search(query)
            _state.update { s ->
                s.copy(
                    searchingYoutube = false,
                    youtube = results.getOrDefault(emptyList()),
                    message = results.exceptionOrNull()?.let { "YouTube search failed" } ?: s.message,
                )
            }
        }
    }

    /**
     * Queues a download with the best guess at where it belongs.
     *
     * yt-dlp gives a title and an artist and nothing resembling an album, so
     * singles go to a folder of that name. The review sheet is where this gets
     * corrected before it is queued.
     */
    fun download(result: YoutubeResult, album: String? = null) {
        DownloadWorker.enqueue(
            app,
            DownloadRequest(
                url = result.url,
                title = result.title,
                artist = result.uploader.ifBlank { "Unknown Artist" },
                album = album?.ifBlank { null } ?: "Singles",
            ),
        )
        _state.update { it.copy(message = "Queued ${result.title}") }
    }

    /** yt-dlp goes stale and quietly stops returning results when it does. */
    fun updateYtDlp() = viewModelScope.launch {
        _state.update { it.copy(message = "Updating yt-dlp…") }
        val result = youtube.update()
        _state.update {
            it.copy(message = if (result.isSuccess) "yt-dlp updated" else "Could not update yt-dlp")
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val LOCAL_DEBOUNCE_MS = 150L
        const val REMOTE_DEBOUNCE_MS = 600L
        const val LIMIT = 60
    }
}
