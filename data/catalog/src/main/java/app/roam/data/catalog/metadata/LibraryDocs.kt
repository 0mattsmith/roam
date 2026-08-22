package app.roam.data.catalog.metadata

import org.json.JSONArray
import org.json.JSONObject

/**
 * The three metadata files, parsed. See docs/ALBUM_JSON.md for the contract.
 *
 * Reading only. Every field is treated as something a person may have typed by
 * hand or an external editor may have written, so nothing here throws on
 * unexpected input -- a malformed file makes the library fall back to tags and
 * the folder path, which is exactly what happened before these files existed.
 */

/** Highest schema this build understands. */
const val DOC_SCHEMA = 1

data class ArtistDoc(
    val artistName: String?,
    val activeFrom: Int?,
    val activeTo: Int?,
    val debutAlbum: String?,
    val debutAlbumYear: Int?,
    val totalStudioAlbums: Int?,
    val artistInfo: String?,
    val artistImage: String?,
    val artistLogo: String?,
    val artistBanner: String?,
    val sortAs: String?,
)

/** Where a track actually lives, when it is not in its album's folder. */
data class ExternalRef(
    /** Relative to the LIBRARY ROOT, not the album folder. */
    val path: String,
    val source: String,
    val fileId: String?,
    val folderId: String?,
)

data class AlbumTrackEntry(
    /** Relative to the album folder. Null exactly when [external] is set. */
    val file: String?,
    val external: ExternalRef?,
    val trackMeta: String?,
    val lyrics: String?,
    val title: String?,
    val artist: String?,
    val disc: Int,
    val track: Int?,
) {
    /**
     * What identifies this entry, whichever form it took.
     *
     * Callers match on this rather than on a title or a track number, both of
     * which are exactly what an edit tends to change.
     */
    val locator: String get() = external?.path ?: file.orEmpty()
}

data class AlbumDoc(
    val albumArtist: String,
    val albumTitle: String,
    val year: Int?,
    val originalYear: Int?,
    val genre: String?,
    val isCompilation: Boolean,
    val totalDiscs: Int?,
    val totalTracks: Int?,
    val coverArt: String,
    val tracks: List<AlbumTrackEntry>,
) {
    /** Entries by locator, lower-cased, for matching against what the crawl found. */
    fun byLocator(): Map<String, AlbumTrackEntry> =
        tracks.filter { it.locator.isNotBlank() }
            .associateBy { LibraryDocs.normalisePath(it.locator) }
}

data class TrackDoc(
    val title: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val year: Int?,
    val originalYear: Int?,
    val genre: String?,
    val composer: String?,
    val durationMs: Long?,
    val audioFile: String?,
    val lyricsFile: String?,
    /** Absent unless deliberately trimmed. Never derived from a duration. */
    val startMs: Long?,
    val endMs: Long?,
)

object LibraryDocs {

    fun artist(json: String): ArtistDoc? = parse(json) { o ->
        ArtistDoc(
            artistName = o.stringOrNull("artist_name"),
            activeFrom = o.intOrNull("active_from"),
            activeTo = o.intOrNull("active_to"),
            debutAlbum = o.stringOrNull("debut_album"),
            debutAlbumYear = o.intOrNull("debut_album_year"),
            totalStudioAlbums = o.intOrNull("total_studio_albums"),
            artistInfo = o.stringOrNull("artist_info"),
            artistImage = o.stringOrNull("artist_image"),
            artistLogo = o.stringOrNull("artist_logo"),
            artistBanner = o.stringOrNull("artist_banner"),
            sortAs = o.stringOrNull("sort_as"),
        )
    }

    fun album(json: String): AlbumDoc? = parse(json) { o ->
        // The two fields an album cannot be described without. Missing either
        // means this is not an album document, whatever else it contains.
        val albumArtist = o.stringOrNull("album_artist") ?: return@parse null
        val albumTitle = o.stringOrNull("album_title") ?: return@parse null

        AlbumDoc(
            albumArtist = albumArtist,
            albumTitle = albumTitle,
            year = o.intOrNull("year"),
            originalYear = o.intOrNull("original_year"),
            genre = o.stringOrNull("genre"),
            isCompilation = o.optBoolean("is_compilation", false),
            totalDiscs = o.intOrNull("total_discs"),
            totalTracks = o.intOrNull("total_tracks"),
            coverArt = o.stringOrNull("cover_art") ?: "cover.jpg",
            tracks = o.optJSONArray("tracks").objects().mapNotNull { entry(it) },
        )
    }

    fun track(json: String): TrackDoc? = parse(json) { o ->
        TrackDoc(
            title = o.stringOrNull("title"),
            trackNumber = o.intOrNull("track_number"),
            discNumber = o.intOrNull("disc_number"),
            artist = o.stringOrNull("artist"),
            album = o.stringOrNull("album"),
            albumArtist = o.stringOrNull("album_artist"),
            year = o.intOrNull("year"),
            originalYear = o.intOrNull("original_year"),
            genre = o.stringOrNull("genre"),
            composer = o.stringOrNull("composer"),
            durationMs = o.intOrNull("duration_seconds")?.let { it * 1000L },
            audioFile = o.stringOrNull("audio_file"),
            lyricsFile = o.stringOrNull("lyrics_file"),
            startMs = parseClock(o.stringOrNull("start_at")),
            endMs = parseClock(o.stringOrNull("end_at")),
        )
    }

    private fun entry(o: JSONObject): AlbumTrackEntry? {
        val external = o.optJSONObject("external")?.let { ext ->
            val path = ext.stringOrNull("path") ?: return@let null
            ExternalRef(
                path = path,
                source = ext.stringOrNull("source") ?: "drive",
                fileId = ext.stringOrNull("file_id"),
                folderId = ext.stringOrNull("folder_id"),
            )
        }
        val file = o.stringOrNull("file")

        // An entry that names nothing cannot be matched to anything, so it is
        // dropped rather than kept as a row pointing at no file.
        if (file == null && external == null) return null

        return AlbumTrackEntry(
            file = file,
            external = external,
            trackMeta = o.stringOrNull("track_meta"),
            lyrics = o.stringOrNull("lyrics"),
            title = o.stringOrNull("title"),
            artist = o.stringOrNull("artist"),
            disc = o.intOrNull("disc") ?: 1,
            track = o.intOrNull("track"),
        )
    }

    /**
     * Compares paths the way a filesystem someone typed into behaves.
     *
     * Backslashes become slashes because an editor on Windows will write them;
     * a leading "./" is noise; case is ignored because Drive is not consistent
     * about it and neither are people.
     */
    fun normalisePath(path: String): String =
        path.replace('\\', '/')
            .removePrefix("./")
            .trim()
            .trimEnd('/')
            .lowercase()

    /**
     * "m:ss", "mm:ss" or "hh:mm:ss" to milliseconds.
     *
     * Only ever called for the trim points, which are absent on nearly every
     * track. Anything unparseable is null rather than zero -- zero is a
     * position, and treating a typo as "start at the beginning" would silently
     * discard a trim someone set deliberately.
     */
    fun parseClock(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null

        val parts = text.split(':')
        if (parts.size !in 2..3) return null

        val numbers = parts.map { it.trim().toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null

        val (h, m, s) = when (numbers.size) {
            2 -> Triple(0, numbers[0], numbers[1])
            else -> Triple(numbers[0], numbers[1], numbers[2])
        }
        // Minutes and seconds beyond 59 are a malformed clock, not a big number.
        if (m > 59 || s > 59) return null

        return (h * 3600L + m * 60L + s) * 1000L
    }

    /**
     * Refuses a document from a newer schema rather than half-reading it.
     *
     * A file written by a version that knows more than this one may mean
     * something different by a field with the same name, and guessing is worse
     * than falling back to the tags.
     */
    private inline fun <T> parse(json: String, body: (JSONObject) -> T?): T? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val schema = o.intOrNull("schema") ?: return null
        if (schema > DOC_SCHEMA) return null
        return runCatching { body(o) }.getOrNull()
    }
}

/**
 * org.json turns an explicit JSON null into the STRING "null" through
 * optString, which is how `"lyrics": null` becomes a file named null.
 * Every read goes through here so that cannot happen.
 */
private fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }
}

private fun JSONObject.intOrNull(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    // A number written as a string is common in hand-edited files.
    return when (val value = opt(key)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
