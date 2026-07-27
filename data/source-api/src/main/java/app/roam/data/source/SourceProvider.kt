package app.roam.data.source

import androidx.media3.datasource.DataSource
import kotlinx.coroutines.flow.Flow
import java.io.File

/** A file discovered on a remote source. Cheap -- no tags read yet. */
data class RemoteFile(
    val remoteId: String,
    val name: String,
    /** Path segments below the configured root, e.g. ["Artist", "Album"]. */
    val pathSegments: List<String>,
    val sizeBytes: Long,
    val mimeType: String,
    /** md5 / etag / mtime+size. Unchanged => nothing to re-read. */
    val revision: String?,
    val modifiedAt: Long,
)

data class ChangeSet(
    val upserted: List<RemoteFile>,
    val removedIds: List<String>,
    val nextToken: String?,
)

enum class Capability { DELTA_SYNC, RANDOM_ACCESS, WRITE }

/**
 * One contract for Drive, SMB and WebDAV. Adding a source type later means
 * one new module and nothing else changes.
 */
interface SourceProvider {

    val sourceId: String
    val capabilities: Set<Capability>

    /** Full recursive crawl. Emits as it goes so the UI can show progress. */
    fun listAll(root: String): Flow<RemoteFile>

    /**
     * Delta since [token]. Drive implements this with changes.list, which is
     * the single biggest performance win in the sync engine. Sources without
     * DELTA_SYNC fall back to listAll + local diff.
     */
    suspend fun listChanges(token: String?): ChangeSet

    /** Factory for ExoPlayer. Must attach fresh auth on EVERY request. */
    fun dataSourceFactory(): DataSource.Factory

    /**
     * Ranged read used by tag extraction. Reading the first ~1 MB gets the
     * ID3/FLAC header and the embedded cover without pulling the whole file.
     */
    suspend fun readRange(remoteId: String, offset: Long, length: Long): ByteArray

    /** Create folders as needed and upload. Returns the new remote id. */
    suspend fun write(pathSegments: List<String>, fileName: String, file: File): String
}
