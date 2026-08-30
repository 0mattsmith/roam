package app.roam.data.catalog.metadata

import app.roam.core.database.DocTrackRow
import app.roam.core.model.Genres
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns catalogue rows into the two files that describe an album.
 *
 * Pure and separate from the upload for the same reason [DocMatcher] is
 * separate from the apply: this is the part whose mistakes are silent. A
 * dropped field does not throw, it just quietly deletes somebody's work on the
 * next save -- and these files are shared with an external editor, so "somebody"
 * is not necessarily Roam.
 *
 * Every builder takes what is already on the source and writes THROUGH it,
 * keeping every field it does not recognise. See docs/ALBUM_JSON.md, "Rewriting".
 */
object DocBuilder {

    /**
     * Key order for `album.json`, and what counts as recognised.
     *
     * Public alongside [ARTIST_KEYS] because [withRetiredArtwork] is handed one
     * of the two by its caller -- a patch has to know the order to re-emit in,
     * and the two documents do not share one.
     */
    val ALBUM_KEYS = listOf(
        "schema", "modified", "musicbrainz_release_id", "discogs_release_id",
        "album_artist", "album_title", "year", "original_year",
        "genres", "is_compilation", "total_discs", "total_tracks",
        "album_sort", "album_artist_sort", "cover_art", "previous_artwork", "tracks",
    )

    /** Key order for `artist.json`. Roam does not build these yet; it patches them. */
    val ARTIST_KEYS = listOf(
        "schema", "modified", "musicbrainz_artist_id", "discogs_artist_id",
        "artist_name", "active_from", "active_to", "debut_album",
        "debut_album_year", "total_studio_albums", "artist_info", "artist_image",
        "artist_logo", "artist_banner", "previous_artwork", "sort_as",
    )

    const val PREVIOUS_ARTWORK = "previous_artwork"

    private val ENTRY_KEYS = listOf(
        "disc", "track", "title", "artist", "file", "track_meta", "lyrics", "external",
    )

    private val TRACK_KEYS = listOf(
        "schema", "modified", "musicbrainz_recording_id",
        "title", "track_number", "disc_number", "artist", "album",
        "album_artist", "year", "original_year", "genres", "composer", "grouping",
        "is_compilation", "total_tracks", "total_discs",
        "title_sort", "artist_sort", "album_sort", "album_artist_sort", "composer_sort",
        "duration_seconds", "audio_file", "lyrics_file", "lyrics_checked",
        "start_at", "end_at",
    )

    /**
     * The album folder these tracks share, as path segments.
     *
     * The longest common prefix, taken segment by segment rather than character
     * by character -- "Oasis/Definitely Maybe" and "Oasis/Definitely Maybe Live"
     * share fourteen characters and no folder at all. Disc subfolders fall out
     * of this for free: two tracks in `.../Disc 1` and `.../Disc 2` agree
     * exactly up to the album.
     *
     * Empty when they agree on nothing, which means the album is spread across
     * the library and has no folder to be indexed from.
     */
    fun albumFolder(rows: List<DocTrackRow>): List<String> {
        val paths = rows.mapNotNull { row ->
            row.folderPath?.let { LibraryDocs.tidyPath(it).split('/').filter(String::isNotBlank) }
        }
        if (paths.isEmpty()) return emptyList()

        var common = paths.first()
        for (path in paths.drop(1)) {
            val shared = common.zip(path).takeWhile { (a, b) -> a.equals(b, ignoreCase = true) }
            common = shared.map { it.first }
            if (common.isEmpty()) break
        }
        return common
    }

    /** Where a track sits relative to the album folder. "" when it sits in it. */
    fun below(albumFolder: List<String>, row: DocTrackRow): String {
        val path = LibraryDocs.tidyPath(row.folderPath.orEmpty())
            .split('/').filter(String::isNotBlank)
        return path.drop(albumFolder.size).joinToString("/")
    }

    /** The entry's `file`: its path relative to the album folder. */
    fun locator(albumFolder: List<String>, row: DocTrackRow): String? {
        val name = row.fileName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val sub = below(albumFolder, row)
        return if (sub.isEmpty()) name else "$sub/$name"
    }

    /** `01 Rock.mp3` describes itself in `01 Rock.json`. */
    fun trackDocName(fileName: String): String = "${fileName.substringBeforeLast('.', fileName)}.json"

    /**
     * @param modified stamped into the document. Passed in rather than read
     *   from the clock here so this stays a pure function of its inputs -- a
     *   builder that quietly consulted the time could not be tested for
     *   round-trip stability at all.
     */
    fun albumDocument(
        existing: JSONObject?,
        rows: List<DocTrackRow>,
        modified: String = isoNow(),
    ): String {
        val folder = albumFolder(rows)
        val first = rows.first()

        // Entries are matched to their old selves by locator so that per-entry
        // fields Roam does not set -- `lyrics`, and anything a future version or
        // the desktop editor adds -- survive the rewrite.
        val previous = existing?.optJSONArray("tracks")
            ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }
            .orEmpty()
            .mapNotNull { entry ->
                val file = entry.optString("file").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LibraryDocs.normalisePath(file) to entry
            }
            .toMap()

        val entries = rows.mapNotNull { row ->
            val file = locator(folder, row) ?: return@mapNotNull null
            val was = previous[LibraryDocs.normalisePath(file)]
            val trackMeta = row.fileName?.let { name ->
                val sub = below(folder, row)
                val leaf = trackDocName(name)
                if (sub.isEmpty()) leaf else "$sub/$leaf"
            }

            // The track artist is only worth stating when it differs from the
            // album's. Repeating it on every entry of a normal album is noise in
            // a file people are meant to read.
            val artist = row.artistName.takeIf { !it.equals(row.albumArtistName, ignoreCase = true) }

            Json.Obj(
                buildList<Pair<String, Json>> {
                    add("disc" to Json.Num((row.discNo ?: 1).toLong()))
                    row.trackNo?.let { add("track" to Json.Num(it.toLong())) }
                    add("title" to Json.Str(row.title))
                    artist.json()?.let { add("artist" to it) }
                    add("file" to Json.Str(file))
                    trackMeta.json()?.let { add("track_meta" to it) }
                    // Never invented. Roam does not know whether a .lrc is
                    // beside the file without asking, and a path pointing at
                    // nothing is worse than no path -- the reader falls back to
                    // the basename lookup either way.
                    was?.takeIf { it.has("lyrics") }?.let { add("lyrics" to fromJson(it.opt("lyrics"))) }
                    was?.optJSONObject("external")?.let { add("external" to fromJson(it)) }
                    was?.let { addAll(it.preserving(ENTRY_KEYS.toSet())) }
                }
            )
        }

        val genres = splitGenres(first.genre)
        val document = Json.Obj(
            buildList<Pair<String, Json>> {
                add("schema" to Json.Num(DOC_SCHEMA.toLong()))
                add("modified" to Json.Str(modified))
                add("album_artist" to Json.Str(first.albumArtistName))
                add("album_title" to Json.Str(first.albumTitle))
                first.year.json()?.let { add("year" to it) }
                first.originalYear.json()?.let { add("original_year" to it) }
                if (genres.isNotEmpty()) add("genres" to genres.jsonArray())
                add("is_compilation" to first.compilation.json())
                add("total_discs" to Json.Num(
                    (rows.mapNotNull { it.discNo }.maxOrNull() ?: first.discTotal).coerceAtLeast(1).toLong()
                ))
                add("total_tracks" to Json.Num(rows.size.toLong()))
                first.albumSort.json()?.let { add("album_sort" to it) }
                first.albumArtistSort.json()?.let { add("album_artist_sort" to it) }
                add("cover_art" to Json.Str(existing.stringOr("cover_art", "cover.jpg")))
                // Carried straight across. The retired covers are a fact about
                // the FOLDER, and nothing in the catalogue knows them -- they
                // are appended by whatever retires an image, not rebuilt here.
                existing?.optJSONArray(PREVIOUS_ARTWORK)
                    ?.takeIf { it.length() > 0 }
                    ?.let { add(PREVIOUS_ARTWORK to fromJson(it)) }
                add("tracks" to Json.Arr(entries))
                existing?.let { addAll(it.preserving(ALBUM_KEYS.toSet())) }
            }
        )
        return document.render()
    }

    fun trackDocument(
        existing: JSONObject?,
        row: DocTrackRow,
        modified: String = isoNow(),
    ): String {
        val genres = splitGenres(row.genre)
        val document = Json.Obj(
            buildList<Pair<String, Json>> {
                add("schema" to Json.Num(DOC_SCHEMA.toLong()))
                add("modified" to Json.Str(modified))
                add("title" to Json.Str(row.title))
                row.trackNo.json()?.let { add("track_number" to it) }
                add("disc_number" to Json.Num((row.discNo ?: 1).toLong()))
                add("artist" to Json.Str(row.artistName))
                add("album" to Json.Str(row.albumTitle))
                // Repeated deliberately: a file moved somewhere else still
                // knows what it is, which is most of the point of having one
                // of these per track.
                add("album_artist" to Json.Str(row.albumArtistName))
                row.year.json()?.let { add("year" to it) }
                row.originalYear.json()?.let { add("original_year" to it) }
                if (genres.isNotEmpty()) add("genres" to genres.jsonArray())
                // The catalogue knows these now, so they are written rather
                // than merely preserved -- which is the difference between a
                // field surviving a rewrite and a field being editable.
                (row.composer.json() ?: existing?.opt("composer").asString().json())
                    ?.let { add("composer" to it) }
                row.grouping.json()?.let { add("grouping" to it) }
                add("is_compilation" to row.compilation.json())
                row.trackTotal.json()?.let { add("total_tracks" to it) }
                add("total_discs" to Json.Num(row.discTotal.coerceAtLeast(1).toLong()))
                row.titleSort.json()?.let { add("title_sort" to it) }
                row.artistSort.json()?.let { add("artist_sort" to it) }
                row.albumSort.json()?.let { add("album_sort" to it) }
                row.albumArtistSort.json()?.let { add("album_artist_sort" to it) }
                row.composerSort.json()?.let { add("composer_sort" to it) }
                if (row.durationMs > 0) add("duration_seconds" to Json.Num(row.durationMs / 1000))
                row.fileName.json()?.let { add("audio_file" to it) }
                existing?.takeIf { it.has("lyrics_file") }
                    ?.let { add("lyrics_file" to fromJson(it.opt("lyrics_file"))) }
                // A miss is an answer, and this is where it stops being one
                // Roam alone remembers: a reinstall throws the database away,
                // and without this every track with no words is asked about
                // again from scratch.
                row.lyricsAttemptedAt?.let { add("lyrics_checked" to Json.Str(isoDate(it))) }
                clock(row.startMs)?.let { add("start_at" to Json.Str(it)) }
                clock(row.endMs)?.let { add("end_at" to Json.Str(it)) }
                existing?.let { addAll(it.preserving(TRACK_KEYS.toSet())) }
            }
        )
        return document.render()
    }

    /**
     * The same document with one more retired image named in it.
     *
     * A patch rather than a rebuild, and that distinction matters: replacing a
     * cover must not have the side effect of rewriting an album's metadata from
     * the catalogue. It re-emits what is already there in the documented key
     * order, so the diff is the one line that changed.
     *
     * Returns null when there is nothing to do -- no document, or this image is
     * already listed -- so the caller can skip the upload entirely.
     */
    fun withRetiredArtwork(
        existing: JSONObject?,
        retired: String,
        keyOrder: List<String>,
        modified: String = isoNow(),
    ): String? {
        if (existing == null) return null
        val name = retired.trim().takeIf { it.isNotEmpty() } ?: return null

        val already = existing.optJSONArray(PREVIOUS_ARTWORK)
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() } }
            .orEmpty()
        if (already.any { it.equals(name, ignoreCase = true) }) return null

        // Appended, never sorted: the order IS the history, and reordering
        // would make every save show the whole list as changed.
        val updated = (already.filter { it.isNotEmpty() } + name).jsonArray()

        val known = keyOrder.toSet()
        val entries = buildList<Pair<String, Json>> {
            for (key in keyOrder) {
                when {
                    key == PREVIOUS_ARTWORK -> add(key to updated)
                    // Restamped: this IS a write, and a reader comparing two
                    // copies has to see that it happened.
                    key == "modified" -> add(key to Json.Str(modified))
                    existing.has(key) -> add(key to fromJson(existing.opt(key)))
                }
            }
            addAll(existing.preserving(known))
        }
        return Json.Obj(entries).render()
    }

    /**
     * The single `genre` column back into a list.
     *
     * Delegates to [Genres] so the writer and the reader cannot drift: a
     * document written here and read back has to give the same list, and two
     * implementations of "does a slash separate genres" would not.
     */
    fun splitGenres(genre: String?): List<String> = Genres.split(genre)

    /** Milliseconds as the "m:ss" the contract uses. Null stays null. */
    fun clock(ms: Long?): String? {
        if (ms == null || ms < 0) return null
        val total = ms / 1000
        val hours = total / 3600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, (total % 3600) / 60, total % 60)
        } else {
            "%d:%02d".format(total / 60, total % 60)
        }
    }

    /** Now, in UTC, to the second. The stamp a writer puts on a document. */
    fun isoNow(at: Long = System.currentTimeMillis()): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(at / 1000))

    fun isoDate(epochMillis: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(
            Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()
        )

    private fun JSONObject?.stringOr(key: String, fallback: String): String =
        this?.opt(key).asString()?.takeIf { it.isNotBlank() } ?: fallback

    private fun Any?.asString(): String? = when {
        this == null || this == JSONObject.NULL -> null
        else -> toString().trim().takeIf { it.isNotEmpty() && it != "null" }
    }
}
