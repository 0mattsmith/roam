package app.roam.feature.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.roam.core.database.TrackListItem
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
    val isPlaying: Boolean = false,
    val hasItem: Boolean = false,
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
        c.setMediaItems(tracks.map { it.toMediaItem() }, startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    private fun publish(player: Player) {
        val meta = player.currentMediaItem?.mediaMetadata
        _nowPlaying.value = NowPlaying(
            title = meta?.title?.toString().orEmpty(),
            artist = meta?.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            hasItem = player.currentMediaItem != null,
        )
    }
}

fun TrackListItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri("drive://file/$remoteId")
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistName)
            .setAlbumTitle(albumTitle)
            .setTrackNumber(trackNo ?: 0)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()
