package app.roam.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What counts as a play decides "most played", the recently played list, and
 * eventually how a smart playlist weights anything. Getting it wrong is silent
 * -- the numbers just quietly mean something other than what they claim.
 */
class PlayThresholdTest {

    private val threeMinutes = 3 * 60_000L
    private val tenMinutes = 10 * 60_000L

    @Test
    fun `half of an ordinary track is a play`() {
        assertFalse(PlayThreshold.countsAsPlay(threeMinutes / 2 - 1, threeMinutes))
        assertTrue(PlayThreshold.countsAsPlay(threeMinutes / 2, threeMinutes))
    }

    @Test
    fun `four minutes is always enough, however long the track`() {
        // Half of ten minutes would be five. The cap means a long piece does not
        // demand more attention than anything else to register.
        assertEquals(4 * 60_000L, PlayThreshold.requiredMs(tenMinutes))
        assertTrue(PlayThreshold.countsAsPlay(4 * 60_000L, tenMinutes))
    }

    @Test
    fun `very short tracks count as neither played nor skipped`() {
        // A two-second interlude would otherwise collect plays simply by being
        // passed through on the way down an album.
        assertNull(PlayThreshold.requiredMs(2_000))
        assertFalse(PlayThreshold.countsAsPlay(2_000, 2_000))
        assertFalse(PlayThreshold.countsAsSkip(1_500, 2_000))
    }

    @Test
    fun `the boundary is exactly thirty seconds`() {
        assertNull(PlayThreshold.requiredMs(PlayThreshold.MIN_TRACK_MS - 1))
        assertEquals(15_000L, PlayThreshold.requiredMs(PlayThreshold.MIN_TRACK_MS))
    }

    @Test
    fun `moving straight past a track is browsing, not a skip`() {
        // Scrolling to what you actually wanted must not count against every
        // track on the way, or the shuffle weighting learns nonsense.
        assertFalse(PlayThreshold.countsAsSkip(1_000, threeMinutes))
        assertFalse(PlayThreshold.countsAsSkip(4_999, threeMinutes))
    }

    @Test
    fun `giving up part way through is a skip`() {
        assertTrue(PlayThreshold.countsAsSkip(30_000, threeMinutes))
        assertTrue(PlayThreshold.countsAsSkip(threeMinutes / 2 - 1, threeMinutes))
    }

    @Test
    fun `a play is never also a skip`() {
        // The two are asked separately by the caller, so an overlap would
        // record both against the same listen.
        for (position in 0..threeMinutes step 1_000) {
            val played = PlayThreshold.countsAsPlay(position, threeMinutes)
            val skipped = PlayThreshold.countsAsSkip(position, threeMinutes)
            assertFalse("both at $position", played && skipped)
        }
    }

    @Test
    fun `a zero or unknown duration counts as nothing`() {
        assertNull(PlayThreshold.requiredMs(0))
        assertFalse(PlayThreshold.countsAsPlay(10_000, 0))
        assertFalse(PlayThreshold.countsAsSkip(10_000, 0))
    }
}
