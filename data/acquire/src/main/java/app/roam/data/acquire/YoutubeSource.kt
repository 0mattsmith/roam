package app.roam.data.acquire

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
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * What the upload says about itself, word for word, kept apart from the
     * fields above because none of it is ever written as a tag. These are
     * CLUES: [VideoClues] reads them to ask a catalogue what the video is.
     */
    val videoTitle: String = title,
    /** The channel that posted it -- "OasisVEVO", "Oasis - Topic". */
    val channel: String? = null,
    /** Only a full extraction carries this. */
    val description: String? = null,
    /** YouTube Music's own credit for the song, where it gave one. */
    val creditedTitle: String? = null,
    val creditedArtist: String? = null,
    /** A full extraction rather than a flat search row, which knows far less. */
    val extracted: Boolean = false,
    /**
     * Where the audio lives when it is not on YouTube -- SoundCloud, Bandcamp,
     * the Internet Archive and the rest of an album page's fallbacks (see
     * [TrackResolver]). Null for anything on YouTube, whose address is built
     * from [videoId].
     */
    val link: String? = null,
    /**
     * Set when [link] is a plain audio file, with its extension: fetched over
     * HTTP as it stands, with no yt-dlp in between. The archive's MP3s and
     * the like are files, not pages to be extracted.
     */
    val fileExtension: String? = null,
) {
    val url: String get() = link ?: "https://music.youtube.com/watch?v=$videoId"

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
    /**
     * Consecutive failures of the direct fetch, capped at the give-up point.
     *
     * Volatile rather than guarded: several download jobs read and write this
     * at once, and the worst a race costs is one extra attempt. A lock here
     * would be a lock more than the thing is worth.
     */
    @Volatile
    private var directFailures = 0

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
            // BOUNDED, because the alternative is a spinner that never stops.
            //
            // Everything that runs yt-dlp queues behind one mutex, and a queue
            // of downloads can hold it for a long time -- so a search typed
            // while the library is filling up waits, with nothing on screen
            // saying why. yt-dlp can also simply hang. Both look identical from
            // here and both are better reported than waited on.
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { searchLocked(query, limit) }
            } ?: error("Search timed out - Roam may be busy adding to your library")
        }.rethrowCancellation()

    private suspend fun searchLocked(query: String, limit: Int): List<YoutubeResult> =
        runLock.withLock {
            val music = runCatching {
                query("https://music.youtube.com/search?q=${query.urlEncoded()}", limit)
            }.getOrNull()

            // A music search page is built from shelves -- Songs, Videos,
            // Albums, Artists -- so a short or ambiguous query can come back as
            // sections containing nothing flat. ytsearch always returns plain
            // videos, so it is the fallback rather than the first choice.
            if (!music.isNullOrEmpty()) music
            else query("ytsearch$limit:$query", limit)
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
                    .mapNotNull { line -> runCatching { toResult(JSONObject(line), extracted = true) }.getOrNull() }
                    .associateBy { it.videoId }

                // Restored to the order asked for: yt-dlp emits whatever
                // finishes first, and a search list that reorders itself as it
                // loads is worse than one that fills in.
                ids.mapNotNull { byId[it] }
            }
        }
    }.rethrowCancellation()

    private fun toResult(json: JSONObject, extracted: Boolean): YoutubeResult {
        val videoTitle = json.text("title").orEmpty()
        // `track` is the song's real title where `title` is whatever the video
        // was called, which on Music is often "Song (Official Audio)".
        val creditedTitle = json.text("track")

        // The list first, where yt-dlp gives one: it is the only form that
        // keeps "Tyler, The Creator" in one piece. The joined string it
        // replaced is cut at its first comma, since a comma is all that
        // separates two acts in it.
        val creditedArtist = json.optJSONArray("artists")?.optString(0)?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
            ?: json.text("artist")?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() }
        val channel = json.text("channel") ?: json.text("uploader")

        val year = json.optInt("release_year").takeIf { it > 0 }
            ?: json.text("upload_date")?.take(4)?.toIntOrNull()

        return YoutubeResult(
            videoId = json.optString("id"),
            title = (creditedTitle ?: videoTitle).ifBlank { "Unknown" },
            artist = creditedArtist ?: json.text("uploader") ?: channel.orEmpty(),
            album = json.text("album"),
            year = year,
            durationSec = json.optDouble("duration").takeIf { !it.isNaN() }?.toInt(),
            thumbnailUrl = squareThumbnail(json) ?: json.text("thumbnail"),
            videoTitle = videoTitle,
            channel = channel,
            description = json.text("description"),
            creditedTitle = creditedTitle,
            creditedArtist = creditedArtist,
            extracted = extracted,
        )
    }

    /**
     * A field's text, or null when it is missing, blank or JSON null. A flat
     * listing is full of explicit nulls, and Android's org.json reads one as
     * the four letters "null" through optString -- which would otherwise
     * become a description, or worse, a title.
     */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

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
                if (id.length == VIDEO_ID_LENGTH) listOf(toResult(entry, extracted = false)) else emptyList()
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
     * that is a copy, not an encode. See [finish].
     */
    /**
     * YouTube Music's Songs shelf alone: the labels' own audio, each upload
     * named for the album it came from. What an album page's search is put to
     * first (see [TrackResolver]), because the general search ranks music
     * videos, live sets and lyric uploads beside the record -- often above it.
     *
     * The `#songs` fragment is yt-dlp's own way of choosing a shelf of a
     * YouTube Music search; it never reaches YouTube.
     */
    suspend fun searchSongs(query: String, limit: Int): Result<List<YoutubeResult>> =
        runCatching {
            ensureStarted()
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runLock.withLock {
                        query("https://music.youtube.com/search?q=${query.urlEncoded()}#songs", limit)
                    }
                }
            } ?: error("Search timed out - Roam may be busy adding to your library")
        }.rethrowCancellation()

    /**
     * SoundCloud, searched the same way: one of the places an album page looks
     * for a track YouTube does not have as itself. Older catalogue is often
     * there, uploaded by the act or the label; recent major-label releases
     * tend to be thirty-second previews, which [extractSoundCloud] drops.
     */
    suspend fun searchSoundCloud(query: String, limit: Int): Result<List<YoutubeResult>> =
        runCatching {
            ensureStarted()
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runLock.withLock { soundCloudSearch(query, limit) }
                }
            } ?: error("Search timed out - Roam may be busy adding to your library")
        }.rethrowCancellation()

    /**
     * A flat listing, read separately from YouTube's: a SoundCloud id is a
     * long number, so the eleven-character test [flatten] uses would throw
     * every result away, and the address has to be kept rather than rebuilt.
     */
    private fun soundCloudSearch(query: String, limit: Int): List<YoutubeResult> {
        val request = YoutubeDLRequest("scsearch$limit:$query")
            .addOption("--flat-playlist")
            .addOption("--dump-single-json")
            .addOption("--no-warnings")
            .addOption("--ignore-errors")

        val out = YoutubeDL.getInstance().execute(request).out
        val json = JSONObject(out.substring(out.indexOf('{').coerceAtLeast(0)))
        val entries = json.optJSONArray("entries") ?: return emptyList()

        return (0 until entries.length()).mapNotNull { i ->
            val entry = entries.optJSONObject(i) ?: return@mapNotNull null
            val link = entry.text("webpage_url") ?: entry.text("url") ?: return@mapNotNull null
            toResult(entry, extracted = false).copy(link = link)
        }
    }

    /**
     * A full extraction of pages yt-dlp reads -- SoundCloud's, Bandcamp's --
     * for judging them as [enrich] lets YouTube's be judged.
     *
     * A track whose every stream is a preview is left out. SoundCloud serves
     * thirty seconds of what it will not play in full here, under the FULL
     * track's length -- so it would pass every check and land as a clip.
     */
    suspend fun extractLinks(links: List<String>): Result<List<YoutubeResult>> = runCatching {
        if (links.isEmpty()) return@runCatching emptyList()
        ensureStarted()
        withContext(Dispatchers.IO) {
            runLock.withLock {
                val request = YoutubeDLRequest(links)
                    .addOption("--dump-json")
                    .addOption("--skip-download")
                    .addOption("--no-warnings")
                    // One track that will not play here must not take the rest.
                    .addOption("--ignore-errors")

                YoutubeDL.getInstance().execute(request).out
                    .lineSequence()
                    .filter { it.startsWith("{") }
                    .mapNotNull { line ->
                        runCatching {
                            val json = JSONObject(line)
                            val link = json.text("webpage_url")
                            if (link == null || previewOnly(json)) null
                            else toResult(json, extracted = true).copy(link = link)
                        }.getOrNull()
                    }
                    .toList()
            }
        }
    }.rethrowCancellation()

    /** Every stream marked as a preview -- or none listed at all. */
    private fun previewOnly(json: JSONObject): Boolean {
        val formats = json.optJSONArray("formats") ?: return false
        return (0 until formats.length()).all { i ->
            val format = formats.optJSONObject(i) ?: return@all true
            format.text("format_note")?.contains("preview", ignoreCase = true) == true ||
                format.text("format_id")?.contains("preview", ignoreCase = true) == true ||
                format.text("url")?.contains("/preview/") == true
        }
    }

    private fun String.isYoutube(): Boolean =
        contains("youtube.com/") || contains("youtu.be/")

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
        /** Set when [url] is a plain audio file rather than a page; see [YoutubeResult.fileExtension]. */
        fileExtension: String? = null,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = runCatching {
        // A plain file needs no yt-dlp at all, and so none of its lock.
        if (fileExtension != null) {
            return@runCatching withContext(Dispatchers.IO) {
                into.deleteRecursively()
                into.mkdirs()
                fetch(StreamRef(url, fileExtension, expectedBytes = 0L, headers = emptyMap()), into, onProgress)
            }
        }

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

            // Once the direct path has failed twice running, stop paying for
            // it. Resolving is a whole yt-dlp process holding the shared lock,
            // so an approach that does not work in this environment otherwise
            // costs every download an extra turn at the one resource the
            // interactive search also needs -- making a broken fetch look like
            // a broken app. Reset on success, and on restart.
            //
            // And only for YouTube. SoundCloud's streams are HLS playlists, and
            // fetching one directly saves the playlist's few hundred bytes of
            // text under an audio file's name -- a "success" that is not music.
            // yt-dlp knows how to follow the segments; this does not.
            val fromYoutube = url.isYoutube()
            val stream = if (fromYoutube && directFailures < DIRECT_FETCH_ATTEMPTS) {
                runCatching { runLock.withLock { resolve(url) } }
                    .rethrowCancellation()
                    .getOrNull()
            } else null

            val direct = stream?.let {
                runCatching { fetch(it, into, onProgress) }.rethrowCancellation().getOrNull()
            }

            if (direct != null) {
                directFailures = 0
            } else if (fromYoutube && directFailures < DIRECT_FETCH_ATTEMPTS) {
                directFailures++
            }

            direct ?: runLock.withLock { fetchWithYoutubeDl(url, into, onProgress) }
        }
    }.rethrowCancellation()

    /**
     * Makes a downloaded file ready to go up: the catalogue's tags written into
     * it in its own format, and its audio moved into a container Roam can see
     * and tag where it arrived in one it cannot -- WebM, which the Drive scan
     * does not list, to Opus; raw AAC, which holds no tags at all, to M4A.
     *
     * A copy, never an encode: re-encoding a lossy file can only lose more,
     * and tags do not need one container to be consistent. FFmpeg does the
     * work through yt-dlp's own metadata step, pointed at the finished file by
     * an info file ([FileTags.infoJson]) -- yt-dlp finds the file already
     * there, skips the download, and runs its post-processing on it. A
     * fetched M4A also comes out a plain MP4 rather than YouTube's fragmented
     * one, with its index at the front.
     *
     * Never fails. The work is done on a copy, and anything going wrong gives
     * back the file exactly as it was downloaded -- an untagged track on Drive
     * is better than no track.
     */
    suspend fun finish(file: File, tags: FileTags, sourceUrl: String): File {
        val ext = file.extension.lowercase()
        if (ext.isEmpty()) return file

        return runCatching {
            ensureStarted()
            withContext(Dispatchers.IO) {
                val work = file.resolveSibling("finish").apply {
                    deleteRecursively()
                    mkdirs()
                }
                file.copyTo(work.resolve("audio.$ext"), overwrite = true)
                val info = file.resolveSibling("finish.info.json")
                info.writeText(tags.infoJson(ext, sourceUrl))

                runLock.withLock {
                    val request = YoutubeDLRequest(emptyList<String>())
                        .addOption("--load-info-json", info.absolutePath)
                        .addOption("-o", "${work.absolutePath}/audio.%(ext)s")
                        .addOption("--embed-metadata")
                        .addOption("--remux-video", "webm>opus/aac>m4a")
                        .addOption("--no-warnings")
                        .addOption("--no-progress")
                    YoutubeDL.getInstance().execute(request)
                }

                work.listFiles()
                    ?.filter { it.isFile && it.extension.lowercase() in FINISHED && ".temp." !in it.name }
                    ?.maxByOrNull { it.length() }
            }
        }.rethrowCancellation()
            .getOrNull()
            // A fraction of what went in is not the same audio with tags on,
            // whatever produced it.
            ?.takeIf { it.length() >= file.length() / 2 }
            ?: file
    }

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

        /** What [finish] may leave behind: audio, in any container the pipeline knows. */
        val FINISHED = setOf("mp3", "m4a", "opus", "ogg", "flac", "aac", "wav", "webm")

        /**
         * How long a search may wait before saying so.
         *
         * Generous, because a cold yt-dlp start is genuinely slow and cutting
         * off a search that was about to answer is worse than the wait. It is
         * here to turn "forever" into a sentence, not to be a tight bound.
         */
        const val SEARCH_TIMEOUT_MS = 45_000L

        /** Consecutive direct-fetch failures before it stops being attempted. */
        const val DIRECT_FETCH_ATTEMPTS = 2

        /**
         * Ids are cheap to collect and only ever enriched a batch at a time,
         * so this is generous -- it is the size of the pool "Show more" draws
         * from, not the amount of work done up front.
         */
        const val SEARCH_LIMIT = 60
    }
}
