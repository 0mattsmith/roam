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

    /**
     * Clears the album's cover and every track's copy of it.
     *
     * Local only, deliberately: cover.jpg stays on Drive. Removing a picture
     * from a list is a different act from deleting the user's file, and only
     * one of those is undoable.
     */
    suspend fun clearAlbumArtwork(albumId: Long): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            albums.clearArtwork(albumId)
            tracks.clearArtworkForAlbum(albumId)
            "Cover removed (cover.jpg left on Drive)"
        }
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

    /** One retired image, as the history list needs it. */
    data class PastImage(val remoteId: String, val name: String)

    /**
     * Numbered images the folder has held before, oldest first.
     *
     * Empty unless something has actually been replaced, so the UI can hide
     * the whole section rather than showing an empty shelf.
     */
    suspend fun previousImages(
        pathSegments: List<String>,
        currentName: String,
    ): List<PastImage> = withContext(Dispatchers.IO) {
        val folder = folderFor(pathSegments) ?: return@withContext emptyList()
        val provider = providers[SourceType.DRIVE]?.get() ?: return@withContext emptyList()
        val images = runCatching { provider.listImages(folder) }.getOrDefault(emptyList())
        val names = images.map { it.name }
        ArtworkFiles.archivedVersions(currentName, names).mapNotNull { name ->
            images.firstOrNull { it.name == name }?.let { PastImage(it.remoteId, it.name) }
        }
    }

    /**
     * Pulls a retired image into the local store so it can be shown.
     *
     * Only called when the history is actually opened -- these live on Drive,
     * and downloading every past cover just to draw a section nobody expanded
     * would be rude on mobile data. Ids are content hashes, so a second look
     * costs nothing.
     */
    suspend fun cachePastImage(remoteId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val provider = providers[SourceType.DRIVE]?.get() ?: return@runCatching null
            artwork.put(provider.read(remoteId), ArtworkSource.USER)
        }.getOrNull()
    }

    /** Copies a retired image into Pictures/Roam without disturbing the source. */
    suspend fun savePastImage(remoteId: String, displayName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val provider = providers[SourceType.DRIVE]?.get() ?: error("Drive not connected")
                val bytes = provider.read(remoteId)
                val artworkId = artwork.put(bytes, ArtworkSource.USER)
                    ?: error("That file is not an image Roam can read")
                saveToGallery(artworkId, displayName).getOrThrow()
            }
        }

    /**
     * Makes a retired image current again.
     *
     * Goes through the normal replace path rather than renaming the archived
     * file back, so the live name stays cover.jpg and the numbering never has
     * gaps. That does leave two copies of the same picture in the folder --
     * the acceptable cost of never destroying anything.
     */
    suspend fun restoreAlbumCover(
        albumId: Long,
        albumArtist: String,
        albumTitle: String,
        remoteId: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val provider = providers[SourceType.DRIVE]?.get() ?: error("Drive not connected")
            val bytes = provider.read(remoteId)
            val artworkId = artwork.put(bytes, ArtworkSource.USER)
                ?: error("That file is not an image Roam can read")

            albums.setArtwork(albumId, artworkId)
            tracks.setArtworkForAlbum(albumId, artworkId)
            pushToSource(
                pathSegments = listOf(albumArtist, albumTitle),
                fileName = ArtworkFiles.ALBUM_UPLOAD_NAME,
                candidates = ArtworkFiles.ALBUM_NAMES,
                bytes = bytes,
            )
            "Cover restored"
        }
    }

    private suspend fun folderFor(pathSegments: List<String>): String? {
        val saved = settings.settings.first()
        val root = saved.driveFolderId ?: return null
        val provider = providers[SourceType.DRIVE]?.get() ?: return null
        return runCatching { provider.resolveFolder(root, pathSegments, create = false) }.getOrNull()
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
            val images = provider.listImages(folderId)
            val existing = images.firstOrNull { it.name.lowercase() in candidates.map { c -> c.lowercase() } }

            // Number the outgoing file rather than overwriting it: cover.jpg
            // becomes cover1.jpg, then cover2.jpg, and the live image is always
            // plain cover.jpg. Nothing that points at it ever has to change,
            // and every version the folder has held is still sitting there.
            if (existing != null) {
                provider.rename(
                    existing.remoteId,
                    ArtworkFiles.nextArchiveName(existing.name, images.map { it.name }),
                )
            }
            provider.write(root, pathSegments, fileName, tmp)
            true
        } finally {
            tmp.delete()
        }
    }

    /** MediaStore rejects a display name containing a path separator. */
    private fun safeName(displayName: String): String =
        displayName.replace(Regex("[^A-Za-z0-9 _()&,'-]"), "").trim().ifBlank { "Artwork" }
}
