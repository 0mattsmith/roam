package app.roam.data.catalog.artwork

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.database.ArtistDao
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.ArtworkSource
import app.roam.core.model.Ids
import app.roam.core.model.SourceType
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import javax.inject.Provider

/**
 * Gives every artist a photo, preferring the library over the internet.
 *
 * Order is deliberate:
 *   1. artist.jpg / folder.jpg already in the artist's folder on the source
 *   2. Deezer's public search API, and the result is written back as artist.jpg
 *
 * So Deezer is consulted at most once per artist ever, across reinstalls and
 * across devices, and anything the user drops in a folder by hand always wins.
 * Deezer needs no key or token, which is why it beat Discogs -- Discogs only
 * returns image URLs to authenticated requests.
 *
 * The photo is still cached locally and served from ArtworkProvider. Reading it
 * from the source at browse time would break invariant 1 and, worse, would mean
 * blank faces in the car exactly when signal is poor.
 */
@HiltWorker
class ArtistPhotoWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val artists: ArtistDao,
    private val artwork: ArtworkStore,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
) : CoroutineWorker(ctx, params) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        val saved = settings.settings.first()

        // Captured locally: Kotlin will not smart-cast a property declared in
        // another module, because it cannot prove the getter is stable.
        val root = saved.driveFolderId
        val provider = providers[SourceType.DRIVE]?.get()
        val mayUpload = saved.saveArtistPhotosToDrive && !isMetered()

        var fromSource = 0
        var fromDeezer = 0
        var attempted = 0

        while (true) {
            val batch = artists.artistsNeedingPhotos(BATCH)
            if (batch.isEmpty()) break

            for (row in batch) {
                attempted++
                val now = System.currentTimeMillis()

                if (isPlaceholder(row.name)) {
                    artists.markPhotoAttempted(listOf(row.id), now)
                    continue
                }

                // Resolved without create: an artist whose tag name does not
                // match any folder must not cause a stray folder to appear in
                // someone's music library.
                val folderId =
                    if (root != null && provider != null) {
                        runCatching { provider.resolveFolder(root, listOf(row.name), create = false) }
                            .getOrNull()
                    } else null

                var artworkId: String? = null

                if (folderId != null && provider != null) {
                    artworkId = runCatching { fromFolder(provider, folderId) }.getOrNull()
                    if (artworkId != null) fromSource++
                }

                if (artworkId == null) {
                    val photo = runCatching { fetchFromDeezer(row.name) }.getOrNull()
                    if (photo != null) {
                        artworkId = artwork.put(photo, ArtworkSource.DEEZER)
                        if (artworkId != null) fromDeezer++

                        // Only when the folder already exists, so this can add a
                        // file but never a directory.
                        if (mayUpload && folderId != null && provider != null && root != null) {
                            runCatching { upload(provider, root, row.name, photo) }
                        }
                    }
                    // Deezer is rate limited per IP. Sequential with a small gap
                    // is plenty -- nothing is waiting on this.
                    delay(REQUEST_GAP_MS)
                }

                artists.setArtwork(row.id, artworkId, now)
            }

            setProgress(progress(fromSource, fromDeezer, attempted))
        }

        return Result.success(progress(fromSource, fromDeezer, attempted))
    }

    private fun progress(fromSource: Int, fromDeezer: Int, attempted: Int) =
        workDataOf(
            KEY_FROM_SOURCE to fromSource,
            KEY_FROM_DEEZER to fromDeezer,
            KEY_ATTEMPTED to attempted,
        )

    /** A photo already sitting in the artist's folder. Always wins. */
    private suspend fun fromFolder(provider: SourceProvider, folderId: String): String? {
        val existing = provider.findInFolder(folderId, ArtistPhotos.NAMES) ?: return null
        val bytes = provider.read(existing.remoteId)
        return artwork.put(bytes, ArtworkSource.FOLDER_JPG)
    }

    private suspend fun upload(
        provider: SourceProvider,
        root: String,
        artistName: String,
        photo: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("artist", ".jpg", applicationContext.cacheDir)
        try {
            tmp.writeBytes(photo)
            provider.write(root, listOf(artistName), ArtistPhotos.UPLOAD_NAME, tmp)
        } finally {
            tmp.delete()
        }
    }

    private suspend fun fetchFromDeezer(name: String): ByteArray? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(name, "UTF-8")
        val url = "https://api.deezer.com/search/artist?q=$query&limit=5"

        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string() ?: return@withContext null
        }

        val results = JSONObject(body).optJSONArray("data") ?: return@withContext null
        val wanted = Ids.normalise(name)

        // Deezer happily returns near-misses for a name it does not know, so
        // only a normalised exact match counts. A wrong face is worse than none.
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            if (Ids.normalise(entry.optString("name")) != wanted) continue

            val picture = entry.optString("picture_xl").takeIf { it.isNotBlank() }
                ?: entry.optString("picture_big").takeIf { it.isNotBlank() }
                ?: continue

            return@withContext client.newCall(Request.Builder().url(picture).build()).execute().use {
                if (!it.isSuccessful) null else it.body?.bytes()
            }
        }
        null
    }

    /**
     * Uploads are polite, not urgent. Downloading a photo on mobile data is a
     * few KB; pushing a few hundred back up is not something to do without
     * being asked.
     */
    private fun isMetered(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Bucket names from path inference; searching for them returns nonsense. */
    private fun isPlaceholder(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in setOf("unknown artist", "unknown", "various artists", "va", "soundtrack", "")
    }

    companion object {
        const val NAME = "roam_artist_photos"
        const val KEY_FROM_SOURCE = "fromSource"
        const val KEY_FROM_DEEZER = "fromDeezer"
        const val KEY_ATTEMPTED = "attempted"

        private const val BATCH = 40
        private const val REQUEST_GAP_MS = 250L

        fun enqueue(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<ArtistPhotoWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
