package app.roam.data.catalog.metadata

/** Which catalogue a release came from. */
enum class MetadataSource(val label: String) {
    MUSICBRAINZ("MusicBrainz"),
    DISCOGS("Discogs"),
}

/** One candidate release from a search, before its tracklist is fetched. */
data class ReleaseMatch(
    /** Opaque, and only meaningful to the source that issued it. */
    val id: String,
    val source: MetadataSource,
    val title: String,
    val artist: String,
    val year: Int?,
    val trackCount: Int,
    /** "CD", "Vinyl", "Digital Media" -- what tells reissues apart. */
    val format: String?,
    /** Discogs ships cover URLs with the search; MusicBrainz derives one. */
    val coverUrl: String?,
) {
    val subtitle: String
        get() = listOfNotNull(
            artist.ifBlank { null },
            year?.toString(),
            format,
            trackCount.takeIf { it > 0 }?.let { "$it tracks" },
            source.label,
        ).joinToString(" · ")
}

/** A release with its tracks, in order. */
data class ReleaseDetail(
    val id: String,
    val source: MetadataSource,
    val title: String,
    val artist: String,
    val year: Int?,
    val coverUrl: String?,
    val tracks: List<ReleaseTrack>,
)

data class ReleaseTrack(
    val position: Int,
    val discNo: Int,
    val title: String,
    /** Per-track credit, which differs from the album artist on a compilation. */
    val artist: String,
    val durationMs: Long?,
)

/**
 * A catalogue Roam can ask what an album contains.
 *
 * Two implementations rather than one because they disagree usefully:
 * MusicBrainz is free and needs no account but is thin on rare and physical
 * pressings; Discogs knows about nearly every version ever pressed but will
 * not answer a search at all without a token. Behind one interface so the
 * album screen never has to care which answered.
 */
interface ReleaseSource {
    val source: MetadataSource

    /** False when the source cannot be used yet -- Discogs without a token. */
    suspend fun available(): Boolean

    /**
     * @param compilationsOnly narrows to various-artists collections. Searching
     * "greatest hits" otherwise buries the compilation you meant under every
     * studio album that shares a word with it.
     */
    suspend fun searchReleases(
        query: String,
        limit: Int = 10,
        compilationsOnly: Boolean = false,
    ): Result<List<ReleaseMatch>>

    suspend fun release(id: String): Result<ReleaseDetail?>

    suspend fun searchArtists(query: String, limit: Int = 20): Result<List<ArtistMatch>>

    /** A discography, newest first. */
    suspend fun releasesForArtist(artistId: String, limit: Int = 50): Result<List<ReleaseMatch>>
}

/** One artist from a search, enough to list and to drill into. */
data class ArtistMatch(
    val id: String,
    val source: MetadataSource,
    val name: String,
    /** "British rock band", or a disambiguation like "US punk band". */
    val detail: String?,
    val imageUrl: String?,
)

/**
 * "A1", "B2", "1", "1-04" all have to become a track number.
 *
 * Vinyl positions carry the side rather than a number, so the letter becomes
 * the disc and the digits the track. Anything unparseable falls back to the
 * index it appeared at, because a filename needs SOME number and the order on
 * the page is the order on the record.
 */
internal fun parsePosition(raw: String, index: Int): Pair<Int, Int> {
    val text = raw.trim()
    if (text.isEmpty()) return 1 to (index + 1)

    // "1-04" or "2.3": disc first, then track.
    Regex("""^(\d+)[-.](\d+)$""").find(text)?.let {
        return it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }

    // "A1", "B12": the side letter is the disc, A and B being two sides of one.
    Regex("""^([A-Za-z])(\d+)$""").find(text)?.let {
        val side = it.groupValues[1].uppercase().first() - 'A'
        return (side / 2 + 1) to it.groupValues[2].toInt()
    }

    return 1 to (text.toIntOrNull() ?: (index + 1))
}

/** "4:33" and "1:02:11" to milliseconds. Blank or malformed gives null. */
internal fun parseDuration(raw: String?): Long? {
    val parts = raw?.trim()?.takeIf { it.isNotBlank() }?.split(':') ?: return null
    if (parts.size !in 2..3) return null
    val numbers = parts.map { it.trim().toLongOrNull() ?: return null }
    val seconds = when (numbers.size) {
        2 -> numbers[0] * 60 + numbers[1]
        else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
    }
    return seconds * 1000
}
