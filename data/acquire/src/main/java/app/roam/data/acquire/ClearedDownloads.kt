package app.roam.data.acquire

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Failures somebody has seen and dismissed.
 *
 * WorkManager cannot forget one finished job -- only pruneWork, which forgets
 * every one, the successes included -- so a dismissed failure is remembered
 * here and left out of what the Adding list and the search page read. On disk,
 * or a failure cleared before a restart would come straight back after it.
 *
 * Trimmed on every write to the jobs WorkManager still has. It prunes finished
 * work by itself after a day, and an id it has forgotten can never come back.
 */
@Singleton
class ClearedDownloads @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _ids = MutableStateFlow(prefs.getStringSet(KEY, null)?.toSet().orEmpty())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /**
     * @param known every job id WorkManager still lists, so the set only ever
     * holds what could otherwise reappear.
     */
    fun clear(jobIds: Collection<String>, known: Collection<String>) {
        val keep = known.toSet() + jobIds
        val next = (_ids.value + jobIds).filterTo(HashSet()) { it in keep }
        _ids.value = next
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private companion object {
        const val FILE = "roam_cleared_downloads"
        const val KEY = "ids"
    }
}
