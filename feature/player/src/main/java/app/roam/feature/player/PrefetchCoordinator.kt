package app.roam.feature.player

import app.roam.core.model.CachePolicy
import kotlinx.coroutines.Job
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the next N queue items warm so playback survives a dead spot.
 *
 * Both cache modes are the same machinery with a different bound:
 *   NextTracks(n)      -> window = n, evictor = max(n * 12 MB, 250 MB)
 *   StorageBudget(b)   -> evictor = b, window = clamp(b / avgTrackBytes, 3, 50)
 *
 * On a skip the window is recomputed and in-flight CacheWriters outside it are
 * cancelled, so a rapid-skip burst does not queue ten redundant downloads.
 * Skipped-past items are NOT purged -- LRU reclaims them naturally and you may
 * skip back.
 */
@Singleton
class PrefetchCoordinator @Inject constructor() {

    private val inFlight = mutableMapOf<Long, Job>()

    fun windowSize(policy: CachePolicy, avgTrackBytes: Long = AVG_TRACK_BYTES): Int = when (policy) {
        is CachePolicy.NextTracks -> policy.count
        is CachePolicy.StorageBudget -> (policy.bytes / avgTrackBytes).toInt().coerceIn(3, 50)
    }

    fun evictorBytes(policy: CachePolicy): Long = when (policy) {
        is CachePolicy.NextTracks -> maxOf(policy.count * 12L * 1024 * 1024, 250L * 1024 * 1024)
        is CachePolicy.StorageBudget -> policy.bytes
    }

    /** TODO(phase3): run Media3 CacheWriter over the window, lowest index first. */
    fun onQueuePositionChanged(queue: List<Long>, index: Int, policy: CachePolicy) {
        val window = queue.drop(index + 1).take(windowSize(policy)).toSet()
        inFlight.keys.filterNot { it in window }.forEach { inFlight.remove(it)?.cancel() }
    }

    companion object { const val AVG_TRACK_BYTES = 8L * 1024 * 1024 }
}
