package app.roam.data.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Discover -> Diff -> ExtractTags -> ResolveArtwork -> Reconcile -> Index
 *
 * Checkpoints its position so killing the app mid-scan does not restart a
 * 10,000-file crawl. Runs as a foreground worker with an n/total notification.
 *
 * Reconcile MUST NOT write loved / playCount / lastPlayedAt. Sync owns
 * file-derived columns only.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // TODO(phase1): full crawl. TODO(phase3): switch to changes.list delta.
        return Result.success()
    }

    companion object {
        const val NAME_PERIODIC = "roam_sync_periodic"
        const val NAME_ONESHOT = "roam_sync_now"
    }
}
