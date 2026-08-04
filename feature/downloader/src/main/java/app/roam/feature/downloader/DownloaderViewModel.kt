package app.roam.feature.downloader

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.TrackSort
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.roam.data.catalog.LibraryQueries
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row of the download queue. */
data class DownloadStatus(
    val id: String,
    val title: String,
    val artist: String,
    /** Null only if the tag could not be decoded; without it, retry is impossible. */
    val request: DownloadRequest?,
    val state: WorkInfo.State,
    val progress: Float,
) {
    val finished: Boolean get() = state.isFinished
    val running: Boolean get() = state == WorkInfo.State.RUNNING
    val failed: Boolean
        get() = state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED
    val succeeded: Boolean get() = state == WorkInfo.State.SUCCEEDED
}

data class SearchUiState(
    val query: String = "",
    val library: List<TrackListItem> = emptyList(),
    val youtube: List<YoutubeResult> = emptyList(),
    val searchingYoutube: Boolean = false,
    /** True while "Show more" is waiting on a batch that is not ready yet. */
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
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

    /**
     * The download queue, straight from WorkManager.
     *
     * No mirror of it is kept in Room. WorkManager already survives process
     * death and reboots, and a second copy of the same list is a second thing
     * that can be wrong -- so this reads its state rather than tracking it.
     */
    val downloads: StateFlow<List<DownloadStatus>> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(DownloadWorker.NAME)
            .map { infos ->
                infos.map { info ->
                    val request = DownloadWorker.requestOf(info)
                    DownloadStatus(
                        id = info.id.toString(),
                        title = request?.title ?: "Download",
                        artist = request?.artist.orEmpty(),
                        request = request,
                        state = info.state,
                        // Absent until the worker reports any, which for a
                        // queued job is never.
                        progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var localJob: Job? = null
    private var remoteJob: Job? = null

    fun onQueryChanged(query: String) {
        // Set here rather than after the debounce. Waiting meant the first
        // half-second showed "No results" and then a spinner, which reads as a
        // failed search that changed its mind.
        _state.update { it.copy(query = query, searchingYoutube = query.isNotBlank()) }

        localJob?.cancel()
        remoteJob?.cancel()

        prefetchJob?.cancel()
        remaining = emptyList()
        prefetched = null

        if (query.isBlank()) {
            _state.update {
                it.copy(
                    library = emptyList(),
                    youtube = emptyList(),
                    searchingYoutube = false,
                    loadingMore = false,
                    hasMore = false,
                )
            }
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
            _state.update { it.copy(youtube = emptyList()) }

            val ids = youtube.searchIds(query)
            val failure = ids.exceptionOrNull()
            if (failure != null) {
                _state.update {
                    // The real message, not a polite summary of it. yt-dlp says
                    // exactly what went wrong -- a missing binary reads very
                    // differently from a blocked request -- and throwing that
                    // away leaves nothing to act on.
                    it.copy(searchingYoutube = false, message = failure.readable())
                }
                return@launch
            }

            remaining = ids.getOrDefault(emptyList())
            prefetched = null
            showNextBatch(firstPage = true)
        }
    }

    // ---- batching -----------------------------------------------------------
    //
    // Ids are cheap and arrive all at once; turning one into a row costs a real
    // extraction. So a page is fetched, shown, and then the FOLLOWING page is
    // fetched immediately in the background -- by the time "Show more" is
    // pressed the answer is usually already here, and the wait lands while the
    // person is still reading rather than after they ask.

    private var remaining: List<String> = emptyList()
    private var prefetched: List<YoutubeResult>? = null
    private var prefetchJob: Job? = null

    fun showMore() = viewModelScope.launch { showNextBatch(firstPage = false) }

    private suspend fun showNextBatch(firstPage: Boolean) {
        val ready = prefetched
        prefetched = null

        val batch = if (ready != null) {
            ready
        } else {
            // Nothing waiting: either this is the first page, or the person
            // asked faster than the network answered.
            _state.update { it.copy(searchingYoutube = firstPage, loadingMore = !firstPage) }
            val ids = takeBatch()
            youtube.enrich(ids).getOrDefault(emptyList())
        }

        _state.update {
            it.copy(
                youtube = it.youtube + batch,
                searchingYoutube = false,
                loadingMore = false,
                hasMore = remaining.isNotEmpty(),
            )
        }

        prefetchJob?.cancel()
        if (remaining.isNotEmpty()) {
            prefetchJob = viewModelScope.launch {
                val ids = takeBatch()
                prefetched = youtube.enrich(ids).getOrDefault(emptyList())
                _state.update { it.copy(hasMore = it.hasMore || prefetched?.isNotEmpty() == true) }
            }
        }
    }

    private fun takeBatch(): List<String> {
        val batch = remaining.take(BATCH)
        remaining = remaining.drop(BATCH)
        return batch
    }

    /**
     * Queues a download with the best guess at where it belongs.
     *
     * yt-dlp gives a title and an artist and nothing resembling an album, so
     * singles go to a folder of that name. The review sheet is where this gets
     * corrected before it is queued.
     */
    fun download(result: YoutubeResult) {
        DownloadWorker.enqueue(
            app,
            DownloadRequest(
                url = result.url,
                title = result.title,
                artist = result.artist.ifBlank { "Unknown Artist" },
                // Lands in Artist/Album beside everything else. "Singles" only
                // when YouTube genuinely had no album -- every existing track
                // in the library sits inside an album folder, and a loose file
                // in the artist folder would be the odd one out.
                album = result.album?.ifBlank { null } ?: "Singles",
            ),
        )
        _state.update { it.copy(message = "Queued ${result.title}") }
    }

    /**
     * Re-runs the search scoped to this track's album.
     *
     * A YouTube Music album has no id we can address from a video extraction,
     * so this asks for it by name and artist -- which is what someone typing it
     * themselves would do, and it lands the whole tracklist in the same list.
     */
    fun viewAlbum(result: YoutubeResult) {
        val album = result.album ?: return
        onQueryChanged(listOf(album, result.artist).filter { it.isNotBlank() }.joinToString(" "))
    }

    /** yt-dlp goes stale and quietly stops returning results when it does. */
    fun updateYtDlp() = viewModelScope.launch {
        _state.update { it.copy(message = "Updating yt-dlp…") }
        val result = youtube.update()
        _state.update {
            it.copy(
                message = result.exceptionOrNull()?.readable() ?: "yt-dlp updated",
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Clears finished rows only; anything still queued or running stays. */
    fun clearFinishedDownloads() {
        WorkManager.getInstance(app).pruneWork()
    }

    /** Re-queues a failed download from the request stored on its tag. */
    fun retry(download: DownloadStatus) {
        val request = download.request ?: return
        DownloadWorker.enqueue(app, request)
        _state.update { it.copy(message = "Retrying ${request.title}") }
    }

    /**
     * yt-dlp's own errors are several lines of Python traceback. The last
     * non-blank line is the part that says what happened; the rest is where.
     */
    private fun Throwable.readable(): String {
        // Walks the chain: the useful sentence is usually on a cause, and the
        // outer wrapper often has no message at all.
        val detail = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .flatMap { it.lines() }
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        // Never the class name. Under R8 that is a renamed nothing -- "w5.e"
        // -- which reads like a real error and tells you less than silence.
        return detail.take(300).ifBlank { "yt-dlp failed without saying why" }
    }

    private companion object {
        const val LOCAL_DEBOUNCE_MS = 150L
        const val REMOTE_DEBOUNCE_MS = 600L
        const val LIMIT = 60

        /** A screenful, and a batch small enough to arrive while it is read. */
        const val BATCH = 12
    }
}
