package app.roam.data.catalog.metadata

/**
 * One recording a catalogue knows, with every release it appears on.
 *
 * What identifying a video needs. The recording says WHAT was heard; its
 * releases say where it was published -- and which of those a track files
 * under is a separate decision, because the same song is on the single, the
 * album and four compilations.
 */
data class RecordingMatch(
    val id: String,
    val title: String,
    /** The whole credit as written, "Queen & David Bowie" and all. */
    val artist: String,
    /**
     * Every act the credit names, without the words joining them -- both the
     * name as credited and the artist's own where the two differ. What a
     * channel name or a description is compared against, because a video
     * names one act and the credit may name three.
     */
    val artistNames: List<String>,
    val lengthMs: Long?,
    val releases: List<ReleaseAppearance>,
)

/** A release a recording appears on, and where on it. */
data class ReleaseAppearance(
    val id: String,
    /** Every edition of the same record shares this. */
    val groupId: String?,
    val title: String,
    /**
     * The release's own credit -- which a search states only where it differs
     * from the recording's: "Various Artists" on a compilation, "Queen" on the
     * album whose single said "Queen & David Bowie". Blank means the same.
     */
    val artist: String,
    /** "1994-12-18", "1994-12" or "1994", as precisely as the catalogue knows. */
    val date: String?,
    /** "Official", "Promotion", "Bootleg"... Null when not given. */
    val status: String?,
    /** "Album", "Single", "EP"... */
    val primaryType: String?,
    /** "Compilation", "Live", "Soundtrack"... Empty for a plain studio release. */
    val secondaryTypes: List<String>,
    /** The track's place on its disc. */
    val trackNumber: Int?,
    val coverUrl: String?,
) {
    val year: Int? get() = date?.take(4)?.toIntOrNull()

    val isCompilation: Boolean get() = secondaryTypes.any { it.equals("Compilation", ignoreCase = true) }
}
