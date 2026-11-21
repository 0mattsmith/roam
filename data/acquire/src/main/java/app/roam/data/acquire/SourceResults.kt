package app.roam.data.acquire

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * What the places after YouTube answer, read into candidates [ExactTrack] can
 * judge exactly as it judges a YouTube upload.
 *
 * Apart from the network so every shape is tested. None of these is an API
 * anyone promised to keep stable -- Bandcamp's search least of all -- so
 * anything unexpected reads as "nothing here", never as an error.
 */
internal object SourceResults {

    // ---- Bandcamp -----------------------------------------------------------

    /** Track pages from Bandcamp's own search box, tracking parameters off. */
    fun bandcampLinks(json: JSONObject): List<String> =
        objects(json.optJSONObject("auto")?.optJSONArray("results"))
            .filter { it.text("type") == "t" }
            .mapNotNull { result ->
                val path = result.text("item_url_path") ?: return@mapNotNull null
                val link = if (path.startsWith("http")) {
                    path
                } else {
                    val root = result.text("item_url_root") ?: return@mapNotNull null
                    root.trimEnd('/') + "/" + path.trimStart('/')
                }
                link.substringBefore('?')
            }
            .distinct()

    // ---- the Internet Archive -----------------------------------------------

    /** Item identifiers from an advanced search. */
    fun archiveItems(json: JSONObject): List<String> =
        objects(json.optJSONObject("response")?.optJSONArray("docs")).mapNotNull { it.text("identifier") }

    /**
     * One candidate per recording in an item, from its file list.
     *
     * An item holds each track several times over -- the upload, then the
     * archive's own MP3 and Ogg copies of it -- so files are grouped by the
     * one they were made from, and the best format of each is the one fetched.
     * The tags can sit on any copy, so they are read from whichever has them.
     * Covers, torrents, playlists and checksums are not in [FORMATS] and fall
     * away on their own.
     */
    fun archiveFiles(identifier: String, json: JSONObject): List<YoutubeResult> {
        val item = json.optJSONObject("metadata")
        val creator = item?.textOrFirst("creator").orEmpty()
        val itemAlbum = item?.textOrFirst("title").orEmpty().let { title ->
            // "Oasis - Definitely Maybe" is how uploads are usually named, and
            // the album is the part after the act.
            if (creator.isNotEmpty() && title.startsWith("$creator - ", ignoreCase = true)) {
                title.substring(creator.length + 3)
            } else {
                title
            }
        }

        val audio = objects(json.optJSONArray("files")).filter { file ->
            file.text("format")?.let { it in FORMATS } == true
        }
        return audio.groupBy { it.text("original") ?: it.text("name") }.mapNotNull { (_, copies) ->
            val best = copies.minByOrNull { FORMATS.indexOf(it.text("format").orEmpty()) } ?: return@mapNotNull null
            val name = best.text("name") ?: return@mapNotNull null
            val tagged = copies.firstOrNull { it.text("title") != null } ?: best

            val title = tagged.text("title") ?: titleFromName(name)
            val artist = tagged.text("artist") ?: tagged.textOrFirst("creator") ?: creator
            YoutubeResult(
                videoId = "$identifier/$name",
                title = title,
                artist = artist,
                album = tagged.text("album") ?: itemAlbum.ifEmpty { null },
                year = null,
                durationSec = copies.firstNotNullOfOrNull { seconds(it.text("length")) },
                thumbnailUrl = null,
                videoTitle = title,
                channel = artist.ifEmpty { null },
                creditedTitle = title,
                creditedArtist = artist.ifEmpty { null },
                extracted = true,
                link = "https://archive.org/download/$identifier/${encodedPath(name)}",
                fileExtension = name.substringAfterLast('.', "").lowercase().ifEmpty { null },
            )
        }
    }

    // ---- Audius ---------------------------------------------------------------

    /** Search results that can be streamed by anyone; gated ones are left out. */
    fun audiusTracks(json: JSONObject, appName: String): List<YoutubeResult> =
        objects(json.optJSONArray("data")).mapNotNull { track ->
            val id = track.text("id") ?: return@mapNotNull null
            val title = track.text("title") ?: return@mapNotNull null
            if (track.optBoolean("is_streamable", true).not()) return@mapNotNull null
            if (track.optBoolean("is_stream_gated", false)) return@mapNotNull null

            val artist = track.optJSONObject("user")?.text("name").orEmpty()
            YoutubeResult(
                videoId = "audius:$id",
                title = title,
                artist = artist,
                album = null,
                year = null,
                durationSec = track.optInt("duration", 0).takeIf { it > 0 },
                thumbnailUrl = null,
                videoTitle = title,
                channel = artist.ifEmpty { null },
                creditedTitle = title,
                creditedArtist = artist.ifEmpty { null },
                extracted = true,
                link = "https://api.audius.co/v1/tracks/$id/stream?app_name=$appName",
                fileExtension = "mp3",
            )
        }

    // ---- Jamendo ----------------------------------------------------------------

    /**
     * Tracks whose artist allows downloading. Jamendo says so per track, and a
     * track that does not allow it has no download address to take.
     */
    fun jamendoTracks(json: JSONObject): List<YoutubeResult> {
        if (json.optJSONObject("headers")?.text("status") != "success") return emptyList()
        return objects(json.optJSONArray("results")).mapNotNull { track ->
            if (!track.optBoolean("audiodownload_allowed", false)) return@mapNotNull null
            val link = track.text("audiodownload") ?: return@mapNotNull null
            val id = track.text("id") ?: return@mapNotNull null
            val title = track.text("name") ?: return@mapNotNull null
            val artist = track.text("artist_name").orEmpty()
            YoutubeResult(
                videoId = "jamendo:$id",
                title = title,
                artist = artist,
                album = track.text("album_name"),
                year = null,
                durationSec = seconds(track.text("duration")),
                thumbnailUrl = null,
                videoTitle = title,
                channel = artist.ifEmpty { null },
                creditedTitle = title,
                creditedArtist = artist.ifEmpty { null },
                extracted = true,
                link = link,
                fileExtension = "mp3",
            )
        }
    }

    // ---- shared -------------------------------------------------------------

    /** "323.45", "05:23" or "1:02:03" as whole seconds. */
    fun seconds(length: String?): Int? {
        val text = length?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' !in text) return text.toDoubleOrNull()?.toInt()
        val parts = text.split(':').map { it.toDoubleOrNull() ?: return null }
        return parts.fold(0.0) { total, part -> total * 60 + part }.toInt()
    }

    /** "Disc 1/01 - Rock 'n' Roll Star.flac" -> "Rock 'n' Roll Star", for a file with no title tag. */
    fun titleFromName(name: String): String =
        name.substringAfterLast('/')
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace(LEADING_NUMBER, "")
            .trim()

    private fun encodedPath(name: String): String =
        name.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    /** A field's text, or null when it is missing, blank or JSON null ("null" on Android). */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

    /** The archive writes a field as a string or as a list of them, item by item. */
    private fun JSONObject.textOrFirst(key: String): String? {
        val list = optJSONArray(key) ?: return text(key)
        return (0 until list.length())
            .map { list.optString(it).trim() }
            .firstOrNull { it.isNotEmpty() && it != "null" }
    }

    /**
     * The archive's audio formats, best first. Its own high-quality MP3 before
     * the lossless upload it was made from: a tenth of the size, and the same
     * as what the rest of the library is made of. The low-bitrate copies come
     * last, and only win when nothing else was uploaded.
     */
    private val FORMATS = listOf(
        "VBR MP3", "320Kbps MP3", "256Kbps MP3", "192Kbps MP3", "MP3",
        "Flac", "Ogg Vorbis", "Apple Lossless Audio", "24bit Flac",
        "160Kbps MP3", "128Kbps MP3", "96Kbps MP3", "64Kbps MP3",
    )

    private val LEADING_NUMBER = Regex("""^\s*(?:d\d+)?t?\d{1,3}(?:\s*[-.)_]\s*|\s+)""", RegexOption.IGNORE_CASE)
}
