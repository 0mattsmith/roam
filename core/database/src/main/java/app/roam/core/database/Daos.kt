package app.roam.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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

    // ---- sync ----
    @Query("SELECT id, remoteId, remoteRevision FROM tracks WHERE sourceId = :sourceId")
    suspend fun revisions(sourceId: String): List<RevisionRow>

    @Query("DELETE FROM tracks WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteRemote(sourceId: String, remoteIds: List<String>)

    @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Flow<Int>
}

data class ShuffleRow(val id: Long, val loved: Boolean, val skipCount: Int, val lastPlayedAt: Long?)
data class RevisionRow(val id: Long, val remoteId: String, val remoteRevision: String?)

@Dao
interface AlbumDao {
    @Upsert suspend fun upsert(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, sortTitle")
    fun byArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY sortTitle")
    fun pagedAll(): PagingSource<Int, AlbumEntity>

    @Query("SELECT * FROM albums ORDER BY addedAt DESC LIMIT :limit")
    suspend fun recentlyAdded(limit: Int): List<AlbumEntity>

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks)")
    suspend fun pruneOrphans()
}

@Dao
interface ArtistDao {
    @Upsert suspend fun upsert(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists ORDER BY sortName")
    fun pagedAll(): PagingSource<Int, ArtistEntity>

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
