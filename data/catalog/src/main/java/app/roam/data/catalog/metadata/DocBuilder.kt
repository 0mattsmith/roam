package app.roam.data.catalog.metadata

import app.roam.core.database.DocTrackRow
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

    /** Key order for `album.json`, and what counts as recognised. */
    private val ALBUM_KEYS = listOf(
        "schema", "album_artist", "album_title", "year", "original_year",
        "genres", "is_compilation", "total_discs", "total_tracks", "cover_art", "tracks",
    )

    private val ENTRY_KEYS = listOf(
        "disc", "track", "title", "artist", "file", "track_meta", "lyrics", "external",
    )

    private val TRACK_KEYS = listOf(
        "schema", "title", "track_number", "disc_number", "artist", "album",
        "album_artist", "year", "original_year", "genres", "composer",
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

    fun albumDocument(existing: JSONObject?, rows: List<DocTrackRow>): String {
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
                add("cover_art" to Json.Str(existing.stringOr("cover_art", "cover.jpg")))
                add("tracks" to Json.Arr(entries))
                existing?.let { addAll(it.preserving(ALBUM_KEYS.toSet())) }
            }
        )
        return document.render()
    }

    fun trackDocument(existing: JSONObject?, row: DocTrackRow): String {
        val genres = splitGenres(row.genre)
        val document = Json.Obj(
            buildList<Pair<String, Json>> {
                add("schema" to Json.Num(DOC_SCHEMA.toLong()))
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
                existing?.opt("composer").asString().json()?.let { add("composer" to it) }
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
     * The single `genre` column back into a list.
     *
     * The same separators [LibraryDocs] splits on when reading, so a document
     * written here and read back gives the same list -- and a genre string that
     * arrived from an old tag as "Britpop; Indie Rock" becomes two on the way
     * out rather than staying one unmatchable lump.
     */
    fun splitGenres(genre: String?): List<String> =
        genre?.split(';', ',', '/')
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
            .distinctBy { it.lowercase() }

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
