package app.roam.feature.downloader

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.SourceType
import app.roam.data.catalog.sync.SyncWorker
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import javax.inject.Provider

/**
 * Fetch a track, put it where the library expects it, then re-scan.
 *
 * Nothing is written into Room directly. The file lands on Drive in
 * Artist/Album/ and the ordinary sync picks it up, which means a downloaded
 * track is indistinguishable from one that was always there -- same
 * content-derived ids, same tag pass, same artwork rules. Writing a row here
 * as well would mean two paths into the catalogue that could disagree.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val youtube: YoutubeSource,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val artist = inputData.getString(KEY_ARTIST)?.sanitised().orEmpty().ifBlank { "Unknown Artist" }
        val album = inputData.getString(KEY_ALBUM)?.sanitised().orEmpty().ifBlank { "Singles" }
        val title = inputData.getString(KEY_TITLE)?.sanitised().orEmpty().ifBlank { "Unknown" }
        val trackNo = inputData.getInt(KEY_TRACK_NO, 0)

        val root = settings.settings.first().driveFolderId ?: return Result.failure()
        val provider = providers[SourceType.DRIVE]?.get() ?: return Result.failure()

        val staging = applicationContext.cacheDir.resolve("downloads")
        val file = youtube.download(url, staging) { progress ->
            setProgressAsync(workDataOf(KEY_PROGRESS to progress))
        }.getOrElse {
            // Retried rather than failed: the usual cause is a dead connection
            // part way through, and WorkManager already knows how to wait.
            return Result.retry()
        }

        return try {
            val name = buildString {
                if (trackNo > 0) append("%02d ".format(trackNo))
                append(title)
                append(" - ")
                append(artist)
                append('.')
                append(file.extension.ifBlank { "m4a" })
            }

            // create = true here, unlike the artwork passes: a download is
            // explicitly asking for a new album, so making the folder is the
            // point rather than an accident.
            provider.write(root, listOf(artist, album), name, file)

            // The catalogue learns about it the same way it learns about
            // anything else.
            SyncWorker.enqueue(applicationContext, root)
            Result.success(workDataOf(KEY_SAVED_AS to name))
        } catch (e: Exception) {
            Result.retry()
        } finally {
            file.delete()
        }
    }

    /**
     * Drive tolerates almost anything in a name, but a slash would silently
     * become a folder boundary and the rest would go somewhere unintended.
     */
    private fun String.sanitised(): String =
        replace(Regex("""[/\\:*?"<>|]"""), "-").trim().take(120)

    companion object {
        const val KEY_URL = "url"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_TRACK_NO = "track_no"
        const val KEY_PROGRESS = "progress"
        const val KEY_SAVED_AS = "saved_as"

        /**
         * The whole request rides along as a tag.
         *
         * WorkInfo exposes state, progress, tags and output -- but NOT the
         * input data it was given. So a failed job cannot be retried from its
         * WorkInfo alone, and a queued one cannot even say what it is for.
         * Encoding the request into a tag is what makes both possible without
         * keeping a parallel copy of the queue in Room.
         */
        const val TAG_REQUEST = "req:"
        private const val SEP = "\u001F"

        fun requestOf(info: androidx.work.WorkInfo): DownloadRequest? {
            val raw = info.tags.firstOrNull { it.startsWith(TAG_REQUEST) }
                ?.removePrefix(TAG_REQUEST)
                ?: return null
            val parts = raw.split(SEP)
            if (parts.size < 4) return null
            return DownloadRequest(
                url = parts[0],
                title = parts[1],
                artist = parts[2],
                album = parts[3],
                trackNo = parts.getOrNull(4)?.toIntOrNull(),
            )
        }

        fun enqueue(ctx: Context, request: DownloadRequest) {
            // A unit separator, because titles legitimately contain every
            // punctuation mark anyone would reach for as a delimiter.
            val encoded = listOf(
                request.url, request.title, request.artist, request.album,
                request.trackNo?.toString().orEmpty(),
            ).joinToString(SEP)

            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag("$TAG_REQUEST$encoded")
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URL, request.url)
                        .putString(KEY_TITLE, request.title)
                        .putString(KEY_ARTIST, request.artist)
                        .putString(KEY_ALBUM, request.album)
                        .putInt(KEY_TRACK_NO, request.trackNo ?: 0)
                        .build()
                )
                .build()

            // APPEND, not KEEP: queueing a second track must not be mistaken
            // for a duplicate of the first, and two large downloads at once on
            // a phone connection is worse than one after the other.
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }

        const val NAME = "roam_download"
    }
}

/** What the review sheet decided a download should become. */
data class DownloadRequest(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNo: Int? = null,
)
