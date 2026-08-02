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

    /**
     * Probes both extensions. Covers are JPEG, but a band logo is a
     * transparent PNG and re-encoding one to JPEG flattens the transparency to
     * black -- a black rectangle with the band's name on it, which is worse
     * than showing nothing.
     */
    fun file(id: String, size: Int? = null): File {
        val base = if (size == null) id else "${id}_$size"
        val jpeg = File(dir, "$base.jpg")
        return if (jpeg.exists()) jpeg else File(dir, "$base.png")
    }

    /**
     * Returns the artworkId. Re-encodes to JPEG by default -- some head units
     * will not decode a PNG APIC frame and show a blank cover. Set [keepAlpha]
     * for a logo, where the transparency is the whole point.
     *
     * [maxEdge] bounds the stored master. Album covers want the full
     * [MAX_EDGE] because the now-playing screen shows them near full width;
     * artist photos are only ever a 44dp avatar, so storing a 1000px master
     * for one wastes about 120KB each for pixels nothing will ever read.
     */
    suspend fun put(
        raw: ByteArray,
        kind: ArtworkSource,
        maxEdge: Int = MAX_EDGE,
        keepAlpha: Boolean = false,
    ): String? {
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val scaled = downscale(decoded, maxEdge)
        val format = if (keepAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        val extension = if (keepAlpha) "png" else "jpg"
        val encoded = ByteArrayOutputStream().also { scaled.compress(format, 88, it) }.toByteArray()
        val id = sha256(encoded)

        if (!dao.exists(id)) {
            File(dir, "$id.$extension").writeBytes(encoded)
            // A separate thumb is pointless once the master is already thumb
            // sized. ArtworkProvider falls back from the sized file to the
            // master, so a ?size=320 request still resolves.
            if (maxOf(scaled.width, scaled.height) > THUMB_EDGE) {
                val thumb = downscale(scaled, THUMB_EDGE)
                ByteArrayOutputStream().also { thumb.compress(format, 85, it) }
                    .toByteArray().let { File(dir, "${id}_$THUMB_EDGE.$extension").writeBytes(it) }
            }
            dao.upsert(ArtworkEntity(id, scaled.width, scaled.height, encoded.size, kind))
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
