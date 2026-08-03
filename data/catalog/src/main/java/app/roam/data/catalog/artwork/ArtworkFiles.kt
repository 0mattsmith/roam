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

    /** Wide header image. fanart.jpg is the Kodi name; banner.jpg is ours. */
    val BANNER_NAMES = listOf("banner.jpg", "banner.jpeg", "banner.png", "fanart.jpg")

    const val ARTIST_UPLOAD_NAME = "artist.jpg"
    /** PNG, not JPEG: a logo without its transparency is a black rectangle. */
    const val LOGO_UPLOAD_NAME = "logo.png"
    const val BANNER_UPLOAD_NAME = "banner.jpg"
    const val ALBUM_UPLOAD_NAME = "cover.jpg"

    /**
     * The next free numbered name for an image being retired.
     *
     * cover.jpg -> cover1.jpg -> cover2.jpg ... so the live file is ALWAYS
     * cover.jpg and nothing that points at it ever has to change. Numbering
     * ignores the extension, so a JPEG replaced by a PNG still counts.
     */
    fun nextArchiveName(currentName: String, siblings: List<String>): String {
        val base = currentName.substringBeforeLast('.', currentName)
        val extension = currentName.substringAfterLast('.', "")
        val pattern = Regex("^${Regex.escape(base)}(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE)

        val highest = siblings.mapNotNull { pattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        val next = highest + 1
        return if (extension.isEmpty()) "$base$next" else "$base$next.$extension"
    }

    /** Retired versions of [currentName], newest number last. */
    fun archivedVersions(currentName: String, siblings: List<String>): List<String> {
        val base = currentName.substringBeforeLast('.', currentName)
        val pattern = Regex("^${Regex.escape(base)}(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE)
        return siblings
            .mapNotNull { name -> pattern.find(name)?.let { it.groupValues[1].toInt() to name } }
            .sortedBy { it.first }
            .map { it.second }
    }
}
