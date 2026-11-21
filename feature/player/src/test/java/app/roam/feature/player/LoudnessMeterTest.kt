package app.roam.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The meter against the standard's own test signals. EBU Tech 3341 gives the
 * cases and the answers, to within 0.1 LU; BS.1770 itself gives the
 * calibration -- a full-scale 997 Hz tone in one channel reads -3.01 LUFS. If
 * these hold, "the same volume" means what it means to everyone else.
 */
class LoudnessMeterTest {

    private fun meter(rate: Int = 48_000, channels: Int = 2) =
        LoudnessMeter().apply { configure(rate, channels) }

    /** A tone, or silence for a null level, fed in chunks the way the audio thread feeds it. */
    private fun LoudnessMeter.tone(
        seconds: Double,
        dbfs: Double?,
        rate: Int = 48_000,
        channels: Int = 2,
        hz: Double = 1_000.0,
        onlyChannel: Int? = null,
        float: Boolean = false,
    ) {
        val amplitude = dbfs?.let { 10.0.pow(it / 20) } ?: 0.0
        val total = (rate * seconds).toInt()
        var done = 0
        while (done < total) {
            val frames = minOf(CHUNK, total - done)
            val bytes = if (float) 4 else 2
            val buffer = ByteBuffer.allocate(frames * channels * bytes).order(ByteOrder.nativeOrder())
            for (f in 0 until frames) {
                val v = amplitude * sin(2 * PI * hz * (done + f) / rate)
                for (ch in 0 until channels) {
                    val s = if (onlyChannel == null || onlyChannel == ch) v else 0.0
                    if (float) buffer.putFloat(s.toFloat()) else buffer.putShort((s * 32767).toInt().toShort())
                }
            }
            buffer.flip()
            if (float) feedFloat(buffer) else feedPcm16(buffer)
            assertEquals("the meter must not move the buffer it reads", 0, buffer.position())
            done += frames
        }
    }

    private fun assertLufs(expected: Double, meter: LoudnessMeter) {
        val lufs = meter.take().lufs
        assertEquals(expected, lufs!!.toDouble(), TOLERANCE)
    }

    @Test
    fun `EBU 3341 case 1 - a -23 dBFS tone reads -23 LUFS`() {
        assertLufs(-23.0, meter().apply { tone(20.0, -23.0) })
    }

    @Test
    fun `EBU 3341 case 2 - a -33 dBFS tone reads -33 LUFS`() {
        assertLufs(-33.0, meter().apply { tone(20.0, -33.0) })
    }

    @Test
    fun `EBU 3341 case 3 - quieter passages either side are gated out`() {
        val m = meter().apply {
            tone(10.0, -36.0)
            tone(60.0, -23.0)
            tone(10.0, -36.0)
        }
        assertLufs(-23.0, m)
    }

    @Test
    fun `EBU 3341 case 4 - near silence counts for nothing at all`() {
        val m = meter().apply {
            tone(10.0, -72.0)
            tone(10.0, -36.0)
            tone(60.0, -23.0)
            tone(10.0, -36.0)
            tone(10.0, -72.0)
        }
        assertLufs(-23.0, m)
    }

    @Test
    fun `BS 1770 calibration - full scale 997 Hz in one channel reads -3_01`() {
        assertLufs(-3.01, meter().apply { tone(20.0, 0.0, hz = 997.0, onlyChannel = 0, float = true) })
    }

    @Test
    fun `44_1 kHz reads the same as 48`() {
        assertLufs(-23.0, meter(rate = 44_100).apply { tone(20.0, -23.0, rate = 44_100) })
    }

    @Test
    fun `float and 16-bit agree`() {
        assertLufs(-23.0, meter(rate = 44_100).apply { tone(20.0, -23.0, rate = 44_100, float = true) })
    }

    @Test
    fun `mono counts twice, so it reads like the same song in stereo`() {
        assertLufs(-23.0, meter(rate = 44_100, channels = 1).apply { tone(20.0, -23.0, rate = 44_100, channels = 1) })
    }

    @Test
    fun `silence has no loudness`() {
        assertNull(meter().apply { tone(5.0, null) }.take().lufs)
    }

    @Test
    fun `less than one block has no loudness`() {
        assertNull(meter().apply { tone(0.3, -23.0) }.take().lufs)
    }

    @Test
    fun `take reports how long was heard, and starts over`() {
        val m = meter().apply { tone(10.0, -23.0) }
        assertEquals(10_000.0, m.take().measuredMs.toDouble(), 2.0)
        assertEquals(0L, m.take().measuredMs)
    }

    @Test
    fun `nothing is heard until it knows the format`() {
        val m = LoudnessMeter()
        m.tone(5.0, -23.0)
        assertEquals(0L, m.take().measuredMs)
    }

    private companion object {
        const val CHUNK = 4_096
        const val TOLERANCE = 0.1
    }
}
