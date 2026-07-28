package app.roam.data.catalog.artwork

/**
 * Shared between the automatic photo pass and the manual one in the UI, so the
 * two can never disagree about which filenames count as an artist photo.
 */
object ArtistPhotos {
    /** Matched case-insensitively; real libraries hold both folder.jpg and Folder.jpg. */
    val NAMES = listOf("artist.jpg", "artist.jpeg", "artist.png", "folder.jpg")

    /** What Roam writes when it puts a photo back on the source. */
    const val UPLOAD_NAME = "artist.jpg"
}
