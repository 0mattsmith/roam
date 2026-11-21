package app.roam.core.model

import kotlin.math.log10

/**
 * A track's own volume adjustment, as the editor offers it: a percentage of
 * its level, from -100 (silent) to +100 (twice the level).
 *
 * In one place because three modules handle the number -- the editor that sets
 * it, the catalogue that stores it and the player that applies it -- and they
 * must agree about what it means. Level, not loudness: +100% doubles the
 * amplitude, which is +6 dB.
 */
object TrackVolume {
    const val MIN_PERCENT: Int = -100
    const val MAX_PERCENT: Int = 100

    /** The slider moves in fives: finer than anyone hears, and coarse enough to land on zero. */
    const val STEP_PERCENT: Int = 5

    fun clamp(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** The adjustment in decibels, or null for silence. */
    fun gainDb(percent: Int): Float? {
        val p = clamp(percent)
        if (p == MIN_PERCENT) return null
        return (20.0 * log10(1.0 + p / 100.0)).toFloat()
    }
}
