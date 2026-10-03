package app.roam.feature.webui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPinTest {

    @Test
    fun `the right pin matches`() {
        assertTrue(WebPin.matches("4817", "4817"))
    }

    @Test
    fun `a wrong pin does not`() {
        assertFalse(WebPin.matches("4817", "4818"))
        assertFalse(WebPin.matches("4817", "1784"))
    }

    /**
     * No PIN means nothing is let in. The server has not been told a PIN yet,
     * which must never be the same as "any PIN will do".
     */
    @Test
    fun `an absent or blank expectation refuses everything`() {
        assertFalse(WebPin.matches(null, "4817"))
        assertFalse(WebPin.matches("", ""))
        assertFalse(WebPin.matches("   ", "   "))
        assertFalse(WebPin.matches("4817", null))
    }

    @Test
    fun `length alone is not a match`() {
        assertFalse(WebPin.matches("4817", "48170"))
        assertFalse(WebPin.matches("4817", "481"))
    }

    /** A browser can send any cookie it likes; only four digits get as far as the compare. */
    @Test
    fun `the shape is narrowed before anything is compared`() {
        assertTrue(WebPin.sane("0000"))
        assertTrue(WebPin.sane("4817"))

        assertFalse(WebPin.sane(null))
        assertFalse(WebPin.sane(""))
        assertFalse(WebPin.sane("481"))
        assertFalse(WebPin.sane("48170"))
        assertFalse(WebPin.sane("48a7"))
        assertFalse(WebPin.sane(" 481"))
        assertFalse(WebPin.sane("x".repeat(1_000_000)))
    }

    /** Zero-padded, because "%04d" is what mints it and 0042 is a real PIN. */
    @Test
    fun `a leading zero is part of the pin`() {
        assertTrue(WebPin.matches("0042", "0042"))
        assertFalse(WebPin.matches("0042", "42"))
    }
}
