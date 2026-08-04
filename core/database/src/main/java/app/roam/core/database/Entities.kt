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
    /**
     * Removed from the library WITHOUT touching the file.
     *
     * The row stays, so a re-sync finds the track already known and leaves it
     * alone rather than rediscovering it as new. User state, like [loved] --
     * sync must never clear it, or every hidden track returns on the next scan.
     */
    val hidden: Boolean = false,
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
