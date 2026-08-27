package app.roam.data.catalog.metadata

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.database.AlbumDao
import app.roam.core.model.SourceType
import app.roam.data.source.FileKind
import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import javax.inject.Provider

/**
 * Reads the library's metadata files on request, and only on request.
 *
 * Deliberately not part of sync. These documents are hand-authored -- by an
 * external editor, on a schedule nobody but their author knows -- so "Roam
 * noticed something and acted" is the wrong shape entirely. Somebody presses a
 * button when they have finished editing.
 *
 * Three modes, and the difference between the first two is what they trust:
 *
 * **Quick** believes the revision cache: a document whose bytes, folder and
 * track count all match what was applied last time is skipped without being
 * fetched. Fast, and right whenever Roam is the only thing that has touched it.
 *
 * **Full** believes nothing. Every document is fetched and re-applied. That is
 * the one to reach for after a session with another editor, because "unchanged
 * since Roam last looked" is a claim about Roam's bookkeeping rather than about
 * the files.
 *
 * **Create** is the other direction, offered when a library has no documents at
 * all: write what Roam already knows into files, so there is something to
 * consolidate FROM.
 */
@HiltWorker
class ConsolidateWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
    private val applier: DocApplier,
    private val writer: DocWriter,
    private val albums: AlbumDao,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(KEY_MODE) ?: MODE_QUICK
        val root = inputData.getString(KEY_ROOT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "No music folder chosen"))
        val provider = providers[SourceType.DRIVE]?.get()
            ?: return Result.failure(workDataOf(KEY_ERROR to "No source connected"))

        return if (mode == MODE_CREATE) create() else consolidate(provider, root, mode == MODE_FULL)
    }

    /**
     * Finds every document, then applies them.
     *
     * The crawl is unavoidable in both modes: knowing which documents CHANGED
     * still means listing the folders they live in. What Quick saves is the
     * fetch and the parse of each unchanged file, which on a large library is
     * several hundred requests rather than several hundred listings.
     */
    private suspend fun consolidate(
        provider: SourceProvider,
        root: String,
        force: Boolean,
    ): Result {
        var failure: Throwable? = null
        val documents = provider.listAll(root)
            .catch { failure = it }
            .filter { it.kind == FileKind.DOCUMENT }
            .toList()

        failure?.let { cause ->
            // Same reasoning as the crawl's own failure path: applying a
            // partial listing would let absence look like deletion, and a
            // document that simply was not reached is not a document that is
            // gone.
            val reason = cause.message ?: cause::class.simpleName ?: "Could not read the library"
            return Result.failure(workDataOf(KEY_ERROR to reason))
        }

        setProgress(workDataOf(KEY_FOUND to documents.size))

        if (documents.isEmpty()) {
            // Not a failure. A library with no documents is the normal state
            // before anybody has written any, and the UI offers to make some.
            return Result.success(workDataOf(KEY_FOUND to 0, KEY_APPLIED to 0))
        }

        val report = applier.apply(provider, documents, force = force)
        return Result.success(
            workDataOf(
                KEY_FOUND to report.found,
                KEY_READ to report.read,
                KEY_APPLIED to report.applied,
                KEY_UNREADABLE to report.unreadable,
            )
        )
    }

    /**
     * Writes documents for a library that has none.
     *
     * One album at a time with progress, because this is the slow direction:
     * a file per track plus an index per album, and somebody watching a
     * spinner with no number attached assumes it has hung.
     */
    private suspend fun create(): Result {
        val all = albums.allIds()
        var written = 0
        var skipped = 0

        all.forEachIndexed { index, albumId ->
            writer.writeAlbum(albumId).fold(
                { report ->
                    written += report.filesWritten
                    skipped += report.skippedGuesses
                },
                { skipped++ },
            )
            setProgress(workDataOf(KEY_DONE to index + 1, KEY_TOTAL to all.size))
        }

        return Result.success(
            workDataOf(KEY_DONE to all.size, KEY_TOTAL to all.size, KEY_WRITTEN to written)
        )
    }

    companion object {
        const val NAME = "roam_consolidate"
        const val KEY_MODE = "mode"
        const val KEY_ROOT_ID = "root_id"
        const val KEY_FOUND = "found"
        const val KEY_READ = "read"
        const val KEY_APPLIED = "applied"
        const val KEY_UNREADABLE = "unreadable"
        const val KEY_WRITTEN = "written"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        const val MODE_QUICK = "quick"
        const val MODE_FULL = "full"
        const val MODE_CREATE = "create"

        /**
         * REPLACE rather than KEEP: pressing Full scan while a Quick scan is
         * running means the person has decided the quick one is not what they
         * wanted, and leaving it to finish first would look like the button did
         * nothing.
         */
        fun enqueue(ctx: Context, rootId: String, mode: String) {
            val request = OneTimeWorkRequestBuilder<ConsolidateWorker>()
                .setInputData(workDataOf(KEY_MODE to mode, KEY_ROOT_ID to rootId))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
