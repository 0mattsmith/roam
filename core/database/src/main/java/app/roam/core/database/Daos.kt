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
     *
     * [tagState] rides here rather than with the path-inferred columns because
     * it means "this file needs re-reading", which is a fact about the FILE.
     * Left in refreshFromPath it inherited that query's `userEdited = 0`, so a
     * track someone had renamed was never re-tagged again -- and since the tag
     * pass is the only thing that ever learns a duration, a corrected file's
     * duration stayed 0 forever.
     */
    @Query("""
        UPDATE tracks SET remoteRevision = :remoteRevision, mimeType = :mimeType,
                          sizeBytes = :sizeBytes, fileName = :fileName,
                          folderPath = :folderPath, tagState = :tagState
        WHERE id = :id
    """)
    suspend fun updateFileFacts(
        id: Long,
        remoteRevision: String?,
        mimeType: String,
        sizeBytes: Long,
        fileName: String?,
        folderPath: String?,
        tagState: TagState,
    )

    /**
     * Re-applies what the path implies.
     *
     * Skipped for a track the user edited by hand: their titling beats whatever
     * the filename says, and silently reverting it would be worse than never
     * having offered the edit. Skipped for a track described by `album.json`
     * for the same reason one rank down -- a folder name is the weakest claim
     * there is, and it must not beat a document that names the track outright.
     */
    @Query("""
        UPDATE tracks SET
          title = :title, artistId = :artistId, albumId = :albumId,
          albumArtist = :albumArtist, trackNo = :trackNo
        WHERE id = :id AND userEdited = 0 AND fromDoc = 0
    """)
    suspend fun refreshFromPath(
        id: Long,
        title: String,
        artistId: Long,
        albumId: Long,
        albumArtist: String?,
        trackNo: Int?,
    )

    /**
     * What `album.json` says, written for one track.
     *
     * Shaped like applyUserEdit and deliberately one rank below it: a
     * correction typed into Roam survives a re-read of the file that has not
     * caught up with it yet. Once the editor WRITES the document, it clears
     * userEdited in the same breath, and this becomes the authority.
     *
     * tagState is untouched. The document carries no duration and no embedded
     * cover, so the tag pass still has work to do on this row.
     *
     * Returns rows written, which is not always one: a hand-edited track is
     * refused here, and a count that assumed otherwise would report work that
     * did not happen.
     */
    @Query("""
        UPDATE tracks SET
          title = :title, artistId = :artistId, albumId = :albumId,
          albumArtist = :albumArtist, trackNo = :trackNo, discNo = :discNo,
          year = :year, originalYear = :originalYear, genre = :genre,
          fromDoc = 1
        WHERE id = :id AND userEdited = 0
    """)
    suspend fun applyFromDoc(
        id: Long,
        title: String,
        artistId: Long,
        albumId: Long,
        albumArtist: String?,
        trackNo: Int?,
        discNo: Int?,
        year: Int?,
        originalYear: Int?,
        genre: String?,
    ): Int

    /**
     * The document stopped claiming these tracks, so the file's own tags win
     * again.
     *
     * Only ever called for a folder whose `album.json` was read successfully:
     * absence proves nothing, and a failed read must not look like a deletion.
     */
    @Query("UPDATE tracks SET fromDoc = 0 WHERE id IN (:ids)")
    suspend fun clearFromDoc(ids: List<Long>)

    /**
     * Every track at or below a folder, with what it needs to be matched
     * against a document entry.
     *
     * Below, not just in: a deluxe edition keeps its discs in subfolders and
     * the index names them `Disc 1/01 Track.mp3`. The trailing slash on the
     * LIKE is what stops "Oasis/Definitely Maybe" also matching
     * "Oasis/Definitely Maybe Remastered".
     */
    @Query("""
        SELECT id, fileName, folderPath, fromDoc FROM tracks
        WHERE sourceId = :sourceId
          AND (folderPath = :folderPath OR folderPath LIKE :folderPath || '/%')
    """)
    suspend fun tracksUnderFolder(sourceId: String, folderPath: String): List<FolderTrackRow>

    /** Which album a track belongs to right now, before a document moves it. */
    @Query("SELECT albumId FROM tracks WHERE id = :id")
    suspend fun albumIdOf(id: Long): Long?

    /** By full path below the root, for entries that point outside their album. */
    @Query("""
        SELECT id, fileName, folderPath, fromDoc FROM tracks
        WHERE sourceId = :sourceId
          AND (CASE WHEN folderPath = '' THEN fileName
                    ELSE folderPath || '/' || fileName END) COLLATE NOCASE IN (:paths)
    """)
    suspend fun tracksByPath(sourceId: String, paths: List<String>): List<FolderTrackRow>

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

    // ---- tracks stuck on a guess --------------------------------------------
    //
    // userEdited on a track whose tags were NEVER read successfully. The flag
    // stops the tag pass writing metadata, so such a row is frozen on whatever
    // it showed when the flag landed -- and if the tags had not been read by
    // then, that is a title worked out from the filename, kept forever.
    //
    // The step arrows used to write this flag on every press, so paging down an
    // album to READ it froze every track in it. Roam cannot tell that from a
    // real edit, because the flag does not record why it was set. So it does
    // not guess: it counts them, says so, and lets the person look.

    @Query("SELECT COUNT(*) FROM tracks WHERE userEdited = 1 AND tagState != 'OK' AND hidden = 0")
    fun frozenOnGuessCount(): Flow<Int>

    @Query("""
        SELECT t.id AS id, t.title AS title, ar.name AS artistName, al.title AS albumTitle
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.userEdited = 1 AND t.tagState != 'OK' AND t.hidden = 0
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
    """)
    fun frozenOnGuess(): Flow<List<HiddenTrackRow>>

    /** Hands every one of them back to its file at once. */
    @Query("""
        UPDATE tracks SET userEdited = 0, tagState = 'PENDING'
        WHERE userEdited = 1 AND tagState != 'OK' AND hidden = 0
    """)
    suspend fun releaseFrozenOnGuess(): Int

    /**
     * Lets the tag pass try a file it has already given up on.
     *
     * FAILED is deliberately terminal -- it is what stops a file with no tags
     * being re-read on every sync. Terminal with no way back is a different
     * thing, though: a read that failed on a flaky connection stayed failed
     * forever, and nothing on screen said why the title never improved.
     */
    @Query("UPDATE tracks SET tagState = 'PENDING' WHERE sourceId = :sourceId AND tagState = 'FAILED'")
    suspend fun retryFailedTags(sourceId: String): Int

    /** Everything, including files that were read fine. The bigger hammer. */
    @Query("UPDATE tracks SET tagState = 'PENDING' WHERE sourceId = :sourceId")
    suspend fun retryAllTags(sourceId: String): Int

    // ---- writing the metadata files -----------------------------------------

    /**
     * Everything an album's documents need, in the order they will be listed.
     *
     * Its own projection rather than TrackListItem: that one carries artwork
     * ids and loved flags for drawing a row, and none of the file facts these
     * documents are built out of.
     */
    @Query("""
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               aar.name AS albumArtistName, al.title AS albumTitle,
               al.compilation AS compilation, al.discTotal AS discTotal,
               t.trackNo AS trackNo, t.discNo AS discNo,
               t.year AS year, t.originalYear AS originalYear, t.genre AS genre,
               t.durationMs AS durationMs, t.startMs AS startMs, t.endMs AS endMs,
               t.fileName AS fileName, t.folderPath AS folderPath,
               t.tagState AS tagState, t.lyricsAttemptedAt AS lyricsAttemptedAt
        FROM tracks t
        JOIN artists ar  ON ar.id  = t.artistId
        JOIN albums  al  ON al.id  = t.albumId
        JOIN artists aar ON aar.id = al.artistId
        WHERE t.albumId = :albumId AND t.hidden = 0
        ORDER BY t.discNo, t.trackNo, t.title
    """)
    suspend fun docTracksForAlbum(albumId: Long): List<DocTrackRow>

    /**
     * The document now owns this row: the file is the durable copy, so Roam's
     * own override steps aside for it.
     *
     * Both halves matter. Leaving userEdited set would make the next sync's
     * reader refuse to apply the very file that was just written -- applyFromDoc
     * carries `AND userEdited = 0` -- and the two would drift apart with no way
     * to tell which was right.
     */
    @Query("UPDATE tracks SET fromDoc = 1, userEdited = 0 WHERE id IN (:ids)")
    suspend fun markWrittenToDoc(ids: List<Long>)

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
               t.artworkId AS artworkId, t.loved AS loved,
               t.startMs AS startMs, t.endMs AS endMs
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
               t.artworkId AS artworkId, t.loved AS loved,
               t.startMs AS startMs, t.endMs AS endMs
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

    // ---- removed from the library ----
    //
    // A hidden track keeps its row on purpose. Deleting it would mean the next
    // sync rediscovers the file as new and puts it straight back, and it would
    // take the loved flag and play count with it.

    /**
     * Trim points. Separate from the tag editor on purpose: these are a
     * playback preference, not metadata, so setting one must NOT mark the
     * track userEdited and stop the tag pass ever refreshing it.
     */
    @Query("UPDATE tracks SET startMs = :startMs, endMs = :endMs WHERE id = :id")
    suspend fun setClip(id: Long, startMs: Long?, endMs: Long?)

    /**
     * Caches a lookup, hit or miss.
     *
     * Stamped unconditionally: a track LRCLIB has never heard of must record
     * that it was asked, or every play repeats the request forever. Same
     * mistake the artist photo pass made with artworkAttemptedAt.
     */
    @Query("""
        UPDATE tracks
        SET lyrics = :plain, syncedLyrics = :synced, lyricsAttemptedAt = :at
        WHERE id = :id
    """)
    suspend fun setLyrics(id: Long, plain: String?, synced: String?, at: Long)

    @Query("SELECT lyrics AS plain, syncedLyrics AS synced, lyricsAttemptedAt AS attemptedAt " +
        "FROM tracks WHERE id = :id")
    fun lyricsFor(id: Long): Flow<StoredLyrics?>

    /** The same row read once, for the fetch path's "have we already asked?" check. */
    @Query("SELECT lyrics AS plain, syncedLyrics AS synced, lyricsAttemptedAt AS attemptedAt " +
        "FROM tracks WHERE id = :id")
    suspend fun lyricsForOnce(id: Long): StoredLyrics?

    /** Everything a lyric lookup needs to identify the track. */
    @Query("""
        SELECT t.id AS id, t.title AS title, ar.name AS artistName,
               al.title AS albumTitle, t.durationMs AS durationMs,
               t.fileName AS fileName, t.folderPath AS folderPath
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.id = :id
    """)
    suspend fun lyricSubject(id: Long): LyricSubject?

    /**
     * Tracks with no lyrics yet. Hidden rows are excluded -- fetching words for
     * something removed from the library is work nobody asked for.
     */
    @Query("SELECT id FROM tracks WHERE hidden = 0 AND lyricsAttemptedAt IS NULL")
    suspend fun trackIdsWithoutLyrics(): List<Long>

    /** Everything, for a re-check that ignores a previous miss. */
    @Query("SELECT id FROM tracks WHERE hidden = 0")
    suspend fun allTrackIds(): List<Long>

    @Query("SELECT id FROM tracks WHERE albumId = :albumId AND hidden = 0")
    suspend fun trackIdsForAlbum(albumId: Long): List<Long>

    @Query("UPDATE tracks SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE tracks SET hidden = :hidden WHERE albumId = :albumId")
    suspend fun setHiddenForAlbum(albumId: Long, hidden: Boolean)

    /** Deliberately reads the table directly: every list query filters these out. */
    @Query("""
        SELECT t.id AS id, t.title AS title, ar.name AS artistName, al.title AS albumTitle
        FROM tracks t
        JOIN artists ar ON ar.id = t.artistId
        JOIN albums  al ON al.id = t.albumId
        WHERE t.hidden = 1
        ORDER BY ar.sortName, al.sortTitle, t.discNo, t.trackNo
    """)
    fun hiddenTracks(): Flow<List<HiddenTrackRow>>

    @Query("SELECT COUNT(*) FROM tracks WHERE hidden = 1")
    fun hiddenCount(): Flow<Int>

    @Query("UPDATE tracks SET hidden = 0 WHERE hidden = 1")
    suspend fun restoreAllHidden()

    // ---- tag pass ----

    /**
     * Tracks whose file has not been read yet, oldest first.
     *
     * Deliberately NOT filtered on userEdited or fromDoc. Whoever owns the
     * words, the duration and the embedded cover live only in the file and
     * nothing else ever learns them -- excluding these rows left an edited
     * track at duration 0 for good, which quietly broke play counting and the
     * times in the car for exactly the tracks someone had cared enough to fix.
     * What those flags protect is the METADATA, and updateTags is where that
     * is decided.
     */
    @Query("""
        SELECT t.id AS id, t.remoteId AS remoteId, t.albumId AS albumId,
               t.title AS name, t.sizeBytes AS sizeBytes
        FROM tracks t
        WHERE t.sourceId = :sourceId AND t.tagState != 'OK' AND t.tagState != 'FAILED'
        ORDER BY t.addedAt
        LIMIT :limit
    """)
    suspend fun pendingTags(sourceId: String, limit: Int): List<PendingTagRow>

    /**
     * What only the file knows, written whoever owns the metadata.
     *
     * Duration, the embedded cover and the outcome of the read itself. No
     * document carries these and no edit form offers them, so there is nothing
     * here for a flag to protect -- and gating them behind one is what left
     * every track measuring against a duration of zero.
     */
    @Query("""
        UPDATE tracks SET
          artworkId = COALESCE(:artworkId, artworkId),
          durationMs = COALESCE(:durationMs, durationMs),
          tagState = :tagState
        WHERE id = :id
    """)
    suspend fun updateTagFacts(id: Long, artworkId: String?, durationMs: Long?, tagState: TagState)

    /**
     * Writes only tag-derived metadata. loved, playCount, skipCount and
     * lastPlayedAt are the user's and must never be touched here.
     * COALESCE keeps the path-inferred value when a tag is absent.
     *
     * Refused for a hand-edited track, and refused for one described by
     * `album.json` -- otherwise the tag pass runs along behind the reader and
     * puts the file's own tags back, which is the whole thing those documents
     * exist to stop.
     */
    @Query("""
        UPDATE tracks SET
          title = COALESCE(:title, title),
          year = COALESCE(:year, year),
          genre = COALESCE(:genre, genre),
          trackNo = COALESCE(:trackNo, trackNo),
          trackTotal = COALESCE(:trackTotal, trackTotal),
          discNo = COALESCE(:discNo, discNo),
          discTotal = COALESCE(:discTotal, discTotal)
        WHERE id = :id AND userEdited = 0 AND fromDoc = 0
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

/**
 * Cached lyrics for one track.
 *
 * [attemptedAt] separates "never looked" from "looked and found nothing",
 * which are the same shape but need opposite behaviour.
 */
data class StoredLyrics(
    val plain: String?,
    val synced: String?,
    val attemptedAt: Long?,
)

/** What identifies a track to a lyrics service. */
data class LyricSubject(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val durationMs: Long,
    /** Null until a crawl has run since schema 13 filled these in. */
    val fileName: String?,
    val folderPath: String?,
)

/** A track removed from the library, as the restore list needs it. */
data class HiddenTrackRow(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String,
)

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
    /** Playback window. Null means play the whole file. */
    val startMs: Long?,
    val endMs: Long?,
)

data class ShuffleRow(val id: Long, val loved: Boolean, val skipCount: Int, val lastPlayedAt: Long?)
data class RevisionRow(val id: Long, val remoteId: String, val remoteRevision: String?)

/**
 * Enough of a track to match it against a document entry.
 *
 * Both path columns are nullable: they arrived in schema 13 and fill in on the
 * next crawl, so a library that has not been re-crawled since simply matches
 * nothing rather than matching wrongly.
 */
data class FolderTrackRow(
    val id: Long,
    val fileName: String?,
    val folderPath: String?,
    val fromDoc: Boolean,
)

/** One track, as the metadata files describe it. */
data class DocTrackRow(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumArtistName: String,
    val albumTitle: String,
    val compilation: Boolean,
    val discTotal: Int,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val originalYear: Int?,
    val genre: String?,
    val durationMs: Long,
    val startMs: Long?,
    val endMs: Long?,
    val fileName: String?,
    val folderPath: String?,
    val tagState: TagState,
    val lyricsAttemptedAt: Long?,
)

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
     * The release year, when a document states one.
     *
     * Sync leaves this null -- a folder name is not a claim about the album --
     * so for most libraries the Albums list has never had a year to show. An
     * `album.json` is a claim, which is why this is unconditional rather than
     * an IF MISSING variant.
     */
    @Query("UPDATE albums SET year = :year WHERE id = :albumId")
    suspend fun setYear(albumId: Long, year: Int)

    /**
     * Forgets Roam's cover. Does NOT delete cover.jpg from the source -- that
     * is the user's file, and a tap in a dialog should not remove something
     * from their Drive.
     */
    @Query("UPDATE albums SET artworkId = NULL WHERE id = :albumId")
    suspend fun clearArtwork(albumId: Long)

    // hidden = 0 throughout: a count that includes tracks the list will not
    // show is how an album ends up claiming twelve tracks and displaying ten.
    @Query("""
        UPDATE albums SET
          trackCount = (SELECT COUNT(*) FROM tracks WHERE tracks.albumId = albums.id AND tracks.hidden = 0),
          -- Clipped length, not file length: an album whose tracks each skip a
          -- minute of silence is genuinely shorter than the sum of its files.
          durationMs = (
            SELECT COALESCE(SUM(COALESCE(endMs, durationMs) - COALESCE(startMs, 0)), 0)
            FROM tracks WHERE tracks.albumId = albums.id AND tracks.hidden = 0
          ),
          -- Counted from the tracks rather than trusted from a tag: plenty of
          -- rips carry no discTotal at all, and this is what decides whether
          -- the album view shows disc headings.
          discTotal = (SELECT COALESCE(MAX(discNo), 1) FROM tracks WHERE tracks.albumId = albums.id AND tracks.hidden = 0)
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
            WHERE t.hidden = 0
              AND (t.artistId = artists.id
               OR t.artistId IN (SELECT g.id FROM artists g WHERE g.groupArtistId = artists.id))
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

/**
 * Which `album.json` files have already been applied.
 *
 * Upsert is safe here in a way it is not for tracks (invariant 3a): every
 * column of this entity is written by the same pass that reads it, and there is
 * no user state anywhere near it.
 */
@Dao
interface DocRevisionDao {
    @Upsert suspend fun upsert(rows: List<DocRevisionEntity>)

    @Query("SELECT * FROM doc_revisions WHERE sourceId = :sourceId")
    suspend fun all(sourceId: String): List<DocRevisionEntity>

    /** A document that is no longer on the source has nothing left to cache. */
    @Query("DELETE FROM doc_revisions WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun forget(sourceId: String, remoteIds: List<String>)
}
