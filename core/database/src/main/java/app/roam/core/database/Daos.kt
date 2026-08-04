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

    /**
     * Sync's insert path. IGNORE, never REPLACE: an existing row carries loved,
     * playCount and lastPlayedAt, and @Upsert would write the freshly built
     * entity's defaults straight over them.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(tracks: List<TrackEntity>)

    /**
     * Bookkeeping for a file whose bytes changed. Runs even for an edited
     * track -- without stamping the new revision the crawl would treat it as
     * changed on every single sync.
     */
    @Query("""
        UPDATE tracks SET remoteRevision = :remoteRevision, mimeType = :mimeType,
                          sizeBytes = :sizeBytes
        WHERE id = :id
    """)
    suspend fun updateFileFacts(id: Long, remoteRevision: String?, mimeType: String, sizeBytes: Long)

    /**
     * Re-applies what the path implies and queues a re-read of the tags.
     * Skipped for a track the user edited by hand: their titling beats
     * whatever the filename says, and silently reverting it would be worse
     * than never having offered the edit.
     */
    @Query("""
        UPDATE tracks SET
          title = :title, artistId = :artistId, albumId = :albumId,
          albumArtist = :albumArtist, trackNo = :trackNo, tagState = :tagState
        WHERE id = :id AND userEdited = 0
    """)
    suspend fun refreshFromPath(
        id: Long,
        title: String,
        artistId: Long,
        albumId: Long,
        albumArtist: String?,
        trackNo: Int?,
        tagState: TagState,
    )

    /** Everything the edit form writes, in one go. */
    @Query("""
        UPDATE tracks SET
          title = :title, artistId = :artistId, albumId = :albumId,
          albumArtist = :albumArtist, trackNo = :trackNo, discNo = :discNo,
          year = :year, genre = :genre, userEdited = 1
        WHERE id = :id
    """)
    suspend fun applyUserEdit(
        id: Long,
        title: String,
        artistId: Long,
        albumId: Long,
        albumArtist: String?,
        trackNo: Int?,
        discNo: Int?,
        year: Int?,
        genre: String?,
    )

    /**
     * Artwork for one track, chosen by hand. Local only: the picture the rest
     * of the world sees lives in the file's APIC frame, and rewriting that
     * needs the tag writer.
     */
    @Query("UPDATE tracks SET artworkId = :artworkId WHERE id = :id")
    suspend fun setArtwork(id: Long, artworkId: String)

    /** Hands the track back to the file: cleared, the next pass re-reads it. */
    @Query("UPDATE tracks SET userEdited = 0, tagState = 'PENDING' WHERE id = :id")
    suspend fun clearUserEdit(id: Long)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNo, trackNo, title")
    fun byAlbum(albumId: Long): Flow<List<TrackEntity>>

    /** One-shot, for a bulk edit that needs each track's current values. */
    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNo, trackNo, title")
    suspend fun entitiesForAlbum(albumId: Long): List<TrackEntity>

    /** Same picture on every track of an album, in one statement. */
    @Query("UPDATE tracks SET artworkId = :artworkId WHERE albumId = :albumId")
    suspend fun setArtworkForAlbum(albumId: Long, artworkId: String)

    @Query("UPDATE tracks SET artworkId = NULL WHERE albumId = :albumId")
    suspend fun clearArtworkForAlbum(albumId: Long)

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
        ORDER BY aar.sortName, al.sortTitle, t.discNo, t.trackNo, t.title
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
        ORDER BY aar.sortName, al.sortTitle, t.discNo, t.trackNo, t.title
        LIMIT :limit
    """)
    suspend fun listItems(limit: Int): List<TrackListItem>

    /**
     * Everything the shuffle engine needs, and nothing else. Loading 10k full
     * rows to build a queue is the easiest way to make this app feel slow.
     */
    @Query("SELECT id, loved, skipCount, lastPlayedAt FROM tracks")
    suspend fun shuffleCandidates(): List<ShuffleRow>

    /** Includes grouped aliases, so "shuffle 2Pac" covers the Makaveli records. */
    @Query("""
        SELECT id, loved, skipCount, lastPlayedAt FROM tracks
        WHERE artistId = :artistId
           OR artistId IN (SELECT id FROM artists WHERE groupArtistId = :artistId)
    """)
    suspend fun shuffleCandidatesForArtist(artistId: Long): List<ShuffleRow>

    @Query("SELECT id, loved, skipCount, lastPlayedAt FROM tracks WHERE loved = 1")
    suspend fun shuffleCandidatesLoved(): List<ShuffleRow>

    // ---- user state ----
    @Query("UPDATE tracks SET loved = :loved, lovedAt = :at WHERE id = :id")
    suspend fun setLoved(id: Long, loved: Boolean, at: Long?)

    /**
     * Loves or unloves a whole album in one statement.
     *
     * lovedAt is stamped identically across the album, so the Loved list keeps
     * them together rather than interleaving them with whatever else was
     * hearted around the same moment.
     */
    @Query("UPDATE tracks SET loved = :loved, lovedAt = :at WHERE albumId = :albumId")
    suspend fun setLovedForAlbum(albumId: Long, loved: Boolean, at: Long?)

    /** Zero means every track on the album is loved. */
    @Query("SELECT COUNT(*) FROM tracks WHERE albumId = :albumId AND loved = 0")
    suspend fun unlovedCountForAlbum(albumId: Long): Int

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("UPDATE tracks SET skipCount = skipCount + 1 WHERE id = :id")
    suspend fun markSkipped(id: Long)

    // ---- tag pass ----

    /** Tracks still carrying path-derived metadata, oldest first. */
    @Query("""
        SELECT t.id AS id, t.remoteId AS remoteId, t.albumId AS albumId,
               t.title AS name, t.sizeBytes AS sizeBytes
        FROM tracks t
        WHERE t.sourceId = :sourceId AND t.tagState != 'OK' AND t.tagState != 'FAILED'
          AND t.userEdited = 0
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
        WHERE id = :id AND userEdited = 0
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
    val name: String,
    /**
     * Needed to read the END of the file. An M4A that never went through
     * faststart keeps its moov atom after all the audio, so the tail read
     * has to know where the tail is.
     */
    val sizeBytes: Long,
)

data class TrackListItem(
    val id: Long,
    val remoteId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    /** The album's artist. "Various Artists" on a compilation. */
    val albumArtistName: String,
    val compilation: Boolean,
    /** Carried so a list can spot album boundaries without a second query. */
    val albumId: Long,
    /** The album's own cover, which is what an album header should show. */
    val albumArtworkId: String?,
    val albumYear: Int?,
    /** Highest disc number in the album. 1 unless it is a real multi-disc set. */
    val albumDiscTotal: Int,
    val trackNo: Int?,
    val discNo: Int?,
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
    val logoArtworkId: String?,
    val preferLogo: Boolean,
    /** Non-null when this artist is filed under someone else's name. */
    val sortAs: String?,
    /** The artist this one is folded into, if any. */
    val groupArtistId: Long?,
    val bannerArtworkId: String?,
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

    /**
     * Sync's insert path. The id is derived from artist + title, so a rename
     * makes a different row -- there is never anything to update, and an
     * upsert here would null out a cover the user picked.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(albums: List<AlbumEntity>)

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun byId(id: Long): AlbumEntity?

    @Query("SELECT * FROM albums WHERE artistId = :artistId ORDER BY year DESC, sortTitle")
    fun byArtist(artistId: Long): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY sortTitle")
    fun pagedAll(): PagingSource<Int, AlbumEntity>

    @RawQuery(observedEntities = [AlbumEntity::class, ArtistEntity::class])
    fun pagedListItemsRaw(query: SupportSQLiteQuery): PagingSource<Int, AlbumListItem>

    /** One-shot window, for the car -- Android Auto asks page by page. */
    @RawQuery(observedEntities = [AlbumEntity::class, ArtistEntity::class])
    suspend fun listItemsRaw(query: SupportSQLiteQuery): List<AlbumListItem>

    @Query("SELECT * FROM albums ORDER BY addedAt DESC LIMIT :limit")
    suspend fun recentlyAdded(limit: Int): List<AlbumEntity>

    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks)")
    suspend fun pruneOrphans()

    /** First track to yield a cover supplies the album's; the rest inherit. */
    @Query("UPDATE albums SET artworkId = :artworkId WHERE id = :albumId AND artworkId IS NULL")
    suspend fun setArtworkIfMissing(albumId: Long, artworkId: String)

    /**
     * Unconditional, for a cover the user picked. The IF MISSING variant above
     * is what protects it afterwards: a re-tag will not overwrite an album that
     * already has artwork.
     */
    @Query("UPDATE albums SET artworkId = :artworkId WHERE id = :albumId")
    suspend fun setArtwork(albumId: Long, artworkId: String)

    @Query("UPDATE albums SET compilation = :compilation WHERE id = :albumId")
    suspend fun setCompilation(albumId: Long, compilation: Boolean)

    /**
     * Forgets Roam's cover. Does NOT delete cover.jpg from the source -- that
     * is the user's file, and a tap in a dialog should not remove something
     * from their Drive.
     */
    @Query("UPDATE albums SET artworkId = NULL WHERE id = :albumId")
    suspend fun clearArtwork(albumId: Long)

    @Query("""
        UPDATE albums SET
          trackCount = (SELECT COUNT(*) FROM tracks WHERE tracks.albumId = albums.id),
          durationMs = (SELECT COALESCE(SUM(durationMs), 0) FROM tracks WHERE tracks.albumId = albums.id),
          -- Counted from the tracks rather than trusted from a tag: plenty of
          -- rips carry no discTotal at all, and this is what decides whether
          -- the album view shows disc headings.
          discTotal = (SELECT COALESCE(MAX(discNo), 1) FROM tracks WHERE tracks.albumId = albums.id)
    """)
    suspend fun recomputeRollups()
}

@Dao
interface ArtistDao {
    @Upsert suspend fun upsert(artists: List<ArtistEntity>)

    /** As AlbumDao.insertIgnore -- an upsert would wipe artworkId and artworkAttemptedAt. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(artists: List<ArtistEntity>)

    @Query("SELECT * FROM artists WHERE id = :id")
    suspend fun byId(id: Long): ArtistEntity?

    @Query("SELECT * FROM artists ORDER BY sortName")
    fun pagedAll(): PagingSource<Int, ArtistEntity>

    @RawQuery(observedEntities = [ArtistEntity::class])
    fun pagedListItemsRaw(query: SupportSQLiteQuery): PagingSource<Int, ArtistListItem>

    /** One-shot window, for the car -- Android Auto asks page by page. */
    @RawQuery(observedEntities = [ArtistEntity::class])
    suspend fun listItemsRaw(query: SupportSQLiteQuery): List<ArtistListItem>

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

    /** Artists with no logo that have not been looked up yet. */
    @Query("""
        SELECT id, name FROM artists
        WHERE logoArtworkId IS NULL AND logoAttemptedAt IS NULL
        ORDER BY trackCount DESC
        LIMIT :limit
    """)
    suspend fun artistsNeedingLogos(limit: Int): List<ArtistPhotoRow>

    @Query("UPDATE artists SET logoArtworkId = :logoArtworkId, logoAttemptedAt = :at WHERE id = :id")
    suspend fun setLogo(id: Long, logoArtworkId: String?, at: Long)

    /**
     * Artists with no banner that have not been looked up yet.
     *
     * Deliberately separate from [artistsNeedingLogos]. Riding on that query
     * meant an artist was only ever asked about a banner during their one and
     * only logo lookup -- so anyone stamped before banners existed, or anyone
     * with a logo.png already in their folder, never got one.
     */
    @Query("""
        SELECT id, name FROM artists
        WHERE bannerArtworkId IS NULL AND bannerAttemptedAt IS NULL
        ORDER BY trackCount DESC
        LIMIT :limit
    """)
    suspend fun artistsNeedingBanners(limit: Int): List<ArtistPhotoRow>

    /**
     * The stamp is written on failure too, or an artist TheAudioDB has never
     * heard of is re-searched on every single run.
     */
    @Query("UPDATE artists SET bannerArtworkId = :bannerArtworkId, bannerAttemptedAt = :at WHERE id = :id")
    suspend fun setBanner(id: Long, bannerArtworkId: String?, at: Long)

    /** Which image this artist is drawn with. Purely the user's choice. */
    @Query("UPDATE artists SET preferLogo = :preferLogo WHERE id = :id")
    suspend fun setPreferLogo(id: Long, preferLogo: Boolean)

    /**
     * Files an artist under a different name without renaming them.
     *
     * sortName is what every ORDER BY in the app already uses, so writing the
     * override into it makes aliases and side projects group with the main
     * artist everywhere at once -- Artists list, album-major track sorting and
     * the car -- with no query changes.
     */
    @Query("UPDATE artists SET sortAs = :sortAs, sortName = :sortName WHERE id = :id")
    suspend fun setSortAs(id: Long, sortAs: String?, sortName: String)

    @Query("UPDATE artists SET groupArtistId = :groupArtistId WHERE id = :id")
    suspend fun setGroupArtist(id: Long, groupArtistId: Long?)

    /**
     * Where [id] itself is grouped, if anywhere.
     *
     * Used to flatten chains at write time: grouping A into B when B is already
     * inside C should put A in C, not build a two-hop path every query would
     * then have to walk.
     */
    @Query("SELECT groupArtistId FROM artists WHERE id = :id")
    suspend fun groupTargetOf(id: Long): Long?

    /** Marks a lookup as done even when nothing was found, so it is not repeated. */
    @Query("UPDATE artists SET artworkAttemptedAt = :at WHERE id IN (:ids)")
    suspend fun markPhotoAttempted(ids: List<Long>, at: Long)

    /**
     * An artist is orphaned only when nothing references it AT ALL -- neither a
     * track nor an album.
     *
     * The album clause is not optional: on a compilation no track carries
     * "Various Artists" as its own artist, so tracks alone would prune the row,
     * and the album-artist join in TRACK_COLUMNS is inner -- the entire
     * compilation would silently disappear from every list.
     */
    @Query("""
        DELETE FROM artists
        WHERE id NOT IN (SELECT DISTINCT artistId FROM tracks)
          AND id NOT IN (SELECT DISTINCT artistId FROM albums)
          AND id NOT IN (SELECT groupArtistId FROM artists WHERE groupArtistId IS NOT NULL)
    """)
    suspend fun pruneOrphans()

    /**
     * Counts include anything grouped into this artist, so the number under a
     * name matches what opening it actually shows.
     */
    @Query("""
        UPDATE artists SET
          trackCount = (
            SELECT COUNT(*) FROM tracks t
            WHERE t.artistId = artists.id
               OR t.artistId IN (SELECT g.id FROM artists g WHERE g.groupArtistId = artists.id)
          ),
          albumCount = (
            SELECT COUNT(*) FROM albums al
            WHERE al.artistId = artists.id
               OR al.artistId IN (SELECT g.id FROM artists g WHERE g.groupArtistId = artists.id)
          )
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
