package app.roam.data.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.model.SourceType
import app.roam.data.catalog.artwork.ArtistPhotoWorker
import app.roam.data.catalog.tags.TagWorker
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import app.roam.data.source.RemoteFile
import kotlinx.coroutines.flow.catch
import javax.inject.Provider

/**
 * Discover -> Diff -> ExtractTags -> ResolveArtwork -> Reconcile -> Index
 *
 * Phase 1 implements Discover only: crawl the tree and report how many audio
 * files are there. Getting the crawl right and observable first means the tag
 * pass has something trustworthy to build on.
 *
 * Reconcile, when it lands, MUST NOT write loved / playCount / lastPlayedAt.
 * Sync owns file-derived columns only.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
    private val catalog: CatalogWriter,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val typeName = inputData.getString(KEY_SOURCE_TYPE) ?: SourceType.DRIVE.name
        val root = inputData.getString(KEY_ROOT_ID) ?: return Result.failure(
            errorData("No root folder configured")
        )

        val provider = providers[SourceType.valueOf(typeName)]?.get()
            ?: return Result.failure(errorData("No provider for $typeName"))

        // Revisions of everything already stored. An unchanged file costs one
        // map lookup, which is what makes a re-sync near-instant compared with
        // the first crawl.
        val known = catalog.existingRevisions(provider.sourceId)

        var found = 0
        var written = 0
        val seen = HashSet<String>(known.size.coerceAtLeast(64))
        val batch = ArrayList<RemoteFile>(BATCH)
        var failure: Throwable? = null

        suspend fun flush() {
            if (batch.isEmpty()) return
            written += catalog.writeBatch(provider.sourceId, batch, known)
            batch.clear()
        }

        provider.listAll(root)
            .catch { failure = it }
            .collect { file ->
                found++
                seen += file.remoteId
                batch += file
                if (batch.size >= BATCH) {
                    flush()
                    // Batched rather than per-file: at 25 updates a second the
                    // IPC costs more than the work being reported.
                    setProgress(workDataOf(KEY_FOUND to found))
                }
            }

        // Keep what the crawl did manage to read. Discarding a partial result
        // means one flaky request throws away the whole pass, and the user sees
        // an unchanged count with no idea why.
        flush()

        failure?.let { cause ->
            // Deliberately NOT calling finish(): it deletes rows the crawl did
            // not see, and a crawl that stopped early did not see plenty of
            // real tracks. Reconciling against a partial listing would wipe
            // most of the library.
            val reason = cause.message ?: cause::class.simpleName ?: "Crawl failed"
            return Result.failure(
                workDataOf(
                    KEY_FOUND to found,
                    KEY_WRITTEN to written,
                    KEY_ERROR to "Stopped after $found files ($written added): $reason",
                )
            )
        }

        catalog.finish(provider.sourceId, seen, known)

        // Second pass: real tags and embedded covers. Separate job so the
        // catalogue is browsable now rather than after every ranged read.
        TagWorker.enqueue(applicationContext)
        ArtistPhotoWorker.enqueue(applicationContext)

        return Result.success(workDataOf(KEY_FOUND to found, KEY_WRITTEN to written))
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val NAME_ONESHOT = "roam_sync_now"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_ROOT_ID = "root_id"
        const val KEY_FOUND = "found"
        const val KEY_WRITTEN = "written"
        /** Rows per transaction. Large enough to amortise, small enough to stream. */
        const val BATCH = 250
        const val KEY_ERROR = "error"

        fun enqueue(ctx: Context, rootId: String, type: SourceType = SourceType.DRIVE) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_SOURCE_TYPE to type.name, KEY_ROOT_ID to rootId))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME_ONESHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
