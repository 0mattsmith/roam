package app.roam.data.catalog.artwork

import android.content.Context
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
import app.roam.core.model.ArtworkSource
import app.roam.core.model.Ids
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fills in artist photos from Deezer's public search API.
 *
 * Deezer needs no key, no signup and no token, which is why it beat Discogs
 * here -- Discogs only returns image URLs to authenticated requests, so it
 * would have meant generating and pasting a personal access token before a
 * single photo appeared.
 *
 * Tags do not carry artist photos, so unlike covers this genuinely has to come
 * from somewhere external.
 */
@HiltWorker
class ArtistPhotoWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val artists: ArtistDao,
    private val artwork: ArtworkStore,
) : CoroutineWorker(ctx, params) {

    private val client = OkHttpClient()

    override suspend fun doWork(): Result {
        var found = 0
        var attempted = 0

        while (true) {
            val batch = artists.artistsNeedingPhotos(BATCH)
            if (batch.isEmpty()) break

            val now = System.currentTimeMillis()
            for (row in batch) {
                attempted++

                if (isPlaceholder(row.name)) {
                    artists.markPhotoAttempted(listOf(row.id), now)
                    continue
                }

                val artworkId = runCatching { fetchPhoto(row.name) }.getOrNull()
                artists.setArtwork(row.id, artworkId, now)
                if (artworkId != null) found++

                // Deezer throttles per IP. Sequential with a small gap is
                // plenty here: this is background work nobody is waiting on.
                delay(REQUEST_GAP_MS)
            }

            setProgress(workDataOf(KEY_FOUND to found, KEY_ATTEMPTED to attempted))
        }

        return Result.success(workDataOf(KEY_FOUND to found, KEY_ATTEMPTED to attempted))
    }

    private suspend fun fetchPhoto(name: String): String? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(name, "UTF-8")
        val url = "https://api.deezer.com/search/artist?q=$query&limit=5"

        val body = client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.body?.string() ?: return@withContext null
        }

        val results = JSONObject(body).optJSONArray("data") ?: return@withContext null
        val wanted = Ids.normalise(name)

        // Deezer happily returns near-misses for an unknown name, so only a
        // normalised exact match counts. A wrong face is worse than no face.
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            if (Ids.normalise(entry.optString("name")) != wanted) continue

            val picture = entry.optString("picture_xl").takeIf { it.isNotBlank() }
                ?: entry.optString("picture_big").takeIf { it.isNotBlank() }
                ?: continue

            val bytes = client.newCall(Request.Builder().url(picture).build()).execute().use {
                if (!it.isSuccessful) return@withContext null
                it.body?.bytes() ?: return@withContext null
            }
            return@withContext artwork.put(bytes, ArtworkSource.DEEZER)
        }
        null
    }

    /** Bucket names from path inference; searching for them returns nonsense. */
    private fun isPlaceholder(name: String): Boolean {
        val n = name.trim().lowercase()
        return n in setOf("unknown artist", "unknown", "various artists", "va", "soundtrack", "")
    }

    companion object {
        const val NAME = "roam_artist_photos"
        const val KEY_FOUND = "found"
        const val KEY_ATTEMPTED = "attempted"

        private const val BATCH = 40
        /** Deezer rate-limits per IP; this stays comfortably underneath. */
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
