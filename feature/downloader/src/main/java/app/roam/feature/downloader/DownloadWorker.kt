package app.roam.feature.downloader

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.SourceType
import app.roam.data.catalog.artwork.ArtworkFiles
import app.roam.data.catalog.sync.SyncWorker
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    private val http = OkHttpClient()

    override suspend fun doWork(): Result {
        // Every failure carries a reason. "Failed" on its own is unactionable,
        // and a queue full of it is impossible to tell apart from a bug.
        val url = inputData.getString(KEY_URL)
            ?: return Result.failure(reason("Nothing to fetch - the request was empty"))
        // The artist and album are read into the placement below rather than
        // here: they name a FOLDER, and FolderNames decides that now. They
        // stopped being part of the file name with "01 Supersonic.m4a".
        val title = inputData.getString(KEY_TITLE)?.sanitised().orEmpty().ifBlank { "Unknown" }
        val trackNo = inputData.getInt(KEY_TRACK_NO, 0)
        val expectedMs = inputData.getLong(KEY_DURATION_MS, 0L)
        val coverUrl = inputData.getString(KEY_COVER_URL)
        val placement = AlbumPlacement(
            artist = inputData.getString(KEY_ARTIST).orEmpty(),
            album = inputData.getString(KEY_ALBUM).orEmpty(),
            albumArtist = inputData.getString(KEY_ALBUM_ARTIST).orEmpty(),
            year = inputData.getInt(KEY_YEAR, 0).takeIf { it > 0 },
            compilation = inputData.getBoolean(KEY_COMPILATION, false),
        )

        val root = settings.settings.first().driveFolderId
            ?: return Result.failure(reason("No music folder chosen - set one in Settings"))
        val provider = providers[SourceType.DRIVE]?.get()
            ?: return Result.failure(reason("Google Drive is not connected"))

        // A search is resolved to one recording BEFORE anything is fetched, so
        // the wrong take is never written to Drive in the first place. Failing
        // is deliberate -- retrying would ask the same question and get the
        // same answer, and the queue entry stays visible saying what happened.
        val target = if (url.startsWith(SEARCH_PREFIX) && expectedMs > 0) {
            val picked = youtube.resolveByDuration(url.removePrefix(SEARCH_PREFIX), expectedMs)
                .getOrElse { cause ->
                    // The search itself broke, so this may well work next time.
                    return retryOrFail(cause.message ?: "Search failed")
                }
                ?: return Result.failure(
                    // Not a retry: asking again returns the same recordings.
                    reason("No result within 15s of ${format(expectedMs)}")
                )
            "https://music.youtube.com/watch?v=$picked"
        } else url

        // Per-job, because the queue can be appended to while one is running
        // and two downloads sharing a directory would each pick up the other's
        // file. `id` is WorkManager's, so it is unique and stable across a retry.
        val staging = applicationContext.cacheDir.resolve("downloads/$id")
        val file = youtube.download(target, staging) { progress ->
            setProgressAsync(workDataOf(KEY_PROGRESS to progress))
        }.getOrElse { cause ->
            val why = cause.message ?: "Could not fetch the audio"

            // A 403 is not a network problem, it is a STALE EXTRACTOR. YouTube
            // changes how it signs stream URLs every few weeks and an old
            // yt-dlp keeps asking the old way, so every download in the queue
            // fails identically and retrying achieves nothing at all.
            //
            // Updating is the actual fix, and it is the one thing the app can
            // do for itself. Once only, on the first attempt: if a fresh binary
            // still gets a 403 then something else is wrong and hammering the
            // updater will not find it.
            if (runAttemptCount == 0 && why.looksStale()) {
                youtube.update()
                return Result.retry()
            }

            // Retried rather than failed while there is reason to hope -- the
            // usual cause is a dead connection part way through. But NOT
            // forever: WorkManager's backoff doubles to five hours, so an
            // uncapped retry looks exactly like a job that is quietly waiting
            // for something, and there is no way to tell the difference.
            return retryOrFail(
                if (why.looksStale()) "$why - yt-dlp may need updating" else why
            )
        }

        return try {
            // The performer rides in the NAME only on a compilation, where the
            // folder is Various Artists and cannot say it. On a normal album it
            // would repeat the parent folder and nothing more.
            val name = TrackFileName.of(
                trackNo = trackNo,
                title = title,
                extension = file.extension,
                artist = inputData.getString(KEY_ARTIST).takeIf { placement.compilation },
            )

            // Asked rather than assumed. An album may already live under a
            // different spelling -- "100 Hits: 80s Pop" beside "100 Hits - 80s
            // Pop" is one record in two half-folders that nothing will merge --
            // and a compilation files under Various Artists rather than growing
            // an artist folder per guest.
            val segments = placement.folderSegments(provider, root)

            // create = true here, unlike the artwork passes: a download is
            // explicitly asking for a new album, so making the folder is the
            // point rather than an accident.
            provider.write(root, segments, name, file)

            // Cover art goes in the FOLDER, not into every track.
            //
            // cover.jpg beside the music is the convention Roam already reads
            // (invariant 6c) and the one its own cover editor writes, so a
            // downloaded album ends up indistinguishable from one that was
            // always there. It is also one upload per album instead of one per
            // track, and it survives a re-tag -- an embedded picture would have
            // to be rewritten into every file to change.
            //
            // Never overwrites: the first track to land supplies the cover and
            // the rest find it already there. A cover the user put there by
            // hand always wins (invariant 6d).
            coverUrl?.let { runCatching { seedCover(provider, root, segments, it) } }

            // The catalogue learns about it the same way it learns about
            // anything else.
            SyncWorker.enqueue(applicationContext, root)
            Result.success(workDataOf(KEY_SAVED_AS to name))
        } catch (e: Exception) {
            retryOrFail(e.message ?: "Could not save to Drive")
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Fails with the reason attached, or retries while attempts remain.
     *
     * The message is carried on the FAILURE either way, so a job that gave up
     * after six tries still says what went wrong on the last one rather than
     * going quiet at exactly the point someone starts wondering.
     */
    private fun retryOrFail(why: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(reason(why))

    private fun reason(why: String): Data = workDataOf(KEY_ERROR to why)

    /**
     * Whether the failure smells like yt-dlp having fallen behind YouTube.
     *
     * 403 is the usual face of it. The signature and nsig wording shows up when
     * the extractor got far enough to try descrambling and could not, which is
     * the same illness one stage earlier.
     */
    private fun String.looksStale(): Boolean {
        val text = lowercase()
        return "403" in text ||
            "forbidden" in text ||
            "nsig" in text ||
            "signature" in text ||
            "unable to download video data" in text
    }

    private fun format(ms: Long): String =
        "%d:%02d".format(ms / 60_000, (ms % 60_000) / 1000)

    /**
     * Writes cover.jpg into the album folder, if and only if the folder has no
     * album art at all yet.
     *
     * The folder is resolved WITHOUT create: the track upload just made it, so
     * a missing folder here means something else went wrong and inventing one
     * would leave a cover sitting on its own with no music beside it.
     */
    private suspend fun seedCover(
        provider: SourceProvider,
        root: String,
        /** The folder the track was just written to, not a fresh guess at it. */
        segments: List<String>,
        coverUrl: String,
    ) {
        val folder = provider.resolveFolder(root, segments, create = false) ?: return
        if (provider.findInFolder(folder, ArtworkFiles.ALBUM_NAMES) != null) return

        val bytes = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(coverUrl).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.bytes()
            }
        } ?: return

        val temp = applicationContext.cacheDir.resolve("covers/$id.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        try {
            provider.write(root, segments, ArtworkFiles.ALBUM_UPLOAD_NAME, temp)
        } finally {
            temp.delete()
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
        const val KEY_ERROR = "error"

        /** What the catalogue says the track should be, for the duration guard. */
        const val KEY_DURATION_MS = "duration_ms"
        const val KEY_COVER_URL = "cover_url"
        const val KEY_ALBUM_ARTIST = "album_artist"
        const val KEY_YEAR = "year"
        const val KEY_COMPILATION = "compilation"

        /** yt-dlp's "search and take the best one" form, which carries no id. */
        const val SEARCH_PREFIX = "ytsearch1:"

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
                // getOrNull throughout: a job queued by an older build has a
                // shorter tag, and it must still be listable and retryable
                // rather than vanishing from the manager.
                durationMs = parts.getOrNull(5)?.toLongOrNull(),
                coverUrl = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
            )
        }

        /**
         * @param wifiOnly wait for an unmetered network before starting.
         */
        fun enqueue(ctx: Context, request: DownloadRequest, wifiOnly: Boolean) {
            // A unit separator, because titles legitimately contain every
            // punctuation mark anyone would reach for as a delimiter.
            val encoded = listOf(
                request.url, request.title, request.artist, request.album,
                request.trackNo?.toString().orEmpty(),
                request.durationMs?.toString().orEmpty(),
                request.coverUrl.orEmpty(),
            ).joinToString(SEP)

            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .addTag(TAG_ALL)
                .addTag("$TAG_REQUEST$encoded")
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                        )
                        .build()
                )
                .setInputData(
                    Data.Builder()
                        .putString(KEY_URL, request.url)
                        .putString(KEY_TITLE, request.title)
                        .putString(KEY_ARTIST, request.artist)
                        .putString(KEY_ALBUM, request.album)
                        .putInt(KEY_TRACK_NO, request.trackNo ?: 0)
                        .putLong(KEY_DURATION_MS, request.durationMs ?: 0L)
                        .putString(KEY_COVER_URL, request.coverUrl)
                        .putString(KEY_ALBUM_ARTIST, request.albumArtist)
                        .putInt(KEY_YEAR, request.year ?: 0)
                        .putBoolean(KEY_COMPILATION, request.compilation)
                        .build()
                )
                .build()

            // ONE UNIQUE JOB PER TRACK, not a single appended chain.
            //
            // A chain looked right -- downloads run one at a time on a phone
            // connection -- but it made every track hostage to the one in
            // front. A job that keeps retrying leaves everything behind it
            // BLOCKED and apparently waiting for nothing, and APPEND_OR_REPLACE
            // resolves a failed chain by DELETING the queue behind it, which is
            // worse. Independent jobs cannot do either.
            //
            // KEEP also makes queueing the same track twice a no-op, since the
            // name is derived from the request itself.
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(nameFor(request), ExistingWorkPolicy.KEEP, work)
        }

        private fun nameFor(request: DownloadRequest): String =
            "$NAME:${request.url.hashCode()}:${request.title.hashCode()}"

        const val NAME = "roam_download"

        /** Every download carries this, so the manager can list them all. */
        const val TAG_ALL = "roam_download_all"

        /** Two failures are a bad connection; six is something that will not work. */
        const val MAX_ATTEMPTS = 6
    }
}

/** What the review sheet decided a download should become. */
data class DownloadRequest(
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val trackNo: Int? = null,
    /** From the catalogue, so a search result of the wrong length is refused. */
    val durationMs: Long? = null,
    /** Seeds cover.jpg in the album folder; not embedded in the track. */
    val coverUrl: String? = null,
    /**
     * Where this belongs, as answered before the download started.
     *
     * Carried rather than derived: by the time the worker runs, the search
     * result is all it has, and a channel name is not an album artist.
     */
    val albumArtist: String = "",
    val year: Int? = null,
    val compilation: Boolean = false,
) {
    /** The five answers, back in the shape that decides a folder. */
    val placement: AlbumPlacement
        get() = AlbumPlacement(
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            compilation = compilation,
        )
}
