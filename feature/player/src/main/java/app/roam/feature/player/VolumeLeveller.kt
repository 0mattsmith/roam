package app.roam.feature.player

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Every track at one level, and each track's own adjustment on top of that
 * (docs/VOLUME.md).
 *
 * Two halves. While a track plays, [LoudnessMeter] hears it through
 * [MeteringAudioProcessor], and once most of it has been heard its loudness is
 * kept against its row, once. At every track change the gain for the next one
 * is worked out -- from its own measurement, from its album's while it has
 * none, and from its own adjustment -- and applied.
 *
 * The gain acts where the audio is HEARD, not where it is decoded: lowering
 * through the player's volume, which is the AudioTrack's, and lifting through a
 * LoudnessEnhancer on the player's audio session, which works in the mixer.
 * Both are past the decode-ahead buffer, so a change made at the track
 * transition lands where the new track begins. Applied inside the audio
 * pipeline it would meet the next track up to a second before anyone heard it,
 * and every track would open at the last one's level.
 *
 * Everything here runs on the main thread, the player's own.
 */
internal class VolumeLeveller(
    private val player: ExoPlayer,
    private val meter: LoudnessMeter,
    private val tracks: TrackDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    /** Null where the device will not give one, which costs the lift and nothing else. */
    private var enhancer: LoudnessEnhancer? = null
    private var applied: Gain = Gain.UNITY
    private var following: Job? = null
    private var trackId: Long? = null
    private var durationMs: Long? = null

    fun start() {
        attach(player.audioSessionId)
        follow(player.currentMediaItem)
    }

    fun release() {
        following?.cancel()
        runCatching { enhancer?.release() }
        enhancer = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        keepWhatWasHeard()
        follow(mediaItem)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            // The real length, trim included, once the player knows it: the
            // stored duration is 0 until the tag pass has read the file, and a
            // track measured against nothing would never be kept.
            Player.STATE_READY -> player.duration
                .takeIf { it != C.TIME_UNSET && it > 0 }
                ?.let { durationMs = it }
            // The last track in a queue never transitions anywhere, so it is
            // kept here or not at all -- and on an album that is the closer.
            Player.STATE_ENDED -> keepWhatWasHeard()
        }
    }

    override fun onAudioSessionIdChanged(audioSessionId: Int) {
        attach(audioSessionId)
    }

    /**
     * Follows the track now playing: its gain now, and again whenever its row
     * or the setting changes -- an adjustment made in the editor while the song
     * plays is heard at once.
     */
    private fun follow(item: MediaItem?) {
        following?.cancel()
        meter.reset()
        val id = item?.let(::trackIdOf)
        trackId = id
        durationMs = item?.mediaMetadata?.durationMs?.takeIf { it > 0 }
        if (id == null) {
            apply(Gain.UNITY)
            return
        }
        following = scope.launch {
            combine(
                settings.settings.map { it.levelVolume }.distinctUntilChanged(),
                tracks.levelFor(id),
            ) { levelling, level -> Leveller.gainFor(level, levelling) }
                .distinctUntilChanged()
                .collect { apply(it) }
        }
    }

    /**
     * Keeps the loudness of the track just left, when enough of it was heard.
     * Measured whether levelling is on or not, so turning it on later has a
     * library's worth of measurements to work with.
     */
    private fun keepWhatWasHeard() {
        val id = trackId ?: return
        val reading = meter.take()
        val lufs = reading.lufs ?: return
        if (!Leveller.worthKeeping(reading.measuredMs, durationMs)) return
        scope.launch(Dispatchers.IO) { tracks.setLoudnessIfMissing(id, lufs) }
    }

    private fun apply(gain: Gain) {
        applied = gain
        player.volume = gain.volume
        val effect = enhancer ?: return
        runCatching {
            if (gain.boostMb > 0) {
                effect.setTargetGain(gain.boostMb)
                effect.setEnabled(true)
            } else {
                effect.setEnabled(false)
            }
        }
    }

    /**
     * A LoudnessEnhancer on the player's session. Some devices refuse one --
     * another app holding the session's effects, or a vendor build without it
     * -- and then quiet tracks are not lifted, while loud ones are still
     * lowered. Better than refusing to play.
     */
    private fun attach(sessionId: Int) {
        runCatching { enhancer?.release() }
        enhancer = if (sessionId == C.AUDIO_SESSION_ID_UNSET) {
            null
        } else {
            runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        }
        apply(applied)
    }

    private fun trackIdOf(item: MediaItem): Long? =
        runCatching { MediaId.parse(item.mediaId) }.getOrNull()
            ?.let { (it as? MediaId.Track)?.id }
}
