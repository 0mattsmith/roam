package app.roam.core.model

/** The three tag dialects a music library actually contains. */
enum class TagFormat { ID3V2, MP4, VORBIS }

/**
 * One metadata field, and what it is called everywhere it appears.
 *
 * @param jsonKey the key in `album.json` / `<track>.json`.
 * @param label what the editor calls it.
 * @param id3 ID3v2 frame, for MP3.
 * @param mp4 MP4 atom, for M4A and AAC.
 * @param vorbis Vorbis comment, for FLAC, Ogg and Opus.
 */
data class TagMapping(
    val jsonKey: String,
    val label: String,
    val id3: String? = null,
    val mp4: String? = null,
    val vorbis: String? = null,
) {
    /**
     * True when nothing outside Roam has a name for this.
     *
     * Worth showing in the editor rather than hiding: a field with no tag
     * equivalent is one that will NOT survive being read by another player, and
     * that is the difference between a correction that travels and one that
     * only exists here.
     */
    val roamOnly: Boolean get() = id3 == null && mp4 == null && vorbis == null

    fun name(format: TagFormat): String? = when (format) {
        TagFormat.ID3V2 -> id3
        TagFormat.MP4 -> mp4
        TagFormat.VORBIS -> vorbis
    }
}

/**
 * The contract between the sidecar documents and the files they describe.
 *
 * One table, read by three things that must not drift apart: the tag reader,
 * the document writer, and the editor's tag-name toggle. Written out separately
 * in each of those, they would eventually disagree about something like
 * ALBUMARTISTSORT and nobody would notice until a syncer round-tripped a field
 * into the wrong frame.
 *
 * See docs/ALBUM_JSON.md, "How this maps onto tags".
 */
object TagMap {

    val ALL: List<TagMapping> = listOf(
        TagMapping("title", "Title", "TIT2", "©nam", "TITLE"),
        TagMapping("artist", "Artist", "TPE1", "©ART", "ARTIST"),
        TagMapping("album", "Album", "TALB", "©alb", "ALBUM"),
        TagMapping("album_artist", "Album artist", "TPE2", "aART", "ALBUMARTIST"),
        // Both halves of one frame. ID3 and MP4 store "3/12" as a pair, Vorbis
        // splits it across two comments -- which is why the json splits it too.
        TagMapping("track_number", "Track", "TRCK", "trkn", "TRACKNUMBER"),
        TagMapping("total_tracks", "of", "TRCK", "trkn", "TRACKTOTAL"),
        TagMapping("disc_number", "Disc", "TPOS", "disk", "DISCNUMBER"),
        TagMapping("total_discs", "of", "TPOS", "disk", "DISCTOTAL"),
        TagMapping("year", "Year", "TDRC", "©day", "DATE"),
        TagMapping(
            "original_year", "Original year",
            "TDOR", "----:com.apple.iTunes:ORIGINAL YEAR", "ORIGINALYEAR",
        ),
        TagMapping("genres", "Genres", "TCON", "©gen", "GENRE"),
        TagMapping("composer", "Composer", "TCOM", "©wrt", "COMPOSER"),
        TagMapping("grouping", "Grouping", "TIT1", "©grp", "GROUPING"),
        TagMapping("is_compilation", "Compilation", "TCMP", "cpil", "COMPILATION"),
        TagMapping("title_sort", "Title sort", "TSOT", "sonm", "TITLESORT"),
        TagMapping("artist_sort", "Artist sort", "TSOP", "soar", "ARTISTSORT"),
        TagMapping("album_sort", "Album sort", "TSOA", "soal", "ALBUMSORT"),
        TagMapping("album_artist_sort", "Album artist sort", "TSO2", "soaa", "ALBUMARTISTSORT"),
        TagMapping("composer_sort", "Composer sort", "TSOC", "soco", "COMPOSERSORT"),
        TagMapping("cover_art", "Cover art", "APIC", "covr", "METADATA_BLOCK_PICTURE"),

        // Roam's own. No frame, no atom, no comment -- these live in the
        // documents and nowhere else, and the editor says so.
        TagMapping("start_at", "Start at"),
        TagMapping("end_at", "End at"),
        TagMapping("group_artist", "Group artist"),
        TagMapping("lyrics_file", "Lyrics"),
    )

    private val byKey: Map<String, TagMapping> = ALL.associateBy { it.jsonKey }

    fun of(jsonKey: String): TagMapping? = byKey[jsonKey]

    /**
     * Which dialect a file speaks, from its mime type.
     *
     * Null for anything unrecognised rather than a guess: showing an ID3 frame
     * name over a FLAC field would be worse than showing nothing.
     */
    fun formatOf(mimeType: String?): TagFormat? = when {
        mimeType == null -> null
        mimeType.contains("mpeg") || mimeType.contains("mp3") -> TagFormat.ID3V2
        mimeType.contains("mp4") || mimeType.contains("m4a") || mimeType.contains("aac") ->
            TagFormat.MP4
        mimeType.contains("flac") || mimeType.contains("ogg") || mimeType.contains("opus") ||
            mimeType.contains("vorbis") -> TagFormat.VORBIS
        else -> null
    }
}
