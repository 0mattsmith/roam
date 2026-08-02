package app.roam.data.catalog

import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import app.roam.core.database.TrackDao
import app.roam.core.model.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** The fields the edit form exposes. Everything else is file-derived. */
data class TrackEdits(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNo: Int?,
    val discNo: Int?,
    val year: Int?,
    val genre: String?,
)

/**
 * Hand-typed track metadata.
 *
 * The hard part is not writing the columns, it is that artist and album ids are
 * derived from their names (invariant 7). Renaming an artist therefore does not
 * update a row -- it moves the track to a *different* row, which may not exist
 * yet. Miss that and the track points at an id with nothing behind it and
 * disappears from every list, because the joins are inner.
 *
 * Edits are marked with userEdited so neither sync nor TagWorker overwrites
 * them. That flag is what makes this safe to offer before a real tag writer
 * exists: nothing here touches the file, and nothing later reverts it.
 */
@Singleton
class TrackEditor @Inject constructor(
    private val tracks: TrackDao,
    private val artists: ArtistDao,
    private val albums: AlbumDao,
) {

    /** Current values, for populating the form. */
    suspend fun current(trackId: Long): TrackEdits? = withContext(Dispatchers.IO) {
        val track = tracks.byId(trackId) ?: return@withContext null
        TrackEdits(
            title = track.title,
            artist = artists.byId(track.artistId)?.name.orEmpty(),
            album = albums.byId(track.albumId)?.title.orEmpty(),
            albumArtist = track.albumArtist,
            trackNo = track.trackNo,
            discNo = track.discNo,
            year = track.year,
            genre = track.genre,
        )
    }

    suspend fun apply(trackId: Long, edits: TrackEdits): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val artistName = edits.artist.trim().ifBlank { UNKNOWN_ARTIST }
            val albumName = edits.album.trim().ifBlank { UNKNOWN_ALBUM }
            // A blank album artist means "same as the track artist", which is
            // what keeps a normal album together instead of splitting it.
            val albumArtistName = edits.albumArtist?.trim()?.ifBlank { null } ?: artistName

            val artistId = Ids.artist(artistName)
            val albumArtistId = Ids.artist(albumArtistName)
            val albumId = Ids.album(albumArtistName, albumName)

            // Parents first, and insertIgnore so an existing artist keeps its
            // photo and an existing album keeps its cover.
            artists.insertIgnore(
                buildList {
                    add(ArtistEntity(artistId, artistName, Ids.normalise(artistName)))
                    if (albumArtistId != artistId) {
                        add(ArtistEntity(albumArtistId, albumArtistName, Ids.normalise(albumArtistName)))
                    }
                }
            )
            albums.insertIgnore(
                listOf(
                    AlbumEntity(
                        id = albumId,
                        title = albumName,
                        sortTitle = Ids.normalise(albumName),
                        artistId = albumArtistId,
                        year = edits.year,
                        addedAt = System.currentTimeMillis(),
                    )
                )
            )

            tracks.applyUserEdit(
                id = trackId,
                title = edits.title.trim().ifBlank { UNKNOWN_TITLE },
                artistId = artistId,
                albumId = albumId,
                albumArtist = albumArtistName,
                trackNo = edits.trackNo,
                discNo = edits.discNo,
                year = edits.year,
                genre = edits.genre?.trim()?.ifBlank { null },
            )

            // The old artist or album may now hold nothing. Prune before the
            // rollups, or the counts are recomputed for rows about to vanish.
            albums.pruneOrphans()
            artists.pruneOrphans()
            albums.recomputeRollups()
            artists.recomputeRollups()
        }
    }

    /** Drops the override so the file's own tags win again on the next pass. */
    suspend fun revert(trackId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { tracks.clearUserEdit(trackId) }
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        const val UNKNOWN_TITLE = "Untitled"
    }
}
