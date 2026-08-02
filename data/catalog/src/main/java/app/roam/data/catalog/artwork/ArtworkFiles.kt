package app.roam.data.catalog.artwork

/**
 * The filenames Roam recognises as artwork sitting beside the music, shared
 * between the automatic passes and the manual ones so the two can never
 * disagree about what counts.
 *
 * Matched case-insensitively: real libraries hold both folder.jpg and
 * Folder.jpg, and Drive's `name =` is case-sensitive.
 */
object ArtworkFiles {

    val ARTIST_NAMES = listOf("artist.jpg", "artist.jpeg", "artist.png", "folder.jpg")

    /** cover.jpg first -- it is the near-universal convention for album art. */
    val ALBUM_NAMES = listOf(
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "album.jpg",
    )

    /** Kodi and Plex both use logo.png in the artist folder; follow the herd. */
    val LOGO_NAMES = listOf("logo.png", "logo.jpg", "clearlogo.png")

    const val ARTIST_UPLOAD_NAME = "artist.jpg"
    /** PNG, not JPEG: a logo without its transparency is a black rectangle. */
    const val LOGO_UPLOAD_NAME = "logo.png"
    const val ALBUM_UPLOAD_NAME = "cover.jpg"
}
