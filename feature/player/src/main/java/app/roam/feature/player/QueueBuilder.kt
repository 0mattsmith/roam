package app.roam.feature.player

import android.content.Context
import androidx.media3.common.MediaItem
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.TrackSort
import app.roam.data.catalog.LibraryQueries
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a browse-tree tap into a queue.
 *
 * The car hands back the MediaItem it was given, which carries a media id and
 * no URI, so something has to expand "album/123" into the album's tracks with
 * playable Drive URIs. Without this the tree renders correctly and every tap
 * does nothing at all.
 */
@Singleton
class QueueBuilder @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracks: TrackDao,
    private val shuffleEngine: ShuffleEngine,
) {

    /** The queue a tap produces, and where in it playback should begin. */
    data class Queue(val items: List<MediaItem>, val startIndex: Int)

    suspend fun resolve(id: MediaId): List<MediaItem> = resolveWithStart(id).items

    suspend fun resolveWithStart(id: MediaId): Queue = when (id) {
        // Tapping a track inside an album queues the whole album and starts
        // there. Queueing the single track instead would leave playback
        // stopping dead at the end of one song.
        is MediaId.Track -> {
            val track = tracks.listItemsRaw(LibraryQueries.tracksForTrack(id.id)).firstOrNull()
            if (track == null) Queue(emptyList(), 0)
            else {
                val album = tracks.listItemsRaw(LibraryQueries.tracksForAlbum(albumIdOf(id.id)))
                val index = album.indexOfFirst { it.id == track.id }
                if (index >= 0) Queue(album.toItems(), index)
                else Queue(listOf(track).toItems(), 0)
            }
        }

        is MediaId.Album ->
            Queue(tracks.listItemsRaw(LibraryQueries.tracksForAlbum(id.id)).toItems(), 0)

        is MediaId.Artist ->
            Queue(
                tracks.listItemsRaw(
                    LibraryQueries.tracksForArtistLimited(id.id, TrackSort.ALBUM, QUEUE_LIMIT)
                ).toItems(),
                0,
            )

        MediaId.Loved ->
            Queue(
                tracks.listItemsRaw(
                    LibraryQueries.lovedTracksPage(TrackSort.ARTIST, QUEUE_LIMIT, 0)
                ).toItems(),
                0,
            )

        MediaId.ShuffleAll -> Queue(shuffled(tracks.shuffleCandidates()), 0)
        MediaId.ShuffleLoved -> Queue(shuffled(tracks.shuffleCandidatesLoved()), 0)
        is MediaId.ShuffleArtist ->
            Queue(shuffled(tracks.shuffleCandidatesForArtist(id.id)), 0)
        is MediaId.ShuffleAlbum ->
            Queue(tracks.listItemsRaw(LibraryQueries.tracksForAlbum(id.id)).toItems().shuffled(), 0)

        // Browsable containers are not playable; the car should never ask.
        MediaId.Root, MediaId.Home, MediaId.Artists, MediaId.Albums,
        MediaId.RecentlyAdded, MediaId.RecentlyPlayed -> Queue(emptyList(), 0)
    }

    /**
     * Weighted shuffle picks ids, then one query fetches the rows. Selecting
     * four columns for the whole library and joining afterwards keeps this off
     * the "never load the full library into memory" list.
     */
    private suspend fun shuffled(candidates: List<app.roam.core.database.ShuffleRow>): List<MediaItem> {
        val ids = shuffleEngine.shuffle(candidates).take(QUEUE_LIMIT)
        if (ids.isEmpty()) return emptyList()

        // Re-apply the shuffled order: SQL returns rows in whatever order it
        // likes, which would quietly undo the weighting.
        return itemsForIds(ids)
    }

    /** Rows for an explicit id order, preserving that order. */
    suspend fun itemsForIds(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val byId = tracks.listItemsRaw(LibraryQueries.tracksForIds(ids)).associateBy { it.id }
        return ids.mapNotNull { byId[it] }.toItems()
    }

    private suspend fun albumIdOf(trackId: Long): Long = tracks.byId(trackId)?.albumId ?: 0L

    private fun List<TrackListItem>.toItems(): List<MediaItem> = map { it.toMediaItem(ctx) }

    private companion object {
        /**
         * A session queue crosses Binder, and a few thousand items would blow
         * the 1MB limit. Phase 3 windows this properly.
         */
        const val QUEUE_LIMIT = 500
    }
}
