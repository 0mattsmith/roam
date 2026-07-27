package app.roam.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Daily, unmetered. Unauthenticated GitHub API allows 60 requests/hour per IP,
 * so this is nowhere near the limit.
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val checker: UpdateChecker,
    private val installer: UpdateInstaller,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = try {
        // TODO(phase1): read settings, compare against BuildConfig.VERSION_CODE,
        //   post a notification if an update exists. NEVER auto-install.
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object { const val NAME = "roam_update_check" }
}
