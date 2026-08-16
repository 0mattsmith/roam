package app.roam.data.catalog.metadata

import android.content.Context
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.SourceType
import app.roam.data.source.SourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Provider
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

/** What a sweep did, for the progress line in Settings. */
data class LyricsSweep(val found: Int, val missed: Int, val attempted: Int)

/**
 * Room is the cache; the file beside the track is the real home.
 *
 * Order matches the artwork rules (invariant 6c) for the same reason: what the
 * user has is worth more than what the internet thinks, and something they put
 * there by hand must never be argued with. So:
 *
 *   1. `.lrc` / `.txt` sitting next to the audio file
 *   2. LRCLIB, whose answer is written back as a sidecar so step 1 finds it
 *      next time -- on this device, on the desktop app, after a reinstall
 *
 * Which also means the second lookup for a track is free and works offline.
 */
@Singleton
class LyricsRepository @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracks: TrackDao,
    private val lrcLib: LrcLib,
    private val files: LyricFiles,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
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
     * [force] is the re-check: the person asked again on purpose, so a previous
     * miss should not stand in the way. It still will not overwrite a sidecar
     * file, because that is the one source that outranks LRCLIB.
     */
    suspend fun ensureFetched(trackId: Long, force: Boolean = false): Boolean {
        if (!force && tracks.lyricsForOnce(trackId)?.attemptedAt != null) return false

        val subject = tracks.lyricSubject(trackId) ?: return false
        val saved = settings.settings.first()
        val provider = providers[SourceType.DRIVE]?.get()
        val root = saved.driveFolderId

        // The source first, and if it answers nothing else happens -- no
        // request, no upload, and it works with no signal at all.
        val fromFile = if (provider != null && root != null &&
            subject.fileName != null && subject.folderPath != null
        ) {
            runCatching {
                files.read(provider, root, subject.folderPath, subject.fileName)
            }.getOrNull()
        } else null

        val found = fromFile ?: lrcLib.find(
            title = subject.title,
            artist = subject.artistName,
            album = subject.albumTitle,
            durationMs = subject.durationMs,
        )

        // Written back only when it came from the network. Saving a file we
        // just read from that same folder would be a no-op at best.
        if (fromFile == null && found != null && saved.saveLyricsToDrive &&
            provider != null && root != null &&
            subject.fileName != null && subject.folderPath != null
        ) {
            runCatching {
                files.write(
                    provider = provider,
                    root = root,
                    folderPath = subject.folderPath,
                    fileName = subject.fileName,
                    lyrics = found,
                    cacheDir = ctx.cacheDir,
                )
            }
        }

        // Stamped whether or not anything came back. Writing only on success is
        // exactly the bug the artist artwork pass had -- a track LRCLIB has
        // never heard of would be asked about on every single play.
        tracks.setLyrics(
            id = trackId,
            plain = found?.plain,
            synced = found?.synced,
            at = System.currentTimeMillis(),
        )
        return found != null
    }

    /**
     * Fills in lyrics for a set of tracks, reporting as it goes.
     *
     * Sequential on purpose. LRCLIB asks for nothing and rate limits nobody,
     * which is exactly why it deserves not to be hammered with a fan-out --
     * and nothing is waiting on this, so there is no reason to hurry.
     */
    suspend fun sweep(
        trackIds: List<Long>,
        force: Boolean,
        onProgress: suspend (LyricsSweep) -> Unit = {},
    ): LyricsSweep {
        var found = 0
        var attempted = 0

        for (id in trackIds) {
            if (ensureFetched(id, force)) found++
            attempted++
            onProgress(LyricsSweep(found, attempted - found, attempted))
        }
        return LyricsSweep(found, attempted - found, attempted)
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
