package app.roam.feature.player

import app.roam.core.database.ShuffleRow
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random

data class ShufflePrefs(
    val lovedMultiplier: Double = 3.0,
    val recencyDamping: Boolean = true,
    val skipPenalty: Boolean = true,
)

/**
 * Weighted shuffle without replacement -- Efraimidis-Spirakis A-Res.
 *
 * Assign each item key = U^(1/w) with U uniform on (0,1), then sort by key
 * descending. One pass, unbiased, no duplicates, and no reservoir juggling.
 *
 * A loved track at 3.0x is roughly three times as likely to land near the
 * front. It is a preference, not a filter -- everything still gets played.
 */
class ShuffleEngine @Inject constructor() {

    fun shuffle(rows: List<ShuffleRow>, prefs: ShufflePrefs = ShufflePrefs()): List<Long> {
        val now = System.currentTimeMillis()
        return rows.map { row ->
            var w = 1.0
            if (row.loved) w *= prefs.lovedMultiplier
            if (prefs.skipPenalty && row.skipCount > 3) w *= 0.5
            if (prefs.recencyDamping) row.lastPlayedAt?.let { last ->
                val days = (now - last) / 86_400_000.0
                if (days < RECENCY_DAYS) w *= 0.35 + 0.65 * (days / RECENCY_DAYS)
            }
            row.id to Random.nextDouble().pow(1.0 / w.coerceAtLeast(0.01))
        }.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Player shuffle button: reorder only what has not played yet. Never
     * re-shuffles history and never interrupts the current track.
     */
    fun reshuffleTail(queue: List<Long>, currentIndex: Int): List<Long> {
        if (currentIndex >= queue.lastIndex) return queue
        val head = queue.take(currentIndex + 1)
        val tail = queue.drop(currentIndex + 1).shuffled()
        return head + tail
    }

    companion object { const val RECENCY_DAYS = 14.0 }
}
