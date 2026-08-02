package app.roam.data.catalog.artwork

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.roam.core.database.AlbumDao
import app.roam.core.database.ArtistDao
import app.roam.core.database.TrackDao
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
 * The manual half of artwork: export one to the gallery, or replace one with a
 * file the user picked.
 *
 * Deliberately separate from the automatic passes. Those are cautious because
 * they act on their own -- they never overwrite and never create a folder.
 * These operations were asked for explicitly, so replacing an existing file is
 * the whole point rather than something to avoid.
 */
@Singleton
class ArtworkEditor @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val artwork: ArtworkStore,
    private val artists: ArtistDao,
    private val albums: AlbumDao,
    private val tracks: TrackDao,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
) {

    /**
     * Copies a stored image into Pictures/Roam so it shows up in Photos.
     *
     * MediaStore only takes a relative path from Android 10. Below that this
     * would need WRITE_EXTERNAL_STORAGE and a runtime permission flow, which is
     * a lot of machinery for a version this app is unlikely to ever run on.
     */
    suspend fun saveToGallery(artworkId: String, displayName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = artwork.file(artworkId)
                if (!source.exists()) error("That image has not downloaded yet")

                // The version check wraps the API-29 usage rather than guarding
                // with an early throw, so lint can see it -- a NewApi hit fails
                // lintVitalRelease and takes the release job with it.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = ctx.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${safeName(displayName)}.jpg")
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

    suspend fun setArtistPhoto(artistId: Long, artistName: String, picked: Uri): Result<String> =
        adopt(picked) { artworkId, bytes ->
            artists.setArtwork(artistId, artworkId, System.currentTimeMillis())
            pushToSource(
                pathSegments = listOf(artistName),
                fileName = ArtworkFiles.ARTIST_UPLOAD_NAME,
                candidates = ArtworkFiles.ARTIST_NAMES,
                bytes = bytes,
            )
        }

    /**
     * Album art normally comes from the tags embedded in each track. Replacing
     * it writes cover.jpg beside the music rather than rewriting the APIC frame
     * in every file -- a folder cover is the standard convention, and rewriting
     * the tracks would mean downloading and re-uploading the whole album.
     *
     * The row survives a re-tag on its own: TagWorker only ever calls
     * setArtworkIfMissing, which will not touch an album that already has one.
     */
    suspend fun setAlbumCover(
        albumId: Long,
        albumArtist: String,
        albumTitle: String,
        picked: Uri,
    ): Result<String> =
        adopt(picked) { artworkId, bytes ->
            albums.setArtwork(albumId, artworkId)
            pushToSource(
                pathSegments = listOf(albumArtist, albumTitle),
                fileName = ArtworkFiles.ALBUM_UPLOAD_NAME,
                candidates = ArtworkFiles.ALBUM_NAMES,
                bytes = bytes,
            )
        }

    /**
     * One picture for the album row and every track in it.
     *
     * setAlbumCover alone changes what the album shows; a track still carries
     * its own artworkId, which is what an ungrouped list draws. Someone asking
     * to change "the album's artwork" means both.
     */
    suspend fun setAlbumArtworkEverywhere(
        albumId: Long,
        albumArtist: String,
        albumTitle: String,
        picked: Uri,
    ): Result<String> =
        adopt(picked) { artworkId, bytes ->
            albums.setArtwork(albumId, artworkId)
            tracks.setArtworkForAlbum(albumId, artworkId)
            pushToSource(
                pathSegments = listOf(albumArtist, albumTitle),
                fileName = ArtworkFiles.ALBUM_UPLOAD_NAME,
                candidates = ArtworkFiles.ALBUM_NAMES,
                bytes = bytes,
            )
        }

    /**
     * Artwork for a single track. Unlike an album cover this is not written
     * back to the source: a per-track picture belongs in that file's APIC
     * frame, and a cover.jpg beside it would change the whole album.
     */
    suspend fun setTrackArtwork(trackId: Long, picked: Uri): Result<String> =
        adopt(picked) { artworkId, _ ->
            tracks.setArtwork(trackId, artworkId)
            false
        }

    /** Which image an artist is drawn with. Purely a display preference. */
    suspend fun setPreferLogo(artistId: Long, preferLogo: Boolean): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                artists.setPreferLogo(artistId, preferLogo)
                if (preferLogo) "Showing the logo" else "Showing the photo"
            }
        }

    /** Shared shape: decode, store, hand off to the caller's write, report. */
    private suspend fun adopt(
        picked: Uri,
        apply: suspend (artworkId: String, bytes: ByteArray) -> Boolean,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(picked)?.use { it.readBytes() }
                ?: error("Could not read that image")

            val artworkId = artwork.put(bytes, ArtworkSource.USER)
                ?: error("That file is not an image Roam can read")

            val pushed = runCatching { apply(artworkId, bytes) }.getOrDefault(false)
            if (pushed) "Updated and saved to Drive" else "Updated"
        }
    }

    /** Returns true only when the bytes actually reached the source. */
    private suspend fun pushToSource(
        pathSegments: List<String>,
        fileName: String,
        candidates: List<String>,
        bytes: ByteArray,
    ): Boolean {
        val saved = settings.settings.first()
        if (!saved.saveArtistPhotosToDrive) return false

        // Captured locally: Kotlin will not smart-cast a property declared in
        // another module, because it cannot prove the getter is stable.
        val root = saved.driveFolderId ?: return false
        val provider = providers[SourceType.DRIVE]?.get() ?: return false

        // create = false: a tag name that matches no folder must not cause a
        // stray folder to appear in the user's music library.
        val folderId = provider.resolveFolder(root, pathSegments, create = false) ?: return false

        val tmp = File.createTempFile("artwork", ".jpg", ctx.cacheDir)
        return try {
            tmp.writeBytes(bytes)
            val existing = provider.findInFolder(folderId, candidates)
            // Replace in place rather than uploading a second file. Drive
            // permits duplicate names in a folder, so adding one would leave
            // two covers and make which one wins a matter of luck.
            if (existing != null) {
                provider.overwrite(existing.remoteId, tmp)
            } else {
                provider.write(root, pathSegments, fileName, tmp)
            }
            true
        } finally {
            tmp.delete()
        }
    }

    /** MediaStore rejects a display name containing a path separator. */
    private fun safeName(displayName: String): String =
        displayName.replace(Regex("[^A-Za-z0-9 _()&,'-]"), "").trim().ifBlank { "Artwork" }
}
