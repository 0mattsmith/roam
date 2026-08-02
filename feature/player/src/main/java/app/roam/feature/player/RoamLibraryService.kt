package app.roam.feature.player

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.roam.core.database.TrackDao
import app.roam.core.model.SourceType
import app.roam.data.source.SourceProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Provider

/**
 * The playback engine and the car surface.
 *
 * HARD RULE: browse responses read Room and the ArtworkStore only. They never
 * block on the network. With the catalogue synced, browsing works with no
 * signal at all; only the audio stream needs data.
 */
@AndroidEntryPoint
class RoamLibraryService : MediaLibraryService() {

    @Inject lateinit var browseTree: BrowseTree
    @Inject lateinit var queueBuilder: QueueBuilder
    @Inject lateinit var shuffleEngine: ShuffleEngine
    @Inject lateinit var tracks: TrackDao
    @Inject lateinit var cache: SimpleCache
    @Inject lateinit var providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>

    private var session: MediaLibrarySession? = null

    /**
     * Browse answers come from Room, so they suspend, but Media3 wants a
     * ListenableFuture. Bridged here rather than with runBlocking -- blocking
     * the binder thread that asked for a browse page is how the car UI freezes.
     */
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    private fun <T> CoroutineScope.future(block: suspend () -> T): ListenableFuture<T> {
        val settable = SettableFuture.create<T>()
        launch {
            runCatching { block() }
                .onSuccess { settable.set(it) }
                .onFailure { settable.setException(it) }
        }
        return settable
    }

    override fun onCreate() {
        super.onCreate()

        val drive = providers[SourceType.DRIVE]?.get()
            ?: error("No Drive provider bound")

        // Every read goes cache-first, then Drive. FLAG_IGNORE_CACHE_ON_ERROR
        // is what lets playback survive a dead spot: a network failure serves
        // whatever is cached instead of killing the track.
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(drive.dataSourceFactory())
            .setFlags(PlayerModule.CACHE_FLAGS)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()

        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(
                packageManager.getLaunchIntentForPackage(packageName)?.let {
                    android.app.PendingIntent.getActivity(
                        this, 0, it,
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                } ?: return
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    /** Stop the service when the user swipes the app away with nothing playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    inner class Callback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // The head unit advertises how many root tabs it can render.
            val limit = params?.extras?.getInt(CarConstants.ROOT_HINT_CHILDREN_LIMIT, 0)
                ?.takeIf { it > 0 } ?: CarConstants.DEFAULT_ROOT_TABS
            browseTree.rootChildrenLimit = limit
            return Futures.immediateFuture(LibraryResult.ofItem(browseTree.rootItem(), params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val parent = MediaId.parse(parentId)
            return scope.future {
                LibraryResult.ofItemList(
                    browseTree.children(parent, page, pageSize),
                    browseTree.paramsFor(parent, params),
                )
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            browseTree.item(MediaId.parse(mediaId))
                ?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        /**
         * The car hands back the browse item it was given, which carries a
         * media id and no URI. Resolving it here is what turns a tap in the
         * browse tree into audio -- without this the tree renders perfectly and
         * nothing plays.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                // An item that already carries a URI came from the phone UI and
                // is ready to play as-is.
                if (item.localConfiguration != null) listOf(item)
                else queueBuilder.resolve(MediaId.parse(item.mediaId))
            }.toMutableList()
        }

        /**
         * Same resolution as onAddMediaItems, but this one can say where to
         * start. Tapping track five of an album has to begin at five, and only
         * this callback carries a start index back to the player.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val single = mediaItems.singleOrNull()
            if (single != null && single.localConfiguration == null) {
                val queue = queueBuilder.resolveWithStart(MediaId.parse(single.mediaId))
                MediaSession.MediaItemsWithStartPosition(
                    queue.items,
                    if (startIndex == C.INDEX_UNSET) queue.startIndex else startIndex,
                    startPositionMs,
                )
            } else {
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(CarConstants.ACTION_LOVE, Bundle.EMPTY))
                .add(SessionCommand(CarConstants.ACTION_SHUFFLE_QUEUE, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(customLayout(loved = false))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            CarConstants.ACTION_LOVE -> scope.future {
                toggleLoveOnCurrent()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            CarConstants.ACTION_SHUFFLE_QUEUE -> scope.future {
                reshuffleQueueTail()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }

        // TODO(phase2): onSearch / onGetSearchResult over the FTS index. Voice
        //   is the only search available in the car -- there is no keyboard
        //   while driving. Needs an FTS table, which does not exist yet.
    }

    /** The heart reflects the current track, so it is rebuilt as tracks change. */
    private fun customLayout(loved: Boolean): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder()
            .setDisplayName(if (loved) "Loved" else "Love")
            .setIconResId(if (loved) R.drawable.ic_car_loved else R.drawable.ic_car_love)
            .setSessionCommand(SessionCommand(CarConstants.ACTION_LOVE, Bundle.EMPTY))
            .build(),
        CommandButton.Builder()
            .setDisplayName("Shuffle queue")
            .setIconResId(R.drawable.ic_car_shuffle)
            .setSessionCommand(SessionCommand(CarConstants.ACTION_SHUFFLE_QUEUE, Bundle.EMPTY))
            .build(),
    )

    private suspend fun currentTrackId(): Long? = withContext(Dispatchers.Main) {
        session?.player?.currentMediaItem?.mediaId
            ?.let { MediaId.parse(it) as? MediaId.Track }
            ?.id
    }

    private suspend fun toggleLoveOnCurrent() {
        val id = currentTrackId() ?: return
        val now = tracks.byId(id) ?: return
        val loved = !now.loved
        tracks.setLoved(id, loved, if (loved) System.currentTimeMillis() else null)
        withContext(Dispatchers.Main) {
            session?.setCustomLayout(customLayout(loved))
        }
    }

    /**
     * Reorders only what has not played yet. Shuffling the whole queue would
     * move the track currently playing, which stops the audio mid-song.
     */
    private suspend fun reshuffleQueueTail() {
        val player = session?.player ?: return
        val (ids, index) = withContext(Dispatchers.Main) {
            val items = (0 until player.mediaItemCount).mapNotNull { i ->
                (MediaId.parse(player.getMediaItemAt(i).mediaId) as? MediaId.Track)?.id
            }
            items to player.currentMediaItemIndex
        }
        if (ids.size < 2) return

        val reordered = shuffleEngine.reshuffleTail(ids, index)
        val items = queueBuilder.itemsForIds(reordered)
        withContext(Dispatchers.Main) {
            val position = player.currentPosition
            player.setMediaItems(items, index, position)
            player.prepare()
        }
    }
}
