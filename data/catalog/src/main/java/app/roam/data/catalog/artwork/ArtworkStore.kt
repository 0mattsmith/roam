package app.roam.data.catalog.artwork

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.roam.core.database.ArtworkDao
import app.roam.core.database.ArtworkEntity
import app.roam.core.model.ArtworkSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Album art on disk, deduplicated by content hash. A 14-track album stores
 * one image, not fourteen. Never a Room BLOB.
 */
@Singleton
class ArtworkStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val dao: ArtworkDao,
) {
    private val dir = File(ctx.filesDir, "artwork").apply { mkdirs() }

    fun file(id: String, size: Int? = null): File =
        if (size == null) File(dir, "$id.jpg") else File(dir, "${id}_$size.jpg")

    /** Returns the artworkId. Re-encodes to JPEG -- some head units will not
     *  decode a PNG APIC frame and show a blank cover. */
    suspend fun put(raw: ByteArray, kind: ArtworkSource): String? {
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scaled = downscale(decoded, MAX_EDGE)
        val jpeg = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        val id = sha256(jpeg)

        if (!dao.exists(id)) {
            file(id).writeBytes(jpeg)
            val thumb = downscale(scaled, THUMB_EDGE)
            ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                .toByteArray().let { file(id, THUMB_EDGE).writeBytes(it) }
            dao.upsert(ArtworkEntity(id, scaled.width, scaled.height, jpeg.size, kind))
        }
        return id
    }

    private fun downscale(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = maxOf(src.width, src.height)
        if (edge <= maxEdge) return src
        val s = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
    }

    private fun sha256(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_EDGE = 1000
        const val THUMB_EDGE = 320
    }
}
