package app.roam.feature.downloader

/**
 * Pulls a YouTube video id out of whatever the share sheet handed over.
 *
 * Shares are messy. YouTube sends "Watch this: <title> https://youtu.be/ID?si=x"
 * as plain text, other apps send a bare link, and some prepend a comment. So
 * this scans for a link rather than assuming the whole string is one.
 *
 * Kept as pure string handling with no Android types, because the awkward part
 * is the URL zoo and that is worth testing on the JVM.
 */
object YoutubeLink {

    /** 11 characters of base64url, which is what every YouTube id has been. */
    private const val ID = "[A-Za-z0-9_-]{11}"

    /**
     * Ordered: the most specific paths first, so /shorts/ID is not mistaken for
     * a bare path segment by a looser pattern later in the list.
     */
    private val PATTERNS = listOf(
        Regex("""[?&]v=($ID)"""),                       // watch?v=ID, music and m too
        Regex("""youtu\.be/($ID)"""),                   // the share-sheet short form
        Regex("""youtube\.com/shorts/($ID)"""),
        Regex("""youtube\.com/embed/($ID)"""),
        Regex("""youtube\.com/live/($ID)"""),
        Regex("""youtube\.com/v/($ID)"""),
    )

    /**
     * The id, or null when this is not a YouTube link at all.
     *
     * Null is the ordinary answer for "someone shared a paragraph of text",
     * which happens the moment Roam claims text/plain -- the share sheet offers
     * it for every selection anywhere on the device.
     */
    fun videoId(shared: String?): String? {
        if (shared.isNullOrBlank()) return null
        // Only inside a youtube host, so a bare 11-character word in a sentence
        // is not treated as an id.
        if (!shared.contains("youtu", ignoreCase = true)) return null

        return PATTERNS.firstNotNullOfOrNull { it.find(shared)?.groupValues?.get(1) }
    }

    /** The canonical watch URL for an id, which is what yt-dlp is given. */
    fun watchUrl(videoId: String): String = "https://music.youtube.com/watch?v=$videoId"
}
