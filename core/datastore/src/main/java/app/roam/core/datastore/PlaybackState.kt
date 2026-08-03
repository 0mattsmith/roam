package app.roam.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where playback was when Roam last stopped.
 *
 * Deliberately its own DataStore file rather than a corner of RoamSettings.
 * This is written every few seconds while a track plays; settings are written
 * when a person taps something. Sharing a file would mean rewriting the whole
 * settings blob on a timer, and a corrupted write would take the Drive folder
 * id with it.
 */
data class SavedPlayback(
    /** Track ids, in queue order. */
    val trackIds: List<Long>,
    /**
     * The track that was playing. Stored alongside the index because the
     * library can change underneath a saved queue -- a re-sync that drops one
     * track shifts every index after it, and resuming three songs adrift is
     * worse than not resuming at all.
     */
    val currentTrackId: Long,
    val index: Int,
    val positionMs: Long,
    val repeatMode: Int,
    val shuffleEnabled: Boolean,
)

/**
 * Every public function here declares an explicit return type -- see the note
 * in SettingsRepository. `edit {}` returns Preferences, and an expression body
 * would leak androidx.datastore into this module's public API.
 */
@Singleton
class PlaybackStateStore @Inject constructor(@ApplicationContext private val ctx: Context) {

    private object K {
        val TRACK_IDS = stringPreferencesKey("queue_track_ids")
        val CURRENT_ID = longPreferencesKey("queue_current_id")
        val INDEX = intPreferencesKey("queue_index")
        val POSITION = longPreferencesKey("queue_position_ms")
        val REPEAT = intPreferencesKey("queue_repeat_mode")
        val SHUFFLE = booleanPreferencesKey("queue_shuffle")
    }

    /** Null when there is nothing worth resuming. */
    suspend fun load(): SavedPlayback? {
        val p = ctx.playbackStore.data.first()
        val ids = p[K.TRACK_IDS]
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return SavedPlayback(
            trackIds = ids,
            currentTrackId = p[K.CURRENT_ID] ?: ids.first(),
            index = p[K.INDEX] ?: 0,
            positionMs = p[K.POSITION] ?: 0L,
            repeatMode = p[K.REPEAT] ?: 0,
            shuffleEnabled = p[K.SHUFFLE] ?: false,
        )
    }

    suspend fun save(state: SavedPlayback) {
        // An empty queue is never persisted: the player is momentarily empty
        // while the service starts, and letting that land would wipe the very
        // state the restore is about to read.
        if (state.trackIds.isEmpty()) return
        ctx.playbackStore.edit {
            it[K.TRACK_IDS] = state.trackIds.joinToString(",")
            it[K.CURRENT_ID] = state.currentTrackId
            it[K.INDEX] = state.index
            it[K.POSITION] = state.positionMs
            it[K.REPEAT] = state.repeatMode
            it[K.SHUFFLE] = state.shuffleEnabled
        }
    }

    suspend fun clear() {
        ctx.playbackStore.edit { it.clear() }
    }
}

private val Context.playbackStore by preferencesDataStore("roam_playback")
