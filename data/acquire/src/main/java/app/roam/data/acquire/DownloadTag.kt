package app.roam.data.acquire

/**
 * A request, written into a WorkManager tag and read back out.
 *
 * WorkInfo exposes state, progress, tags and output -- but NOT the input data
 * a job was given. So a failed job could not be retried from its WorkInfo
 * alone, and a queued one could not even say what it is for. The whole
 * request riding along as a tag is what makes both possible without keeping a
 * parallel copy of the Adding list in Room.
 *
 * Apart from the worker and pure, so it is tested on the JVM without loading
 * a WorkManager class -- and a retry is only ever as good as what this keeps.
 */
internal object DownloadTag {
    const val PREFIX = "req:"

    /**
     * A unit separator, because titles legitimately contain every punctuation
     * mark anyone would reach for as a delimiter.
     */
    private const val SEP = "\u001F"

    fun encode(request: DownloadRequest): String = PREFIX + listOf(
        request.url, request.title, request.artist, request.album,
        request.trackNo?.toString().orEmpty(),
        request.durationMs?.toString().orEmpty(),
        request.coverUrl.orEmpty(),
        request.albumArtist,
        request.year?.toString().orEmpty(),
        if (request.compilation) "1" else "",
    ).joinToString(SEP)

    /**
     * The request a tag carries, or null for one that is not a request.
     *
     * getOrNull throughout: a job queued by an older build has a shorter tag,
     * and it must still be listable and retryable rather than vanishing from
     * the Adding list.
     */
    fun decode(tag: String): DownloadRequest? {
        if (!tag.startsWith(PREFIX)) return null
        val parts = tag.removePrefix(PREFIX).split(SEP)
        if (parts.size < 4) return null
        return DownloadRequest(
            url = parts[0],
            title = parts[1],
            artist = parts[2],
            album = parts[3],
            trackNo = parts.getOrNull(4)?.toIntOrNull(),
            durationMs = parts.getOrNull(5)?.toLongOrNull(),
            coverUrl = parts.getOrNull(6)?.takeIf { it.isNotBlank() },
            // The placement, which the tag did not carry until the search page
            // needed a retry to land where the first attempt would have:
            // without it a retried compilation track filed under its own
            // performer, and the album it belonged to split in two.
            albumArtist = parts.getOrNull(7).orEmpty(),
            year = parts.getOrNull(8)?.toIntOrNull(),
            compilation = parts.getOrNull(9) == "1",
        )
    }
}
