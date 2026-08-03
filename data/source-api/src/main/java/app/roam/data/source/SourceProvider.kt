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

    /** Whole-file read. Small assets only -- artwork, never audio. */
    suspend fun read(remoteId: String): ByteArray

    /**
     * Resolve a folder path below [root], optionally creating missing levels.
     * Returns null when a level is absent and [create] is false.
     *
     * Implementations are expected to cache: this is called once per artist
     * and the same ids are then reused for the write.
     */
    suspend fun resolveFolder(root: String, pathSegments: List<String>, create: Boolean = false): String?

    /** First non-folder child of [folderId] matching any of [names]. */
    suspend fun findInFolder(folderId: String, names: List<String>): RemoteFile?

    /**
     * Every image in [folderId]. Used to work out the next archive number and
     * to list the covers a folder has held before.
     */
    suspend fun listImages(folderId: String): List<RemoteFile>

    /** Create folders as needed and upload. Returns the new remote id. */
    suspend fun write(root: String, pathSegments: List<String>, fileName: String, file: File): String

    /**
     * Replace the contents of an existing file, keeping its id.
     *
     * Needed because most sources allow two files with the same name in one
     * folder -- uploading a second artist.jpg would leave the folder with two
     * of them and which one wins would be luck.
     */
    suspend fun overwrite(remoteId: String, file: File)

    /**
     * Rename in place, keeping the file and its contents.
     *
     * How artwork is replaced without destroying anything: the old image is
     * renamed out of the way rather than overwritten, so every cover, photo and
     * logo Roam has ever put on the source stays there.
     */
    suspend fun rename(remoteId: String, newName: String)
}
