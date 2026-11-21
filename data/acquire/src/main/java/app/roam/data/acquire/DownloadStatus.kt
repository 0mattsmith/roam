package app.roam.data.acquire

import androidx.work.WorkInfo

/**
 * One row of the Adding list: a job, read straight from WorkManager.
 *
 * No mirror of the list is kept in Room. WorkManager already survives process
 * death and reboots, and a second copy of the same list is a second thing that
 * can be wrong -- so this reads its state rather than tracking it.
 */
data class DownloadStatus(
    val id: String,
    val title: String,
    val artist: String,
    /** Null only if the tag could not be decoded; without it, retry is impossible. */
    val request: DownloadRequest?,
    val state: WorkInfo.State,
    val progress: Float,
    /**
     * Why it failed, when the worker knew. A refused duration match is not a
     * network problem and retrying will not fix it, so saying so is the whole
     * difference between a useful row and a mysterious one.
     */
    val error: String? = null,
) {
    val finished: Boolean get() = state.isFinished
    val running: Boolean get() = state == WorkInfo.State.RUNNING
    val failed: Boolean
        get() = state == WorkInfo.State.FAILED || state == WorkInfo.State.CANCELLED
    val succeeded: Boolean get() = state == WorkInfo.State.SUCCEEDED

    companion object {
        /** A WorkManager record as a row. */
        fun of(info: WorkInfo): DownloadStatus {
            val request = DownloadWorker.requestOf(info)
            return DownloadStatus(
                id = info.id.toString(),
                title = request?.title ?: "Track",
                artist = request?.artist.orEmpty(),
                request = request,
                state = info.state,
                // Absent until the worker reports any, which for a queued job
                // is never.
                progress = info.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                error = info.outputData.getString(DownloadWorker.KEY_ERROR),
            )
        }
    }
}
