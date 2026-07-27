package app.roam.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The car surface.
 *
 * HARD RULE: this service reads only from Room and the ArtworkStore. It never
 * blocks on the network to build a browse response. With the catalogue synced,
 * browsing works with no signal at all -- only the audio stream needs data.
 */
@AndroidEntryPoint
class RoamLibraryService : MediaLibraryService() {

    @Inject lateinit var browseTree: BrowseTree
    @Inject lateinit var shuffleEngine: ShuffleEngine

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        // TODO(phase2): build ExoPlayer with CacheDataSource.Factory, then
        //   MediaLibrarySession.Builder(this, player, Callback())
        //     .setSessionActivity(pendingIntentToMainActivity())
        //     .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

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

        // TODO(phase2): onSearch / onGetSearchResult over the FTS index.
        //   Voice is the ONLY search available in the car -- there is no
        //   keyboard while driving.

        // TODO(phase2): onCustomCommand for ACTION_LOVE / ACTION_SHUFFLE_QUEUE.
        //   Love writes to Room; every surface updates because the row changed,
        //   not because the car called back.
    }
}

/** Placeholder so the module compiles before phase 2. */
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
