package app.roam.feature.player

import app.roam.core.database.TrackLevel
import app.roam.core.model.TrackVolume
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A gain, as the two things that can carry it. Neither can do the whole job:
 * the player's volume only lowers, and the LoudnessEnhancer only lifts.
 */
internal data class Gain(
    /** The player's own volume, 0 to 1. */
    val volume: Float,
    /** The lift through the LoudnessEnhancer, in millibels. */
    val boostMb: Int,
) {
    companion object {
        val UNITY = Gain(1f, 0)
        val SILENT = Gain(0f, 0)
    }
}

/**
 * What a track should be turned up or down by (docs/VOLUME.md). Pure, so the
 * rules are tested rather than listened for.
 */
internal object Leveller {

    /**
     * Where every track is brought to: the level Spotify, YouTube and Tidal
     * all settle on. Low enough that most music is lowered to meet it, which
     * costs nothing; a quiet old master is lifted, through a limiter.
     */
    const val TARGET_LUFS = -14f

    /**
     * How far a quiet track is lifted at most. Past this the limiter is doing
     * the work, and a quiet track becomes a squashed one.
     */
    const val MAX_LIFT_DB = 9f

    /** How far a loud track is lowered at most. Generous: lowering costs nothing. */
    const val MAX_CUT_DB = 18f

    /** The ceiling on lift with a track's own adjustment included, so +100% cannot run away. */
    const val MAX_TOTAL_LIFT_DB = 15f

    /** Long enough to know a track's level, however long the track. */
    const val LONG_ENOUGH_MS = 4 * 60_000L

    /**
     * The gain for one track. Levelling brings it to [TARGET_LUFS] once its
     * loudness is known; its own adjustment applies on top, and on its own
     * when levelling is off -- it is a setting somebody chose for that track.
     */
    fun gainFor(level: TrackLevel?, levelling: Boolean): Gain {
        val own = TrackVolume.gainDb(level?.volumePercent ?: 0) ?: return Gain.SILENT
        val loudness = level?.loudnessLufs
        val levelled = if (levelling && loudness != null) {
            (TARGET_LUFS - loudness).coerceIn(-MAX_CUT_DB, MAX_LIFT_DB)
        } else {
            0f
        }
        val total = (levelled + own).coerceAtMost(MAX_TOTAL_LIFT_DB)
        return if (total <= 0f) {
            Gain(10f.pow(total / 20f), 0)
        } else {
            Gain(1f, (total * 100).roundToInt())
        }
    }

    /**
     * Whether a measurement says enough to keep: most of the track, or four
     * minutes of a long one. A track skipped after its intro measured its
     * intro, and a quiet intro would have it lifted for good.
     */
    fun worthKeeping(measuredMs: Long, durationMs: Long?): Boolean {
        if (measuredMs <= 0) return false
        if (measuredMs >= LONG_ENOUGH_MS) return true
        val duration = durationMs ?: return false
        return duration > 0 && measuredMs * 4 >= duration * 3
    }
}
