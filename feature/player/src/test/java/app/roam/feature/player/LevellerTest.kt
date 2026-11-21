package app.roam.feature.player

import app.roam.core.database.TrackLevel
import app.roam.core.model.TrackVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide what every track sounds like. A wrong sign here is a
 * loud track made louder, which is exactly what this exists to stop.
 */
class LevellerTest {

    private fun level(lufs: Float?, percent: Int = 0) = TrackLevel(loudnessLufs = lufs, volumePercent = percent)

    private fun dbOf(gain: Gain): Double = if (gain.boostMb > 0) {
        gain.boostMb / 100.0
    } else {
        20 * kotlin.math.log10(gain.volume.toDouble())
    }

    @Test
    fun `a quiet track is lifted to the target`() {
        val gain = Leveller.gainFor(level(-20f), levelling = true)
        assertEquals(1f, gain.volume)
        assertEquals(600, gain.boostMb)
    }

    @Test
    fun `a loud track is lowered to the target`() {
        val gain = Leveller.gainFor(level(-8f), levelling = true)
        assertEquals(0, gain.boostMb)
        assertEquals(-6.0, dbOf(gain), 0.01)
    }

    @Test
    fun `a track already at the target is left alone`() {
        assertEquals(Gain.UNITY, Leveller.gainFor(level(Leveller.TARGET_LUFS), levelling = true))
    }

    @Test
    fun `an unmeasured track is left alone`() {
        assertEquals(Gain.UNITY, Leveller.gainFor(level(null), levelling = true))
        assertEquals(Gain.UNITY, Leveller.gainFor(null, levelling = true))
    }

    @Test
    fun `with levelling off, the measurement is ignored`() {
        assertEquals(Gain.UNITY, Leveller.gainFor(level(-30f), levelling = false))
    }

    @Test
    fun `a track's own adjustment applies with levelling off too`() {
        val gain = Leveller.gainFor(level(-30f, percent = 100), levelling = false)
        assertEquals(602, gain.boostMb)
    }

    @Test
    fun `the adjustment adds to levelling`() {
        // -8 LUFS is lowered 6 dB; +100% gives 6.02 of it back.
        val gain = Leveller.gainFor(level(-8f, percent = 100), levelling = true)
        assertEquals(0.02, dbOf(gain), 0.01)
    }

    @Test
    fun `minus a hundred percent is silence, whatever else applies`() {
        assertEquals(Gain.SILENT, Leveller.gainFor(level(-30f, percent = -100), levelling = true))
        assertEquals(Gain.SILENT, Leveller.gainFor(level(null, percent = -100), levelling = false))
    }

    @Test
    fun `lift is capped, so a near-silent recording is not turned into noise`() {
        val gain = Leveller.gainFor(level(-60f), levelling = true)
        assertEquals((Leveller.MAX_LIFT_DB * 100).toInt(), gain.boostMb)
    }

    @Test
    fun `lift with the adjustment is capped too`() {
        val gain = Leveller.gainFor(level(-60f, percent = 100), levelling = true)
        assertEquals((Leveller.MAX_TOTAL_LIFT_DB * 100).toInt(), gain.boostMb)
    }

    @Test
    fun `the percentages mean level, not loudness`() {
        assertEquals(6.02, TrackVolume.gainDb(100)!!.toDouble(), 0.01)
        assertEquals(-6.02, TrackVolume.gainDb(-50)!!.toDouble(), 0.01)
        assertEquals(0.0, TrackVolume.gainDb(0)!!.toDouble(), 0.0)
        assertNull(TrackVolume.gainDb(-100))
        assertEquals(100, TrackVolume.clamp(250))
    }

    @Test
    fun `a measurement is kept for most of a track, or four minutes of a long one`() {
        assertTrue(Leveller.worthKeeping(measuredMs = 150_000, durationMs = 200_000))
        assertFalse(Leveller.worthKeeping(measuredMs = 30_000, durationMs = 200_000))
        assertTrue(Leveller.worthKeeping(measuredMs = 240_000, durationMs = 1_200_000))
        // An unknown length only counts once it is plainly enough.
        assertFalse(Leveller.worthKeeping(measuredMs = 200_000, durationMs = null))
        assertTrue(Leveller.worthKeeping(measuredMs = 240_000, durationMs = null))
        assertFalse(Leveller.worthKeeping(measuredMs = 0, durationMs = 200_000))
    }
}
