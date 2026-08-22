package app.roam.data.catalog.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.SourceType
import app.roam.data.catalog.artwork.ArtistPhotoWorker
import app.roam.data.catalog.metadata.DocApplier
import app.roam.data.catalog.metadata.DocReport
import app.roam.data.catalog.tags.TagWorker
import app.roam.data.source.SourceProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import app.roam.data.source.FileKind
import app.roam.data.source.RemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import javax.inject.Provider

/**
 * Discover -> Diff -> ExtractTags -> ResolveArtwork -> Reconcile -> Index
 *
 * Phase 1 implements Discover only: crawl the tree and report how many audio
 * files are there. Getting the crawl right and observable first means the tag
 * pass has something trustworthy to build on.
 *
 * Reconcile, when it lands, MUST NOT write loved / playCount / lastPlayedAt.
 * Sync owns file-derived columns only.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
    private val catalog: CatalogWriter,
    private val docApplier: DocApplier,
    private val settings: SettingsRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val typeName = inputData.getString(KEY_SOURCE_TYPE) ?: SourceType.DRIVE.name
        val root = inputData.getString(KEY_ROOT_ID) ?: return Result.failure(
            errorData("No root folder configured")
        )

        val provider = providers[SourceType.valueOf(typeName)]?.get()
            ?: return Result.failure(errorData("No provider for $typeName"))

        // Revisions of everything already stored. An unchanged file costs one
        // map lookup, which is what makes a re-sync near-instant compared with
        // the first crawl.
        val known = catalog.existingRevisions(provider.sourceId)

        var found = 0
        var written = 0
        val seen = HashSet<String>(known.size.coerceAtLeast(64))
        val batch = ArrayList<RemoteFile>(BATCH)
        // The album indexes, collected as they go past. Kept rather than
        // applied inline because a folder's album.json can arrive after some of
        // its tracks have already been written -- the crawl fans out across
        // folders and a page's order is nobody's promise. One per album, so a
        // ten-thousand-track library holds a few hundred of these.
        val documents = ArrayList<RemoteFile>()
        var failure: Throwable? = null

        suspend fun flush() {
            if (batch.isEmpty()) return
            written += catalog.writeBatch(provider.sourceId, batch, known)
            batch.clear()
        }

        provider.listAll(root)
            .catch { failure = it }
            .collect { file ->
                if (file.kind == FileKind.DOCUMENT) {
                    documents += file
                    return@collect
                }
                found++
                seen += file.remoteId
                batch += file
                if (batch.size >= BATCH) {
                    flush()
                    // Batched rather than per-file: at 25 updates a second the
                    // IPC costs more than the work being reported.
                    setProgress(workDataOf(KEY_FOUND to found))
                }
            }

        // Keep what the crawl did manage to read. Discarding a partial result
        // means one flaky request throws away the whole pass, and the user sees
        // an unchanged count with no idea why.
        flush()

        failure?.let { cause ->
            // Deliberately NOT calling finish(): it deletes rows the crawl did
            // not see, and a crawl that stopped early did not see plenty of
            // real tracks. Reconciling against a partial listing would wipe
            // most of the library.
            val reason = cause.message ?: cause::class.simpleName ?: "Crawl failed"
            return Result.failure(
                workDataOf(
                    KEY_FOUND to found,
                    KEY_WRITTEN to written,
                    KEY_ERROR to "Stopped after $found files ($written added): $reason",
                )
            )
        }

        catalog.finish(provider.sourceId, seen, known)

        // The metadata files, before the tag pass rather than after it. Both
        // write the same columns and the document is meant to win, so the order
        // is not a preference: applied afterwards, every album would show its
        // corrected titles for as long as the tag pass took to undo them.
        //
        // Only ever reached on a clean crawl -- the failure path above returns
        // before this. This pass concludes things from absence, and a partial
        // listing is not evidence of anything.
        val docs = try {
            docApplier.apply(provider, documents)
        } catch (cancelled: CancellationException) {
            // Not swallowed: a stopped worker must stop, not carry on into the
            // tag pass looking like the documents simply failed.
            throw cancelled
        } catch (t: Throwable) {
            // Everything the crawl wrote is already committed and correct. A
            // failed document pass costs corrections, not the library.
            DocReport(found = documents.size)
        }

        // Second pass: real tags and embedded covers. Separate job so the
        // catalogue is browsable now rather than after every ranged read.
        //
        // The crawl itself runs on any connection -- it is a handful of folder
        // listings, and it is what makes new music appear at all. These two are
        // the expensive part, so they are what the wifi-only setting holds.
        val wifiOnly = settings.settings.first().wifiOnlyForLargeTransfers
        TagWorker.enqueue(applicationContext, wifiOnly)
        ArtistPhotoWorker.enqueue(applicationContext, wifiOnly)

        return Result.success(
            workDataOf(
                KEY_FOUND to found,
                KEY_WRITTEN to written,
                KEY_DOCS to docs.read,
                KEY_DOC_TRACKS to docs.applied,
            )
        )
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val NAME_ONESHOT = "roam_sync_now"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_ROOT_ID = "root_id"
        const val KEY_FOUND = "found"
        const val KEY_WRITTEN = "written"
        /** album.json files read this pass, and the tracks they described. */
        const val KEY_DOCS = "docs"
        const val KEY_DOC_TRACKS = "doc_tracks"
        /** Rows per transaction. Large enough to amortise, small enough to stream. */
        const val BATCH = 250
        const val KEY_ERROR = "error"

        fun enqueue(ctx: Context, rootId: String, type: SourceType = SourceType.DRIVE) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_SOURCE_TYPE to type.name, KEY_ROOT_ID to rootId))
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NAME_ONESHOT, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
