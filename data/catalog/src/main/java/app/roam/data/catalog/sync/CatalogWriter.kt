package app.roam.data.catalog.sync

import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import app.roam.core.database.RevisionRow
import app.roam.core.database.TrackDao
import app.roam.core.database.TrackEntity
import app.roam.core.model.Ids
import app.roam.core.model.TagState
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
        val changed = batch.filter { file ->
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
                addedAt = now,
                tagState = TagState.PATH_INFERRED,
            )
        }

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
            tracks.updateFileFacts(row.id, row.remoteRevision, row.mimeType, row.sizeBytes)
            tracks.refreshFromPath(
                id = row.id,
                title = row.title,
                artistId = row.artistId,
                albumId = row.albumId,
                albumArtist = row.albumArtist,
                trackNo = row.trackNo,
                tagState = row.tagState,
            )
        }
        return trackRows.size
    }

    /**
     * Removes rows for files that are no longer in the source, then recomputes
     * counts.
     *
     * IMPORTANT: this only deletes tracks the crawl did not see. It never
     * rewrites loved / playCount / lastPlayedAt on surviving rows -- those
     * belong to the user, and a re-scan that clears them is unforgivable.
     */
    suspend fun finish(sourceId: String, seenRemoteIds: Set<String>, known: Map<String, RevisionRow>) {
        val gone = known.keys - seenRemoteIds
        gone.chunked(500).forEach { tracks.deleteRemote(sourceId, it) }

        albums.pruneOrphans()
        artists.pruneOrphans()
        albums.recomputeRollups()
        artists.recomputeRollups()
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
    }
}
