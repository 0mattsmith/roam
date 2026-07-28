package app.roam.data.catalog.tags

/** What a tag block yielded. Any field may be absent; callers fall back to path inference. */
class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    /** Raw bytes of the embedded cover, if the file carries one. */
    val artwork: ByteArray? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && artwork == null
}

/** "5/12" to 5 and 12. Also handles a bare "5". */
internal fun parsePair(raw: String?): Pair<Int?, Int?> {
    if (raw.isNullOrBlank()) return null to null
    val parts = raw.trim().split('/', '-')
    return parts.getOrNull(0)?.trim()?.toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
}

/** Tags carry all sorts of date formats; the year is the only part worth keeping. */
internal fun parseYear(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    return Regex("""\d{4}""").find(raw)?.value?.toIntOrNull()
}
