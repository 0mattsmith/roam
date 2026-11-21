package app.roam.data.acquire

import app.roam.core.database.ArtistDao
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
    private val artists: ArtistDao,
) {

    /**
     * A song is yours when a track has the same normalised title AND the same
     * normalised artist or album. Title alone is not enough: plenty of albums
     * open with a track called "Intro". (resolveTrackId can fall back to title
     * alone because it already knows which file it is looking for. This
     * cannot.)
     *
     * An empty normalised string matches nothing. Ids.normalise keeps only
     * a-z and 0-9, so a title written entirely in another script normalises
     * to nothing, and any two such titles would otherwise match each other.
     *
     * The track's id rather than a yes or no, because a song found to be
     * yours is one the page can play.
     */
    suspend fun heldSong(title: String, artist: String, album: String?): Long? {
        val wantedTitle = Ids.normalise(title)
        if (wantedTitle.isEmpty()) return null
        val wantedArtist = Ids.normalise(artist)
        val wantedAlbum = Ids.normalise(album.orEmpty())
        if (wantedArtist.isEmpty() && wantedAlbum.isEmpty()) return null
        return candidates(title, TrackSort.ARTIST).firstOrNull { row ->
            Ids.normalise(row.title) == wantedTitle && (
                (wantedArtist.isNotEmpty() && (
                    Ids.normalise(row.artistName) == wantedArtist ||
                        Ids.normalise(row.albumArtistName) == wantedArtist
                    )) ||
                    (wantedAlbum.isNotEmpty() && Ids.normalise(row.albumTitle) == wantedAlbum)
                )
        }?.id
    }

    /**
     * How many tracks the library holds of this album, matched on normalised
     * title and artist. Against a release's track count, this is what decides
     * whether the release is yours, partly yours ("4 of 12 in your library"),
     * or not yours at all.
     */
    suspend fun heldCount(albumTitle: String, artist: String): Int {
        val wantedTitle = Ids.normalise(albumTitle)
        if (wantedTitle.isEmpty()) return 0
        val wantedArtist = Ids.normalise(artist)
        return candidates(albumTitle, TrackSort.ALBUM, ALBUM_CANDIDATES).count { row ->
            Ids.normalise(row.albumTitle) == wantedTitle && (
                wantedArtist.isEmpty() ||
                    Ids.normalise(row.albumArtistName) == wantedArtist ||
                    Ids.normalise(row.artistName) == wantedArtist
                )
        }
    }

    /** An artist the library already lists, by normalised name. */
    suspend fun hasArtist(name: String): Boolean {
        val wanted = Ids.normalise(name)
        if (wanted.isEmpty()) return false
        return runCatching { artists.listItemsRaw(LibraryQueries.searchArtists(name, CANDIDATES)) }
            .getOrDefault(emptyList())
            .any { Ids.normalise(it.name) == wanted }
    }

    /**
     * The tracks held under this album title, by normalised title, so a
     * tracklist from a catalogue can say which of its rows are yours -- and
     * play them.
     */
    suspend fun heldTracks(albumTitle: String): Map<String, Long> {
        val wanted = Ids.normalise(albumTitle)
        if (wanted.isEmpty()) return emptyMap()
        return candidates(albumTitle, TrackSort.ALBUM, ALBUM_CANDIDATES)
            .filter { Ids.normalise(it.albumTitle) == wanted }
            .associate { Ids.normalise(it.title) to it.id }
            .filterKeys { it.isNotEmpty() }
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

    private suspend fun candidates(
        term: String,
        sort: TrackSort,
        limit: Int = CANDIDATES,
    ): List<TrackListItem> =
        runCatching { tracks.listItemsRaw(LibraryQueries.search(term, sort, limit)) }
            .getOrDefault(emptyList())

    private companion object {
        /** LIKE narrows, normalised equality decides; this is plenty to decide among. */
        const val CANDIDATES = 60

        /** Counting an album's tracks: a long compilation must not be cut off. */
        const val ALBUM_CANDIDATES = 400
    }
}
