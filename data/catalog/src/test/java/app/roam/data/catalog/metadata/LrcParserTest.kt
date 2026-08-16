package app.roam.data.catalog.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LRC parser, which is the only part of the lyrics feature that can be
 * wrong quietly. A network client either answers or it does not; a timestamp
 * read a decimal place out puts every line a second off and still looks like
 * it works.
 */
class LrcParserTest {

    @Test
    fun `reads minutes seconds and centiseconds`() {
        val lines = LrcLib.parseLrc("[01:02.50]Hello")
        assertEquals(1, lines.size)
        assertEquals(62_500L, lines[0].atMs)
        assertEquals("Hello", lines[0].text)
    }

    @Test
    fun `two-digit fraction is centiseconds not milliseconds`() {
        // The whole bug this guards: .50 is half a second, not 50ms. Read as
        // milliseconds every line lands 450ms early, which is enough to feel
        // wrong without being obviously broken.
        assertEquals(500L, LrcLib.parseLrc("[00:00.50]x").single().atMs)
    }

    @Test
    fun `three-digit fraction is milliseconds`() {
        assertEquals(50L, LrcLib.parseLrc("[00:00.050]x").single().atMs)
    }

    @Test
    fun `metadata tags are not lyrics`() {
        // [ar:] and friends look exactly like a timestamp to a loose parser and
        // would each become a line at time zero, putting the artist's name and
        // the album title into the middle of the song.
        val lrc = """
            [ar:Pink Floyd]
            [ti:Time]
            [al:The Dark Side of the Moon]
            [00:10.00]Ticking away
        """.trimIndent()

        val lines = LrcLib.parseLrc(lrc)
        assertEquals(1, lines.size)
        assertEquals("Ticking away", lines[0].text)
    }

    @Test
    fun `one line with several timestamps repeats`() {
        // A chorus is written once with every time it occurs, and taking only
        // the first stamp silently drops every later repeat.
        val lines = LrcLib.parseLrc("[00:10.00][01:20.00][02:30.00]Chorus")
        assertEquals(3, lines.size)
        assertEquals(listOf(10_000L, 80_000L, 150_000L), lines.map { it.atMs })
        assertTrue(lines.all { it.text == "Chorus" })
    }

    @Test
    fun `lines come back in time order`() {
        val lines = LrcLib.parseLrc("[00:30.00]Third\n[00:10.00]First\n[00:20.00]Second")
        assertEquals(listOf("First", "Second", "Third"), lines.map { it.text })
    }

    @Test
    fun `a blank line is kept as a pause`() {
        val lines = LrcLib.parseLrc("[00:10.00]Words\n[00:14.00]\n[00:18.00]More")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `minutes beyond an hour still parse`() {
        // LRC has no hours field, so a long track counts minutes upward.
        assertEquals(75L * 60_000, LrcLib.parseLrc("[75:00.00]x").single().atMs)
    }

    @Test
    fun `text is taken after the LAST timestamp`() {
        val line = LrcLib.parseLrc("[00:10.00][00:20.00]  Sung  ").first()
        assertEquals("Sung", line.text)
    }

    @Test
    fun `garbage yields nothing rather than throwing`() {
        assertTrue(LrcLib.parseLrc("no timestamps here at all").isEmpty())
        assertTrue(LrcLib.parseLrc("").isEmpty())
    }

    // ---- the highlight ------------------------------------------------------

    private val song = LrcLib.parseLrc(
        "[00:10.00]One\n[00:20.00]Two\n[00:30.00]Three"
    )

    @Test
    fun `nothing is highlighted before the first line`() {
        // An instrumental intro must not light up the first lyric for twenty
        // seconds before anyone sings it.
        assertEquals(-1, LrcLib.activeLineIndex(song, 0))
        assertEquals(-1, LrcLib.activeLineIndex(song, 9_999))
    }

    @Test
    fun `the line that has started is the active one`() {
        assertEquals(0, LrcLib.activeLineIndex(song, 10_000))
        assertEquals(0, LrcLib.activeLineIndex(song, 19_999))
        assertEquals(1, LrcLib.activeLineIndex(song, 20_000))
    }

    @Test
    fun `the last line stays active to the end`() {
        assertEquals(2, LrcLib.activeLineIndex(song, 30_000))
        assertEquals(2, LrcLib.activeLineIndex(song, 600_000))
    }

    @Test
    fun `an empty song has no active line`() {
        assertEquals(-1, LrcLib.activeLineIndex(emptyList(), 1_000))
    }
}
