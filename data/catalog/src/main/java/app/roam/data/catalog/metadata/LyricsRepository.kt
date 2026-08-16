package app.roam.data.catalog.metadata

import app.roam.core.database.TrackDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Lyrics as a screen needs them: already parsed, and already decided about. */
data class TrackLyrics(
    val lines: List<LyricLine> = emptyList(),
    val plain: String? = null,
    /** True once a lookup has happened, whatever it found. */
    val resolved: Boolean = false,
) {
    val synced: Boolean get() = lines.isNotEmpty()
    val hasAny: Boolean get() = synced || !plain.isNullOrBlank()
    val instrumental: Boolean get() = plain == LrcLib.INSTRUMENTAL
}

/**
 * Room is the source of truth; LRCLIB is only ever consulted once per track.
 *
 * The fetch is on demand rather than a background sweep. A library sweep would
 * be one request per track for words almost none of which anyone will read,
 * against a service that costs nothing and asks for nothing -- the polite and
 * cheap thing is to ask only about the song actually playing.
 */
@Singleton
class LyricsRepository @Inject constructor(
    private val tracks: TrackDao,
    private val lrcLib: LrcLib,
) {

    fun lyricsFor(trackId: Long): Flow<TrackLyrics> =
        tracks.lyricsFor(trackId).map { stored ->
            TrackLyrics(
                lines = stored?.synced?.let { LrcLib.parseLrc(it) }.orEmpty(),
                plain = stored?.plain,
                resolved = stored?.attemptedAt != null,
            )
        }

    /**
     * Looks a track up if it has never been looked up before.
     *
     * [force] is the "Fetch lyrics" button in the editor: the person is asking
     * again on purpose, so a previous miss should not stand in the way.
     */
    suspend fun ensureFetched(trackId: Long, force: Boolean = false) {
        val subject = tracks.lyricSubject(trackId) ?: return

        if (!force) {
            val stored = tracks.lyricsForOnce(trackId)
            // Already asked. A miss is an answer and is not asked again --
            // otherwise a track LRCLIB has never heard of costs a request on
            // every play, forever.
            if (stored?.attemptedAt != null) return
        }

        val found = lrcLib.find(
            title = subject.title,
            artist = subject.artistName,
            album = subject.albumTitle,
            durationMs = subject.durationMs,
        )

        // Stamped whether or not anything came back. Writing only on success is
        // exactly the bug the artist artwork pass had.
        tracks.setLyrics(
            id = trackId,
            plain = found?.plain,
            synced = found?.synced,
            at = System.currentTimeMillis(),
        )
    }

    /** Typed by hand in the metadata editor. Stamped, so nothing overwrites it. */
    suspend fun setManual(trackId: Long, plain: String?) {
        tracks.setLyrics(
            id = trackId,
            plain = plain?.takeIf { it.isNotBlank() },
            synced = null,
            at = System.currentTimeMillis(),
        )
    }
}
