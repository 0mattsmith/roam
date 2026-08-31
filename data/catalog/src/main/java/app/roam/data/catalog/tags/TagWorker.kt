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
import androidx.room.withTransaction
import app.roam.core.database.RoamDatabase
import app.roam.core.database.AlbumDao
import app.roam.core.database.ArtistDao
import app.roam.core.database.TrackDao
import app.roam.core.model.ArtworkSource
import app.roam.core.model.Ids
import app.roam.core.model.SourceType
import app.roam.core.model.TagState
import app.roam.data.catalog.artwork.ArtworkStore
import app.roam.data.catalog.sync.ParsedTags
import app.roam.data.catalog.sync.TagExtractor
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
    private val tagExtractor: TagExtractor,
    private val db: RoamDatabase,
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
                        gate.withPermit {
                            tagExtractor.readTags(provider, row.remoteId, row.sizeBytes)
                        }?.let { row to it }
                    }
                }.awaitAll()
            }

            // ONE transaction for the batch, and this is not about speed.
            //
            // Room fires an invalidation per completed write, and Paging
            // answers each one by starting a fresh load. Two writes per track
            // across a library of two thousand is four thousand invalidations
            // arriving faster than a page can load, so the list never settles
            // and shows nothing at all -- while a one-shot query like search
            // keeps working perfectly, because nothing invalidates it.
            //
            // That is the whole bug: the library going blank while the tag
            // pass runs, and staying blank because WorkManager resumes the pass
            // after the app is closed. Batched, it is one invalidation per
            // sixty tracks instead of a hundred and twenty, and Paging has time
            // to finish between them.
            //
            // The artwork write stays OUTSIDE, because it touches the disk and
            // a filesystem write has no business inside a database transaction.
            val prepared = results.filterNotNull().map { (row, tags) ->
                Triple(row, tags, artworkFor(tags))
            }

            db.withTransaction {
                for ((row, tags, artworkId) in prepared) {
                    applyTags(row.id, row.albumId, tags, artworkId)
                }
                // In the same transaction: marking a batch attempted is part of
                // the same unit of work, and a second invalidation to say so
                // would undo half the point.
                tracks.markTagsAttempted(pending.map { it.id })
            }
            done += prepared.size
            failed += results.count { it == null }

            setProgress(workDataOf(KEY_DONE to done, KEY_FAILED to failed))
        }

        albums.recomputeRollups()
        artists.recomputeRollups()
        return Result.success(workDataOf(KEY_DONE to done, KEY_FAILED to failed))
    }

    /** The disk half, done before the transaction opens. */
    private suspend fun artworkFor(tags: ParsedTags): String? = withContext(Dispatchers.IO) {
        tags.artwork?.let { runCatching { artwork.put(it, ArtworkSource.EMBEDDED) }.getOrNull() }
    }

    /**
     * The database half. Called inside the batch transaction, so it must not
     * touch the disk or the network -- both are done before it opens.
     */
    private suspend fun applyTags(
        trackId: Long,
        albumId: Long,
        tags: ParsedTags,
        artworkId: String?,
    ) {
        // Two writes, because they answer to different owners. What only the
        // file knows lands unconditionally; what a person or an album.json may
        // have corrected is refused if either of them has. One statement for
        // both meant an edited track never learned its own duration, and the
        // play threshold had nothing to measure.
        tracks.updateTagFacts(
            id = trackId,
            artworkId = artworkId,
            // Only when the container actually told us. A null leaves whatever
            // is stored alone rather than zeroing it.
            durationMs = tags.durationMs?.takeIf { it > 0 },
            tagState = TagState.OK,
        )
        tracks.updateTags(
            id = trackId,
            title = tags.title,
            year = tags.year,
            genre = tags.genre,
            trackNo = tags.trackNo,
            trackTotal = tags.trackTotal,
            discNo = tags.discNo,
            discTotal = tags.discTotal,
        )

        // One cover per album is enough: the first track to yield artwork
        // supplies it, and the rest inherit.
        if (artworkId != null) albums.setArtworkIfMissing(albumId, artworkId)
    }

    companion object {
        const val NAME = "roam_tag_pass"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_ERROR = "error"

        const val BATCH = 60
        /** Ranged reads are far heavier than folder listings, so fan out less. */
        const val CONCURRENCY = 4

        /**
         * @param wifiOnly hold until unmetered. This pass costs a 1 MB ranged read for every new track,
         * so it is one of the large transfers that setting is about -- the
         * library crawl is not.
         */
        fun enqueue(ctx: Context, wifiOnly: Boolean = true) {
            val request = OneTimeWorkRequestBuilder<TagWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
