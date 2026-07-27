package app.roam.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateDownloader @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val installer: UpdateInstaller,
) {
    private val client = OkHttpClient()

    /**
     * Downloads the APK and verifies it against the .sha256 sidecar the release
     * workflow publishes beside it. A corrupted or substituted APK must never
     * reach PackageInstaller.
     */
    suspend fun download(
        update: AvailableUpdate,
        onProgress: (Int) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }        // one update at a time
        val apk = File(dir, update.apkName)

        client.newCall(Request.Builder().url(update.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("Download failed: HTTP ${response.code}")
            val body = response.body ?: error("Download failed: empty body")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes

            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }

        update.sha256Url?.let { url ->
            val expected = client.newCall(Request.Builder().url(url).build()).execute().use {
                if (!it.isSuccessful) error("Could not fetch checksum: HTTP ${it.code}")
                it.body?.string().orEmpty().trim().substringBefore(' ')
            }
            installer.verify(apk, expected)
        } ?: error("No checksum published for ${update.apkName} - refusing to install")

        apk
    }
}
