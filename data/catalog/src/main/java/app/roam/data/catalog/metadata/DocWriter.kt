package app.roam.data.catalog.metadata

import android.content.Context
import app.roam.core.database.DocRevisionDao
import app.roam.core.database.DocTrackRow
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.SourceType
import app.roam.data.source.DocNames
import app.roam.data.source.SourceProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** What one write did, and what it deliberately left alone. */
data class DocWriteReport(
    val albumTitle: String,
    val filesWritten: Int,
    /** Tracks skipped because nothing has vouched for their metadata. */
    val skippedGuesses: Int,
) {
    val wroteNothing: Boolean get() = filesWritten == 0
}

/**
 * Writes `album.json` and the per-track files onto the source.
 *
 * The point of the whole exercise: Roam's corrections live in a database that a
 * reinstall deletes, and these files sit beside the music where they do not.
 *
 * Two rules shape everything here.
 *
 * **Nothing is created that was not asked for.** Folders are resolved with
 * `create = false`, exactly as the artwork and lyric passes do -- an album whose
 * folder cannot be found is skipped rather than conjured, because a tag and a
 * folder name are allowed to disagree and Roam must not litter someone's music
 * library with the difference.
 *
 * **Nothing is dropped.** Every file is written THROUGH whatever is already
 * there, keeping fields this version does not recognise. These documents are
 * shared with an external editor; a writer that discarded what it did not
 * understand would silently undo the other's work on every save.
 *
 * Unlike artwork (invariant 6d) these are overwritten in place rather than
 * numbered aside. An index is regenerable and is edited over and over; a
 * photograph is not, and archiving every save would bury the folder in
 * album1.json, album2.json. Field preservation is what protects this file
 * instead, and it is the stronger protection for something written this often.
 */
@Singleton
class DocWriter @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val tracks: TrackDao,
    private val docs: DocRevisionDao,
    private val settings: SettingsRepository,
    private val providers: Map<SourceType, @JvmSuppressWildcards Provider<SourceProvider>>,
) {

    /**
     * The everyday entry point: one album, resolving the source itself.
     *
     * Callers hold an album id and nothing else -- the editor, the album sheet,
     * the sweep -- and making each of them find the provider and the root
     * folder first would be the same six lines in three places.
     */
    suspend fun writeAlbum(
        albumId: Long,
        onlyTrackId: Long? = null,
        skipGuesses: Boolean = true,
    ): Result<DocWriteReport> = runCatching {
        val provider = providers[SourceType.DRIVE]?.get() ?: error("No source connected")
        val root = settings.settings.first().driveFolderId ?: error("No music folder chosen")
        write(
            provider = provider,
            root = root,
            albumId = albumId,
            cacheDir = ctx.cacheDir,
            onlyTrackId = onlyTrackId,
            skipGuesses = skipGuesses,
        ).getOrThrow()
    }

    /**
     * Whether this album already has an `album.json` on the source.
     *
     * What the editor asks before saving. Roam UPDATES documents that exist and
     * never creates one -- creating them is Consolidate Metadata's job, so that
     * new files appearing on somebody's Drive is something they asked for
     * rather than a side effect of fixing a title.
     *
     * Answered from doc_revisions rather than by asking the source: the crawl
     * already found every album.json, and a network round trip to decide
     * whether to show a message would be absurd.
     */
    suspend fun hasDocument(albumId: Long): Boolean {
        val rows = tracks.docTracksForAlbum(albumId)
        val folder = DocBuilder.albumFolder(rows)
        if (folder.isEmpty()) return false
        val sourceId = providers[SourceType.DRIVE]?.get()?.sourceId ?: return false
        return docs.all(sourceId).any { it.folderPath == folder.joinToString("/") }
    }

    /**
     * @param onlyTrackId write just this track's own file, plus the index.
     *   Null writes a file for every track, which is what creating an index
     *   from scratch means.
     * @param skipGuesses refuse tracks nobody has vouched for -- see
     *   [DocTrackRow.isGuess]. Writing a filename-derived title down makes json
     *   outrank the tags, so the guess is recorded and the real ones are never
     *   consulted again, and nothing would look wrong afterwards.
     *
     *   Deliberately narrower than "tags not read". A hand-typed correction on
     *   a file whose tags are unread is not a guess, it is the strongest claim
     *   there is -- and refusing THOSE meant the tracks somebody had just
     *   carefully fixed were the only ones that could not be made durable.
     */
    suspend fun write(
        provider: SourceProvider,
        root: String,
        albumId: Long,
        cacheDir: File,
        onlyTrackId: Long? = null,
        skipGuesses: Boolean = true,
    ): Result<DocWriteReport> = withContext(Dispatchers.IO) {
        runCatching {
            val rows = tracks.docTracksForAlbum(albumId)
            if (rows.isEmpty()) error("This album has no tracks")

            val folder = DocBuilder.albumFolder(rows)
            if (folder.isEmpty()) {
                // Every track in one folder is the normal case; nothing in
                // common means they are scattered across the library and there
                // is no one place an index for them belongs.
                error("This album's tracks are spread across folders, so there is nowhere to put its index")
            }

            val folderId = provider.resolveFolder(root, folder, create = false)
                ?: error("Could not find ${folder.joinToString("/")} on the source")

            val usable = rows.filterNot { skipGuesses && it.isGuess }
            val toWrite = usable.filter { onlyTrackId == null || it.id == onlyTrackId }

            var written = 0
            val staging = File(cacheDir, "docs/$albumId").apply { mkdirs() }
            try {
                // Track files first. The index names them, so writing it last
                // means it never points at a file that is not there yet.
                for (row in toWrite) {
                    if (writeTrack(provider, root, folder, row, staging)) written++
                }

                // Built from every usable row, not just the ones written: the
                // index describes the album, and leaving the others out would
                // make an edit to one track delete the rest from it.
                if (usable.isNotEmpty()) {
                    val existing = readJson(provider, folderId, DocNames.ALBUM)
                    val body = DocBuilder.albumDocument(existing, usable)
                    upload(provider, root, folder, DocNames.ALBUM, body, staging, folderId)
                    written++
                }

                // The file is the durable copy now, so Roam's own override
                // steps aside for it -- see markWrittenToDoc.
                if (written > 0) tracks.markWrittenToDoc(usable.map { it.id })
            } finally {
                staging.deleteRecursively()
            }

            DocWriteReport(
                albumTitle = rows.first().albumTitle,
                filesWritten = written,
                skippedGuesses = rows.size - usable.size,
            )
        }
    }

    /**
     * Names a retired image in the document that describes its folder.
     *
     * Called after an image has been numbered aside. Does nothing when there is
     * no document there: changing a cover must not CREATE an album.json, which
     * is a deliberate act with its own checkbox -- new files appearing on
     * someone's Drive because they picked a different picture would be a
     * surprise, and surprises on someone's own storage are how an app stops
     * being trusted.
     *
     * Failure is swallowed. The image has already been retired safely and the
     * folder listing still finds it; this list is a convenience, and losing it
     * is not worth failing an operation that has otherwise succeeded.
     */
    suspend fun recordRetiredArtwork(
        provider: SourceProvider,
        root: String,
        folderSegments: List<String>,
        docName: String,
        retiredName: String,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val folderId = provider.resolveFolder(root, folderSegments, create = false)
                ?: return@runCatching false
            val existing = readJson(provider, folderId, docName) ?: return@runCatching false

            val keys = if (docName == DocNames.ALBUM) DocBuilder.ALBUM_KEYS else DocBuilder.ARTIST_KEYS
            val body = DocBuilder.withRetiredArtwork(existing, retiredName, keys)
                ?: return@runCatching false

            val staging = File(ctx.cacheDir, "docs/artwork").apply { mkdirs() }
            try {
                upload(provider, root, folderSegments, docName, body, staging, folderId)
            } finally {
                staging.deleteRecursively()
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun writeTrack(
        provider: SourceProvider,
        root: String,
        albumFolder: List<String>,
        row: DocTrackRow,
        staging: File,
    ): Boolean {
        val fileName = row.fileName?.trim()?.takeIf { it.isNotEmpty() } ?: return false

        // The track's own folder, which is the album folder for most albums and
        // a disc subfolder for the rest.
        val segments = albumFolder + DocBuilder.below(albumFolder, row)
            .split('/').filter(String::isNotBlank)
        val folderId = provider.resolveFolder(root, segments, create = false) ?: return false

        val name = DocBuilder.trackDocName(fileName)
        val body = DocBuilder.trackDocument(readJson(provider, folderId, name), row)
        upload(provider, root, segments, name, body, staging, folderId)
        return true
    }

    /**
     * Replaces the file if it is there, creates it if not.
     *
     * overwrite rather than a second upload: most sources allow two files with
     * the same name in one folder, so adding would leave the folder holding two
     * album.json files and which one Roam read next would be luck.
     */
    private suspend fun upload(
        provider: SourceProvider,
        root: String,
        segments: List<String>,
        name: String,
        body: String,
        staging: File,
        folderId: String,
    ) {
        val temp = File(staging, name.replace('/', '_')).apply {
            parentFile?.mkdirs()
            writeText(body)
        }
        try {
            val existing = provider.findInFolder(folderId, listOf(name))
            if (existing != null) provider.overwrite(existing.remoteId, temp)
            else provider.write(root, segments, name, temp)
        } finally {
            temp.delete()
        }
    }

    /**
     * Whatever is already at that name, parsed but not interpreted.
     *
     * A raw JSONObject rather than a LibraryDocs type, because the whole job of
     * this read is the fields LibraryDocs does NOT know about. A file that will
     * not parse comes back null and is written over -- there is nothing in it
     * to preserve.
     */
    private suspend fun readJson(provider: SourceProvider, folderId: String, name: String): JSONObject? {
        val file = provider.findInFolder(folderId, listOf(name)) ?: return null
        val text = runCatching { provider.read(file.remoteId).decodeToString() }.getOrNull() ?: return null
        // Notepad writes a byte order mark and org.json calls it a syntax error.
        return runCatching { JSONObject(text.removePrefix("\uFEFF")) }.getOrNull()
    }
}
