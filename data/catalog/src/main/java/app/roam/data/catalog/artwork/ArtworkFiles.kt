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

    const val ARTIST_UPLOAD_NAME = "artist.jpg"
    const val ALBUM_UPLOAD_NAME = "cover.jpg"
}
