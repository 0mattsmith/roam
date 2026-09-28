package app.roam.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.roam.core.model.ArtworkSource
import app.roam.core.model.SourceType
import app.roam.core.model.TagState

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val type: SourceType,
    val displayName: String,
    val rootPath: String,
    val credentialAlias: String? = null,
    /** Drive startPageToken for changes.list; mtime watermark for SMB. */
    val deltaToken: String? = null,
    val lastSyncAt: Long? = null,
    val enabled: Boolean = true,
)

// groupArtistId is indexed because the rollups look it up once per artist
// row, so an unindexed scan there is squared across the table.
@Entity(tableName = "artists", indices = [Index("sortName"), Index("groupArtistId")])
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    /** What the lists actually order by. Normally the name; the override when set. */
    val sortName: String,
    /**
     * "File this artist under X". Makaveli under 2Pac, D12 under Eminem -- the
     * tracks keep their real credit, they just sort together.
     */
    val sortAs: String? = null,
    /**
     * Folds this artist INTO another one. Makaveli grouped into 2Pac stops
     * being his own entry in the Artists list, and his albums show up under
     * 2Pac instead -- which is where they belonged all along.
     *
     * Distinct from [sortAs], which only files an artist next to another while
     * leaving them a separate entry.
     */
    val groupArtistId: Long? = null,
    val artworkId: String? = null,
    /**
     * When a photo lookup was last attempted, successful or not. Without it an
     * artist with no photo anywhere gets re-searched on every single run.
     */
    val artworkAttemptedAt: Long? = null,
    /** Band logo or wordmark. A different thing from a photo of the artist. */
    val logoArtworkId: String? = null,
    /** Wide header image for the artist page. */
    val bannerArtworkId: String? = null,
    /**
     * Its own stamp, not the logo's. Sharing one meant an artist whose logo
     * had already been looked up -- which is every artist, since logos shipped
     * first -- was never asked about a banner at all, and an artist with a
     * logo.png in their folder skipped the lookup entirely.
     */
    val bannerAttemptedAt: Long? = null,
    val logoAttemptedAt: Long? = null,
    /** Which of the two this artist should be drawn with. */
    val preferLogo: Boolean = false,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Entity(tableName = "albums", indices = [Index("artistId"), Index("sortTitle")])
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val sortTitle: String,
    /**
     * TSOA / `soal` / ALBUMSORT. "How this album files", when it differs.
     *
     * Same shape as [ArtistEntity.sortAs]: this is the override, [sortTitle] is
     * what every ORDER BY actually reads. One field to set, and no query has to
     * know the feature exists.
     */
    val sortAs: String? = null,
    /** ALBUM artist, not track artist. Compilations stay together. */
    val artistId: Long,
    /**
     * Tracks by many different artists under one album artist. Album-major
     * views group on the album artist so the album stays whole, while each
     * row still shows whoever actually performed it.
     */
    val compilation: Boolean = false,
    val year: Int? = null,
    val discTotal: Int = 1,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val artworkId: String? = null,
    /**
     * The revision of the cover file this album last took its artwork from.
     *
     * Two jobs, and the second is the surprising one. It stops a cover being
     * re-downloaded on every sync, obviously -- but it is also what makes
     * "Remove cover" survive. That clears [artworkId] and deliberately leaves
     * cover.jpg on Drive, so without a record of having already seen that
     * exact file the next crawl would helpfully put the picture straight back.
     *
     * Null means no cover has ever been read from the folder, which is not the
     * same as there being none.
     */
    val coverRevision: String? = null,
    val addedAt: Long = 0,
)

@Entity(
    tableName = "tracks",
    indices = [
        Index("albumId"), Index("artistId"), Index("sourceId"), Index("loved"),
        Index(value = ["sourceId", "remoteId"], unique = true),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: Long,
    val sourceId: String,
    val remoteId: String,
    /** Drive md5Checksum, or SMB mtime+size. Unchanged => skip re-tagging. */
    val remoteRevision: String? = null,
    val title: String,
    val artistId: Long,
    val albumId: Long,
    val albumArtist: String? = null,
    val trackNo: Int? = null,
    val trackTotal: Int? = null,
    val discNo: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    /**
     * When the material first came out, where that differs from [year].
     *
     * A 2011 remaster of a 1994 record is a 1994 record, and a nineties smart
     * playlist that misses it is wrong in the way people notice. Null means
     * nobody has claimed a difference, and [year] answers for both.
     */
    val originalYear: Int? = null,
    val genre: String? = null,
    /** TCOM / `©wrt` / COMPOSER. Who wrote it, as opposed to who performed it. */
    val composer: String? = null,
    /**
     * TIT1 / `©grp` / GROUPING. The work a track belongs to.
     *
     * Classical uses it for the symphony a movement is part of; plenty of
     * people use it as a free-form second genre. Roam does not interpret it --
     * it carries it, because dropping a field on a re-save is how an editor
     * loses somebody's work.
     */
    val grouping: String? = null,
    /** TSOT / `sonm` / TITLESORT. "Ballad of..." filing under B. */
    val titleSort: String? = null,
    /** TSOC / `soco` / COMPOSERSORT. "Gallagher, Noel". */
    val composerSort: String? = null,
    val durationMs: Long = 0,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val mimeType: String = "audio/mpeg",
    val sizeBytes: Long = 0,
    val artworkId: String? = null,
    /**
     * The file's own name, e.g. "03 Time - Pink Floyd.mp3". A file fact, owned
     * by sync -- a rename on the source must be followed here, or the sidecar
     * lyric lookup goes hunting for a file that no longer exists.
     */
    val fileName: String? = null,
    /** Folder segments below the root, joined with '/'. */
    val folderPath: String? = null,

    // ---- User state. Sync MUST NOT touch these columns. ----
    /**
     * Tags typed by hand. While set, neither sync nor TagWorker rewrites the
     * tag columns -- the file's own tags are exactly what the user overrode.
     */
    val userEdited: Boolean = false,
    /**
     * Words, cached forever once found. Fetched for the track being PLAYED
     * rather than swept over the library -- most people play a small fraction
     * of what they own, and a bulk pass would be thousands of requests to a
     * free service for lyrics nobody is reading.
     */
    val lyrics: String? = null,
    /** The same words with LRC timestamps, when the source had them. */
    val syncedLyrics: String? = null,
    /** Stamped even on a miss, or a track with no lyrics is asked about forever. */
    val lyricsAttemptedAt: Long? = null,
    /**
     * Removed from the library WITHOUT touching the file.
     *
     * The row stays, so a re-sync finds the track already known and leaves it
     * alone rather than rediscovering it as new. User state, like [loved] --
     * sync must never clear it, or every hidden track returns on the next scan.
     */
    val hidden: Boolean = false,

    /**
     * The last crawl did not find the file. Roam's observation, not a decision.
     *
     * Sync used to DELETE these rows, which broke invariant 3c in the one place
     * nobody was looking: a deleted row takes the loved flag, the play count and
     * every correction with it, and the next crawl rediscovers the file as new.
     * That makes a wrong deletion permanent and silent -- and a crawl can be
     * wrong, because Drive answers a stale folder id with an empty list and a
     * 200 rather than an error.
     *
     * Separate from [hidden] because they are cleared by different things. This
     * one goes the moment the file is seen again; [hidden] must survive every
     * sync, or removing a track would only last until the next one.
     */
    val missing: Boolean = false,
    /**
     * Where playback should actually begin and end, in milliseconds.
     *
     * For the silence, the count-in or the DJ talking over the intro. Nothing
     * is trimmed -- the file is untouched and these are handed to the player as
     * a clipping window, so clearing them restores the whole track instantly.
     *
     * User state: neither sync nor the tag pass may write these.
     */
    val startMs: Long? = null,
    val endMs: Long? = null,
    val loved: Boolean = false,
    val lovedAt: Long? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long? = null,

    val addedAt: Long = 0,
    /**
     * This row's metadata came from `album.json`, not from the file's tags.
     *
     * Not user state -- sync writes it -- but it protects the same thing
     * userEdited does, one rank lower. Without it the tag pass would run along
     * behind the reader and put the embedded tags back, which is exactly the
     * mess the metadata files exist to end.
     *
     * It guards the METADATA columns only. Duration, bitrate and the embedded
     * cover are still read from the file, because no document carries them.
     */
    val fromDoc: Boolean = false,
    val tagState: TagState = TagState.PENDING,
)

/**
 * The `album.json` files already applied, so a re-sync reads only what changed.
 *
 * Same trick as [TrackEntity.remoteRevision]: an unchanged document costs one
 * map lookup instead of an HTTP request. [trackCount] is here because the
 * revision alone is not enough -- dropping a new file into a folder leaves the
 * index byte-identical while genuinely needing another pass.
 */
@Entity(tableName = "doc_revisions", primaryKeys = ["sourceId", "remoteId"])
data class DocRevisionEntity(
    val sourceId: String,
    val remoteId: String,
    val revision: String?,
    /** Folder segments below the root, joined with '/'. The album this indexes. */
    val folderPath: String,
    /** How many tracks that folder held when this was applied. */
    val trackCount: Int,
    val appliedAt: Long,
)

@Entity(tableName = "artwork")
data class ArtworkEntity(
    /** sha-256 of the encoded bytes -- dedupes a 14-track album to one file. */
    @PrimaryKey val id: String,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val sourceKind: ArtworkSource,
)
