package app.roam.data.catalog.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One line of a synced lyric, and when it starts.
 *
 * [atMs] is relative to the start of the FILE, not to any trim point. Roam's
 * clipped playback hides the intro from the player entirely (invariant on
 * ClippingConfiguration), so a lyric timed against the trimmed timeline would
 * drift by exactly the amount that was cut.
 */
data class LyricLine(val atMs: Long, val text: String)

/** What a lookup found, if anything. */
data class FoundLyrics(
    val plain: String?,
    /** Raw LRC, kept as it arrived so the parse can be improved later. */
    val synced: String?,
) {
    val isEmpty: Boolean get() = plain.isNullOrBlank() && synced.isNullOrBlank()
}

/**
 * LRCLIB, which hosts lyrics and gives them away.
 *
 * Chosen over MusicBrainz because MusicBrainz does not host lyrics at all --
 * it links out to sites that do, most of which forbid scraping. LRCLIB needs
 * no account, no key and no attribution header, and it carries SYNCED lyrics
 * for a good share of popular music, which is the difference between a wall of
 * text and words that follow the song.
 *
 * Deliberately no rate limiter. Unlike MusicBrainz this is called once per
 * track ever, from a screen a person is looking at -- there is no pass that
 * could ever run it in a loop.
 */
@Singleton
class LrcLib @Inject constructor() {

    private val client = OkHttpClient()

    /**
     * Looks a track up by what it is rather than by an id.
     *
     * [durationMs] matters more than it looks: LRCLIB matches on duration to
     * separate a single edit from an album version, and passing it is what
     * stops the wrong timings landing on a track that shares a name. Omitted
     * when unknown rather than guessed.
     */
    suspend fun find(
        title: String,
        artist: String,
        album: String? = null,
        durationMs: Long = 0,
    ): FoundLyrics? = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext null

        val url = buildString {
            append("https://lrclib.net/api/get")
            append("?track_name=").append(title.encoded())
            append("&artist_name=").append(artist.encoded())
            if (!album.isNullOrBlank()) append("&album_name=").append(album.encoded())
            if (durationMs > 0) append("&duration=").append(durationMs / 1000)
        }

        // An exact match is tried first; the search endpoint is the fallback
        // because it will happily return something plausible for a query that
        // had no real answer, and a confident wrong lyric is worse than none.
        exact(url)?.let { return@withContext it }
        search(title, artist)
    }

    private fun exact(url: String): FoundLyrics? = get(url)?.let { toLyrics(it) }

    private fun search(title: String, artist: String): FoundLyrics? {
        val url = "https://lrclib.net/api/search" +
            "?track_name=${title.encoded()}&artist_name=${artist.encoded()}"

        val body = raw(url) ?: return null
        val first = runCatching { JSONArray(body) }.getOrNull()
            ?.takeIf { it.length() > 0 }
            ?.optJSONObject(0)
            ?: return null

        return toLyrics(first)
    }

    private fun toLyrics(json: JSONObject): FoundLyrics? {
        // The API answers with instrumental = true for tracks it knows have no
        // words. That is a real answer, not a miss, and it should be cached as
        // such rather than retried forever.
        if (json.optBoolean("instrumental", false)) {
            return FoundLyrics(plain = INSTRUMENTAL, synced = null)
        }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() }
        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
        return FoundLyrics(plain, synced).takeUnless { it.isEmpty }
    }

    private fun get(url: String): JSONObject? =
        raw(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun raw(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            // Asked for by LRCLIB's docs, and it costs nothing to be nameable
            // if Roam ever starts behaving badly.
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            // 404 is the ordinary answer for "we do not have this", not a fault.
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        const val INSTRUMENTAL = "[Instrumental]"

        private const val USER_AGENT = "Roam/1.0 (https://github.com/0mattsmith/roam)"

        /**
         * Parses LRC into lines, dropping the metadata tags at the top.
         *
         * `[ar:...]`, `[ti:...]` and friends look exactly like timestamps to a
         * naive parser and would each become a lyric line at time zero -- so
         * the minutes field is required to be digits, which no metadata tag is.
         *
         * One line may carry SEVERAL timestamps when the same words repeat, so
         * each is emitted separately rather than only the first being taken.
         */
        fun parseLrc(lrc: String): List<LyricLine> {
            val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

            return lrc.lineSequence().flatMap { line ->
                val stamps = stamp.findAll(line).toList()
                if (stamps.isEmpty()) return@flatMap emptySequence()

                val text = line.substring(stamps.last().range.last + 1).trim()

                stamps.asSequence().map { match ->
                    val (m, s, frac) = match.destructured
                    // Two-digit fractions are centiseconds, three are millis --
                    // reading both as milliseconds puts a line 900ms early.
                    val fraction = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.toLong()
                    }
                    LyricLine(m.toLong() * 60_000 + s.toLong() * 1_000 + fraction, text)
                }
            }.sortedBy { it.atMs }.toList()
        }

        /**
         * The line that should be highlighted at [positionMs]: the last one
         * that has already started.
         *
         * Returns -1 before the first line rather than 0, so an intro does not
         * light up the first lyric for twenty seconds before it is sung.
         */
        fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
            if (lines.isEmpty() || positionMs < lines.first().atMs) return -1
            return lines.indexOfLast { it.atMs <= positionMs }
        }
    }
}
