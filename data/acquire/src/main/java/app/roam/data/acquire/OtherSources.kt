package app.roam.data.acquire

import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.FolderNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where an album page looks once YouTube has nothing that IS the track:
 * Bandcamp, the Internet Archive, Audius and Jamendo. SoundCloud, which yt-dlp
 * can search, lives in [YoutubeSource] with the rest of yt-dlp.
 *
 * Each answers with candidates for [ExactTrack], judged exactly as a YouTube
 * upload is, and each fails QUIETLY -- an empty list, never an exception.
 * They are fallbacks: one being down is no reason to retry what YouTube
 * already answered.
 */
@Singleton
class OtherSources @Inject constructor(
    private val youtube: YoutubeSource,
    private val settings: SettingsRepository,
) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Bandcamp: its own search box for the track, then yt-dlp reads each page
     * for the title, the album and the length. Most releases stream in full,
     * which is what makes it worth asking -- and independent and reissued
     * catalogue is what it has most of.
     */
    internal suspend fun bandcamp(query: String, limit: Int): List<YoutubeResult> = quietly {
        val body = JSONObject()
            .put("search_text", query)
            .put("search_filter", "t")
            .put("full_page", false)
            .put("fan_id", JSONObject.NULL)
            .toString()
        val found = post("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic", body)
            ?: return@quietly emptyList()
        val links = SourceResults.bandcampLinks(found).take(limit)
        youtube.extractLinks(links).getOrNull().orEmpty()
    }

    /**
     * The Internet Archive: items named for the album, then each item's own
     * file list.
     *
     * Files, not torrents. Every item offers its tracks one by one over plain
     * HTTP -- the same files its torrent holds -- so the one track wanted is
     * fetched on its own, with nothing to unpack and nothing else to throw
     * away. A compilation is looked up by its title alone: an archive's
     * "creator" for one is anybody's guess.
     */
    internal suspend fun internetArchive(want: ExactTrack.Wanted, items: Int): List<YoutubeResult> = quietly {
        val album = VideoClues.searchable(VideoClues.cleanTitle(want.album))
        if (VideoClues.key(album).isEmpty()) return@quietly emptyList()
        val various = VideoClues.key(want.albumArtist) == VideoClues.key(FolderNames.VARIOUS_ARTISTS)
        val act = if (various) "" else VideoClues.queryArtist(want.albumArtist.ifBlank { want.artist })

        val query = buildString {
            append("title:(").append(phrase(album)).append(")")
            if (VideoClues.key(act).isNotEmpty()) {
                append(" AND (creator:(").append(phrase(act)).append(") OR title:(").append(phrase(act)).append("))")
            }
            append(" AND mediatype:(audio)")
        }
        val found = get(
            "https://archive.org/advancedsearch.php?q=${query.encoded()}" +
                "&fl%5B%5D=identifier&rows=$items&output=json",
        ) ?: return@quietly emptyList()

        SourceResults.archiveItems(found).flatMap { identifier ->
            get("https://archive.org/metadata/${identifier.encoded()}")
                ?.let { SourceResults.archiveFiles(identifier, it) }
                .orEmpty()
        }
    }

    /** Audius: a free, open catalogue that artists upload to themselves. */
    internal suspend fun audius(query: String, limit: Int): List<YoutubeResult> = quietly {
        val found = get("https://api.audius.co/v1/tracks/search?query=${query.encoded()}&app_name=$APP_NAME")
            ?: return@quietly emptyList()
        SourceResults.audiusTracks(found, APP_NAME).take(limit)
    }

    /**
     * Jamendo: independent artists' music under free licences. The one place
     * that needs a key, and skipped -- not failed -- without one.
     */
    internal suspend fun jamendo(query: String, limit: Int): List<YoutubeResult> = quietly {
        val clientId = settings.settings.first().jamendoClientId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@quietly emptyList()
        val found = get(
            "https://api.jamendo.com/v3.0/tracks/?client_id=${clientId.encoded()}" +
                "&format=json&limit=$limit&search=${query.encoded()}",
        ) ?: return@quietly emptyList()
        SourceResults.jamendoTracks(found)
    }

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let { JSONObject(it) }
        }
    }

    private suspend fun post(url: String, json: String): JSONObject? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let { JSONObject(it) }
        }
    }

    /** Anything that goes wrong is "nothing here" -- but cancellation is still cancellation. */
    private inline fun quietly(block: () -> List<YoutubeResult>): List<YoutubeResult> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /** A search phrase. Inside quotes only the quote and the backslash need escaping. */
    private fun phrase(text: String): String =
        "\"" + text.trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        /** Audius asks every caller to name itself; it is not a key. */
        const val APP_NAME = "Roam"

        const val USER_AGENT = "Roam/1.0 ( https://github.com/0mattsmith/roam )"
    }
}
