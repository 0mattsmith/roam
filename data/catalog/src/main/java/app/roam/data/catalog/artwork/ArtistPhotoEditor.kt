package app.roam.data.catalog.artwork

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.roam.core.database.ArtistDao
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.ArtworkSource
import app.roam.core.model.SourceType
import app.roam.data.source.SourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * The manual half of artist photos: export one to the gallery, or replace one
 * with a file the user picked.
 *
 * Deliberately separate from ArtistPhotoWorker. The worker is cautious because
 * it acts on its own -- it never overwrites and never creates a folder. These
 * operations were asked for explicitly, so replacing an existing artist.jpg is
 * the whole point rather than something to avoid.
 */
@Singleton
class ArtistPhotoEditor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val artwork: ArtworkStore,
    private val artists: ArtistDao,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
) {

    /**
     * Copies the stored photo into Pictures/Roam so it shows up in Photos.
     *
     * MediaStore only takes a relative path from Android 10. Below that this
     * would need WRITE_EXTERNAL_STORAGE and a runtime permission flow, which is
     * a lot of machinery for a version this app is unlikely to ever run on.
     */
    suspend fun saveToGallery(artworkId: String, artistName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = artwork.file(artworkId)
                if (!source.exists()) error("That photo has not downloaded yet")

                // The version check wraps the API-29 usage rather than guarding
                // with an early throw, so lint can see it -- a NewApi hit fails
                // lintVitalRelease and takes the release job with it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = ctx.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName(artistName)}.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Roam")
                        // Hides the row until the bytes are there, so the gallery
                        // never shows a half-written image.
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Could not create the file")

                    resolver.openOutputStream(uri)?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("Could not open the file for writing")

                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)

                    "Saved to Pictures/Roam"
                } else {
                    error("Saving to Photos needs Android 10 or newer")
                }
            }
        }

    /**
     * Adopts a photo the user picked. Stored locally for rendering and pushed
     * to the source so it survives a reinstall and reaches the other device.
     */
    suspend fun setUserPhoto(artistId: Long, artistName: String, picked: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                    ?: error("Could not read that image")

                val artworkId = artwork.put(bytes, ArtworkSource.USER)
                    ?: error("That file is not an image Roam can read")

                artists.setArtwork(artistId, artworkId, System.currentTimeMillis())

                val pushed = runCatching { pushToSource(artistName, bytes) }.getOrDefault(false)
                if (pushed) "Photo updated and saved to Drive" else "Photo updated"
            }
        }

    /** Returns true only when the bytes actually reached the source. */
    private suspend fun pushToSource(artistName: String, bytes: ByteArray): Boolean {
        val saved = settings.settings.first()
        if (!saved.saveArtistPhotosToDrive) return false

        // Captured locally: Kotlin will not smart-cast a property declared in
        // another module, because it cannot prove the getter is stable.
        val root = saved.driveFolderId ?: return false
        val provider = providers[SourceType.DRIVE]?.get() ?: return false

        // create = false: a tag name that matches no folder must not cause a
        // stray folder to appear in the user's music library.
        val folderId = provider.resolveFolder(root, listOf(artistName), create = false) ?: return false

        val tmp = File.createTempFile("artist", ".jpg", ctx.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            val existing = provider.findInFolder(folderId, ArtistPhotos.NAMES)
            // Replace in place rather than uploading a second file. Drive
            // permits duplicate names in a folder, so adding one would leave
            // two artist.jpgs and make which one wins a matter of luck.
            if (existing != null) {
                provider.overwrite(existing.remoteId, tmp)
            } else {
                provider.write(root, listOf(artistName), ArtistPhotos.UPLOAD_NAME, tmp)
            }
            true
        } finally {
            tmp.delete()
        }
    }

    /** MediaStore rejects a display name containing a path separator. */
    private fun safeName(artistName: String): String =
        artistName.replace(Regex("[^A-Za-z0-9 _()&,'-]"), "").trim().ifBlank { "Artist" }
}
