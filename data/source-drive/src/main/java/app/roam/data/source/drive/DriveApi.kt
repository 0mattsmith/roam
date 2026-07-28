package app.roam.data.source.drive

import app.roam.data.source.RemoteFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: String? = null,
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
) {
    fun toRemoteFile(path: List<String>) = RemoteFile(
        remoteId = id,
        name = name,
        pathSegments = path,
        sizeBytes = size?.toLongOrNull() ?: 0L,
        mimeType = mimeType,
        revision = md5Checksum,
        modifiedAt = 0L,
    )
}

@Serializable data class FileList(val files: List<DriveFile> = emptyList(), val nextPageToken: String? = null)
@Serializable data class StartToken(@SerialName("startPageToken") val startPageToken: String)

/**
 * Metadata for a create or an upload. Defaults are not encoded, so a null
 * mimeType is simply absent and Drive infers it.
 */
@Serializable
data class NewFile(
    val name: String,
    val mimeType: String? = null,
    val parents: List<String>? = null,
)

interface DriveApi {
    @GET("drive/v3/files")
    suspend fun list(
        @Query("q") q: String,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int,
        @Query("pageToken") pageToken: String?,
    ): FileList

    @GET("drive/v3/changes/startPageToken")
    suspend fun startPageToken(): StartToken

    /** Ranged read. Auth comes from DriveAuthInterceptor, per request. */
    @GET("drive/v3/files/{fileId}")
    suspend fun media(
        @Path("fileId") fileId: String,
        @Header("Range") range: String,
        @Query("alt") alt: String = "media",
    ): ResponseBody

    /** Whole-file read. Small assets only -- covers and photos, never audio. */
    @GET("drive/v3/files/{fileId}")
    suspend fun download(
        @Path("fileId") fileId: String,
        @Query("alt") alt: String = "media",
    ): ResponseBody

    /** Metadata-only create. Used for folders. */
    @POST("drive/v3/files")
    suspend fun create(
        @Body body: NewFile,
        @Query("fields") fields: String = "id,name,mimeType",
    ): DriveFile

    /**
     * Multipart upload. The body must be built as `multipart/related` --
     * Retrofit's own @Multipart emits `multipart/form-data`, which this
     * endpoint rejects.
     *
     * Fine for artwork. Phase 4's audio uploads want the resumable protocol
     * instead, since a dropped connection here means starting over.
     */
    @POST("upload/drive/v3/files")
    suspend fun upload(
        @Body body: RequestBody,
        @Query("uploadType") uploadType: String = "multipart",
        @Query("fields") fields: String = "id,name,mimeType",
    ): DriveFile
}
