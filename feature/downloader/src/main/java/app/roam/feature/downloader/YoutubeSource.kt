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

/**
 * One search result, once it has been looked up properly.
 *
 * [album] and [year] are absent from a flat search and only arrive from a full
 * extraction, which is why results are fetched a batch at a time.
 */
data class YoutubeResult(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val durationSec: Int?,
    val thumbnailUrl: String?,
) {
    val url: String get() = "https://music.youtube.com/watch?v=$videoId"

    /** "Nevermind (1991)", or just the album when the year is unknown. */
    val albumLine: String?
        get() = album?.let { if (year != null) "$it ($year)" else it }
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
     * yt-dlp is ONE native process with one working directory, and its execute
     * call blocks a thread rather than suspending. Two at once is not slower,
     * it is undefined -- and typing "d12" fires three searches in under a
     * second, so this is the common case rather than an edge one.
     *
     * Cancelling the coroutine does not stop the process either, which is the
     * other half of why searches are serialised here instead of being raced.
     */
    private val runLock = Mutex()

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
     * Searches YouTube Music rather than YouTube proper: music results carry a
     * real artist and album where plain YouTube gives whatever the uploader
     * typed, and they skip the lyric videos and hour-long compilations.
     *
     * The cheap half. One request, ids only -- deliberately separate from
     * [enrich], because the whole point of the split is that this is fast and
     * that is not.
     */
    suspend fun searchIds(query: String, limit: Int = SEARCH_LIMIT): Result<List<String>> =
        runCatching {
            ensureStarted()
            withContext(Dispatchers.IO) {
                runLock.withLock {
                    val music = runCatching {
                        query("https://music.youtube.com/search?q=${query.urlEncoded()}", limit)
                    }.getOrNull()

                    // A music search page is built from shelves -- Songs,
                    // Videos, Albums, Artists -- so a short or ambiguous query
                    // can come back as sections containing nothing flat.
                    // ytsearch always returns plain videos, so it is the
                    // fallback rather than the first choice.
                    if (!music.isNullOrEmpty()) music
                    else query("ytsearch$limit:$query", limit)
                }
            }
        }

    /**
     * The expensive half: a real extraction per id, which is the only way to
     * learn the album, the year and the square cover.
     *
     * All the ids go to ONE yt-dlp invocation. Starting the process is a large
     * fixed cost -- it is a Python interpreter -- so fifteen ids in one call is
     * far cheaper than fifteen calls, even though the network work is the same.
     * Output is one JSON document per line rather than a single array.
     */
    suspend fun enrich(ids: List<String>): Result<List<YoutubeResult>> = runCatching {
        if (ids.isEmpty()) return@runCatching emptyList()
        ensureStarted()
        withContext(Dispatchers.IO) {
            runLock.withLock {
                val request = YoutubeDLRequest(ids.map { "https://music.youtube.com/watch?v=$it" })
                    .addOption("--dump-json")
                    .addOption("--skip-download")
                    .addOption("--no-warnings")
                    // One dead video must not take the whole batch with it.
                    .addOption("--ignore-errors")

                val byId = YoutubeDL.getInstance().execute(request).out
                    .lineSequence()
                    .filter { it.startsWith("{") }
                    .mapNotNull { line -> runCatching { toResult(JSONObject(line)) }.getOrNull() }
                    .associateBy { it.videoId }

                // Restored to the order asked for: yt-dlp emits whatever
                // finishes first, and a search list that reorders itself as it
                // loads is worse than one that fills in.
                ids.mapNotNull { byId[it] }
            }
        }
    }

    private fun toResult(json: JSONObject): YoutubeResult {
        // `track` is the song's real title where `title` is whatever the video
        // was called, which on Music is often "Song (Official Audio)".
        val title = json.optString("track")
            .ifBlank { json.optString("title") }
            .ifBlank { "Unknown" }

        val artist = json.optString("artist")
            .ifBlank { json.optJSONArray("artists")?.optString(0).orEmpty() }
            .ifBlank { json.optString("uploader") }
            .ifBlank { json.optString("channel") }
            // Music credits arrive comma-joined; the first is the primary.
            .substringBefore(",")
            .trim()

        val year = json.optInt("release_year").takeIf { it > 0 }
            ?: json.optString("upload_date").take(4).toIntOrNull()

        return YoutubeResult(
            videoId = json.optString("id"),
            title = title,
            artist = artist,
            album = json.optString("album").ifBlank { null },
            year = year,
            durationSec = json.optDouble("duration").takeIf { !it.isNaN() }?.toInt(),
            thumbnailUrl = squareThumbnail(json) ?: json.optString("thumbnail").ifBlank { null },
        )
    }

    /**
     * Music tracks carry a square cover among their thumbnails; videos only
     * have 16:9 stills. Picking the widest square gives real album art where
     * there is any and falls back to the still where there is not.
     */
    private fun squareThumbnail(json: JSONObject): String? {
        val thumbs = json.optJSONArray("thumbnails") ?: return null
        var best: String? = null
        var bestWidth = 0
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val w = t.optInt("width")
            val h = t.optInt("height")
            if (w > 0 && w == h && w > bestWidth) {
                bestWidth = w
                best = t.optString("url").ifBlank { null }
            }
        }
        return best
    }

    private fun query(target: String, limit: Int): List<String> {
        val request = YoutubeDLRequest(target)
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--playlist-end", limit.toString())
            .addOption("--no-warnings")
            .addOption("--ignore-errors")

        val out = YoutubeDL.getInstance().execute(request).out
        // yt-dlp still prints the odd notice ahead of the payload, and one
        // stray line makes the whole document unparseable.
        val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0)))

        return flatten(json, depth = 0).take(limit)
    }

    private fun flatten(node: JSONObject, depth: Int): List<String> {
        if (depth > MAX_SHELF_DEPTH) return emptyList()
        val entries = node.optJSONArray("entries") ?: return emptyList()

        return (0 until entries.length()).flatMap { i ->
            val entry = entries.optJSONObject(i) ?: return@flatMap emptyList()
            if (entry.optJSONArray("entries") != null) {
                flatten(entry, depth + 1)
            } else {
                // Anything that is not an 11-character video id is a browse id
                // belonging to a shelf header, not something that plays.
                val id = entry.optString("id")
                if (id.length == VIDEO_ID_LENGTH) listOf(id) else emptyList()
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

    private companion object {
        /** Songs, Videos, Albums, Artists -- shelves are one level, with room. */
        const val MAX_SHELF_DEPTH = 3

        /** Anything else in an entry list is a browse id, not something playable. */
        const val VIDEO_ID_LENGTH = 11

        /**
         * Ids are cheap to collect and only ever enriched a batch at a time,
         * so this is generous -- it is the size of the pool "Show more" draws
         * from, not the amount of work done up front.
         */
        const val SEARCH_LIMIT = 60
    }
}
