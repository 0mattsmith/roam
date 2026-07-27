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
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
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
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val typeName = inputData.getString(KEY_SOURCE_TYPE) ?: SourceType.DRIVE.name
        val root = inputData.getString(KEY_ROOT_ID) ?: return Result.failure(
            errorData("No root folder configured")
        )

        val provider = providers[SourceType.valueOf(typeName)]?.get()
            ?: return Result.failure(errorData("No provider for $typeName"))

        var found = 0
        var failure: Throwable? = null

        provider.listAll(root)
            .catch { failure = it }
            .collect {
                found++
                // Cheap heartbeat. The crawl is network-bound, so updating on
                // every file would spend more time on IPC than on Drive.
                if (found % 25 == 0) setProgress(workDataOf(KEY_FOUND to found))
            }

        failure?.let { return Result.failure(errorData(it.message ?: it::class.simpleName ?: "Crawl failed")) }

        return Result.success(workDataOf(KEY_FOUND to found))
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val NAME_ONESHOT = "roam_sync_now"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_ROOT_ID = "root_id"
        const val KEY_FOUND = "found"
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
