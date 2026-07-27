package app.roam.data.source.drive

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * ExoPlayer streams Drive files straight from
 *   GET /drive/v3/files/{id}?alt=media
 * which honours HTTP Range, so seeking works natively.
 *
 * The critical detail is ResolvingDataSource: the token is stamped on EVERY
 * request including each individual range request. Setting it once per track
 * means playback dies mid-song when the token rolls over.
 */
class DriveDataSourceFactory @Inject constructor(
    private val auth: DriveAuth,
    private val client: OkHttpClient,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val upstream = OkHttpDataSource.Factory(client)
        return ResolvingDataSource.Factory(upstream) { spec: DataSpec ->
            val fileId = spec.uri.lastPathSegment.orEmpty()
            spec.buildUpon()
                .setUri("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .setHttpRequestHeaders(
                    spec.httpRequestHeaders + mapOf(
                        "Authorization" to "Bearer " + runBlocking { auth.accessToken() },
                    ),
                )
                .build()
        }.createDataSource()
    }
}
