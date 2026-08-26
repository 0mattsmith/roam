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
    /** Images this artist has had before, oldest first. See [AlbumDoc.previousArtwork]. */
    val previousArtwork: List<String>,
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
    val genres: List<String>,
    val isCompilation: Boolean,
    val totalDiscs: Int?,
    val totalTracks: Int?,
    /** ALBUMSORT and ALBUMARTISTSORT. How the album files, when it differs. */
    val albumSort: String?,
    val albumArtistSort: String?,
    val coverArt: String,
    /**
     * Covers this album has had before, oldest retired first.
     *
     * A record of the numbering Roam already does -- cover.jpg becomes
     * cover1.jpg when it is replaced -- so an external editor gets the
     * history without having to know the convention, and a reader gets it
     * without listing the folder.
     *
     * Never load-bearing. The FOLDER says which images exist; this only
     * points at them, so an entry naming a missing file is skipped and a
     * numbered image it does not mention is still found.
     */
    val previousArtwork: List<String>,
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
    val genres: List<String>,
    val composer: String?,
    /** TIT1 / grouping. Carried, never interpreted -- see the entity comment. */
    val grouping: String?,
    /**
     * The totals a track states about its own release.
     *
     * Part two of TRCK and TPOS, which is why they sit beside the numbers
     * rather than only in the album index.
     */
    val totalTracks: Int?,
    val totalDiscs: Int?,
    val isCompilation: Boolean,
    /** The sort orders, all five. See docs/ALBUM_JSON.md. */
    val titleSort: String?,
    val artistSort: String?,
    val albumSort: String?,
    val albumArtistSort: String?,
    val composerSort: String?,
    val durationMs: Long?,
    val audioFile: String?,
    val lyricsFile: String?,
    /**
     * When somebody last looked for words, `YYYY-MM-DD`.
     *
     * Only meaningful alongside [lyricsFile]. A null file with no date means
     * nobody has looked; a null file WITH a date means somebody looked and
     * there are none, so nothing should keep asking. Roam knows that today
     * only in its own database, which a reinstall discards -- recorded here it
     * travels with the music.
     */
    val lyricsChecked: String?,
    /** Absent unless deliberately trimmed. Never derived from a duration. */
    val startMs: Long?,
    val endMs: Long?,
) {
    /** True when a lookup has happened, whatever it found. */
    val lyricsResolved: Boolean get() = lyricsFile != null || lyricsChecked != null

    /** True when somebody looked and there was nothing to find. */
    val knownToHaveNoLyrics: Boolean get() = lyricsFile == null && lyricsChecked != null
}

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
            previousArtwork = o.strings("previous_artwork"),
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
            genres = o.genres(),
            isCompilation = o.optBoolean("is_compilation", false),
            totalDiscs = o.intOrNull("total_discs"),
            totalTracks = o.intOrNull("total_tracks"),
            albumSort = o.stringOrNull("album_sort"),
            albumArtistSort = o.stringOrNull("album_artist_sort"),
            coverArt = o.stringOrNull("cover_art") ?: "cover.jpg",
            previousArtwork = o.strings("previous_artwork"),
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
            genres = o.genres(),
            composer = o.stringOrNull("composer"),
            grouping = o.stringOrNull("grouping"),
            totalTracks = o.intOrNull("total_tracks"),
            totalDiscs = o.intOrNull("total_discs"),
            isCompilation = o.optBoolean("is_compilation", false),
            titleSort = o.stringOrNull("title_sort"),
            artistSort = o.stringOrNull("artist_sort"),
            albumSort = o.stringOrNull("album_sort"),
            albumArtistSort = o.stringOrNull("album_artist_sort"),
            composerSort = o.stringOrNull("composer_sort"),
            durationMs = o.intOrNull("duration_seconds")?.let { it * 1000L },
            audioFile = o.stringOrNull("audio_file"),
            lyricsFile = o.stringOrNull("lyrics_file"),
            lyricsChecked = o.stringOrNull("lyrics_checked"),
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
     * Tidies a path without deciding anything about it.
     *
     * Backslashes become slashes because an editor on Windows will write them,
     * and a leading "./" is noise. Case is left alone: this is the form handed
     * to a query, where the stored path has its real case.
     */
    fun tidyPath(path: String): String =
        path.replace('\\', '/')
            .removePrefix("./")
            .trim()
            .trimEnd('/')

    /**
     * Compares paths the way a filesystem someone typed into behaves.
     *
     * As [tidyPath], plus case folding -- Drive is not consistent about case
     * and neither are people. Only ever for COMPARING; never store this.
     */
    fun normalisePath(path: String): String = tidyPath(path).lowercase()

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

/**
 * Genres, as a list, however they were written.
 *
 * A track has several: Britpop AND Alternative Rock AND Indie, and a smart
 * playlist asking for one of them should find it. Crammed into a single string
 * they can only be matched by guessing at separators, which is what made genre
 * rules unreliable in the first place.
 *
 * The singular "genre" is still read, because embedded tags only ever carry one
 * and a hand-written file may too. A string containing separators is split, so
 * "Britpop; Indie Rock" from an old tag becomes two.
 */
private fun JSONObject.genres(): List<String> {
    val fromArray = optJSONArray("genres")
        ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } }
        .orEmpty()

    val fromString = stringOrNull("genre")
        ?.split(';', ',', '/')
        .orEmpty()

    return (fromArray + fromString)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "null" }
        // Case-insensitively distinct, keeping the first spelling seen.
        .distinctBy { it.lowercase() }
}

/** A string array, minus the blanks and the org.json "null" trap. */
private fun JSONObject.strings(key: String): List<String> =
    optJSONArray(key)
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        .orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "null" }

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
