package app.roam.data.source.drive

import androidx.media3.datasource.DataSource
import app.roam.data.source.Capability
import app.roam.data.source.ChangeSet
import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff")

class DriveSourceProvider(
    override val sourceId: String,
    private val api: DriveApi,
    private val auth: DriveAuth,
    private val dataSourceFactory: DriveDataSourceFactory,
) : SourceProvider {

    override val capabilities = setOf(Capability.DELTA_SYNC, Capability.RANDOM_ACCESS, Capability.WRITE)

    /**
     * Breadth-first crawl. Keep the field mask tight -- asking for the default
     * field set roughly triples the payload on a 10k-file library.
     */
    override fun listAll(root: String): Flow<RemoteFile> = flow {
        val queue = ArrayDeque(listOf(root to emptyList<String>()))
        while (queue.isNotEmpty()) {
            val (folderId, path) = queue.removeFirst()
            var pageToken: String? = null
            do {
                val page = api.list(
                    q = "'$folderId' in parents and trashed = false",
                    fields = "nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)",
                    pageSize = 1000,
                    pageToken = pageToken,
                )
                for (f in page.files) {
                    if (f.mimeType == FOLDER_MIME) {
                        queue.addLast(f.id to (path + f.name))
                    } else if (f.name.substringAfterLast('.', "").lowercase() in AUDIO) {
                        emit(f.toRemoteFile(path))
                    }
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
        }
    }

    /**
     * Drive Changes API. This is what makes the 6-hourly sync take seconds
     * instead of re-walking the entire tree.
     */
    override suspend fun listChanges(token: String?): ChangeSet {
        val start = token ?: api.startPageToken().startPageToken
        TODO("Phase 3: page through changes.list(pageToken = start)")
    }

    override fun dataSourceFactory(): DataSource.Factory = dataSourceFactory

    /** Head-range read for tag extraction. See TagExtractor. */
    override suspend fun readRange(remoteId: String, offset: Long, length: Long): ByteArray =
        api.media(
            fileId = remoteId,
            range = "bytes=$offset-${offset + length - 1}",
            authorization = "Bearer " + auth.accessToken(),
        ).bytes()

    override suspend fun write(pathSegments: List<String>, fileName: String, file: File): String {
        TODO("Phase 4: resolve-or-create folders (cache ids!), then resumable upload")
    }

    companion object { const val FOLDER_MIME = "application/vnd.google-apps.folder" }
}
