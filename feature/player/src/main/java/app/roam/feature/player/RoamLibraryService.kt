package app.roam.feature.player

import android.content.Intent
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
import androidx.media3.session.MediaSession
import app.roam.core.model.SourceType
import app.roam.data.source.SourceProvider
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
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
    @Inject lateinit var shuffleEngine: ShuffleEngine
    @Inject lateinit var cache: SimpleCache
    @Inject lateinit var providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>

    private var session: MediaLibrarySession? = null

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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(browseTree.children(MediaId.parse(parentId), page, pageSize), params)
            )

        // TODO(phase2): onSearch / onGetSearchResult over the FTS index. Voice
        //   is the only search available in the car -- there is no keyboard
        //   while driving.
        // TODO(phase2): onCustomCommand for ACTION_LOVE / ACTION_SHUFFLE_QUEUE.
    }
}

/** Placeholder until phase 2 builds the real tree. */
class BrowseTree @Inject constructor() {
    var rootChildrenLimit: Int = CarConstants.DEFAULT_ROOT_TABS

    fun rootItem(): MediaItem = MediaItem.Builder()
        .setMediaId(MediaId.Root.raw)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    fun children(parent: MediaId, page: Int, pageSize: Int): ImmutableList<MediaItem> =
        ImmutableList.of()
}
