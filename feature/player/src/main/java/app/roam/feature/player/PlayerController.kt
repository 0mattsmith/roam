package app.roam.feature.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.roam.core.database.TrackListItem
import app.roam.data.catalog.artwork.ArtworkProvider
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NowPlaying(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val hasItem: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/**
 * The UI's handle on playback.
 *
 * Everything goes through a MediaController rather than touching ExoPlayer
 * directly -- the same interface Android Auto uses, so the phone and the car
 * cannot drift apart.
 */
@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private var controller: MediaController? = null

    private val _nowPlaying = MutableStateFlow(NowPlaying())
    val nowPlaying: StateFlow<NowPlaying> = _nowPlaying.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Idempotent: safe to call from every screen that needs playback. */
    fun connect() {
        if (controller != null) return
        val token = SessionToken(ctx, ComponentName(ctx, RoamLibraryService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = future.get().also {
                it.addListener(listener)
                publish(it)
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /**
     * Plays [tracks] starting at [startIndex].
     *
     * The URI is drive://file/<fileId>; DriveDataSourceFactory rewrites it to
     * the googleapis media endpoint and stamps a fresh token on every request,
     * including each range request. Baking a signed URL into the MediaItem
     * would expire mid-track.
     */
    fun play(tracks: List<TrackListItem>, startIndex: Int) {
        val c = controller ?: return
        c.setMediaItems(tracks.map { it.toMediaItem(ctx) }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }

    /**
     * Restart the track if we are more than a few seconds in, otherwise go
     * back one. Matches what every other player does, and stops a mistimed
     * tap skipping a song you were enjoying.
     */
    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > RESTART_THRESHOLD_MS) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    /** Cycles off -> all -> one, the order people expect from the icon. */
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Position does not emit events, so the UI needs a tick while playing. */
    fun refreshPosition() {
        controller?.let { publish(it) }
    }

    private fun publish(player: Player) {
        val meta = player.currentMediaItem?.mediaMetadata
        _nowPlaying.value = NowPlaying(
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.artist?.toString().orEmpty(),
            album = meta?.albumTitle?.toString().orEmpty(),
            artworkUri = meta?.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            hasItem = player.currentMediaItem != null,
            positionMs = player.currentPosition.coerceAtLeast(0),
            // Duration is C.TIME_UNSET until the track is prepared.
            durationMs = player.duration.takeIf { it > 0 } ?: 0,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    private companion object { const val RESTART_THRESHOLD_MS = 3_000L }
}

fun TrackListItem.toMediaItem(ctx: Context): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri("drive://file/$remoteId")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumTitle)
            .setTrackNumber(trackNo ?: 0)
            // content:// rather than a bitmap: Android Auto refuses bitmaps and
            // they blow the Binder limit, so both surfaces use the same URI.
            .setArtworkUri(artworkId?.let { ArtworkProvider.uri(ctx, it, size = 640) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()
