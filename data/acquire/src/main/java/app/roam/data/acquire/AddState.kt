package app.roam.data.acquire

import androidx.work.WorkInfo
import app.roam.core.model.Ids

/**
 * What a row of music you do not have says about itself: docs/SEARCH.md's
 * table, as data.
 *
 * Derived and never stored. Every input is something WorkManager or Room
 * already keeps, which is why a row still says Adding after the screen was
 * left and the app was killed -- and why there is no second copy to be wrong.
 */
sealed interface AddState {
    /** Nothing asked for yet: the +. */
    data object NotAdded : AddState

    /** Waiting its turn, or for wifi, with nothing moved yet. */
    data object Queued : AddState

    /** On its way. Zero until yt-dlp reports any bytes, which it does late. */
    data class Adding(val progress: Float) : AddState

    /** On Drive, waiting for the sync that makes it playable. */
    data object Syncing : AddState

    /** Playable, as [trackId] -- the row that sync made of it. */
    data class Ready(val trackId: Long) : AddState

    /**
     * On Drive and synced, and still not matched to a row: its tags named it
     * something else. Never a ring that spins forever.
     */
    data object NotFound : AddState

    /** Needs you. Every failure is kept, so Retry and Clear take them all. */
    data class Failed(val jobs: List<DownloadStatus>) : AddState {
        /** A refused duration match is a verdict on the recording; say which. */
        val reason: String? get() = jobs.firstNotNullOfOrNull { it.error }
    }
}

/**
 * Everything a row needs to say what state it is in: the jobs, which of them
 * sync has turned into library rows, and whether a sync is still to come.
 *
 * Rebuilt whenever one of those moves, and indexed when it is, so asking it
 * from a row is a map lookup rather than a walk of every job on every frame.
 */
class AddLedger(
    val jobs: List<DownloadStatus>,
    /**
     * The URL a job was queued under, to the library row it became. Kept for
     * as long as the screen is, rather than for as long as the job is: "Clear
     * finished" forgets the job, and a row that had turned into Play must not
     * turn back into a + for music you now own.
     */
    private val resolved: Map<String, Long>,
    /** The same, by album, for a release row. */
    private val resolvedAlbums: Map<String, Long>,
    private val syncing: Boolean,
) {
    private val byUrl: Map<String?, List<DownloadStatus>> = jobs.groupBy { it.request?.url }
    private val byAlbum: Map<String?, List<DownloadStatus>> =
        jobs.groupBy { job -> job.request?.let(::albumKey) }

    /** The jobs behind a row, for the tick's lookup and the failure's Retry. */
    fun jobsForUrl(url: String): List<DownloadStatus> = byUrl[url].orEmpty()

    fun jobsForAlbum(title: String, filingArtist: String): List<DownloadStatus> =
        byAlbum[albumKey(title, filingArtist)].orEmpty()

    /** One song, or one track of a tracklist: the jobs queued under its URL. */
    fun forUrl(url: String): AddState {
        val state = stateOf(byUrl[url].orEmpty(), syncing) { resolved[it] }
        if (state != AddState.NotAdded) return state
        return resolved[url]?.let { AddState.Ready(it) } ?: state
    }

    /**
     * A whole release: every job filed under its title and album artist,
     * whichever way it was queued -- a track at a time from its tracklist or
     * all the missing ones at once.
     */
    fun forAlbum(title: String, filingArtist: String): AddState {
        val key = albumKey(title, filingArtist)
        val state = stateOf(byAlbum[key].orEmpty(), syncing) { resolved[it] }
        if (state != AddState.NotAdded) return state
        return resolvedAlbums[key]?.let { AddState.Ready(it) } ?: state
    }

    companion object {
        val EMPTY: AddLedger = AddLedger(emptyList(), emptyMap(), emptyMap(), syncing = false)

        /**
         * What files a job and a release under the same album. Normalised,
         * like every other match against the library, so a catalogue that
         * capitalises a title differently still finds its own jobs.
         */
        fun albumKey(title: String, filingArtist: String): String =
            Ids.normalise(title) + "|" + Ids.normalise(filingArtist)

        /**
         * A job's album. Falls back to the track's own credit only for a job
         * queued before the album artist rode along on its tag.
         */
        fun albumKey(request: DownloadRequest): String =
            albumKey(request.album, request.albumArtist.ifBlank { request.artist })

        /**
         * One state for a set of jobs: one job for a song, every track's for
         * an album. Pure, so the rules below are tested rather than trusted.
         *
         * - A cancelled job is somebody changing their mind, not a failure, so
         *   it reads as never having been asked.
         * - Anything still in flight beats any history. A failed track whose
         *   retry is queued is on its way again, and an album with one bad
         *   track is still adding the rest -- amber until all of it settles.
         * - Then a failure, because red is what needs you.
         * - Then success, which is Play only once sync has made a row of it.
         *   WorkManager drops a failed job's record when the same request is
         *   queued again, so a retry that worked leaves no stale failure here.
         */
        internal fun stateOf(
            jobs: List<DownloadStatus>,
            syncing: Boolean,
            resolve: (url: String) -> Long?,
        ): AddState {
            val live = jobs.filterNot { it.state == WorkInfo.State.CANCELLED }
            if (live.isEmpty()) return AddState.NotAdded

            if (live.any { !it.finished }) {
                val done = live.count { it.succeeded }
                val running = live.filter { it.running }
                // Dashed until something has actually moved. An album that
                // has landed five of twelve says so with the ring, even while
                // the next track waits for wifi.
                if (running.isEmpty() && done == 0) return AddState.Queued
                val moved = done + running.sumOf { it.progress.toDouble() }
                return AddState.Adding((moved / live.size).toFloat().coerceIn(0f, 1f))
            }

            val failed = live.filter { it.state == WorkInfo.State.FAILED }
            if (failed.isNotEmpty()) return AddState.Failed(failed)

            // Every one landed. The first track of an album is the one that
            // stands for it, since Play starts there.
            val ready = live
                .sortedBy { it.request?.trackNo ?: Int.MAX_VALUE }
                .firstNotNullOfOrNull { job -> job.request?.url?.let(resolve) }
            return when {
                ready != null -> AddState.Ready(ready)
                syncing -> AddState.Syncing
                else -> AddState.NotFound
            }
        }
    }
}
