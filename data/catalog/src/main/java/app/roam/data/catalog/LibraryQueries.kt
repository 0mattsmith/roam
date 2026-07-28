package app.roam.data.catalog

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.roam.core.model.AlbumSort
import app.roam.core.model.ArtistSort
import app.roam.core.model.TrackSort

/**
 * Builds the raw queries behind the library's sortable lists.
 *
 * Only ORDER BY varies, and only ever from an enum constant. Anything
 * caller-supplied -- an artist id, an album id -- goes through a bind argument
 * rather than string concatenation.
 */
object LibraryQueries {

    private const val TRACK_COLUMNS = """
        SELECT t.id AS id, t.remoteId AS remoteId, t.title AS title,
               ar.name AS artistName, al.title AS albumTitle,
               t.trackNo AS trackNo, t.durationMs AS durationMs,
               t.artworkId AS artworkId, t.loved AS loved
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
    """

    fun tracks(sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS ORDER BY ${sort.orderBy}")

    fun lovedTracks(sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS WHERE t.loved = 1 ORDER BY ${sort.orderBy}")

    fun tracksForArtist(artistId: Long, sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS WHERE t.artistId = ? ORDER BY ${sort.orderBy}", arrayOf(artistId))

    fun tracksForAlbum(albumId: Long): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE t.albumId = ? ORDER BY t.discNo, t.trackNo, t.title",
            arrayOf(albumId),
        )

    /** Queue building: same order as the visible list, capped. */
    fun tracksLimited(sort: TrackSort, limit: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS ORDER BY ${sort.orderBy} LIMIT $limit")

    fun lovedTracksLimited(sort: TrackSort, limit: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS WHERE t.loved = 1 ORDER BY ${sort.orderBy} LIMIT $limit")

    fun artists(sort: ArtistSort): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            SELECT ar.id AS id, ar.name AS name,
                   ar.albumCount AS albumCount, ar.trackCount AS trackCount,
                   ar.artworkId AS artworkId
            FROM artists ar
            ORDER BY ${sort.orderBy}
            """
        )

    fun albums(sort: AlbumSort): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            SELECT al.id AS id, al.title AS title, ar.name AS artistName,
                   al.year AS year, al.trackCount AS trackCount,
                   al.artworkId AS artworkId
            FROM albums al
            JOIN artists ar ON ar.id = al.artistId
            ORDER BY ${sort.orderBy}
            """
        )
}
