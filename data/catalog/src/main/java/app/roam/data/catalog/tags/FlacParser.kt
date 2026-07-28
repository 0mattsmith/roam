package app.roam.data.catalog.tags

/**
 * FLAC metadata blocks: VORBIS_COMMENT for tags, PICTURE for the cover.
 *
 * Both sit near the start of the file, immediately after the "fLaC" marker, so
 * a 1 MB head read reaches them comfortably unless the cover is enormous.
 */
object FlacParser {

    private const val TYPE_VORBIS_COMMENT = 4
    private const val TYPE_PICTURE = 6

    fun parse(bytes: ByteArray): AudioTags? {
        if (bytes.size < 8) return null
        if (bytes[0] != 'f'.code.toByte() || bytes[1] != 'L'.code.toByte() ||
            bytes[2] != 'a'.code.toByte() || bytes[3] != 'C'.code.toByte()
        ) return null

        var pos = 4
        val comments = mutableMapOf<String, String>()
        var artwork: ByteArray? = null

        while (pos + 4 <= bytes.size) {
            val header = bytes[pos].toInt() and 0xFF
            val isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val length = ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                         ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                         (bytes[pos + 3].toInt() and 0xFF)

            val body = pos + 4
            if (length <= 0 || body + length > bytes.size) break   // truncated head

            when (type) {
                TYPE_VORBIS_COMMENT -> readComments(bytes, body, length, comments)
                TYPE_PICTURE -> if (artwork == null) artwork = readPicture(bytes, body, length)
            }

            pos = body + length
            if (isLast) break
        }

        val (trackNo, trackTotal) = parsePair(comments["TRACKNUMBER"])
        val (discNo, discTotal) = parsePair(comments["DISCNUMBER"])

        val tags = AudioTags(
            title = comments["TITLE"],
            artist = comments["ARTIST"],
            album = comments["ALBUM"],
            albumArtist = comments["ALBUMARTIST"] ?: comments["ALBUM ARTIST"],
            year = parseYear(comments["DATE"] ?: comments["YEAR"]),
            genre = comments["GENRE"],
            trackNo = trackNo,
            trackTotal = trackTotal ?: comments["TRACKTOTAL"]?.toIntOrNull(),
            discNo = discNo,
            discTotal = discTotal ?: comments["DISCTOTAL"]?.toIntOrNull(),
            artwork = artwork,
        )
        return if (tags.isEmpty) null else tags
    }

    /** Vorbis comments are little-endian length-prefixed "KEY=value" UTF-8 strings. */
    private fun readComments(b: ByteArray, off: Int, length: Int, into: MutableMap<String, String>) {
        val end = off + length
        var p = off

        val vendorLen = leInt(b, p) ?: return
        p += 4 + vendorLen
        if (p + 4 > end) return

        val count = leInt(b, p) ?: return
        p += 4

        repeat(count.coerceAtMost(MAX_COMMENTS)) {
            if (p + 4 > end) return
            val len = leInt(b, p) ?: return
            p += 4
            if (len < 0 || p + len > end) return

            val entry = String(b, p, len, Charsets.UTF_8)
            p += len

            val eq = entry.indexOf('=')
            if (eq > 0) {
                val key = entry.substring(0, eq).uppercase()
                val value = entry.substring(eq + 1).trim()
                if (value.isNotBlank()) into.putIfAbsent(key, value)
            }
        }
    }

    /**
     * PICTURE block: type, then length-prefixed MIME and description, then
     * width/height/depth/colours, then the image itself.
     */
    private fun readPicture(b: ByteArray, off: Int, length: Int): ByteArray? {
        val end = off + length
        var p = off + 4                                   // picture type

        val mimeLen = leIntBE(b, p) ?: return null
        p += 4 + mimeLen
        if (p + 4 > end) return null

        val descLen = leIntBE(b, p) ?: return null
        p += 4 + descLen
        if (p + 20 > end) return null

        p += 16                                           // width, height, depth, colours
        val dataLen = leIntBE(b, p) ?: return null
        p += 4

        if (dataLen <= 0 || p + dataLen > end) return null
        return b.copyOfRange(p, p + dataLen)
    }

    /** Vorbis comment lengths are little-endian. */
    private fun leInt(b: ByteArray, off: Int): Int? {
        if (off + 4 > b.size) return null
        return (b[off].toInt() and 0xFF) or
               ((b[off + 1].toInt() and 0xFF) shl 8) or
               ((b[off + 2].toInt() and 0xFF) shl 16) or
               ((b[off + 3].toInt() and 0xFF) shl 24)
    }

    /** PICTURE block lengths are big-endian, unlike the comment block. */
    private fun leIntBE(b: ByteArray, off: Int): Int? {
        if (off + 4 > b.size) return null
        return ((b[off].toInt() and 0xFF) shl 24) or
               ((b[off + 1].toInt() and 0xFF) shl 16) or
               ((b[off + 2].toInt() and 0xFF) shl 8) or
               (b[off + 3].toInt() and 0xFF)
    }

    /** Guard against a corrupt count sending the loop somewhere silly. */
    private const val MAX_COMMENTS = 256
}
