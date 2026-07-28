package app.roam.data.source.drive

import androidx.media3.datasource.DataSource
import app.roam.core.model.SourceType
import app.roam.data.source.Capability
import app.roam.data.source.ChangeSet
import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import app.roam.data.source.SourceTypeKey
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val AUDIO = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "aiff")

@Singleton
class DriveSourceProvider @Inject constructor(
    private val api: DriveApi,
    private val auth: DriveAuth,
    private val dsFactory: DriveDataSourceFactory,
) : SourceProvider {

    override val sourceId: String = SOURCE_ID
    override val capabilities = setOf(Capability.DELTA_SYNC, Capability.RANDOM_ACCESS, Capability.WRITE)

    /**
     * Breadth-first crawl. The field mask is deliberately tight -- the default
     * field set roughly triples the payload on a 10k-file library, and Drive
     * bills you rate limit for it either way.
     */
    /**
     * Parallel breadth-first crawl.
     *
     * The cost here is round-trips, not bandwidth: one files.list per folder at
     * ~250 ms each. A Music/Artist/Album library with 300 albums is ~360
     * sequential requests -- roughly 90 seconds of pure latency. Fanning out
     * across folders collapses that to about a tenth.
     *
     * The semaphore is the whole safety mechanism. Unbounded recursion over a
     * large tree would open hundreds of concurrent connections and earn a
     * 403 userRateLimitExceeded from Drive.
     */
    override fun listAll(root: String): Flow<RemoteFile> = channelFlow {
        val gate = Semaphore(CONCURRENCY)

        suspend fun crawl(folderId: String, path: List<String>) {
            val subfolders = mutableListOf<Pair<String, List<String>>>()
            var pageToken: String? = null
            do {
                val page = gate.withPermit {
                    api.list(
                        q = "'$folderId' in parents and trashed = false",
                        fields = FIELD_MASK,
                        pageSize = 1000,
                        pageToken = pageToken,
                    )
                }
                for (f in page.files) {
                    if (f.mimeType == FOLDER_MIME) {
                        subfolders += f.id to (path + f.name)
                    } else if (f.name.substringAfterLast('.', "").lowercase() in AUDIO) {
                        send(f.toRemoteFile(path))
                    }
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)

            // Structured concurrency: this returns only once every descendant
            // has been walked, so the flow completes when the tree is done.
            coroutineScope {
                subfolders.forEach { (id, p) -> launch { crawl(id, p) } }
            }
        }

        crawl(root, emptyList())
    }

    /** Find a top-level folder by name, for the "which folder?" step in settings. */
    suspend fun findFolder(name: String, parent: String = "root"): DriveFile? =
        api.list(
            q = "name = '${name.replace("'", "\\'")}' and mimeType = '$FOLDER_MIME' " +
                "and '$parent' in parents and trashed = false",
            fields = FIELD_MASK,
            pageSize = 10,
            pageToken = null,
        ).files.firstOrNull()

    override suspend fun listChanges(token: String?): ChangeSet {
        // TODO(phase3): page changes.list from startPageToken. Until then the
        //   sync engine falls back to listAll + local diff, which is correct,
        //   just slower.
        val start = token ?: api.startPageToken().startPageToken
        return ChangeSet(upserted = emptyList(), removedIds = emptyList(), nextToken = start)
    }

    override fun dataSourceFactory(): DataSource.Factory = dsFactory

    override suspend fun readRange(remoteId: String, offset: Long, length: Long): ByteArray =
        api.media(
            fileId = remoteId,
            range = "bytes=$offset-${offset + length - 1}",
        ).bytes()

    override suspend fun write(pathSegments: List<String>, fileName: String, file: File): String {
        TODO("Phase 4: resolve-or-create folders (cache the ids), then resumable upload")
    }

    companion object {
        const val SOURCE_ID = "drive"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        /** Concurrent files.list calls. Above ~12 Drive starts rate-limiting. */
        const val CONCURRENCY = 8
        const val FIELD_MASK = "nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DriveSourceModule {
    @Binds @IntoMap @SourceTypeKey(SourceType.DRIVE)
    abstract fun bindDriveProvider(impl: DriveSourceProvider): SourceProvider
}
