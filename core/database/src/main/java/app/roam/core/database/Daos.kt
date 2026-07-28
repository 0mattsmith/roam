package app.roam.core.database

import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.RawQuery
import androidx.room.Query
import androidx.room.Upsert
import app.roam.core.model.TagState
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Upsert suspend fun upsert(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNo, trackNo, title")
    fun byAlbum(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artistId = :artistId ORDER BY albumId, discNo, trackNo")
    suspend fun byArtist(artistId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE loved = 1 ORDER BY lovedAt DESC")
    fun loved(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY title")
    fun pagedAll(): PagingSource<Int, TrackEntity>

    /**
     * Everything a list row needs, joined once in SQL. Fetching TrackEntity and
     * then looking up each artist and album separately would be a query per
     * visible row.
     */
    @Query("""
        SELECT t.id AS id, t.remoteId AS remoteId, t.title AS title,
               ar.name AS artistName, al.title AS albumTitle,
               t.trackNo AS trackNo, t.durationMs AS durationMs,
               t.artworkId AS artworkId, t.loved AS loved
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo, t.title
    """)
    fun pagedListItems(): PagingSource<Int, TrackListItem>

    /**
     * Sorted and optionally filtered listing.
     *
     * RawQuery because Room cannot parameterise ORDER BY, and writing one
     * @Query per sort order across three tabs is a dozen near-identical
     * queries. The ORDER BY text comes from the TrackSort enum, never from
     * anything a user can type.
     */
    @RawQuery(observedEntities = [TrackEntity::class, AlbumEntity::class, ArtistEntity::class])
    fun pagedListItemsRaw(query: SupportSQLiteQuery): PagingSource<Int, TrackListItem>

    @RawQuery(observedEntities = [TrackEntity::class, AlbumEntity::class, ArtistEntity::class])
    suspend fun listItemsRaw(query: SupportSQLiteQuery): List<TrackListItem>

    /** Queue building: same order as the list, but ids and remote ids only. */
    @Query("""
        SELECT t.id AS id, t.remoteId AS remoteId, t.title AS title,
               ar.name AS artistName, al.title AS albumTitle,
               t.trackNo AS trackNo, t.durationMs AS durationMs,
               t.artworkId AS artworkId, t.loved AS loved
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo, t.title
        LIMIT :limit
    """)
    suspend fun listItems(limit: Int): List<TrackListItem>

    /**
     * Everything the shuffle engine needs, and nothing else. Loading 10k full
     * rows to build a queue is the easiest way to make this app feel slow.
     */
    @Query("SELECT id, loved, skipCount, lastPlayedAt FROM tracks")
    suspend fun shuffleCandidates(): List<ShuffleRow>

    @Query("SELECT id, loved, skipCount, lastPlayedAt FROM tracks WHERE artistId = :artistId")
    suspend fun shuffleCandidatesForArtist(artistId: Long): List<ShuffleRow>

    @Query("SELECT id, loved, skipCount, lastPlayedAt FROM tracks WHERE loved = 1")
    suspend fun shuffleCandidatesLoved(): List<ShuffleRow>

    // ---- user state ----
    @Query("UPDATE tracks SET loved = :loved, lovedAt = :at WHERE id = :id")
    suspend fun setLoved(id: Long, loved: Boolean, at: Long?)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("UPDATE tracks SET skipCount = skipCount + 1 WHERE id = :id")
    suspend fun markSkipped(id: Long)

    // ---- tag pass ----

    /** Tracks still carrying path-derived metadata, oldest first. */
    @Query("""
        SELECT t.id AS id, t.remoteId AS remoteId, t.albumId AS albumId,
               t.title AS name
        FROM tracks t
        WHERE t.sourceId = :sourceId AND t.tagState != 'OK' AND t.tagState != 'FAILED'
        ORDER BY t.addedAt
        LIMIT :limit
    """)
    suspend fun pendingTags(sourceId: String, limit: Int): List<PendingTagRow>

    /**
     * Writes only tag-derived columns. loved, playCount, skipCount and
     * lastPlayedAt are the user's and must never be touched here.
     * COALESCE keeps the path-inferred value when a tag is absent.
     */
    @Query("""
        UPDATE tracks SET
          title = COALESCE(:title, title),
          year = COALESCE(:year, year),
          genre = COALESCE(:genre, genre),
          trackNo = COALESCE(:trackNo, trackNo),
          trackTotal = COALESCE(:trackTotal, trackTotal),
          discNo = COALESCE(:discNo, discNo),
          discTotal = COALESCE(:discTotal, discTotal),
          artworkId = COALESCE(:artworkId, artworkId),
          tagState = :tagState
        WHERE id = :id
    """)
    suspend fun updateTags(
        id: Long,
        title: String?,
        year: Int?,
        genre: String?,
        trackNo: Int?,
        trackTotal: Int?,
        discNo: Int?,
        discTotal: Int?,
        artworkId: String?,
        tagState: TagState,
    )

    /** Stops a file with no tags being re-read on every pass. */
    @Query("UPDATE tracks SET tagState = 'FAILED' WHERE id IN (:ids) AND tagState != 'OK'")
    suspend fun markTagsAttempted(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM tracks WHERE tagState != 'OK' AND tagState != 'FAILED'")
    fun pendingTagCount(): Flow<Int>

    // ---- sync ----
    @Query("SELECT id, remoteId, remoteRevision FROM tracks WHERE sourceId = :sourceId")
    suspend fun revisions(sourceId: String): List<RevisionRow>

    @Query("DELETE FROM tracks WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteRemote(sourceId: String, remoteIds: List<String>)

    /** Disconnecting a source removes its catalogue entirely. */
    @Query("DELETE FROM tracks WHERE sourceId = :sourceId")
    suspend fun deleteAllForSource(sourceId: String)

    @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Flow<Int>
}

data class PendingTagRow(
    val id: Long,
    val remoteId: String,
    val albumId: Long,
    /** The file's title, used only to sniff the extension for parser choice. */
    val name: String,
)

data class TrackListItem(
    val id: Long,
    val remoteId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val trackNo: Int?,
    val durationMs: Long,
    val artworkId: String?,
    val loved: Boolean,
)

data class ShuffleRow(val id: Long, val loved: Boolean, val skipCount: Int, val lastPlayedAt: Long?)
data class RevisionRow(val id: Long, val remoteId: String, val remoteRevision: String?)

data class ArtistPhotoRow(val id: Long, val name: String)

data class ArtistListItem(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val artworkId: String?,
)

data class AlbumListItem(
    val id: Long,
    val title: String,
    val artistName: String,
    val year: Int?,
    val trackCount: Int,
    val artworkId: String?,
)

@Dao
interface AlbumDao {
    @Upsert suspend fun upsert(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, sortTitle")
    fun byArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY sortTitle")
    fun pagedAll(): PagingSource<Int, AlbumEntity>

    @RawQuery(observedEntities = [AlbumEntity::class, ArtistEntity::class])
    fun pagedListItemsRaw(query: SupportSQLiteQuery): PagingSource<Int, AlbumListItem>

    @Query("SELECT * FROM albums ORDER BY addedAt DESC LIMIT :limit")
    suspend fun recentlyAdded(limit: Int): List<AlbumEntity>

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks)")
    suspend fun pruneOrphans()

    /** First track to yield a cover supplies the album's; the rest inherit. */
    @Query("UPDATE albums SET artworkId = :artworkId WHERE id = :albumId AND artworkId IS NULL")
    suspend fun setArtworkIfMissing(albumId: Long, artworkId: String)

    @Query("""
        UPDATE albums SET
          trackCount = (SELECT COUNT(*) FROM tracks WHERE tracks.albumId = albums.id),
          durationMs = (SELECT COALESCE(SUM(durationMs), 0) FROM tracks WHERE tracks.albumId = albums.id)
    """)
    suspend fun recomputeRollups()
}

@Dao
interface ArtistDao {
    @Upsert suspend fun upsert(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists ORDER BY sortName")
    fun pagedAll(): PagingSource<Int, ArtistEntity>

    @RawQuery(observedEntities = [ArtistEntity::class])
    fun pagedListItemsRaw(query: SupportSQLiteQuery): PagingSource<Int, ArtistListItem>

    /** Artists with no photo that have not been looked up yet. */
    @Query("""
        SELECT id, name FROM artists
        WHERE artworkId IS NULL AND artworkAttemptedAt IS NULL
        ORDER BY trackCount DESC
        LIMIT :limit
    """)
    suspend fun artistsNeedingPhotos(limit: Int): List<ArtistPhotoRow>

    @Query("UPDATE artists SET artworkId = :artworkId, artworkAttemptedAt = :at WHERE id = :id")
    suspend fun setArtwork(id: Long, artworkId: String?, at: Long)

    /** Marks a lookup as done even when nothing was found, so it is not repeated. */
    @Query("UPDATE artists SET artworkAttemptedAt = :at WHERE id IN (:ids)")
    suspend fun markPhotoAttempted(ids: List<Long>, at: Long)

    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM tracks)")
    suspend fun pruneOrphans()

    @Query("""
        UPDATE artists SET
          trackCount = (SELECT COUNT(*) FROM tracks WHERE tracks.artistId = artists.id),
          albumCount = (SELECT COUNT(*) FROM albums WHERE albums.artistId = artists.id)
    """)
    suspend fun recomputeRollups()
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(source: SourceEntity)
    @Query("SELECT * FROM sources WHERE enabled = 1") suspend fun enabled(): List<SourceEntity>
    @Query("SELECT * FROM sources") fun all(): Flow<List<SourceEntity>>
    @Query("UPDATE sources SET deltaToken = :token, lastSyncAt = :at WHERE id = :id")
    suspend fun setDelta(id: String, token: String?, at: Long)
}

@Dao
interface ArtworkDao {
    @Upsert suspend fun upsert(art: ArtworkEntity)
    @Query("SELECT * FROM artwork WHERE id = :id") suspend fun byId(id: String): ArtworkEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM artwork WHERE id = :id)") suspend fun exists(id: String): Boolean
}
