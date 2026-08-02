package app.roam.data.catalog.artwork

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File

/**
 * Android Auto and AAOS require artwork as a local content:// URI. Passing
 * bitmaps is unsupported on AAOS and blows the 1 MB Binder limit on browse
 * results, which manifests as the car UI hanging.
 *
 *   content://app.roam.player.artwork/art/<artworkId>?size=320
 *
 * Return the URI immediately even if the file is not ready -- the car shows
 * its own loading state and re-requests.
 */
class ArtworkProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps { fun artworkStore(): ArtworkStore }

    private val store: ArtworkStore by lazy {
        EntryPointAccessors.fromApplication(requireNotNull(context), Deps::class.java).artworkStore()
    }

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val id = uri.lastPathSegment ?: return null
        val size = uri.getQueryParameter("size")?.toIntOrNull()
        var f: File = store.file(id, size)
        if (!f.exists()) f = store.file(id)          // fall back to full size
        if (!f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Logos are PNG so their transparency survives; covers stay JPEG. */
    override fun getType(uri: Uri): String {
        val id = uri.lastPathSegment ?: return "image/jpeg"
        val size = uri.getQueryParameter("size")?.toIntOrNull()
        return if (store.file(id, size).extension.equals("png", ignoreCase = true)) {
            "image/png"
        } else {
            "image/jpeg"
        }
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun uri(ctx: Context, artworkId: String, size: Int? = null): Uri =
            Uri.parse("content://${ctx.packageName}.artwork/art/$artworkId")
                .buildUpon().apply { size?.let { appendQueryParameter("size", it.toString()) } }
                .build()
    }
}
