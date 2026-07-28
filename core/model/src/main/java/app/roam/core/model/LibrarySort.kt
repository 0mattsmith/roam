package app.roam.core.model

/**
 * Sort orders offered in the library.
 *
 * The SQL fragment lives here rather than being assembled from user input:
 * these are fixed constants spliced into a raw query, so there is nothing an
 * outside value could influence.
 */
enum class TrackSort(val label: String, val orderBy: String) {
    TITLE("Title", "t.title COLLATE NOCASE"),
    ARTIST("Artist", "ar.sortName, al.sortTitle, t.discNo, t.trackNo"),
    ALBUM("Album", "al.sortTitle, t.discNo, t.trackNo"),
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
