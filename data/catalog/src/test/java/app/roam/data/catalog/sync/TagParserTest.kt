package app.roam.data.catalog.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The tag parser is the one part of Roam that can be proved on a laptop.
 *
 * Every case here builds a real container by hand rather than shipping a
 * fixture file, so the bytes under test are visible in the test itself -- when
 * one fails you can see exactly which field of which frame is wrong, instead of
 * bisecting a binary.
 */
class TagParserTest {

    // ---- ID3v2 ---------------------------------------------------------------

    @Test
    fun `reads id3v2_3 text frames`() {
        val tag = id3(
            major = 3,
            frames = listOf(
                textFrame("TIT2", "Bohemian Rhapsody"),
                textFrame("TPE1", "Queen"),
                textFrame("TALB", "A Night at the Opera"),
                textFrame("TPE2", "Queen"),
                textFrame("TYER", "1975"),
                textFrame("TRCK", "11/12"),
                textFrame("TPOS", "1/1"),
                textFrame("TCON", "Rock"),
            ),
        )

        val out = TagParser.parse(tag)!!
        assertEquals("Bohemian Rhapsody", out.title)
        assertEquals("Queen", out.artist)
        assertEquals("A Night at the Opera", out.album)
        assertEquals("Queen", out.albumArtist)
        assertEquals(1975, out.year)
        assertEquals(11, out.trackNo)
        assertEquals(12, out.trackTotal)
        assertEquals(1, out.discNo)
        assertEquals("Rock", out.genre)
    }

    /** v2.4 sizes are synchsafe, so a frame over 127 bytes exposes the difference. */
    @Test
    fun `reads id3v2_4 synchsafe frame sizes`() {
        val long = "A".repeat(200)
        val tag = id3(
            major = 4,
            frames = listOf(textFrame("TALB", long, synchsafeSize = true), textFrame("TIT2", "After", synchsafeSize = true)),
            synchsafeFrames = true,
        )

        val out = TagParser.parse(tag)!!
        assertEquals(long, out.album)
        // Only reachable if the first frame's size was read correctly.
        assertEquals("After", out.title)
    }

    @Test
    fun `maps a numeric genre to its name`() {
        val out = TagParser.parse(id3(3, listOf(textFrame("TCON", "(17)"), textFrame("TIT2", "x"))))!!
        assertEquals("Rock", out.genre)
    }

    @Test
    fun `takes the year from a full v2_4 date`() {
        val out = TagParser.parse(id3(4, listOf(textFrame("TDRC", "1975-11-21"), textFrame("TIT2", "x"))))!!
        assertEquals(1975, out.year)
    }

    @Test
    fun `reads a utf16 title`() {
        val frame = ByteArrayOutputStream().apply {
            write(1)                                   // UTF-16 with BOM
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            write("Björk".toByteArray(Charsets.UTF_16LE))
        }.toByteArray()

        val out = TagParser.parse(id3(3, listOf(frame("TIT2", frame))))!!
        assertEquals("Björk", out.title)
    }

    /**
     * The description before the image is terminated in the frame's OWN
     * encoding, so a UTF-16 one ends in two zero bytes. Reading a single
     * terminator leaves the image starting a byte late and every decoder
     * rejects it -- which looks like "no cover" rather than a parsing bug.
     */
    @Test
    fun `finds the picture past a utf16 description`() {
        val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4)
        val apic = ByteArrayOutputStream().apply {
            write(1)
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); write(0)
            write(3)                                                    // front cover
            write("Cover".toByteArray(Charsets.UTF_16LE)); write(0); write(0)
            write(image)
        }.toByteArray()

        val out = TagParser.parse(id3(3, listOf(frame("APIC", apic), textFrame("TIT2", "x"))))!!
        assertTrue(image.contentEquals(out.artwork))
    }

    @Test
    fun `undoes unsynchronisation`() {
        val body = ByteArrayOutputStream().apply { write(textFrame("TIT2", "Test")) }.toByteArray()
        // Insert the FF 00 pair a decoder is meant to collapse back to FF.
        val withPair = body + byteArrayOf(0xFF.toByte(), 0x00)
        val tag = id3Raw(major = 3, flags = 0x80, body = withPair)

        assertEquals("Test", TagParser.parse(tag)!!.title)
    }

    /**
     * A truncated frame must be dropped whole, never clamped.
     *
     * Album and artist ids are content-derived, so half a name is not a
     * slightly worse name -- it is a different album. "Cut" and "Cut short"
     * would be two rows, and the real one would silently lose half its tracks.
     */
    @Test
    fun `drops a frame whose size runs past the end`() {
        val whole = id3(3, listOf(textFrame("TIT2", "Kept"), textFrame("TALB", "Cut short")))
        // Ends part way through the album frame's text.
        val out = TagParser.parse(whole.copyOf(whole.size - 5))!!

        assertEquals("Kept", out.title)
        assertNull(out.album)
    }

    // ---- FLAC ----------------------------------------------------------------

    @Test
    fun `reads flac vorbis comments`() {
        val comments = vorbisBlock(
            "TITLE=Teardrop",
            "ARTIST=Massive Attack",
            "ALBUM=Mezzanine",
            "ALBUMARTIST=Massive Attack",
            "DATE=1998",
            "TRACKNUMBER=2",
            "TRACKTOTAL=11",
            "DISCNUMBER=1",
            "GENRE=Trip-Hop",
        )
        val flac = "fLaC".toByteArray() +
            metadataBlock(type = 0, last = false, body = streamInfo()) +
            metadataBlock(type = 4, last = true, body = comments)

        val out = TagParser.parse(flac)!!
        // 44100 Hz for 180 s. The rate is 20 bits and the sample count 36,
        // neither of them byte-aligned, so getting this right is the only
        // proof the bit shifts are correct.
        assertEquals(180_000L, out.durationMs)
        assertEquals("Teardrop", out.title)
        assertEquals("Massive Attack", out.artist)
        assertEquals("Mezzanine", out.album)
        assertEquals(1998, out.year)
        assertEquals(2, out.trackNo)
        assertEquals(11, out.trackTotal)
        assertEquals("Trip-Hop", out.genre)
    }

    @Test
    fun `reads a flac picture block`() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val picture = ByteArrayOutputStream().apply {
            writeBe32(3)
            writeBe32("image/png".length); write("image/png".toByteArray())
            writeBe32(0)                                   // empty description
            repeat(4) { writeBe32(0) }                     // w, h, depth, colours
            writeBe32(image.size); write(image)
        }.toByteArray()

        val flac = "fLaC".toByteArray() +
            metadataBlock(type = 4, last = false, body = vorbisBlock("TITLE=x")) +
            metadataBlock(type = 6, last = true, body = picture)

        assertTrue(image.contentEquals(TagParser.parse(flac)!!.artwork))
    }

    // ---- MP4 -----------------------------------------------------------------

    @Test
    fun `reads m4a atoms`() {
        val ilst = concat(
            metaAtom("©nam", "Redbone".toByteArray()),
            metaAtom("©ART", "Childish Gambino".toByteArray()),
            metaAtom("©alb", "Awaken, My Love!".toByteArray()),
            metaAtom("aART", "Childish Gambino".toByteArray()),
            metaAtom("©day", "2016".toByteArray()),
            metaAtom("trkn", byteArrayOf(0, 0, 0, 6, 0, 11)),
            metaAtom("disk", byteArrayOf(0, 0, 0, 1, 0, 2)),
        )
        val file = mp4(ilst)

        val out = TagParser.parse(file)!!
        assertEquals("Redbone", out.title)
        assertEquals("Childish Gambino", out.artist)
        assertEquals("Awaken, My Love!", out.album)
        assertEquals("Childish Gambino", out.albumArtist)
        assertEquals(2016, out.year)
        assertEquals(6, out.trackNo)
        assertEquals(11, out.trackTotal)
        assertEquals(1, out.discNo)
        assertEquals(2, out.discTotal)
    }

    /**
     * `meta` is a FullBox: four bytes of version and flags sit before its
     * children. Walking it as a plain container finds no ilst and every M4A
     * comes back untagged.
     */
    @Test
    fun `steps over the meta version and flags`() {
        val out = TagParser.parse(mp4(metaAtom("©nam", "Found".toByteArray())))!!
        assertEquals("Found", out.title)
    }

    @Test
    fun `asks for the tail when moov is missing`() {
        val headOnly = atom("ftyp", "M4A ".toByteArray()) + atom("mdat", ByteArray(64))
        assertTrue(TagParser.needsTailRead(headOnly))
        assertNull(TagParser.parse(headOnly))
    }

    @Test
    fun `does not ask for the tail when moov is present`() {
        assertFalse(TagParser.needsTailRead(mp4(metaAtom("©nam", "x".toByteArray()))))
    }

    /**
     * What the second read actually receives: a slice from 512 KB before the
     * end of the file. It has no ftyp, and it does not start on an atom
     * boundary, so moov has to be found by signature rather than by walking.
     */
    @Test
    fun `parses a tail slice that begins mid-file`() {
        // The last bytes of audio, then moov. No ftyp, and the slice does not
        // start on an atom boundary -- exactly what the second read returns.
        val tail = byteArrayOf(0x11, 0x22, 0x33) +
            moovAtom(metaAtom("©nam", "Tail".toByteArray()))

        assertEquals("Tail", TagParser.parse(tail)!!.title)
    }

    /**
     * A real head read stops at a multi-megabyte mdat it does not hold, and
     * must ask for the tail rather than concluding the file has no tags.
     */
    @Test
    fun `asks for the tail when mdat runs past the read`() {
        val head = atom("ftyp", "M4A ".toByteArray()) +
            // Claims 40 MB, of which we hold 64 bytes.
            byteArrayOf(0x02, 0x68.toByte(), 0, 0) + "mdat".toByteArray() + ByteArray(64)

        assertTrue(TagParser.needsTailRead(head))
    }

    // ---- rejection -----------------------------------------------------------

    @Test
    fun `returns null for something that is not audio`() {
        assertNull(TagParser.parse("hello there".toByteArray()))
        assertNull(TagParser.parse(ByteArray(0)))
        assertNull(TagParser.parse(ByteArray(4096)))
    }

    @Test
    fun `returns null for a tag carrying nothing we want`() {
        assertNull(TagParser.parse(id3(3, listOf(textFrame("TENC", "LAME")))))
    }

    // ---- builders ------------------------------------------------------------

    private fun textFrame(id: String, value: String, synchsafeSize: Boolean = false): ByteArray =
        frame(id, byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1), synchsafeSize)

    private fun frame(id: String, body: ByteArray, synchsafeSize: Boolean = false): ByteArray =
        ByteArrayOutputStream().apply {
            write(id.toByteArray(Charsets.ISO_8859_1))
            if (synchsafeSize) writeSynchsafe(body.size) else writeBe32(body.size)
            write(0); write(0)          // frame flags
            write(body)
        }.toByteArray()

    private fun id3(
        major: Int,
        frames: List<ByteArray>,
        synchsafeFrames: Boolean = false,
    ): ByteArray = id3Raw(major, flags = 0, body = concat(*frames.toTypedArray()))

    private fun id3Raw(major: Int, flags: Int, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(major); write(0)
            write(flags)
            writeSynchsafe(body.size)
            write(body)
        }.toByteArray()

    private fun vorbisBlock(vararg comments: String): ByteArray =
        ByteArrayOutputStream().apply {
            val vendor = "roam-test".toByteArray()
            writeLe32(vendor.size); write(vendor)
            writeLe32(comments.size)
            comments.forEach { c ->
                val bytes = c.toByteArray(Charsets.UTF_8)
                writeLe32(bytes.size); write(bytes)
            }
        }.toByteArray()

    /** 44100 Hz, stereo, 16-bit, 180 seconds. */
    private fun streamInfo(): ByteArray {
        val rate = 44_100L
        val samples = rate * 180
        val packed = (rate shl 44) or (1L shl 41) or (15L shl 36) or samples
        return ByteArrayOutputStream().apply {
            write(byteArrayOf(0x10, 0, 0x10, 0))   // min and max block size
            write(ByteArray(6))                    // min and max frame size
            for (shift in 56 downTo 0 step 8) write(((packed shr shift) and 0xff).toInt())
            write(ByteArray(16))                   // md5
        }.toByteArray()
    }

    private fun metadataBlock(type: Int, last: Boolean, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(if (last) type or 0x80 else type)
            write(body.size shr 16); write(body.size shr 8); write(body.size)
            write(body)
        }.toByteArray()

    private fun atom(type: String, body: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeBe32(body.size + 8)
            write(type.toByteArray(Charsets.ISO_8859_1))
            write(body)
        }.toByteArray()

    /** One ilst entry: the name atom wrapping a `data` atom. */
    private fun metaAtom(name: String, value: ByteArray): ByteArray =
        atom(name, atom("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + value))

    private fun mp4(ilst: ByteArray): ByteArray =
        atom("ftyp", "M4A ".toByteArray()) + moovAtom(ilst)

    /** Split out so the tail test can use it without an ftyp in front. */
    private fun moovAtom(ilst: ByteArray): ByteArray =
        atom("moov", atom("udta", atom("meta", byteArrayOf(0, 0, 0, 0) + atom("ilst", ilst))))

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun ByteArrayOutputStream.writeBe32(v: Int) {
        write(v shr 24); write(v shr 16); write(v shr 8); write(v)
    }

    private fun ByteArrayOutputStream.writeLe32(v: Int) {
        write(v); write(v shr 8); write(v shr 16); write(v shr 24)
    }

    private fun ByteArrayOutputStream.writeSynchsafe(v: Int) {
        write((v shr 21) and 0x7f); write((v shr 14) and 0x7f)
        write((v shr 7) and 0x7f); write(v and 0x7f)
    }
}
