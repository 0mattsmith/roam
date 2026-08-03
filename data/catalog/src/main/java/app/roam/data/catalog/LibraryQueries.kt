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
               aar.name AS albumArtistName, al.compilation AS compilation,
               al.id AS albumId, al.artworkId AS albumArtworkId, al.year AS albumYear,
               al.discTotal AS albumDiscTotal,
               t.trackNo AS trackNo, t.discNo AS discNo, t.durationMs AS durationMs,
               t.artworkId AS artworkId, t.loved AS loved
        FROM tracks t
        JOIN artists ar  ON ar.id  = t.artistId
        JOIN albums  al  ON al.id  = t.albumId
        -- The album's own artist, which is what album-major views group by.
        -- Without this join a compilation fragments across every guest artist.
        JOIN artists aar ON aar.id = al.artistId
    """

    fun tracks(sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS ORDER BY ${sort.orderBy}")

    fun lovedTracks(sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS WHERE t.loved = 1 ORDER BY ${sort.orderBy}")

    /**
     * Tracks BY this artist, plus tracks on albums CREDITED to them.
     *
     * The second clause is what makes "Various Artists" show its compilations:
     * no track on one carries that as its own artist, so filtering on t.artistId
     * alone would open an artist page with nothing in it.
     */
    fun tracksForArtist(artistId: Long, sort: TrackSort): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE $BY_ARTIST_OR_GROUPED ORDER BY ${sort.orderBy}",
            arrayOf(artistId, artistId, artistId, artistId),
        )

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

    /**
     * Top-level artists only. A grouped alias is deliberately absent -- its
     * records show up inside whoever it was folded into, and listing it twice
     * would defeat the point of grouping it.
     */
    fun artists(sort: ArtistSort): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            $ARTIST_COLUMNS
            WHERE ar.groupArtistId IS NULL
            ORDER BY ${sort.orderBy}
            """
        )

    fun albums(sort: AlbumSort): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            $ALBUM_COLUMNS
            ORDER BY ${sort.orderBy}
            """
        )

    // ---- the car -------------------------------------------------------------
    //
    // Android Auto asks for one page at a time rather than observing a
    // PagingSource, so these take an explicit window. LIMIT and OFFSET are
    // interpolated, never bound: they come from Media3's page arithmetic and
    // are Ints by the time they arrive.

    fun artistsPage(sort: ArtistSort, limit: Int, offset: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            $ARTIST_COLUMNS
            WHERE ar.groupArtistId IS NULL
            ORDER BY ${sort.orderBy}
            LIMIT $limit OFFSET $offset
            """
        )

    fun albumsPage(sort: AlbumSort, limit: Int, offset: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            """
            $ALBUM_COLUMNS
            ORDER BY ${sort.orderBy}
            LIMIT $limit OFFSET $offset
            """
        )

    /**
     * The artist's own albums plus those of anyone grouped into them, newest
     * first -- how a discography is usually read.
     */
    fun albumsForArtist(artistId: Long): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$ALBUM_COLUMNS WHERE (al.artistId = ? " +
                "OR al.artistId IN (SELECT id FROM artists WHERE groupArtistId = ?)) " +
                "ORDER BY al.year DESC, al.sortTitle",
            arrayOf(artistId, artistId),
        )

    fun recentAlbums(limit: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery("$ALBUM_COLUMNS ORDER BY al.addedAt DESC LIMIT $limit")

    fun recentTracks(limit: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE t.lastPlayedAt IS NOT NULL " +
                "ORDER BY t.lastPlayedAt DESC LIMIT $limit"
        )

    fun tracksPage(sort: TrackSort, limit: Int, offset: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS ORDER BY ${sort.orderBy} LIMIT $limit OFFSET $offset")

    fun lovedTracksPage(sort: TrackSort, limit: Int, offset: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE t.loved = 1 ORDER BY ${sort.orderBy} LIMIT $limit OFFSET $offset"
        )

    fun tracksForArtistLimited(artistId: Long, sort: TrackSort, limit: Int): SupportSQLiteQuery =
        SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE $BY_ARTIST_OR_GROUPED " +
                "ORDER BY ${sort.orderBy} LIMIT $limit",
            arrayOf(artistId, artistId, artistId, artistId),
        )

    /**
     * Rows for an explicit id list, for a queue the shuffle engine has already
     * ordered. The placeholders are generated from the list size, never from
     * anything caller-supplied -- the ids themselves are bound.
     */
    fun tracksForIds(ids: List<Long>): SupportSQLiteQuery {
        val placeholders = List(ids.size) { "?" }.joinToString(",")
        return SimpleSQLiteQuery(
            "$TRACK_COLUMNS WHERE t.id IN ($placeholders)",
            ids.toTypedArray(),
        )
    }

    /** Single row lookups, for resolving one browse node. */
    fun tracksForTrack(trackId: Long): SupportSQLiteQuery =
        SimpleSQLiteQuery("$TRACK_COLUMNS WHERE t.id = ?", arrayOf(trackId))

    fun albumsForId(albumId: Long): SupportSQLiteQuery =
        SimpleSQLiteQuery("$ALBUM_COLUMNS WHERE al.id = ?", arrayOf(albumId))

    /**
     * Tracks by an artist, on albums credited to them, or belonging to anyone
     * grouped into them. Four binds, all the same artist id.
     *
     * Kept on one line: it is interpolated into ordinary quoted strings, and a
     * multi-line fragment would break them.
     */
    private const val BY_ARTIST_OR_GROUPED =
        "(t.artistId = ? OR al.artistId = ? " +
            "OR t.artistId IN (SELECT id FROM artists WHERE groupArtistId = ?) " +
            "OR al.artistId IN (SELECT id FROM artists WHERE groupArtistId = ?))"

    private const val ARTIST_COLUMNS = """
        SELECT ar.id AS id, ar.name AS name,
               ar.albumCount AS albumCount, ar.trackCount AS trackCount,
               ar.artworkId AS artworkId, ar.logoArtworkId AS logoArtworkId,
               ar.preferLogo AS preferLogo, ar.sortAs AS sortAs,
               ar.groupArtistId AS groupArtistId, ar.bannerArtworkId AS bannerArtworkId
        FROM artists ar
    """

    private const val ALBUM_COLUMNS = """
        SELECT al.id AS id, al.title AS title, ar.name AS artistName,
               al.year AS year, al.trackCount AS trackCount,
               al.artworkId AS artworkId
        FROM albums al
        JOIN artists ar ON ar.id = al.artistId
    """
}
