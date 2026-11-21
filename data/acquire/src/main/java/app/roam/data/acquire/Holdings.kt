package app.roam.data.acquire

import app.roam.core.database.TrackDao
import app.roam.core.database.TrackListItem
import app.roam.core.model.Ids
import app.roam.core.model.TrackSort
import app.roam.data.catalog.LibraryQueries
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the library already holds, asked from the side of music it does not.
 *
 * Queries against Room, and below the screens for that reason: the search
 * page, the album page and the Adding list all ask the same questions, and
 * three copies of a matching rule are three ways for it to drift.
 *
 * Matched on normalised text, never on ids. A content-derived id only agrees
 * when the user's tags already agree with the catalogue exactly -- and whether
 * they do is often the very thing being found out.
 */
@Singleton
class Holdings @Inject constructor(
    private val tracks: TrackDao,
) {

    /** Normalised titles of the tracks held under this album title. */
    suspend fun heldTitles(albumTitle: String): Set<String> {
        val wanted = Ids.normalise(albumTitle)
        if (wanted.isEmpty()) return emptySet()
        return candidates(albumTitle, TrackSort.ALBUM)
            .filter { Ids.normalise(it.albumTitle) == wanted }
            .map { Ids.normalise(it.title) }
            .toSet()
    }

    /**
     * Finds a track that was added, once sync has catalogued it.
     *
     * Null until then: the file reaches Drive first and the row only appears
     * when the catalogue next crawls, so "added" and "in your library" are
     * minutes apart and the screen has to say so rather than opening nothing.
     */
    suspend fun resolveTrackId(request: DownloadRequest): Long? {
        val matches = candidates(request.title, TrackSort.ARTIST)
        val wantedTitle = Ids.normalise(request.title)
        if (wantedTitle.isEmpty()) return null
        val wantedAlbum = Ids.normalise(request.album)
        return matches.firstOrNull {
            Ids.normalise(it.title) == wantedTitle && Ids.normalise(it.albumTitle) == wantedAlbum
        }?.id
            // The album can differ if the tags disagreed with what was queued;
            // a title match alone is still better than refusing to open.
            ?: matches.firstOrNull { Ids.normalise(it.title) == wantedTitle }?.id
    }

    private suspend fun candidates(term: String, sort: TrackSort): List<TrackListItem> =
        runCatching { tracks.listItemsRaw(LibraryQueries.search(term, sort, CANDIDATES)) }
            .getOrDefault(emptyList())

    private companion object {
        /** LIKE narrows, normalised equality decides; this is plenty to decide among. */
        const val CANDIDATES = 60
    }
}
