package app.roam.data.catalog.metadata

import androidx.room.withTransaction
import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import app.roam.core.database.DocRevisionDao
import app.roam.core.database.DocRevisionEntity
import app.roam.core.database.FolderTrackRow
import app.roam.core.database.RoamDatabase
import app.roam.core.database.TrackDao
import app.roam.core.model.Genres
import app.roam.core.model.Ids
import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** What one pass over the library's `album.json` files did. */
data class DocReport(
    /** Documents the crawl found. */
    val found: Int = 0,
    /** Documents actually fetched -- the rest were unchanged since last time. */
    val read: Int = 0,
    /** Tracks whose metadata now comes from a document. */
    val applied: Int = 0,
    /** Tracks a document has stopped describing, handed back to their tags. */
    val released: Int = 0,
    /** Documents that could not be parsed. Not an error -- they are skipped. */
    val unreadable: Int = 0,
    /**
     * Album folder -> the cover file its document names, from `cover_art`.
     *
     * Carried out of this pass rather than worked out later, because the
     * DOCUMENT is the authority on an album's cover and this is the only place
     * that reads one. A folder holding both cover.jpg and folder.jpg is common,
     * and picking by a ranked list of filenames is guessing at something the
     * album.json states outright.
     */
    val declaredCovers: Map<String, String> = emptyMap(),
)

/**
 * Reads the `album.json` files the crawl found and lets them outrank the tags.
 *
 * Runs after a CLEAN crawl only, for the same reason [CatalogWriter.finish]
 * does: this pass concludes things from absence -- a track the index no longer
 * mentions gets handed back to its tags -- and a crawl that stopped early did
 * not see plenty of real files.
 *
 * It reads the album index and nothing else. `<track>.json` is deliberately not
 * fetched here: one request per album is a second of work for a large library,
 * one request per track is several minutes of it. That split is the entire
 * reason the index exists.
 *
 * On the wifi-only setting: this pass is exempt, like the crawl it belongs to.
 * A few kilobytes per album is not what that switch is protecting against, and
 * holding it back would mean a library that browses with the wrong titles until
 * you got home.
 */
@Singleton
class DocApplier @Inject constructor(
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
    private val docs: DocRevisionDao,
    private val db: RoamDatabase,
) {

    /**
     * @param force re-read and re-apply every document, ignoring the revision
     *   cache. What "Full scan" means: after somebody has been rewriting these
     *   files with another tool, "nothing changed since Roam last looked" is a
     *   claim about Roam's bookkeeping rather than about the files.
     */
    suspend fun apply(
        provider: SourceProvider,
        found: List<RemoteFile>,
        force: Boolean = false,
    ): DocReport {
        val sourceId = provider.sourceId
        val cached = docs.all(sourceId).associateBy { it.remoteId }

        // A document that is no longer on the source has nothing left to cache.
        // Its tracks are dealt with by the crawl: either they went with it, or
        // they are still there and the next pass finds no index naming them.
        val vanished = cached.keys - found.mapTo(HashSet<String>()) { it.remoteId }
        if (vanished.isNotEmpty()) docs.forget(sourceId, vanished.toList())

        // Deciding what to fetch BEFORE fetching anything: the whole point of
        // the revision cache is that a re-sync of an unchanged library makes no
        // requests at all.
        val pending = found.mapNotNull { file ->
            val rows = tracks.tracksUnderFolder(sourceId, file.folderPath)
            val previous = cached[file.remoteId]
            val unchanged = !force &&
                previous != null &&
                previous.revision != null &&
                previous.revision == file.revision &&
                // The revision alone is not enough. Dropping a new file into
                // the folder leaves the index byte-identical while genuinely
                // needing another pass to pick the new track up.
                previous.trackCount == rows.size &&
                // Nor is the count. A folder renamed on the source keeps the
                // document's id and its bytes, and this is the only thing that
                // notices it is now describing somewhere else.
                previous.folderPath == file.folderPath
            if (unchanged) null else file to rows
        }

        var report = DocReport(found = found.size)
        val stamped = ArrayList<DocRevisionEntity>(pending.size)
        val now = System.currentTimeMillis()

        // Fetched a chunk at a time and applied serially. The cost here is
        // round-trips, exactly as in the crawl; the database writes are local
        // and fast, and serialising them keeps one transaction per album.
        for (chunk in pending.chunked(FETCH_WIDTH)) {
            val fetched = coroutineScope {
                chunk.map { (file, rows) ->
                    async { Triple(file, rows, readOrNull(provider, file.remoteId)) }
                }.awaitAll()
            }

            for ((file, rows, raw) in fetched) {
                // A failed read is not a verdict. The document is left
                // unstamped so the next sync tries again, and the album keeps
                // whatever it already had.
                if (raw == null) continue
                report = report.copy(read = report.read + 1)

                val doc = LibraryDocs.album(decode(raw))
                if (doc == null) {
                    // Malformed, or from a schema this build does not
                    // understand. Stamped anyway so it is not re-downloaded on
                    // every sync -- editing the file changes its revision,
                    // which is what brings it back.
                    report = report.copy(unreadable = report.unreadable + 1)
                    stamped += stamp(sourceId, file, rows.size, now)
                    continue
                }

                report = applyOne(sourceId, file, doc, rows, report)
                stamped += stamp(sourceId, file, rows.size, now)
            }
        }

        if (stamped.isNotEmpty()) docs.upsert(stamped)

        // Once, not per album. Renaming an album moves tracks to a new
        // content-derived row and leaves the old one holding nothing, so both
        // have to happen -- but they are whole-table sweeps and doing them
        // three hundred times over is what turns a fast pass into a slow one.
        if (report.applied > 0 || report.released > 0) {
            albums.pruneOrphans()
            artists.pruneOrphans()
            albums.recomputeRollups()
            artists.recomputeRollups()
        }
        return report
    }

    private suspend fun applyOne(
        sourceId: String,
        file: RemoteFile,
        doc: AlbumDoc,
        rows: List<FolderTrackRow>,
        report: DocReport,
    ): DocReport {
        val local = DocMatcher.matchLocal(file.folderPath, doc, rows)

        // Entries pointing outside their own album folder. Almost always none,
        // so the query is skipped entirely rather than run with an empty list.
        val externalPaths = DocMatcher.externalPaths(doc)
        val external = if (externalPaths.isEmpty()) {
            emptyList<DocMatch>()
        } else {
            DocMatcher.matchExternal(doc, tracks.tracksByPath(sourceId, externalPaths))
        }

        val matched = local.matched + external
        // Recorded BEFORE the early return. A document whose entries match
        // nothing still names a cover, and that claim is worth honouring --
        // the two failures are unrelated.
        report = report.copy(
            declaredCovers = report.declaredCovers + (file.folderPath to doc.coverArt)
        )

        if (matched.isEmpty() && local.orphaned.isEmpty()) return report

        val albumArtist = doc.albumArtist
        val albumArtistId = Ids.artist(albumArtist)
        val albumId = Ids.album(albumArtist, doc.albumTitle)
        val genre = Genres.join(doc.genres)

        // The album this track belonged to a moment ago, so a rename inherits
        // its cover instead of landing on a fresh row whose artworkId defaults
        // to null.
        //
        // A LOCAL match, deliberately: an external entry belongs to a different
        // folder and a different album, and taking its cover would put one
        // record's sleeve on another.
        val previousAlbumId = local.matched.firstOrNull()?.let { tracks.albumIdOf(it.trackId) }
        val previous = previousAlbumId?.let { albums.byId(it) }

        // Counted from what the database actually wrote rather than from what
        // was matched: applyFromDoc refuses a hand-edited track, and a report
        // that claimed otherwise would describe work that did not happen.
        var written = 0

        val artistRows = LinkedHashMap<Long, ArtistEntity>()
        artistRows[albumArtistId] = ArtistEntity(albumArtistId, albumArtist, Ids.normalise(albumArtist))
        for (match in matched) {
            val name = match.entry.artist?.trim()?.ifBlank { null } ?: albumArtist
            val id = Ids.artist(name)
            artistRows.getOrPut(id) { ArtistEntity(id, name, Ids.normalise(name)) }
        }

        db.withTransaction {
            // Parents before anything points at them. The joins are inner, so
            // a track aimed at an artist or album row that does not exist yet
            // vanishes from every list (invariant 7).
            artists.insertIgnore(artistRows.values.toList())
            albums.insertIgnore(
                listOf(
                    AlbumEntity(
                        id = albumId,
                        title = doc.albumTitle,
                        sortTitle = Ids.normalise(doc.albumTitle),
                        artistId = albumArtistId,
                        compilation = doc.isCompilation,
                        year = doc.year ?: previous?.year,
                        artworkId = previous?.artworkId,
                        addedAt = previous?.addedAt ?: System.currentTimeMillis(),
                    )
                )
            )

            for (match in matched) {
                val entry = match.entry
                val artistName = entry.artist?.trim()?.ifBlank { null } ?: albumArtist
                written += tracks.applyFromDoc(
                    id = match.trackId,
                    title = entry.title?.trim()?.ifBlank { null } ?: fallbackTitle(entry.locator),
                    artistId = Ids.artist(artistName),
                    albumId = albumId,
                    albumArtist = albumArtist,
                    trackNo = entry.track,
                    discNo = entry.disc,
                    year = doc.year,
                    originalYear = doc.originalYear,
                    genre = genre,
                )
            }

            if (local.orphaned.isNotEmpty()) tracks.clearFromDoc(local.orphaned)

            // insertIgnore did nothing if the album already existed, and the
            // year is the one field an existing row genuinely wants: nothing in
            // Roam sets an album's year directly, so it is null for almost
            // every album that was not created by an edit.
            doc.year?.let { albums.setYear(albumId, it) }

            // compilation is NOT written back over an existing row, and that
            // asymmetry is the point. It is the one album field a person
            // toggles by hand, and until the editor WRITES these files their
            // toggle is not in the document -- so taking the document's word
            // would undo it on a schedule nobody asked for. On a new row there
            // is no prior claim, which is why the insert above carries it.
        }

        return report.copy(
            applied = report.applied + written,
            released = report.released + local.orphaned.size,
        )
    }

    /**
     * One document's bytes, or null if they could not be had.
     *
     * Not runCatching: that swallows CancellationException too, so a worker
     * stopped mid-pass would look like a few failed reads and carry on issuing
     * requests instead of stopping. Every other failure genuinely is "try again
     * next time".
     */
    private suspend fun readOrNull(provider: SourceProvider, remoteId: String): ByteArray? =
        try {
            provider.read(remoteId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            null
        }

    private fun stamp(sourceId: String, file: RemoteFile, trackCount: Int, now: Long) =
        DocRevisionEntity(
            sourceId = sourceId,
            remoteId = file.remoteId,
            revision = file.revision,
            folderPath = file.folderPath,
            trackCount = trackCount,
            appliedAt = now,
        )

    /**
     * A title of last resort, when an entry names a file but not a song.
     *
     * The same answer the crawl gives, so a half-filled index never looks
     * worse than no index at all.
     */
    private fun fallbackTitle(locator: String): String =
        locator.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Untitled" }

    /**
     * Bytes to text, minus the byte order mark.
     *
     * Notepad and a good few editors write one, and org.json treats the three
     * leading bytes as a syntax error -- so a file that looks perfect in every
     * editor parses as null and the album silently keeps its old titles.
     */
    private fun decode(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

    private companion object {
        /**
         * Documents in flight at once. Matches the crawl's own fan-out: enough
         * to hide the latency, not enough to earn a userRateLimitExceeded.
         */
        const val FETCH_WIDTH = 8
    }
}
