package app.roam.feature.downloader

import android.app.Application
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
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
 * runCatching CATCHES CancellationException, which is almost never what you
 * want: a coroutine cancelled by the next keystroke is not a failure, and
 * reporting it surfaces "StandaloneCoroutine was cancelled" as though the user
 * had done something wrong. Worse, swallowing it breaks structured
 * concurrency, because the parent is never told the child actually stopped.
 */
private inline fun <T> Result<T>.rethrowCancellation(): Result<T> =
    onFailure { if (it is CancellationException) throw it }

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
     * For moving bytes, which is all this client ever does.
     *
     * No call timeout, because a whole album track legitimately takes minutes;
     * the read timeout is what catches a stream that has actually stalled, and
     * it is the one that matters. Built once -- an OkHttpClient owns a
     * connection pool and a thread pool, so one per download would be worse
     * than the problem being solved here.
     */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

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
    }.rethrowCancellation()

    /**
     * Searches YouTube Music rather than YouTube proper: music results carry a
     * real artist and album where plain YouTube gives whatever the uploader
     * typed, and they skip the lyric videos and hour-long compilations.
     *
     * The cheap half, and now the only one the first screenful waits for.
     *
     * A flat listing already carries the title, the channel, the duration and a
     * thumbnail -- everything a result ROW draws. Roam used to throw all of
     * that away, keep the ids, and then pay for a full extraction of every one
     * before showing anything, so the list appeared at the speed of the slow
     * call rather than the fast one. Now the rows are built from the flat
     * entries and [enrich] fills in the album and the year behind them.
     */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): Result<List<YoutubeResult>> =
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
        }.rethrowCancellation()

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
    }.rethrowCancellation()

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

    private fun query(target: String, limit: Int): List<YoutubeResult> {
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

    private fun flatten(node: JSONObject, depth: Int): List<YoutubeResult> {
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
                if (id.length == VIDEO_ID_LENGTH) listOf(toResult(entry)) else emptyList()
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
    /**
     * Turns a `ytsearch1:` query into a concrete video id whose length agrees
     * with the catalogue, or null when nothing does.
     *
     * An album page queues a SEARCH, not a link -- there is no id to be had
     * until something is picked. Taking the first hit on trust is how a
     * three-minute song ends up as a nine-minute extended mix, a live version,
     * or an hour-long "full album" upload with the whole record inside it. The
     * catalogue already knows how long the track should be, so anything wildly
     * off is the wrong recording whatever it is called.
     *
     * Deliberately returns null rather than falling back to the first result:
     * the whole point is to refuse, and a download saved to Drive is not easily
     * taken back.
     */
    suspend fun resolveByDuration(
        query: String,
        expectedMs: Long,
        toleranceMs: Long = DURATION_TOLERANCE_MS,
    ): Result<String?> = runCatching {
        val flat = search(query, limit = DURATION_CANDIDATES).getOrThrow()

        // THROWN, not returned as null. "Nothing came back" is a failed search
        // and deserves a retry; "these came back and none fits" is a verdict
        // about the recording and must not be retried. Collapsing the two
        // reports a bad connection as a duration mismatch, which sends anyone
        // reading the queue looking in entirely the wrong place.
        if (flat.isEmpty()) error("No search results for \"$query\"")

        // Enriched deliberately, even though the flat rows carry a duration:
        // this decides whether a file is downloaded at all, and a flat listing's
        // duration comes from the search page rather than from the recording.
        val candidates = enrich(flat.take(DURATION_CANDIDATES).map { it.videoId }).getOrThrow()
        if (candidates.isEmpty()) error("Could not read details for \"$query\"")

        candidates.firstOrNull { candidate ->
            val seconds = candidate.durationSec ?: return@firstOrNull false
            kotlin.math.abs(seconds * 1000L - expectedMs) <= toleranceMs
        }?.videoId
    }.rethrowCancellation()

    /**
     * Where the audio actually lives, once yt-dlp has worked it out.
     *
     * [headers] is carried rather than reconstructed. The stream URL is signed
     * for whichever InnerTube client yt-dlp resolved as, and the CDN checks the
     * User-Agent against it -- fetching the same URL with OkHttp's default
     * header gets a 403 that looks exactly like a stale extractor.
     */
    private data class StreamRef(
        val url: String,
        val ext: String,
        val expectedBytes: Long,
        val headers: Map<String, String>,
    )

    /**
     * Fetches the audio, holding the yt-dlp lock only long enough to find it.
     *
     * yt-dlp is one native binary with one working directory, so everything
     * that runs it is serialised -- and while a DOWNLOAD held that lock, one
     * album blocked search and every other download for as long as it took to
     * move the bytes. The bytes are the slow part and the only part that needs
     * no yt-dlp at all: resolving is a few seconds of cipher work, transferring
     * is minutes of plain HTTP.
     *
     * So the lock covers the resolve, and OkHttp does the transfer outside it.
     * Downloads now run in parallel with each other and with searching, which
     * is the trade the comment here used to describe as unavoidable.
     *
     * A failed direct fetch falls back to letting yt-dlp do the whole thing,
     * lock and all. Resolved URLs are signed, IP-bound and short-lived, so
     * there are real ways for this to fail that have nothing to do with the
     * network -- and the worst case has to be today's behaviour, not a broken
     * download.
     */
    suspend fun download(
        url: String,
        into: File,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = runCatching {
        ensureStarted()

        // A directory of its own per download, emptied first.
        //
        // The output used to be found by pulling the video id out of the URL,
        // which breaks the moment the URL is a SEARCH -- an album page asks for
        // "ytsearch1:artist title" and there is no id in it to parse. Whatever
        // lands in an empty directory is the answer, whichever form the request
        // took.
        withContext(Dispatchers.IO) {
            into.deleteRecursively()
            into.mkdirs()

            val stream = runCatching { runLock.withLock { resolve(url) } }
                .rethrowCancellation()
                .getOrNull()

            val direct = stream?.let {
                runCatching { fetch(it, into, onProgress) }.rethrowCancellation().getOrNull()
            }

            direct ?: runLock.withLock { fetchWithYoutubeDl(url, into, onProgress) }
        }
    }.rethrowCancellation()

    /** The cipher work, and nothing else. Brief, and the only part yt-dlp owes us. */
    private fun resolve(target: String): StreamRef {
        val request = YoutubeDLRequest(target)
            .addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("-J")

        val out = YoutubeDL.getInstance().execute(request).out
        val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0)))

        // With a format selector, yt-dlp reports the chosen one under
        // requested_downloads; a single video without one answers at the top
        // level. Both shapes turn up depending on whether the target was a URL
        // or a search, so neither can be assumed.
        val chosen = json.optJSONArray("requested_downloads")?.optJSONObject(0) ?: json

        val streamUrl = chosen.optString("url").ifBlank { null }
            ?: error("yt-dlp resolved no stream URL")

        val headers = chosen.optJSONObject("http_headers")?.let { h ->
            h.keys().asSequence().associateWith { h.optString(it) }
        }.orEmpty().filterValues { it.isNotBlank() }

        return StreamRef(
            url = streamUrl,
            ext = chosen.optString("ext").ifBlank { "m4a" },
            expectedBytes = chosen.optLong("filesize")
                .takeIf { it > 0 }
                ?: chosen.optLong("filesize_approx"),
            headers = headers,
        )
    }

    /** Plain HTTP, no lock held, cancellable between chunks. */
    private suspend fun fetch(
        stream: StreamRef,
        into: File,
        onProgress: (Float) -> Unit,
    ): File {
        val request = Request.Builder()
            .url(stream.url)
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .build()

        val target = into.resolve("audio.${stream.ext}")

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code} fetching the audio")
            val body = response.body ?: error("No body fetching the audio")
            val total = body.contentLength().takeIf { it > 0 } ?: stream.expectedBytes

            val buffer = ByteArray(BUFFER_BYTES)
            var moved = 0L
            var lastPercent = -1

            body.byteStream().use { source ->
                target.outputStream().buffered().use { sink ->
                    while (true) {
                        // Cancelling a WorkManager job has to actually stop the
                        // transfer; a blocking read loop otherwise runs to
                        // completion writing a file nobody asked for any more.
                        currentCoroutineContext().ensureActive()

                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        moved += read

                        if (total <= 0) continue
                        // Whole percents only. Each call posts to WorkManager,
                        // and a 5 MB track at 64 KB a time is otherwise eighty
                        // writes to say the same four things.
                        val percent = (moved * 100 / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress((percent / 100f).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (moved <= 0) error("The audio stream was empty")
            // A truncated body is a success as far as HTTP is concerned. Left
            // alone it becomes a half-length track on Drive, which is worse
            // than a failure because nothing ever says so.
            if (total > 0 && moved < total) {
                error("The audio stream stopped at ${moved * 100 / total}%")
            }
        }

        return target
    }

    /**
     * The whole job through yt-dlp, exactly as it worked before the split.
     *
     * Kept as the fallback rather than deleted: this is the path that is known
     * to work when a signed URL will not, and the cost of keeping it is a
     * function nobody calls on a good day.
     */
    private fun fetchWithYoutubeDl(
        url: String,
        into: File,
        onProgress: (Float) -> Unit,
    ): File {
        into.deleteRecursively()
        into.mkdirs()

        val request = YoutubeDLRequest(url)
            .addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            .addOption("--no-playlist")
            .addOption("--no-warnings")
            .addOption("-o", "${into.absolutePath}/%(id)s.%(ext)s")

        YoutubeDL.getInstance().execute(request) { progress, _, _ ->
            onProgress(progress.coerceIn(0f, 100f) / 100f)
        }

        return into.listFiles()
            // yt-dlp leaves .part files behind on a partial fetch.
            ?.filterNot { it.extension == "part" }
            ?.maxByOrNull { it.length() }
            ?: error("yt-dlp reported success but wrote no file")
    }

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    private companion object {
        /** Songs, Videos, Albums, Artists -- shelves are one level, with room. */
        const val MAX_SHELF_DEPTH = 3

        /** Anything else in an entry list is a browse id, not something playable. */
        const val VIDEO_ID_LENGTH = 11

        /** Big enough that the read loop is not the cost; small enough to cancel promptly. */
        const val BUFFER_BYTES = 64 * 1024

        /**
         * Ids are cheap to collect and only ever enriched a batch at a time,
         * so this is generous -- it is the size of the pool "Show more" draws
         * from, not the amount of work done up front.
         */
        const val SEARCH_LIMIT = 60

        /**
         * Fifteen seconds. Wide enough for a fade, a count-in or a catalogue
         * that rounded, narrow enough that a radio edit, an extended mix or a
         * whole-album upload cannot slip through.
         */
        const val DURATION_TOLERANCE_MS = 15_000L

        /**
         * How many search hits to weigh before giving up. Enriching costs a
         * full extraction each, so this stays small -- if the right recording
         * is not in the first handful, the query itself is wrong.
         */
        const val DURATION_CANDIDATES = 5
    }
}
