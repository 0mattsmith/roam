package app.roam.data.catalog.sync

/**
 * What a container actually said. Every field is optional because every field
 * genuinely is -- a file can carry a title and nothing else.
 */
data class ParsedTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val artwork: ByteArray? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && albumArtist == null &&
            trackNo == null && year == null && genre == null && artwork == null

    // ByteArray in a data class: the generated equals compares the reference,
    // which would make two identical covers unequal. Only tests care, but a
    // wrong equals is the kind of thing that gets trusted later.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedTags) return false
        return title == other.title && artist == other.artist && album == other.album &&
            albumArtist == other.albumArtist && trackNo == other.trackNo &&
            trackTotal == other.trackTotal && discNo == other.discNo &&
            discTotal == other.discTotal && year == other.year && genre == other.genre &&
            durationMs == other.durationMs && artwork.contentEquals(other.artwork)
    }

    override fun hashCode(): Int {
        var r = title?.hashCode() ?: 0
        r = 31 * r + (artist?.hashCode() ?: 0)
        r = 31 * r + (album?.hashCode() ?: 0)
        r = 31 * r + (albumArtist?.hashCode() ?: 0)
        r = 31 * r + (trackNo ?: 0)
        r = 31 * r + (year ?: 0)
        r = 31 * r + (artwork?.contentHashCode() ?: 0)
        return r
    }
}

/**
 * Tag parsing over a partial file.
 *
 * Hand-rolled rather than driven through Media3's extractors, deliberately.
 * The extractors expect a whole, seekable stream; feeding them a truncated
 * head means catching the EOF they throw part way and trusting whatever they
 * managed to emit first. These three formats put their metadata in a fixed
 * place at a known offset, so reading it directly is both shorter and provable
 * -- and provable matters here, because it can be unit tested on the JVM
 * without a device, which nothing else in this project can.
 *
 * Every parser is defensive to the point of paranoia: the input is a prefix of
 * a real file, so a length field pointing past the end is expected, not
 * exceptional. Anything malformed yields null rather than throwing, and the
 * caller falls back to what the path said.
 */
object TagParser {

    /** Containers are identified by their own magic, not by the filename. */
    fun parse(bytes: ByteArray): ParsedTags? = runCatching {
        when {
            startsWith(bytes, "ID3") -> parseId3(bytes)
            startsWith(bytes, "fLaC") -> parseFlac(bytes)
            // The second condition is what makes the tail read work: that
            // buffer starts 512 KB before the end of the file, so it has no
            // ftyp and no atom boundary to walk from -- only a moov somewhere
            // inside it.
            looksLikeMp4(bytes) || indexOfAscii(bytes, "moov") >= 0 -> parseMp4(bytes)
            else -> null
        }?.takeUnless { it.isEmpty }
    }.getOrNull()

    /**
     * True when an MP4's metadata is NOT in the bytes we have.
     *
     * On a file that was never run through faststart the moov atom sits at the
     * very end, after all the audio. Miss this and roughly a third of an AAC
     * library comes back untagged.
     */
    fun needsTailRead(bytes: ByteArray): Boolean =
        looksLikeMp4(bytes) && !containsTopLevelAtom(bytes, "moov")

    // ---- ID3v2 ---------------------------------------------------------------

    private fun parseId3(b: ByteArray): ParsedTags? {
        if (b.size < ID3_HEADER) return null
        val major = b[3].toInt() and 0xff
        val flags = b[5].toInt() and 0xff
        val declared = synchsafe(b, 6)
        if (declared <= 0) return null

        val end = minOf(ID3_HEADER + declared, b.size)
        var body = b.copyOfRange(ID3_HEADER, end)

        // Unsynchronisation turns every FF 00 back into FF. Applied to the
        // whole body before framing, which is the order the spec defines.
        if (flags and 0x80 != 0) body = deUnsynchronise(body)

        var pos = 0
        // Extended header, if present, is simply skipped -- nothing in it is
        // metadata we want.
        if (flags and 0x40 != 0 && body.size >= 4) {
            pos += if (major >= 4) synchsafe(body, 0) else be32(body, 0) + 4
        }

        val idLength = if (major <= 2) 3 else 4
        val sizeLength = if (major <= 2) 3 else 4
        val flagLength = if (major <= 2) 0 else 2

        var out = ParsedTags()
        while (pos + idLength + sizeLength + flagLength <= body.size) {
            val id = String(body, pos, idLength, Charsets.ISO_8859_1)
            // The tag is followed by padding, so the first byte that is not
            // a frame id ends it. Zero is the usual filler, but anything
            // non-alphanumeric here means we have walked off the end.
            if (!id[0].isLetterOrDigit()) break

            val size = when {
                major <= 2 -> be24(body, pos + idLength)
                // v2.4 sizes are synchsafe; v2.3's are plain. Some v2.4 writers
                // get this wrong, so a size that overruns is treated as plain.
                major >= 4 -> synchsafe(body, pos + idLength)
                    .takeIf { pos + 10 + it <= body.size } ?: be32(body, pos + idLength)
                else -> be32(body, pos + idLength)
            }
            if (size <= 0) break

            val from = pos + idLength + sizeLength + flagLength
            val to = from + size

            // A frame that runs past what we read is ABANDONED, not clamped.
            // Clamping would hand back half a value, and half an album name is
            // far worse than none: album and artist ids are content-derived, so
            // "Cut" and "Cut short" are two different rows, and the album
            // silently splits in two. Everything after a truncated frame is
            // unreliable anyway, so this stops rather than skipping on.
            if (to > body.size || from >= to) break
            val frame = body.copyOfRange(from, to)

            out = applyId3Frame(out, id, frame)
            pos = from + size
        }
        return out
    }

    private fun applyId3Frame(t: ParsedTags, id: String, frame: ByteArray): ParsedTags =
        when (id) {
            "TIT2", "TT2" -> t.copy(title = id3Text(frame) ?: t.title)
            "TPE1", "TP1" -> t.copy(artist = id3Text(frame) ?: t.artist)
            "TALB", "TAL" -> t.copy(album = id3Text(frame) ?: t.album)
            "TPE2", "TP2" -> t.copy(albumArtist = id3Text(frame) ?: t.albumArtist)
            "TCON", "TCO" -> t.copy(genre = id3Genre(id3Text(frame)) ?: t.genre)
            // TDRC is a full ISO date in v2.4; the year is its first four chars.
            "TDRC", "TYER", "TYE", "TDAT" ->
                t.copy(year = id3Text(frame)?.take(4)?.toIntOrNull() ?: t.year)
            "TRCK", "TRK" -> {
                val (n, total) = slashPair(id3Text(frame))
                t.copy(trackNo = n ?: t.trackNo, trackTotal = total ?: t.trackTotal)
            }
            "TPOS", "TPA" -> {
                val (n, total) = slashPair(id3Text(frame))
                t.copy(discNo = n ?: t.discNo, discTotal = total ?: t.discTotal)
            }
            "APIC", "PIC" -> t.copy(artwork = t.artwork ?: id3Picture(frame, short = id == "PIC"))
            else -> t
        }

    /** First byte is the encoding; the rest is the string, minus any padding. */
    private fun id3Text(frame: ByteArray): String? {
        if (frame.size < 2) return null
        val text = decode(frame[0].toInt() and 0xff, frame, 1, frame.size)
        // Trailing NULs are normal -- writers pad frames to a round size --
        // and a name ending in one compares unequal to the same name typed
        // by hand, which would fork an artist in two.
        return text.trim { it <= ' ' || it == '\uFEFF' }.ifBlank { null }
    }

    /**
     * APIC: encoding, mime, picture type, description, then the image.
     *
     * v2.2's PIC uses a fixed three-character format code where v2.3 has a
     * null-terminated mime type -- the only difference that matters here.
     */
    private fun id3Picture(frame: ByteArray, short: Boolean): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xff
        var pos = 1

        if (short) {
            pos += 3
        } else {
            while (pos < frame.size && frame[pos].toInt() != 0) pos++
            pos++ // the terminator
        }
        if (pos >= frame.size) return null
        pos++ // picture type

        // The description uses the frame's encoding, so a UTF-16 one ends with
        // TWO zero bytes, not one. Reading a single terminator here leaves the
        // image starting one byte late and every decoder rejects it.
        val wide = encoding == 1 || encoding == 2
        if (wide) {
            while (pos + 1 < frame.size &&
                !(frame[pos].toInt() == 0 && frame[pos + 1].toInt() == 0)
            ) pos += 2
            pos += 2
        } else {
            while (pos < frame.size && frame[pos].toInt() != 0) pos++
            pos++
        }

        return if (pos in 0 until frame.size) frame.copyOfRange(pos, frame.size) else null
    }

    /** "(17)" and "17" both mean Rock. Anything else is already a name. */
    private fun id3Genre(raw: String?): String? {
        val value = raw?.trim()?.ifBlank { null } ?: return null
        val numeric = value.removeSurrounding("(", ")").toIntOrNull() ?: return value
        return ID3V1_GENRES.getOrNull(numeric) ?: value
    }

    // ---- FLAC ----------------------------------------------------------------

    private fun parseFlac(b: ByteArray): ParsedTags? {
        var pos = 4
        var out = ParsedTags()

        while (pos + 4 <= b.size) {
            val header = b[pos].toInt() and 0xff
            val last = header and 0x80 != 0
            val type = header and 0x7f
            val length = be24(b, pos + 1)
            val from = pos + 4
            val to = from + length
            // Truncated: the block we want is not in the bytes we were given.
            if (to > b.size) break

            when (type) {
                0 -> out = out.copy(durationMs = flacDuration(b, from, length) ?: out.durationMs)
                4 -> out = applyVorbis(out, b.copyOfRange(from, to))
                6 -> out = out.copy(artwork = out.artwork ?: flacPicture(b.copyOfRange(from, to)))
            }

            if (last) break
            pos = to
        }
        return out
    }

    /** STREAMINFO packs a 20-bit rate and a 36-bit sample count, unaligned. */
    private fun flacDuration(b: ByteArray, from: Int, length: Int): Long? {
        if (length < 18 || from + 18 > b.size) return null
        val rate = ((b[from + 10].toLong() and 0xff) shl 12) or
            ((b[from + 11].toLong() and 0xff) shl 4) or
            ((b[from + 12].toLong() and 0xff) shr 4)
        if (rate <= 0) return null

        val samples = ((b[from + 13].toLong() and 0x0f) shl 32) or
            ((b[from + 14].toLong() and 0xff) shl 24) or
            ((b[from + 15].toLong() and 0xff) shl 16) or
            ((b[from + 16].toLong() and 0xff) shl 8) or
            (b[from + 17].toLong() and 0xff)
        return if (samples <= 0) null else samples * 1000 / rate
    }

    private fun applyVorbis(start: ParsedTags, block: ByteArray): ParsedTags {
        if (block.size < 8) return start
        var pos = 0
        val vendor = le32(block, pos)
        if (vendor < 0) return start
        pos += 4 + vendor
        if (pos < 0 || pos + 4 > block.size) return start

        val count = le32(block, pos)
        pos += 4

        var t = start
        repeat(count.coerceAtMost(MAX_VORBIS_COMMENTS)) {
            if (pos + 4 > block.size) return t
            val length = le32(block, pos)
            pos += 4
            if (length < 0 || pos + length > block.size) return t

            val comment = String(block, pos, length, Charsets.UTF_8)
            pos += length

            val key = comment.substringBefore('=').uppercase()
            val value = comment.substringAfter('=', "").trim()
            if (value.isNotBlank()) t = applyVorbisField(t, key, value)
        }
        return t
    }

    private fun applyVorbisField(t: ParsedTags, key: String, value: String): ParsedTags =
        when (key) {
            "TITLE" -> t.copy(title = value)
            "ARTIST" -> t.copy(artist = value)
            "ALBUM" -> t.copy(album = value)
            "ALBUMARTIST", "ALBUM ARTIST" -> t.copy(albumArtist = value)
            "GENRE" -> t.copy(genre = value)
            "DATE", "YEAR", "ORIGINALDATE" -> t.copy(year = t.year ?: value.take(4).toIntOrNull())
            // Vorbis allows either a bare number or the ID3-style "3/12".
            "TRACKNUMBER" -> {
                val (n, total) = slashPair(value)
                t.copy(trackNo = n, trackTotal = total ?: t.trackTotal)
            }
            "TRACKTOTAL", "TOTALTRACKS" -> t.copy(trackTotal = value.toIntOrNull() ?: t.trackTotal)
            "DISCNUMBER" -> {
                val (n, total) = slashPair(value)
                t.copy(discNo = n, discTotal = total ?: t.discTotal)
            }
            "DISCTOTAL", "TOTALDISCS" -> t.copy(discTotal = value.toIntOrNull() ?: t.discTotal)
            else -> t
        }

    /** METADATA_BLOCK_PICTURE: four length-prefixed fields, then the image. */
    private fun flacPicture(block: ByteArray): ByteArray? {
        var pos = 4 // picture type
        if (pos + 4 > block.size) return null
        val mime = be32(block, pos)
        if (mime < 0) return null
        pos += 4 + mime
        if (pos < 0 || pos + 4 > block.size) return null

        val description = be32(block, pos)
        if (description < 0) return null
        pos += 4 + description
        pos += 16 // width, height, depth, colour count
        if (pos < 0 || pos + 4 > block.size) return null

        val length = be32(block, pos)
        pos += 4
        if (length <= 0 || pos + length > block.size) return null
        return block.copyOfRange(pos, pos + length)
    }

    // ---- MP4 / M4A -----------------------------------------------------------

    private fun parseMp4(b: ByteArray): ParsedTags? {
        // Walking the tree is preferred -- it cannot be fooled by the letters
        // "moov" appearing inside audio data -- but it only works from an atom
        // boundary, and on a head read it gives up at the first huge mdat
        // anyway. The signature scan is the fallback for both cases.
        val moov = findAtom(b, 0, b.size, "moov") ?: scanForAtom(b, "moov") ?: return null
        var out = ParsedTags()

        findAtom(b, moov.first, moov.second, "mvhd")?.let { (from, to) ->
            out = out.copy(durationMs = mvhdDuration(b, from, to))
        }

        val udta = findAtom(b, moov.first, moov.second, "udta") ?: return out
        val meta = findAtom(b, udta.first, udta.second, "meta") ?: return out
        // meta is a FullBox: four bytes of version and flags sit before its
        // children, and walking it as a plain container finds nothing.
        val ilst = findAtom(b, meta.first + 4, meta.second, "ilst") ?: return out

        var pos = ilst.first
        while (pos + 8 <= ilst.second) {
            val size = be32(b, pos)
            if (size < 8 || pos + size > ilst.second) break
            val name = String(b, pos + 4, 4, Charsets.ISO_8859_1)

            findAtom(b, pos + 8, pos + size, "data")?.let { (from, to) ->
                // version(1) flags(3) locale(4), then the value itself.
                val payload = from + 8
                if (payload < to) out = applyMp4Field(out, name, b, payload, to)
            }
            pos += size
        }
        return out
    }

    private fun applyMp4Field(
        t: ParsedTags,
        name: String,
        b: ByteArray,
        from: Int,
        to: Int,
    ): ParsedTags {
        fun text() = String(b, from, to - from, Charsets.UTF_8).trim().ifBlank { null }

        return when (name) {
            "©nam" -> t.copy(title = text() ?: t.title)
            "©ART" -> t.copy(artist = text() ?: t.artist)
            "©alb" -> t.copy(album = text() ?: t.album)
            "aART" -> t.copy(albumArtist = text() ?: t.albumArtist)
            "©gen" -> t.copy(genre = text() ?: t.genre)
            "gnre" -> {
                // One-based here, unlike ID3's zero-based numbering.
                val index = if (to - from >= 2) be16(b, to - 2) - 1 else -1
                t.copy(genre = ID3V1_GENRES.getOrNull(index) ?: t.genre)
            }
            "©day" -> t.copy(year = text()?.take(4)?.toIntOrNull() ?: t.year)
            // trkn and disk are packed binary: two pad bytes, the number, the
            // total. Not text, despite everything around them being text.
            "trkn" -> if (to - from >= 6) {
                t.copy(
                    trackNo = be16(b, from + 2).takeIf { it > 0 } ?: t.trackNo,
                    trackTotal = be16(b, from + 4).takeIf { it > 0 } ?: t.trackTotal,
                )
            } else t
            "disk" -> if (to - from >= 6) {
                t.copy(
                    discNo = be16(b, from + 2).takeIf { it > 0 } ?: t.discNo,
                    discTotal = be16(b, from + 4).takeIf { it > 0 } ?: t.discTotal,
                )
            } else t
            "covr" -> t.copy(artwork = t.artwork ?: b.copyOfRange(from, to))
            else -> t
        }
    }

    private fun mvhdDuration(b: ByteArray, from: Int, to: Int): Long? {
        if (from >= to || from >= b.size) return null
        val version = b[from].toInt() and 0xff
        return runCatching {
            if (version == 1) {
                val scale = be32(b, from + 20).toLong()
                val duration = be64(b, from + 24)
                if (scale > 0) duration * 1000 / scale else null
            } else {
                val scale = be32(b, from + 12).toLong()
                val duration = be32(b, from + 16).toLong()
                if (scale > 0) duration * 1000 / scale else null
            }
        }.getOrNull()?.takeIf { it > 0 }
    }

    /** Depth-first search for [name] within [from, until). Returns its body. */
    private fun findAtom(b: ByteArray, from: Int, until: Int, name: String): Pair<Int, Int>? {
        var pos = from
        while (pos + 8 <= minOf(until, b.size)) {
            var size = be32(b, pos).toLong()
            var header = 8
            // size 1 means the real, 64-bit size follows the type.
            if (size == 1L) {
                if (pos + 16 > b.size) return null
                size = be64(b, pos + 8)
                header = 16
            }
            // size 0 means "to the end of the file".
            if (size == 0L) size = (until - pos).toLong()
            if (size < header) return null

            // Same rule as ID3 frames: an atom claiming more bytes than we hold
            // is truncated, and a clamped range would yield a partial string.
            if (pos + size > until) return null

            val type = String(b, pos + 4, 4, Charsets.ISO_8859_1)
            if (type == name) return (pos + header) to (pos + size).toInt()

            pos += size.toInt()
        }
        return null
    }

    /**
     * Finds an atom by its four-character type wherever it sits.
     *
     * The size field precedes the type, so the atom really begins four bytes
     * earlier. A size that does not fit what we hold is taken as "to the end
     * of the buffer" -- every field inside is bounds-checked on its own, so a
     * partial moov yields the tags it does contain rather than nothing.
     */
    private fun scanForAtom(b: ByteArray, name: String): Pair<Int, Int>? {
        val needle = name.toByteArray(Charsets.ISO_8859_1)
        var i = 4
        while (i + needle.size <= b.size) {
            if (needle.indices.all { b[i + it] == needle[it] }) {
                val start = i - 4
                val size = be32(b, start)
                val end = if (size >= 8 && start + size <= b.size) start + size else b.size
                return (start + 8) to end
            }
            i++
        }
        return null
    }

    private fun indexOfAscii(b: ByteArray, needle: String): Int {
        val n = needle.toByteArray(Charsets.ISO_8859_1)
        for (i in 0..(b.size - n.size)) {
            if (n.indices.all { b[i + it] == n[it] }) return i
        }
        return -1
    }

    /**
     * Walked, not scanned. This decides whether to spend a second HTTP range
     * request, and the letters "moov" turning up inside compressed audio would
     * make it skip a tail read the file genuinely needs.
     */
    private fun containsTopLevelAtom(b: ByteArray, name: String): Boolean =
        findAtom(b, 0, b.size, name) != null

    /** Every MP4 opens with an ftyp box; the size comes first, then the type. */
    private fun looksLikeMp4(b: ByteArray): Boolean =
        b.size >= 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    // ---- shared --------------------------------------------------------------

    private fun startsWith(b: ByteArray, magic: String): Boolean =
        b.size >= magic.length && String(b, 0, magic.length, Charsets.ISO_8859_1) == magic

    /** "3/12" -> 3 and 12. A bare "3" gives 3 and null. */
    private fun slashPair(raw: String?): Pair<Int?, Int?> {
        val value = raw?.trim() ?: return null to null
        val n = value.substringBefore('/').trim().toIntOrNull()
        val total = value.substringAfter('/', "").trim().toIntOrNull()
        return n to total
    }

    private fun decode(encoding: Int, b: ByteArray, from: Int, to: Int): String {
        val charset = when (encoding) {
            1 -> Charsets.UTF_16      // with BOM
            2 -> Charsets.UTF_16BE    // without
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return runCatching { String(b, from, to - from, charset) }.getOrDefault("")
    }

    /** ID3 sizes use seven bits per byte so a size can never look like a sync. */
    private fun synchsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7f) shl 21) or
            ((b[at + 1].toInt() and 0x7f) shl 14) or
            ((b[at + 2].toInt() and 0x7f) shl 7) or
            (b[at + 3].toInt() and 0x7f)

    private fun deUnsynchronise(b: ByteArray): ByteArray {
        val out = ByteArray(b.size)
        var w = 0
        var r = 0
        while (r < b.size) {
            out[w++] = b[r]
            if (r + 1 < b.size && (b[r].toInt() and 0xff) == 0xff && b[r + 1].toInt() == 0) r++
            r++
        }
        return out.copyOf(w)
    }

    private fun be16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    private fun be24(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 16) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            (b[at + 2].toInt() and 0xff)

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or
            ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or
            (b[at + 3].toInt() and 0xff)

    private fun be64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xff)
        return v
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or
            ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or
            ((b[at + 3].toInt() and 0xff) shl 24)

    private const val ID3_HEADER = 10

    /** A malformed count must not spin; no real file has this many. */
    private const val MAX_VORBIS_COMMENTS = 512

    /** Winamp's original numbering, which both ID3v1 and MP4's gnre index. */
    private val ID3V1_GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock",
        "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack",
        "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alt. Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop",
        "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic",
        "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta Rap",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret",
        "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal",
        "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast-Fusion", "Bebop", "Latin",
        "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
        "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus",
        "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera", "Chamber Music",
        "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam",
        "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul",
        "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House",
        "Dance Hall",
    )
}
