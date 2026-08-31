package app.roam.data.catalog.sync

import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import androidx.room.withTransaction
import app.roam.core.database.RevisionRow
import app.roam.core.database.RoamDatabase
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackEntity
import app.roam.core.model.Ids
import app.roam.core.model.TagState
import app.roam.data.source.FileKind
import app.roam.data.source.RemoteFile
import javax.inject.Inject

/**
 * Turns crawl results into catalogue rows.
 *
 * Discovery and tagging are deliberately separated. The crawl is cheap -- one
 * files.list per folder -- while reading tags means a ranged HTTP read per
 * track. Writing skeleton rows from the folder layout first means the library
 * is browsable in seconds, and the tag pass can fill in real titles and
 * artwork afterwards without blocking anything.
 */
class CatalogWriter @Inject constructor(
    private val tracks: TrackDao,
    private val albums: AlbumDao,
    private val artists: ArtistDao,
    private val tagExtractor: TagExtractor,
    private val db: RoamDatabase,
) {

    /** md5Checksum by remoteId, so unchanged files can be skipped entirely. */
    suspend fun existingRevisions(sourceId: String): Map<String, RevisionRow> =
        tracks.revisions(sourceId).associateBy { it.remoteId }

    /**
     * Upserts a batch. Returns how many rows actually changed -- an unchanged
     * file costs one map lookup and nothing else, which is what makes a
     * re-sync fast.
     */
    suspend fun writeBatch(
        sourceId: String,
        batch: List<RemoteFile>,
        known: Map<String, RevisionRow>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        // The kind filter lives here rather than at the call site, for the same
        // reason the hidden filter lives in TRACK_COLUMNS: a caller that forgets
        // it would turn album.json into a track that browses and cannot play,
        // and nothing about the row would say why.
        val changed = batch.filter { file ->
            if (file.kind != FileKind.AUDIO) return@filter false
            val existing = known[file.remoteId]
            existing == null || existing.remoteRevision != file.revision
        }
        if (changed.isEmpty()) return 0

        val artistRows = mutableMapOf<Long, ArtistEntity>()
        val albumRows = mutableMapOf<Long, AlbumEntity>()
        val trackRows = ArrayList<TrackEntity>(changed.size)

        for (file in changed) {
            val tags = tagExtractor.inferFromPath(file)
            val artistName = tags.albumArtist ?: tags.artist ?: UNKNOWN_ARTIST
            val albumName = tags.album ?: UNKNOWN_ALBUM

            val artistId = Ids.artist(artistName)
            val albumId = Ids.album(artistName, albumName)

            artistRows.getOrPut(artistId) {
                ArtistEntity(
                    id = artistId,
                    name = artistName,
                    sortName = Ids.normalise(artistName),
                )
            }
            albumRows.getOrPut(albumId) {
                AlbumEntity(
                    id = albumId,
                    title = albumName,
                    sortTitle = Ids.normalise(albumName),
                    artistId = artistId,
                    addedAt = now,
                )
            }

            trackRows += TrackEntity(
                id = Ids.track(sourceId, file.remoteId),
                sourceId = sourceId,
                remoteId = file.remoteId,
                remoteRevision = file.revision,
                title = tags.title ?: file.name.substringBeforeLast('.'),
                artistId = artistId,
                albumId = albumId,
                albumArtist = artistName,
                trackNo = tags.trackNo,
                mimeType = file.mimeType,
                sizeBytes = file.sizeBytes,
                fileName = file.name,
                folderPath = file.pathSegments.joinToString("/"),
                addedAt = now,
                tagState = TagState.PATH_INFERRED,
            )
        }

        // One transaction for the batch, for the same reason the tag pass has
        // one: Room invalidates per write and Paging reloads per invalidation,
        // so a few hundred individual updates during a sync stop the library
        // list ever settling. A batch is one invalidation.
        db.withTransaction {
            // Parents first: tracks reference albums which reference artists.
            //
            // insertIgnore, never upsert. An upsert writes every column of the
            // freshly built entity, so the defaults would land on top of real
            // data: loved and playCount on a track, artworkId on an album, and
            // artworkId plus artworkAttemptedAt on an artist. Ids are derived
            // from names, so a rename produces a new row and there is genuinely
            // nothing to update on the parents.
            artists.insertIgnore(artistRows.values.toList())
            albums.insertIgnore(albumRows.values.toList())
            tracks.insertIgnore(trackRows)

            // insertIgnore did nothing for rows that already existed, so refresh
            // those explicitly -- file facts always, path-inferred tags only when
            // the user has not overridden them.
            for (row in trackRows) {
                if (known[row.remoteId] == null) continue
                // fileName and folderPath ride with the other file facts, so a
                // track that MOVED on the source stops pointing at where it used
                // to be. Unconditional, unlike refreshFromPath -- where a file
                // lives is never the user's edit to lose.
                tracks.updateFileFacts(
                    id = row.id,
                    remoteRevision = row.remoteRevision,
                    mimeType = row.mimeType,
                    sizeBytes = row.sizeBytes,
                    fileName = row.fileName,
                    folderPath = row.folderPath,
                    // The bytes changed, so the file has to be read again whoever
                    // owns the metadata -- the duration and the embedded cover come
                    // from nowhere else.
                    tagState = row.tagState,
                )
                tracks.refreshFromPath(
                    id = row.id,
                    title = row.title,
                    artistId = row.artistId,
                    albumId = row.albumId,
                    albumArtist = row.albumArtist,
                    trackNo = row.trackNo,
                )
            }
        }
        return trackRows.size
    }

    /**
     * Reconciles what the crawl saw against what Roam held, then recomputes.
     *
     * Files the crawl did not see are FLAGGED, never deleted -- invariant 3c,
     * arrived at the hard way. A deleted row loses the loved flag, the play
     * count and every correction with it, and the next crawl rediscovers the
     * file as brand new, so a wrong deletion is not merely a wrong deletion:
     * it is permanent and it is silent. A flag costs a column and is reversible
     * by the thing that set it.
     *
     * It is a SEPARATE column from `hidden` on purpose. Hidden is the person's
     * decision and must survive a re-sync; missing is Roam's observation and
     * must be cleared the moment the file turns up again. One flag doing both
     * jobs would have a re-sync silently restore everything they removed.
     *
     * @return how many rows were newly flagged, or -1 when the crawl was
     * refused as implausible.
     */
    suspend fun finish(
        sourceId: String,
        seenRemoteIds: Set<String>,
        known: Map<String, RevisionRow>,
    ): Int {
        // Anything the crawl DID see is present, whatever Roam believed a
        // moment ago. Done first, so a file that comes back is restored even
        // if the pass is about to be refused.
        seenRemoteIds.chunked(CHUNK).forEach { tracks.clearMissing(sourceId, it) }

        val gone = known.keys - seenRemoteIds
        if (!isPlausible(seen = seenRemoteIds.size, known = known.size, gone = gone.size)) {
            // Refused, and the counts still get recomputed: what was cleared
            // above is real, and leaving the rollups stale would show the wrong
            // numbers for a pass that decided to change nothing.
            recount()
            return REFUSED
        }

        gone.chunked(CHUNK).forEach { tracks.markMissing(sourceId, it) }
        recount()
        return gone.size
    }

    /**
     * Whether a crawl looks like a real listing rather than a failed one.
     *
     * Drive answers `files.list` for a folder id that no longer exists with an
     * empty list and a 200 -- not an error -- so "your library is gone" and
     * "nothing came back" are the same response. Reconciling against that
     * removes everything. The same reasoning already governs Consolidate, which
     * refuses a partial listing outright because absence is what it concludes
     * deletions from; sync simply never had the guard.
     *
     * The threshold is deliberately blunt. Losing most of a library at once is
     * something a person does deliberately and can repeat, while a bad pass
     * happens by itself and repeats on its own -- so refusing a real mass
     * deletion until the next sync costs a sync, and accepting a bad one costs
     * the library.
     */
    private fun isPlausible(seen: Int, known: Int, gone: Int): Boolean = when {
        // Nothing was known: a first run cannot delete anything anyway.
        known == 0 -> true
        // Nothing came back at all. Never a real answer for a library that had
        // files a moment ago.
        seen == 0 -> false
        // Most of it vanished in one pass. Possible, but far likelier to be a
        // crawl that stopped without saying so.
        gone > known * MAX_LOSS_PERCENT / 100 -> false
        else -> true
    }

    private suspend fun recount() {
        albums.pruneOrphans()
        artists.pruneOrphans()
        albums.recomputeRollups()
        artists.recomputeRollups()
    }

    companion object {
        private const val UNKNOWN_ARTIST = "Unknown artist"
        private const val UNKNOWN_ALBUM = "Unknown album"
        private const val CHUNK = 500

        /**
         * Above this share of the library disappearing at once, refuse.
         *
         * Blunt on purpose, and cheap to get wrong now that nothing is deleted:
         * a refused pass costs one sync, and a wrongly accepted one only hides
         * rows until the next good crawl clears the flag.
         */
        private const val MAX_LOSS_PERCENT = 50

        /** What [finish] returns when it would not trust the listing. */
        const val REFUSED = -1
    }
}
