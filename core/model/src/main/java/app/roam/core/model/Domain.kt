package app.roam.core.model

enum class SourceType { DRIVE, SMB, WEBDAV }
enum class TagState { PENDING, OK, FAILED, PATH_INFERRED }
enum class ArtworkSource { EMBEDDED, FOLDER_JPG, COVER_ART_ARCHIVE, AUDIODB, DEEZER, ITUNES }

/** How the user bounds the streaming cache. Two shapes over one evictor. */
sealed interface CachePolicy {
    /** Keep the next [count] queue items warm. Budget is implied. */
    data class NextTracks(val count: Int = 10) : CachePolicy
    /** Hard byte ceiling; the prefetch window grows to fill it. */
    data class StorageBudget(val bytes: Long) : CachePolicy
}

data class Track(
    val id: Long,
    val sourceId: String,
    val remoteId: String,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    val albumId: Long,
    val artistId: Long,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val genre: String?,
    val durationMs: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val artworkId: String?,
    val loved: Boolean,
    val playCount: Int,
    val skipCount: Int,
    val lastPlayedAt: Long?,
)

data class Album(
    val id: Long, val title: String, val artistId: Long, val artistName: String,
    val year: Int?, val trackCount: Int, val durationMs: Long, val artworkId: String?,
)

data class Artist(
    val id: Long, val name: String, val sortName: String,
    val albumCount: Int, val trackCount: Int, val artworkId: String?,
)

/** Content-derived IDs: a file that moves in Drive keeps its identity. */
object Ids {
    fun normalise(s: String): String =
        s.trim().lowercase().replace(Regex("^(the|a|an)\\s+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

    fun artist(name: String): Long = hash(normalise(name))
    fun album(albumArtist: String, album: String): Long = hash(normalise(albumArtist) + "\u0000" + normalise(album))
    fun track(sourceId: String, remoteId: String): Long = hash(sourceId + "\u0000" + remoteId)

    private fun hash(s: String): Long {
        var h = -0x340d631b7bdddcdbL           // FNV-1a 64 offset basis
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 0x100000001b3L
        }
        return h
    }
}
