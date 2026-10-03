package app.roam.core.database

import androidx.room.Dao
import androidx.room.Query
import app.roam.core.model.TagState

/**
 * The work queue -- the jobs a big screen is for.
 *
 * Every query here answers a question of the form "what still needs doing",
 * which is a different shape from the rest of the DAOs: those browse a library
 * someone is looking at, these enumerate what is wrong with it. The web
 * interface opens on this rather than on an artist list, because scrolling
 * Artists is not a reason to go and find a laptop and "everything with no year"
 * is.
 *
 * BLOCKING, and neither suspend nor Flow -- unlike every other DAO here. The
 * only caller is the web server, which answers each request on one of its own
 * threads: a Flow would hold a subscription open for a response already
 * written, and a suspend function would need runBlocking to get back to a
 * thread that is already blocking. Room refuses a blocking query on the main
 * thread, so this cannot quietly end up on the wrong one. The phone's own
 * counters stay on Flow, where a live number is the point.
 *
 * `hidden = 0 AND missing = 0` on everything except [missingCount] itself: a
 * track removed from the library is not work, and a file that has vanished is
 * its OWN job rather than an entry in all the others -- otherwise one missing
 * album would show up in four lists at once and none of them would be
 * actionable.
 */
@Dao
interface QueueDao {

    /**
     * Everything a person can actually see, for the header.
     *
     * Worth printing beside the counts: "41 of 2,132" is a different feeling
     * from "41", and the difference is the only thing that says whether the
     * queue is a morning's work or a rainy afternoon.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE hidden = 0 AND missing = 0")
    fun libraryCount(): Int

    /**
     * Nothing has vouched for these values: no hand edit, no `album.json`, and
     * no readable tags.
     *
     * The same rule as `DocTrackRow.isGuess`, deliberately phrased in SQL
     * rather than filtered in Kotlin -- the count is wanted without the rows.
     * Note `tagState != 'OK'` and not `= 'FAILED'`: PENDING means the tag pass
     * has not reached it and PATH_INFERRED means the title came from a
     * filename, and neither of those is a claim about anything.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE hidden = 0 AND missing = 0
          AND userEdited = 0 AND fromDoc = 0 AND tagState != 'OK'
        """
    )
    fun needsLookCount(): Int

    @Query(
        """
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.artworkId AS artworkId, t.year AS year,
               t.genre AS genre, t.tagState AS tagState, t.fileName AS fileName
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.hidden = 0 AND t.missing = 0
          AND t.userEdited = 0 AND t.fromDoc = 0 AND t.tagState != 'OK'
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
        LIMIT :limit OFFSET :offset
        """
    )
    fun needsLook(limit: Int, offset: Int): List<QueueTrackRow>

    /**
     * No year, which is what makes the decade shuffles incomplete.
     *
     * COALESCE, because an original year is still a year -- a remaster tagged
     * only with its reissue date is not missing one, and "Play 80s" reads the
     * original first for exactly that reason.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE hidden = 0 AND missing = 0 AND COALESCE(originalYear, year) IS NULL
        """
    )
    fun noYearCount(): Int

    @Query(
        """
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.artworkId AS artworkId, t.year AS year,
               t.genre AS genre, t.tagState AS tagState, t.fileName AS fileName
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.hidden = 0 AND t.missing = 0
          AND COALESCE(t.originalYear, t.year) IS NULL
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
        LIMIT :limit OFFSET :offset
        """
    )
    fun noYear(limit: Int, offset: Int): List<QueueTrackRow>

    /** Blank counts as absent. An empty genre tag is how most of these arrive. */
    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE hidden = 0 AND missing = 0 AND (genre IS NULL OR TRIM(genre) = '')
        """
    )
    fun noGenreCount(): Int

    @Query(
        """
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.artworkId AS artworkId, t.year AS year,
               t.genre AS genre, t.tagState AS tagState, t.fileName AS fileName
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.hidden = 0 AND t.missing = 0
          AND (t.genre IS NULL OR TRIM(t.genre) = '')
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
        LIMIT :limit OFFSET :offset
        """
    )
    fun noGenre(limit: Int, offset: Int): List<QueueTrackRow>

    /**
     * A file the source stopped returning.
     *
     * `missing = 1` and nothing else: the row is deliberately still here with
     * its loved flag and play count (invariant 3c), and this is the only list
     * that admits it exists.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE missing = 1 AND hidden = 0")
    fun missingCount(): Int

    @Query(
        """
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.artworkId AS artworkId, t.year AS year,
               t.genre AS genre, t.tagState AS tagState, t.fileName AS fileName
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.missing = 1 AND t.hidden = 0
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
        LIMIT :limit OFFSET :offset
        """
    )
    fun missing(limit: Int, offset: Int): List<QueueTrackRow>

    /**
     * Hand-edited before the tags were ever read, so the tag pass will never
     * touch them.
     *
     * Separate from "needs a look" on purpose, and the two cannot overlap:
     * `userEdited = 1` excludes it from that list. Roam must not repair these
     * silently -- the flag records that an edit happened, never why.
     */
    @Query(
        """
        SELECT COUNT(*) FROM tracks
        WHERE hidden = 0 AND missing = 0 AND userEdited = 1 AND tagState != 'OK'
        """
    )
    fun frozenCount(): Int

    @Query(
        """
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.artworkId AS artworkId, t.year AS year,
               t.genre AS genre, t.tagState AS tagState, t.fileName AS fileName
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.hidden = 0 AND t.missing = 0
          AND t.userEdited = 1 AND t.tagState != 'OK'
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
        LIMIT :limit OFFSET :offset
        """
    )
    fun frozen(limit: Int, offset: Int): List<QueueTrackRow>

    /**
     * Albums showing nothing at all.
     *
     * `trackCount > 0` because a rollup of zero means every track in it is
     * hidden or missing, and an album nobody can see does not need a cover.
     * The folder comes from any one of its tracks -- they share a prefix, which
     * is the whole basis of the cover and document passes.
     */
    @Query(
        """
        SELECT COUNT(*) FROM albums WHERE artworkId IS NULL AND trackCount > 0
        """
    )
    fun noCoverCount(): Int

    @Query(
        """
        SELECT al.id AS id, al.title AS title, ar.name AS artistName,
               al.trackCount AS trackCount, al.year AS year,
               (SELECT t.folderPath FROM tracks t
                 WHERE t.albumId = al.id AND t.hidden = 0 AND t.missing = 0
                 LIMIT 1) AS folderPath
        FROM albums al
        JOIN artists ar ON ar.id = al.artistId
        WHERE al.artworkId IS NULL AND al.trackCount > 0
        ORDER BY ar.sortName, al.sortTitle
        LIMIT :limit OFFSET :offset
        """
    )
    fun noCover(limit: Int, offset: Int): List<QueueAlbumRow>
}

/** Enough of a track to list it as work, and to say what is wrong with it. */
data class QueueTrackRow(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val artworkId: String?,
    val year: Int?,
    val genre: String?,
    val tagState: TagState,
    val fileName: String?,
)

/** An album as a unit of work, with the folder its cover would go in. */
data class QueueAlbumRow(
    val id: Long,
    val title: String,
    val artistName: String,
    val trackCount: Int,
    val year: Int?,
    val folderPath: String?,
)
