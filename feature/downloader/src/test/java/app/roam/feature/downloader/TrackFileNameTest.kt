package app.roam.feature.downloader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a downloaded file is called.
 *
 * Worth testing because the failure is invisible where it happens and obvious
 * three weeks later in a car: a name that sorts wrong plays the album out of
 * order on any player that orders by filename, and nothing on screen explains
 * it.
 */
class TrackFileNameTest {

    @Test
    fun `number, space, title, extension`() {
        assertEquals("01 Supersonic.m4a", TrackFileName.of(1, "Supersonic", "m4a"))
        assertEquals("12 Champagne Supernova.mp3", TrackFileName.of(12, "Champagne Supernova", "mp3"))
    }

    @Test
    fun `the number is padded so it sorts as a number`() {
        // The whole reason for the leading zero. Without it "10" sorts before
        // "2" on every player that orders by name, which is most of them.
        val names = listOf(2, 10).map { TrackFileName.of(it, "T$it", "mp3") }
        assertEquals(listOf("02 T2.mp3", "10 T10.mp3"), names)
        assertEquals(names, names.sorted())
    }

    @Test
    fun `past ninety-nine it simply gets wider`() {
        // %02d is a MINIMUM width, not a truncation. A hundred-track
        // compilation still names its tracks correctly, and still sorts.
        assertEquals("100 Late One.mp3", TrackFileName.of(100, "Late One", "mp3"))
    }

    @Test
    fun `no track number means no prefix, not a guessed one`() {
        // A single track from a search knows no position. Inventing 01 would
        // reorder whatever folder it lands in, silently.
        assertEquals("Supersonic.m4a", TrackFileName.of(0, "Supersonic", "m4a"))
        assertEquals("Supersonic.m4a", TrackFileName.of(-1, "Supersonic", "m4a"))
    }

    @Test
    fun `the artist is not in the name any more`() {
        // It was redundant -- the file already lives in Artist/Album (Year)/ --
        // and on a compilation it named Various Artists, which is nobody.
        val name = TrackFileName.of(3, "Never Gonna Give You Up", "mp3")
        assertEquals("03 Never Gonna Give You Up.mp3", name)
    }

    @Test
    fun `characters a filesystem refuses are dealt with`() {
        // Same rules as a folder, because it is the same filesystem. A colon
        // becomes " - " because that is what people write by hand; a slash is
        // simply removed, because any replacement would be a guess and the one
        // thing it must not do is create a directory.
        assertEquals("01 ACDC Tribute.mp3", TrackFileName.of(1, "AC/DC Tribute", "mp3"))
        assertEquals("02 Us - Them.mp3", TrackFileName.of(2, "Us: Them", "mp3"))
    }

    @Test
    fun `punctuation people meant to keep is kept`() {
        assertEquals(
            "01 Rock 'n' Roll Star.mp3",
            TrackFileName.of(1, "Rock 'n' Roll Star", "mp3"),
        )
    }

    @Test
    fun `a missing extension falls back rather than producing a bare name`() {
        assertEquals("01 Supersonic.m4a", TrackFileName.of(1, "Supersonic", ""))
        assertEquals("01 Supersonic.m4a", TrackFileName.of(1, "Supersonic", "   "))
        // A leading dot is how File.extension is sometimes handed over.
        assertEquals("01 Supersonic.flac", TrackFileName.of(1, "Supersonic", ".flac"))
    }

    @Test
    fun `a title that sanitises to nothing still produces a file`() {
        // "..." is a legal title and an illegal filename. Better a named file
        // than a crash or a file called ".mp3", which is hidden on every
        // system that has an opinion about leading dots.
        assertEquals("01 ${TrackFileName.UNKNOWN_TITLE}.mp3", TrackFileName.of(1, "...", "mp3"))
    }
}
