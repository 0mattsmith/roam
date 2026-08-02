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

@Entity(tableName = "artists", indices = [Index("sortName")])
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val sortName: String,
    val artworkId: String? = null,
    /**
     * When a photo lookup was last attempted, successful or not. Without it an
     * artist with no photo anywhere gets re-searched on every single run.
     */
    val artworkAttemptedAt: Long? = null,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
)

@Entity(tableName = "albums", indices = [Index("artistId"), Index("sortTitle")])
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val sortTitle: String,
    /** ALBUM artist, not track artist. Compilations stay together. */
    val artistId: Long,
    val year: Int? = null,
    val discTotal: Int = 1,
    val trackCount: Int = 0,
    val durationMs: Long = 0,
    val artworkId: String? = null,
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
    val genre: String? = null,
    val durationMs: Long = 0,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val mimeType: String = "audio/mpeg",
    val sizeBytes: Long = 0,
    val artworkId: String? = null,

    // ---- User state. Sync MUST NOT touch these columns. ----
    /**
     * Tags typed by hand. While set, neither sync nor TagWorker rewrites the
     * tag columns -- the file's own tags are exactly what the user overrode.
     */
    val userEdited: Boolean = false,
    val loved: Boolean = false,
    val lovedAt: Long? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val lastPlayedAt: Long? = null,

    val addedAt: Long = 0,
    val tagState: TagState = TagState.PENDING,
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
