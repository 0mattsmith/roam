package app.roam.feature.player

/**
 * When a track counts as having been PLAYED rather than skipped past.
 *
 * This is the whole basis of "most played", of recently played, and later of
 * the weighting behind a smart playlist, so it wants to be right rather than
 * convenient. Counting a play the moment a track starts would make skipping
 * through an album look like listening to it, and counting only a completed
 * track would miss every song stopped ten seconds from the end.
 *
 * The scrobbling convention is the one people already have intuitions about:
 * half the track, or four minutes, whichever comes first. Four minutes caps it
 * so a twenty-minute piece does not need ten minutes of attention to register,
 * and very short tracks are ignored entirely because a two-second interlude
 * would otherwise rack up plays just by being passed through.
 */
object PlayThreshold {

    /** Below this a track is too short for a play to mean anything. */
    const val MIN_TRACK_MS = 30_000L

    /** However long the track, this much listening is always enough. */
    const val ALWAYS_ENOUGH_MS = 4 * 60_000L

    /**
     * How far in [durationMs] has to be reached for a play to count.
     *
     * Returns null when the track is too short to count at all, which is a
     * different answer from "not yet" -- the caller must not record a skip for
     * one of these either.
     */
    fun requiredMs(durationMs: Long): Long? {
        if (durationMs < MIN_TRACK_MS) return null
        return minOf(durationMs / 2, ALWAYS_ENOUGH_MS)
    }

    /** True when [positionMs] of a [durationMs] track is enough to be a play. */
    fun countsAsPlay(positionMs: Long, durationMs: Long): Boolean {
        val required = requiredMs(durationMs) ?: return false
        return positionMs >= required
    }

    /**
     * True when leaving at [positionMs] should count as a SKIP.
     *
     * Deliberately not just "not a play". A track too short to measure is
     * neither, and nor is one abandoned in the first few seconds -- that is
     * someone scrolling past on the way to what they actually wanted, and
     * counting it against the track would poison the shuffle weighting.
     */
    fun countsAsSkip(positionMs: Long, durationMs: Long): Boolean {
        if (requiredMs(durationMs) == null) return false
        if (positionMs < BROWSING_MS) return false
        return !countsAsPlay(positionMs, durationMs)
    }

    /** Under this, someone is moving through the list rather than rejecting a song. */
    private const val BROWSING_MS = 5_000L
}
