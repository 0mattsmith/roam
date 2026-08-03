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
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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

    /** "<parentId>/<name>" -> folder id. Drive folder ids are stable. */
    private val folderIds = ConcurrentHashMap<String, String>()

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

    override suspend fun read(remoteId: String): ByteArray = api.download(remoteId).bytes()

    /**
     * One files.list per missing level, then cached for the process lifetime.
     * The artist-photo pass resolves a folder to look for artist.jpg and then
     * reuses the same id to upload one, so caching halves the request count.
     */
    override suspend fun resolveFolder(
        root: String,
        pathSegments: List<String>,
        create: Boolean,
    ): String? {
        var parent = root
        for (segment in pathSegments) {
            val key = "$parent/$segment"
            val cached = folderIds[key]
            if (cached != null) {
                parent = cached
                continue
            }
            val existing = api.list(
                q = "'$parent' in parents and mimeType = '$FOLDER_MIME' " +
                    "and name = '${escape(segment)}' and trashed = false",
                fields = FIELD_MASK,
                pageSize = 10,
                pageToken = null,
            ).files.firstOrNull()

            val existingId = existing?.id
            val id = when {
                existingId != null -> existingId
                create -> api.create(NewFile(segment, FOLDER_MIME, listOf(parent))).id
                else -> return null
            }
            folderIds[key] = id
            parent = id
        }
        return parent
    }

    /**
     * Asks for the folder's images and matches the name locally, because
     * Drive's `name =` is case-sensitive and real libraries contain both
     * `folder.jpg` and `Folder.jpg`. Same single request either way.
     */
    override suspend fun findInFolder(folderId: String, names: List<String>): RemoteFile? {
        val wanted = names.map { it.lowercase() }.toSet()
        return listImages(folderId).firstOrNull { it.name.lowercase() in wanted }
    }

    override suspend fun listImages(folderId: String): List<RemoteFile> =
        api.list(
            q = "'$folderId' in parents and mimeType contains 'image/' and trashed = false",
            fields = FIELD_MASK,
            pageSize = 200,
            pageToken = null,
        ).files.map { it.toRemoteFile(emptyList()) }

    override suspend fun write(
        root: String,
        pathSegments: List<String>,
        fileName: String,
        file: File,
    ): String {
        val parent = resolveFolder(root, pathSegments, create = true)
            ?: error("could not resolve ${pathSegments.joinToString("/")}")

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(
                // The explicit serializer is the member overload, stable since
                // 1.0. The reified form is an extension that has moved between
                // releases and would need an import that comes and goes.
                Json.encodeToString(NewFile.serializer(), NewFile(fileName, parents = listOf(parent)))
                    .toRequestBody("application/json; charset=UTF-8".toMediaType())
            )
            .addPart(file.asRequestBody(mimeFor(fileName).toMediaType()))
            .build()

        return api.upload(body).id
    }

    override suspend fun rename(remoteId: String, newName: String) {
        api.updateMetadata(remoteId, NewFile(newName))
    }

    override suspend fun overwrite(remoteId: String, file: File) {
        api.updateMedia(remoteId, file.asRequestBody(mimeFor(file.name).toMediaType()))
    }

    /**
     * Drive query strings are single-quoted, so a name like "Guns N' Roses"
     * breaks the query unless both the backslash and the quote are escaped --
     * backslash first, or the escapes escape each other.
     */
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun mimeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
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
