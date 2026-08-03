package app.roam.core.model

/**
 * Sort orders offered in the library.
 *
 * The SQL fragment lives here rather than being assembled from user input:
 * these are fixed constants spliced into a raw query, so there is nothing an
 * outside value could influence.
 */
/**
 * [groupByAlbum] decides whether the list draws album headers. Carrying it on
 * the sort rather than testing for one specific constant in the UI means adding
 * another album-major order does not need the screen to know about it.
 */
enum class TrackSort(
    val label: String,
    val orderBy: String,
    val groupByAlbum: Boolean = false,
) {
    TITLE("Title", "t.title COLLATE NOCASE"),
    // aar, not ar: the ALBUM's artist. Grouping a compilation by each track's
    // own artist scatters it across the library one guest at a time.
    ARTIST("Artist", "aar.sortName, al.sortTitle, t.discNo, t.trackNo"),
    ALBUM("Album", "al.sortTitle, t.discNo, t.trackNo", groupByAlbum = true),

    /**
     * Albums again, but walked artist by artist and oldest first within each --
     * a discography rather than an alphabetical shelf. The year is what makes
     * this genuinely different from ARTIST rather than the same order with
     * headers bolted on.
     */
    ALBUM_BY_ARTIST(
        "Album by artist",
        "aar.sortName, al.year, al.sortTitle, t.discNo, t.trackNo",
        groupByAlbum = true,
    ),
    RECENT("Recently added", "t.addedAt DESC"),
}

enum class AlbumSort(val label: String, val orderBy: String) {
    TITLE("Title", "al.sortTitle"),
    ARTIST("Artist", "ar.sortName, al.year"),
    YEAR("Year", "al.year DESC, al.sortTitle"),
    RECENT("Recently added", "al.addedAt DESC"),
}

enum class ArtistSort(val label: String, val orderBy: String) {
    NAME("Name", "ar.sortName"),
    TRACKS("Most tracks", "ar.trackCount DESC, ar.sortName"),
}

/** Which list the library is showing. */
enum class LibraryTab(val label: String) {
    TRACKS("Tracks"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    PLAYLISTS("Playlists"),
}
