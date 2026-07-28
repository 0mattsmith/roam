package app.roam.data.catalog.tags

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.database.AlbumDao
import app.roam.core.database.ArtistDao
import app.roam.core.database.TrackDao
import app.roam.core.model.ArtworkSource
import app.roam.core.model.Ids
import app.roam.core.model.SourceType
import app.roam.core.model.TagState
import app.roam.data.catalog.artwork.ArtworkStore
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import javax.inject.Provider

/**
 * Reads real tags and embedded artwork, one track at a time.
 *
 * Kept separate from the crawl on purpose. Discovery is cheap -- one files.list
 * per folder -- while this costs a ranged HTTP read per track. Running it as a
 * second pass means the library is browsable within seconds of a sync, and
 * titles and covers fill in behind it.
 *
 * The entire design rests on never downloading a whole file: ID3v2 sits at the
 * head of an MP3 and FLAC's comment and picture blocks likewise, so the first
 * megabyte is enough. A 5,000-track library at 8 MB each would otherwise be a
 * 40 GB download.
 */
@HiltWorker
class TagWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
    private val artwork: ArtworkStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val provider = providers[SourceType.DRIVE]?.get()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No Drive provider bound"))

        var done = 0
        var failed = 0

        while (true) {
            val pending = tracks.pendingTags(provider.sourceId, BATCH)
            if (pending.isEmpty()) break

            val gate = Semaphore(CONCURRENCY)
            val results = coroutineScope {
                pending.map { row ->
                    async(Dispatchers.IO) {
                        gate.withPermit { readTags(provider, row.remoteId, row.name) }
                            ?.let { row to it }
                    }
                }.awaitAll()
            }

            for (result in results) {
                if (result == null) { failed++; continue }
                val (row, tags) = result
                applyTags(row.id, row.albumId, tags)
                done++
            }

            setProgress(workDataOf(KEY_DONE to done, KEY_FAILED to failed))

            // Mark everything in this batch as attempted, successful or not, so
            // a file with no tags is not retried forever.
            tracks.markTagsAttempted(pending.map { it.id })
        }

        albums.recomputeRollups()
        artists.recomputeRollups()
        return Result.success(workDataOf(KEY_DONE to done, KEY_FAILED to failed))
    }

    private suspend fun readTags(
        provider: SourceProvider,
        remoteId: String,
        name: String,
    ): AudioTags? = runCatching {
        val head = provider.readRange(remoteId, 0, HEAD_BYTES)
        when (name.substringAfterLast('.', "").lowercase()) {
            "flac" -> FlacParser.parse(head)
            "mp3" -> Id3Parser.parse(head)
            // Many m4a/ogg files still carry an ID3 block; try it and fall back
            // to whatever the path told us. TODO(phase3): MP4 atom parsing,
            // including the moov-at-end tail read.
            else -> Id3Parser.parse(head) ?: FlacParser.parse(head)
        }
    }.getOrNull()

    private suspend fun applyTags(trackId: Long, oldAlbumId: Long, tags: AudioTags) =
        withContext(Dispatchers.IO) {
            val artworkId = tags.artwork?.let {
                runCatching { artwork.put(it, ArtworkSource.EMBEDDED) }.getOrNull()
            }

            tracks.updateTags(
                id = trackId,
                title = tags.title,
                year = tags.year,
                genre = tags.genre,
                trackNo = tags.trackNo,
                trackTotal = tags.trackTotal,
                discNo = tags.discNo,
                discTotal = tags.discTotal,
                artworkId = artworkId,
                tagState = TagState.OK,
            )

            // One cover per album is enough: the first track to yield artwork
            // supplies it, and the rest inherit.
            if (artworkId != null) albums.setArtworkIfMissing(oldAlbumId, artworkId)
        }

    companion object {
        const val NAME = "roam_tag_pass"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"

        /** Covers ID3v2 plus a typical 200-600 KB embedded cover. */
        const val HEAD_BYTES = 1L * 1024 * 1024
        const val BATCH = 60
        /** Ranged reads are far heavier than folder listings, so fan out less. */
        const val CONCURRENCY = 4

        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<TagWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
