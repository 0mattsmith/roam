package app.roam.feature.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaLibraryService.LibraryParams
import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumListItem
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistListItem
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.AlbumSort
import app.roam.core.model.ArtistSort
import app.roam.core.model.TrackSort
import app.roam.data.catalog.LibraryQueries
import app.roam.data.catalog.artwork.ArtworkProvider
import com.google.common.collect.ImmutableList
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the car sees.
 *
 * INVARIANT: every method here reads Room and the ArtworkStore and nothing
 * else. No network, ever. With the catalogue synced, browsing works in a tunnel
 * -- only the audio stream needs a signal, and that is the one place a delay is
 * forgivable.
 *
 * Artwork crosses as a content:// URI rather than a bitmap. AAOS does not
 * support setIconBitmap at all, and a browse page full of bitmaps exceeds the
 * 1MB Binder limit, which shows up as the car UI simply hanging.
 */
@Singleton
class BrowseTree @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
) {

    /**
     * How many tabs the head unit will render. Advertised in the root hints and
     * genuinely varies -- some units show three, some five. Hardcoding four
     * silently drops a tab on the ones that show fewer.
     */
    var rootChildrenLimit: Int = CarConstants.DEFAULT_ROOT_TABS

    /**
     * Whether the unit will render a playable item at the root. Nothing in the
     * hints means nothing was advertised rather than "no", and Android Auto
     * does accept one, so the default is yes; only an explicit browsable-only
     * answer takes the shuffle row away.
     */
    var rootPlayableAllowed: Boolean = true

    fun rootItem(): MediaItem = browsable(MediaId.Root, title = "Roam")

    /**
     * Root children after the shuffle row, in the order they earn their place.
     *
     * This list is taken FROM THE FRONT, never the back. When the unit
     * advertises a smaller limit -- which is what happens in the narrow panel
     * beside maps -- whatever sits last falls off the end. Home is last because
     * its headline row is "Shuffle everything", and that has been promoted to
     * the root itself; what is left inside it is the two recency lists.
     */
    private val rootTabs: List<Pair<MediaId, String>> = listOf(
        MediaId.Artists to "Artists",
        MediaId.Albums to "Albums",
        MediaId.Loved to "Loved",
        MediaId.Home to "Home",
    )

    suspend fun children(parent: MediaId, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val size = pageSize.coerceIn(1, CarConstants.PAGE_SIZE)
        val offset = page * size

        val items: List<MediaItem> = when (parent) {
            // Shuffle first, so it survives any limit down to one. One tap and
            // the library is playing, which is the only thing worth doing on a
            // panel this small while the car is moving.
            MediaId.Root -> buildList {
                if (rootPlayableAllowed) add(playableAction(MediaId.ShuffleAll, SHUFFLE_EVERYTHING))
                rootTabs.forEach { (id, title) -> add(browsable(id, title)) }
            }.take(rootChildrenLimit.coerceAtLeast(1))

            MediaId.Home -> homeChildren()

            MediaId.Artists ->
                artists.listItemsRaw(LibraryQueries.artistsPage(ArtistSort.NAME, size, offset))
                    .map { it.toMediaItem() }

            MediaId.Albums ->
                albums.listItemsRaw(LibraryQueries.albumsPage(AlbumSort.ARTIST, size, offset))
                    .map { it.toMediaItem() }

            MediaId.Loved -> buildList {
                // The action row belongs on the first page only, or it repeats
                // every time the car pages further down the list.
                if (page == 0) add(playableAction(MediaId.ShuffleLoved, "Shuffle loved"))
                addAll(
                    tracks.listItemsRaw(
                        LibraryQueries.lovedTracksPage(TrackSort.ARTIST, size, offset)
                    ).map { it.toMediaItem() }
                )
            }

            MediaId.RecentlyAdded ->
                albums.listItemsRaw(LibraryQueries.recentAlbums(size)).map { it.toMediaItem() }

            MediaId.RecentlyPlayed ->
                tracks.listItemsRaw(LibraryQueries.recentTracks(size)).map { it.toMediaItem() }

            is MediaId.Artist -> buildList {
                if (page == 0) add(playableAction(MediaId.ShuffleArtist(parent.id), "Shuffle this artist"))
                addAll(
                    albums.listItemsRaw(LibraryQueries.albumsForArtist(parent.id))
                        .map { it.toMediaItem() }
                )
            }

            // No per-row artwork here: the car already shows the album's cover
            // as the node you opened, so repeating it down every row is visual
            // noise and needlessly enlarges the browse payload.
            is MediaId.Album ->
                tracks.listItemsRaw(LibraryQueries.tracksForAlbum(parent.id)).map { row ->
                    // The car has no way to draw a heading, but it will group
                    // consecutive items sharing a group title -- which is what
                    // this extra is for and why the constant already existed.
                    row.toMediaItem(
                        withArtwork = false,
                        groupTitle = if (row.albumDiscTotal > 1) "Disc ${row.discNo ?: 1}" else null,
                    )
                }

            // Leaves and action rows have no children.
            is MediaId.Track, MediaId.ShuffleAll, MediaId.ShuffleLoved,
            is MediaId.ShuffleArtist, is MediaId.ShuffleAlbum -> emptyList()
        }
        return ImmutableList.copyOf(items)
    }

    /** A single node, for onGetItem. */
    suspend fun item(id: MediaId): MediaItem? = when (id) {
        MediaId.Root -> rootItem()
        // A root child now, so the browser will ask for it by id.
        MediaId.ShuffleAll -> playableAction(MediaId.ShuffleAll, SHUFFLE_EVERYTHING)
        is MediaId.Track -> tracks.listItemsRaw(LibraryQueries.tracksForTrack(id.id))
            .firstOrNull()?.toMediaItem()
        is MediaId.Album -> albums.listItemsRaw(LibraryQueries.albumsForId(id.id))
            .firstOrNull()?.toMediaItem()
        else -> rootTabs.firstOrNull { it.first == id }?.let { browsable(it.first, it.second) }
    }

    /**
     * Content style hints so the car renders artists and albums as a grid of
     * artwork rather than a wall of text. Attached to the parent's params,
     * which is how the browser learns the style of its children.
     */
    fun paramsFor(parent: MediaId, requested: LibraryParams?): LibraryParams {
        val browsableStyle = when (parent) {
            MediaId.Root, MediaId.Home -> CarConstants.STYLE_LIST
            MediaId.Artists, MediaId.Albums, is MediaId.Artist -> CarConstants.STYLE_GRID
            else -> CarConstants.STYLE_LIST
        }
        val extras = android.os.Bundle().apply {
            requested?.extras?.let { putAll(it) }
            putInt(CarConstants.EXTRA_CONTENT_STYLE_BROWSABLE, browsableStyle)
            putInt(CarConstants.EXTRA_CONTENT_STYLE_PLAYABLE, CarConstants.STYLE_LIST)
        }
        return LibraryParams.Builder().setExtras(extras).build()
    }

    // Kept here as well as at the root, deliberately. On a unit that refuses a
    // playable root child this is the only place it appears, and Home is
    // usually off the end of the tab list anyway, so the two rarely both show.
    private fun homeChildren(): List<MediaItem> = listOf(
        playableAction(MediaId.ShuffleAll, SHUFFLE_EVERYTHING),
        browsable(MediaId.RecentlyAdded, "Recently added"),
        browsable(MediaId.RecentlyPlayed, "Recently played"),
    )

    // ---- item builders -------------------------------------------------------

    private fun browsable(id: MediaId, title: String, artworkUri: Uri? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.raw)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtworkUri(artworkUri)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build()
            )
            .build()

    /**
     * Playable but not browsable: tapping it starts a queue rather than opening
     * a list. The car draws these with a play affordance.
     */
    private fun playableAction(id: MediaId, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.raw)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
                    .build()
            )
            .build()

    private fun ArtistListItem.toMediaItem(): MediaItem {
        // The car honours the same per-artist choice as the phone, falling back
        // to whichever image exists.
        val chosen = if (preferLogo) logoArtworkId ?: artworkId else artworkId ?: logoArtworkId
        return MediaItem.Builder()
            .setMediaId(MediaId.Artist(id).raw)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setSubtitle("$albumCount albums")
                    .setArtworkUri(chosen?.let { ArtworkProvider.uri(ctx, it, size = 320) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
                    .build()
            )
            .build()
    }

    private fun AlbumListItem.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.Album(id).raw)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    // Year in the title, same as the phone. The car gives a
                    // browse row one line, so a separate field would be dropped.
                    .setTitle(year?.takeIf { it > 0 }?.let { "$title ($it)" } ?: title)
                    .setSubtitle(artistName)
                    .setArtist(artistName)
                    .setArtworkUri(artworkId?.let { ArtworkProvider.uri(ctx, it, size = 320) })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
                    .build()
            )
            .build()

    private fun TrackListItem.toMediaItem(
        withArtwork: Boolean = true,
        groupTitle: String? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.Track(id).raw)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(artistName)
                    .setArtist(artistName)
                    .setAlbumTitle(albumTitle)
                    .setTrackNumber(trackNo ?: 0)
                    .setArtworkUri(
                        artworkId
                            ?.takeIf { withArtwork }
                            ?.let { ArtworkProvider.uri(ctx, it, size = 320) }
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setExtras(
                        groupTitle?.let {
                            android.os.Bundle().apply {
                                putString(CarConstants.EXTRA_CONTENT_STYLE_GROUP_TITLE, it)
                            }
                        }
                    )
                    .build()
            )
            .build()

    private companion object {
        /** One label, so the root row and the Home row cannot drift apart. */
        const val SHUFFLE_EVERYTHING = "Shuffle everything"
    }
}
