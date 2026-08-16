package app.roam.data.catalog.metadata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.database.TrackDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Fills in lyrics across the library, or across one album.
 *
 * A background job rather than something the screen does, because the phone
 * being locked half way through a 5,000 track sweep must not abandon it. Unique
 * work, so tapping the button twice is a no-op rather than two passes racing to
 * write the same sidecar files.
 *
 * There is no way to detect a WRONG lyric automatically -- nothing to compare
 * against, since the file itself carries no words. [KEY_FORCE] is the answer to
 * that: it re-asks about tracks that were already tried, including the ones
 * that came back empty, and re-reads any sidecar someone has since dropped in.
 */
@HiltWorker
class LyricsWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val tracks: TrackDao,
    private val lyrics: LyricsRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val albumId = inputData.getLong(KEY_ALBUM_ID, 0L)

        val ids = when {
            albumId != 0L -> tracks.trackIdsForAlbum(albumId)
            force -> tracks.allTrackIds()
            else -> tracks.trackIdsWithoutLyrics()
        }

        if (ids.isEmpty()) {
            return Result.success(workDataOf(KEY_FOUND to 0, KEY_TOTAL to 0))
        }

        val result = lyrics.sweep(ids, force) { progress ->
            setProgress(
                workDataOf(
                    KEY_FOUND to progress.found,
                    KEY_DONE to progress.attempted,
                    KEY_TOTAL to ids.size,
                )
            )
        }

        return Result.success(
            workDataOf(
                KEY_FOUND to result.found,
                KEY_DONE to result.attempted,
                KEY_TOTAL to ids.size,
            )
        )
    }

    companion object {
        const val NAME = "roam_lyrics_sweep"

        const val KEY_FORCE = "force"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_FOUND = "found"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"

        /**
         * @param wifiOnly a full sweep is one request per track plus a small
         * upload each -- individually tiny, but a few megabytes across a large
         * library, so it belongs behind the same switch as the other passes.
         */
        fun enqueue(
            ctx: Context,
            wifiOnly: Boolean,
            force: Boolean = false,
            albumId: Long? = null,
        ) {
            val work = OneTimeWorkRequestBuilder<LyricsWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setInputData(
                    Data.Builder()
                        .putBoolean(KEY_FORCE, force)
                        .putLong(KEY_ALBUM_ID, albumId ?: 0L)
                        .build()
                )
                .build()

            // REPLACE rather than KEEP: asking for one album while a library
            // sweep is queued should do the album now, which is the thing being
            // looked at. The sweep is resumable by its nature -- it only ever
            // asks about tracks that still have no lyrics.
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(nameFor(albumId), ExistingWorkPolicy.REPLACE, work)
        }

        /**
         * An album sweep and a library sweep are separate jobs, so starting one
         * does not cancel the other -- they are answering different questions.
         */
        private fun nameFor(albumId: Long?): String =
            if (albumId == null) NAME else "$NAME:$albumId"
    }
}
