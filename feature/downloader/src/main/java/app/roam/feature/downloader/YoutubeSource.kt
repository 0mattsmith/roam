package app.roam.feature.downloader

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One result from a YouTube Music search, before anything has been fetched. */
data class YoutubeResult(
    val videoId: String,
    val title: String,
    val uploader: String,
    val durationSec: Int?,
    val thumbnailUrl: String?,
) {
    val url: String get() = "https://music.youtube.com/watch?v=$videoId"
}

/**
 * yt-dlp, wrapped so the rest of the app never sees it.
 *
 * Everything here is blocking and slow, so every entry point suspends on IO.
 * The library keeps global state -- one native binary, one working directory --
 * so initialisation is guarded by a mutex and done exactly once.
 */
@Singleton
class YoutubeSource @Inject constructor(private val app: Application) {

    private val initLock = Mutex()
    private var started = false

    /**
     * Unpacks the bundled binaries on first use.
     *
     * Deliberately not done at app start: it costs a second or two and writes
     * tens of megabytes, and someone who never opens the downloader should
     * never pay for it.
     */
    private suspend fun ensureStarted() = initLock.withLock {
        if (started) return@withLock
        withContext(Dispatchers.IO) {
            YoutubeDL.getInstance().init(app)
            FFmpeg.getInstance().init(app)
        }
        started = true
    }

    /**
     * YouTube's own extraction changes without warning and a stale binary
     * simply stops returning results -- the classic symptom is the downloader
     * silently finding nothing. Failure is swallowed: an update that cannot
     * reach the network must not stop a search that might still work.
     */
    suspend fun update(): Result<Unit> = runCatching {
        ensureStarted()
        // The channel argument is left to its default. Naming it would tie
        // this file to where the library currently declares UpdateChannel,
        // which has moved between releases; the default has not.
        withContext(Dispatchers.IO) { YoutubeDL.getInstance().updateYoutubeDL(app) }
        Unit
    }

    /**
     * Searches YouTube Music rather than YouTube proper.
     *
     * Music results carry a real artist and album where plain YouTube gives
     * whatever the uploader typed, and they skip the lyric videos, live
     * recordings and hour-long compilations that otherwise dominate.
     */
    suspend fun search(query: String, limit: Int = 20): Result<List<YoutubeResult>> = runCatching {
        ensureStarted()
        withContext(Dispatchers.IO) {
            val request = YoutubeDLRequest("https://music.youtube.com/search?q=${query.urlEncoded()}")
                .addOption("--flat-playlist")
                .addOption("--dump-single-json")
                .addOption("--playlist-end", limit.toString())
                .addOption("--no-warnings")

            val json = JSONObject(YoutubeDL.getInstance().execute(request).out)
            val entries = json.optJSONArray("entries") ?: return@withContext emptyList()

            (0 until entries.length()).mapNotNull { i ->
                val entry = entries.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optString("id").ifBlank { return@mapNotNull null }
                YoutubeResult(
                    videoId = id,
                    title = entry.optString("title").ifBlank { "Unknown" },
                    // Music results name the artist in `artist`; plain YouTube
                    // only ever has a channel.
                    uploader = entry.optString("artist")
                        .ifBlank { entry.optString("uploader") }
                        .ifBlank { entry.optString("channel") },
                    durationSec = entry.optDouble("duration").takeIf { !it.isNaN() }?.toInt(),
                    thumbnailUrl = entry.optString("thumbnail").ifBlank { null },
                )
            }
        }
    }

    /**
     * Downloads the best audio-only stream into [into] and returns the file.
     *
     * The stream is taken as it comes rather than transcoded. YouTube serves
     * Opus in WebM and AAC in M4A; re-encoding either to MP3 would throw away
     * quality to gain nothing, and it is the slowest thing the phone could
     * possibly do. FFmpeg is still bundled because remuxing -- moving the same
     * audio into a container that carries tags -- is sometimes needed, and
     * that is a copy, not an encode.
     */
    suspend fun download(
        url: String,
        into: File,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = runCatching {
        ensureStarted()
        withContext(Dispatchers.IO) {
            into.mkdirs()
            val request = YoutubeDLRequest(url)
                .addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                .addOption("--no-playlist")
                .addOption("--no-warnings")
                // Names the file by id, so the result can be found afterwards
                // without parsing yt-dlp's chatter for a path.
                .addOption("-o", "${into.absolutePath}/%(id)s.%(ext)s")

            YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                onProgress(progress.coerceIn(0f, 100f) / 100f)
            }

            val id = url.substringAfter("watch?v=").substringBefore('&')
            into.listFiles()?.firstOrNull { it.nameWithoutExtension == id }
                ?: error("yt-dlp reported success but wrote no file")
        }
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
