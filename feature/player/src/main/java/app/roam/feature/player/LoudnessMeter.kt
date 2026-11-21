package app.roam.feature.player

import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * How loud a track is, measured the way ITU-R BS.1770-4 and EBU R128 measure
 * it: K-weighted, in 400 ms blocks that overlap by three quarters, and gated
 * twice -- once at -70 LUFS so silence counts for nothing, and once 10 LU below
 * the track's own level so a quiet intro cannot drag the answer down. The
 * result is integrated loudness in LUFS, the number the streaming services
 * level by, so "the same volume" means what it means everywhere else.
 *
 * Fed from the audio thread while a track plays and read from the main thread
 * when it ends, so every entry point is synchronized. The work is two biquads
 * per sample per channel, which is nothing beside decoding the audio at all.
 */
class LoudnessMeter {

    /** What was heard: its loudness, null when nothing cleared the gate, and for how long. */
    data class Reading(val lufs: Float?, val measuredMs: Long)

    private var sampleRate = 0
    private var channels = 0
    private var weights = DoubleArray(0)

    // The two K-weighting stages, as b0 b1 b2 a1 a2: a high shelf for the
    // effect of the head, then the RLB high-pass that ignores rumble.
    private val shelf = DoubleArray(5)
    private val highPass = DoubleArray(5)

    // Direct form I, per channel: x1 x2 y1 y2 for each of the two stages.
    private var state = DoubleArray(0)

    private var subBlockFrames = 0
    private var subBlockFilled = 0
    private var subBlockSum = 0.0
    private val recent = DoubleArray(SUB_BLOCKS)
    private var recentCount = 0
    private var recentNext = 0

    private var blocks = DoubleArray(INITIAL_BLOCKS)
    private var blockCount = 0
    private var measuredMs = 0.0

    /**
     * Called on every flush -- a seek, a new format, a new track -- so the
     * filters start clean. Finished blocks are kept: they are energies, and an
     * energy means the same thing at any sample rate.
     */
    @Synchronized
    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) {
            this.sampleRate = 0
            this.channels = 0
            return
        }
        if (sampleRate != this.sampleRate || channels != this.channels) {
            this.sampleRate = sampleRate
            this.channels = channels
            weights = weightsFor(channels)
            kWeighting(sampleRate.toDouble())
            subBlockFrames = sampleRate / 10
            state = DoubleArray(channels * STATE_PER_CHANNEL)
        } else {
            state.fill(0.0)
        }
        startWindow()
    }

    /** Interleaved 16-bit PCM in the buffer's own byte order. Reads without moving it. */
    @Synchronized
    fun feedPcm16(buffer: ByteBuffer) {
        if (channels == 0) return
        val frameCount = (buffer.limit() - buffer.position()) / (2 * channels)
        var at = buffer.position()
        repeat(frameCount) {
            var energy = 0.0
            for (ch in 0 until channels) {
                val y = filter(ch, buffer.getShort(at) / 32768.0)
                energy += weights[ch] * y * y
                at += 2
            }
            addFrame(energy)
        }
        measuredMs += frameCount * 1000.0 / sampleRate
    }

    /** Interleaved 32-bit float PCM in the buffer's own byte order. Reads without moving it. */
    @Synchronized
    fun feedFloat(buffer: ByteBuffer) {
        if (channels == 0) return
        val frameCount = (buffer.limit() - buffer.position()) / (4 * channels)
        var at = buffer.position()
        repeat(frameCount) {
            var energy = 0.0
            for (ch in 0 until channels) {
                val y = filter(ch, buffer.getFloat(at).toDouble())
                energy += weights[ch] * y * y
                at += 4
            }
            addFrame(energy)
        }
        measuredMs += frameCount * 1000.0 / sampleRate
    }

    /**
     * Everything heard since the last take or reset, and a fresh start.
     *
     * The filters carry on: the audio itself is continuous across a track
     * change, and only the bookkeeping of whose loudness it is starts over.
     */
    @Synchronized
    fun take(): Reading {
        val reading = Reading(integrated(), measuredMs.toLong())
        blockCount = 0
        measuredMs = 0.0
        startWindow()
        return reading
    }

    @Synchronized
    fun reset() {
        blockCount = 0
        measuredMs = 0.0
        startWindow()
    }

    private fun filter(ch: Int, x: Double): Double {
        val s = ch * STATE_PER_CHANNEL
        val y1 = shelf[0] * x + shelf[1] * state[s] + shelf[2] * state[s + 1] -
            shelf[3] * state[s + 2] - shelf[4] * state[s + 3]
        state[s + 1] = state[s]
        state[s] = x
        state[s + 3] = state[s + 2]
        state[s + 2] = y1

        val y2 = highPass[0] * y1 + highPass[1] * state[s + 4] + highPass[2] * state[s + 5] -
            highPass[3] * state[s + 6] - highPass[4] * state[s + 7]
        state[s + 5] = state[s + 4]
        state[s + 4] = y1
        state[s + 7] = state[s + 6]
        state[s + 6] = y2
        return y2
    }

    /**
     * 100 ms at a time. Every completed tenth of a second closes a 400 ms block
     * made of it and the three before, which is the standard's 75% overlap
     * without keeping any samples around.
     */
    private fun addFrame(energy: Double) {
        subBlockSum += energy
        if (++subBlockFilled < subBlockFrames) return

        recent[recentNext] = subBlockSum
        recentNext = (recentNext + 1) % SUB_BLOCKS
        if (recentCount < SUB_BLOCKS) recentCount++
        subBlockSum = 0.0
        subBlockFilled = 0

        if (recentCount == SUB_BLOCKS) addBlock(recent.sum() / (SUB_BLOCKS.toDouble() * subBlockFrames))
    }

    private fun addBlock(meanSquare: Double) {
        if (blockCount == blocks.size) {
            // Two hours is plenty to know how loud something is.
            if (blocks.size >= MAX_BLOCKS) return
            blocks = blocks.copyOf(minOf(blocks.size * 2, MAX_BLOCKS))
        }
        blocks[blockCount++] = meanSquare
    }

    private fun startWindow() {
        subBlockFilled = 0
        subBlockSum = 0.0
        recentCount = 0
        recentNext = 0
    }

    /** BS.1770-4's two gates, then the mean of what is left, in LUFS. */
    private fun integrated(): Float? {
        var sum = 0.0
        var count = 0
        for (i in 0 until blockCount) {
            if (blocks[i] > ABSOLUTE_GATE) {
                sum += blocks[i]
                count++
            }
        }
        if (count == 0) return null

        // 10 LU below the level of everything above the absolute gate.
        val relativeGate = sum / count * RELATIVE_GATE
        sum = 0.0
        count = 0
        for (i in 0 until blockCount) {
            if (blocks[i] > ABSOLUTE_GATE && blocks[i] > relativeGate) {
                sum += blocks[i]
                count++
            }
        }
        if (count == 0) return null
        return (OFFSET + 10 * log10(sum / count)).toFloat()
    }

    /**
     * The standard gives its coefficients at 48 kHz only. These are the same
     * two filters designed for any rate from their analogue prototypes, which
     * is how libebur128 does it and why 44.1 kHz reads the same as 48.
     */
    private fun kWeighting(fs: Double) {
        run {
            val f0 = 1681.974450955533
            val gain = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / fs)
            val vh = 10.0.pow(gain / 20.0)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1.0 + k / q + k * k
            shelf[0] = (vh + vb * k / q + k * k) / a0
            shelf[1] = 2.0 * (k * k - vh) / a0
            shelf[2] = (vh - vb * k / q + k * k) / a0
            shelf[3] = 2.0 * (k * k - 1.0) / a0
            shelf[4] = (1.0 - k / q + k * k) / a0
        }
        run {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / fs)
            val a0 = 1.0 + k / q + k * k
            highPass[0] = 1.0
            highPass[1] = -2.0
            highPass[2] = 1.0
            highPass[3] = 2.0 * (k * k - 1.0) / a0
            highPass[4] = (1.0 - k / q + k * k) / a0
        }
    }

    private companion object {
        const val SUB_BLOCKS = 4
        const val STATE_PER_CHANNEL = 8
        const val INITIAL_BLOCKS = 4096
        /** Ten blocks a second for two hours. */
        const val MAX_BLOCKS = 72_000
        const val OFFSET = -0.691
        /** -70 LUFS, as a mean square: anything quieter is silence. */
        val ABSOLUTE_GATE = 10.0.pow((-70.0 - OFFSET) / 10.0)
        /** -10 LU, as a ratio of mean squares. */
        const val RELATIVE_GATE = 0.1

        /**
         * BS.1770's channel weights. A mono file counts twice, because it
         * plays out of both speakers -- weighted once it would read 3 dB quieter
         * than the same song in stereo, and be lifted by that much for nothing.
         */
        fun weightsFor(channels: Int): DoubleArray = when (channels) {
            1 -> doubleArrayOf(2.0)
            // Android's 5.1 order: L R C LFE Ls Rs. The LFE is not counted.
            6 -> doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.41, 1.41)
            else -> DoubleArray(channels) { 1.0 }
        }
    }
}
