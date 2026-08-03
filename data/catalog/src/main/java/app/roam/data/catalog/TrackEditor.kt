package app.roam.data.catalog

import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import app.roam.core.database.RoamDatabase
import app.roam.core.database.TrackDao
import app.roam.core.model.Ids
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A bulk edit over one album. A null field means the box was left unticked and
 * that column should not be written -- distinct from a ticked-but-empty field,
 * which clears it.
 *
 * No title and no track number: those are what distinguish the tracks from each
 * other, so applying one value across an album is never the intent.
 */
data class AlbumBulkEdits(
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val discNo: Int? = null,
    val compilation: Boolean? = null,
    val sortArtist: String? = null,
    val groupArtist: String? = null,
) {
    val isEmpty: Boolean
        get() = artist == null && album == null && albumArtist == null &&
            year == null && genre == null && discNo == null && compilation == null &&
            sortArtist == null && groupArtist == null
}

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
    val compilation: Boolean,
    /** Files this artist under another name for sorting. Blank means no override. */
    val sortArtist: String?,
    /** Folds this artist into another one entirely. Blank means standalone. */
    val groupArtist: String?,
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
    private val db: RoamDatabase,
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
            compilation = albums.byId(track.albumId)?.compilation == true,
            sortArtist = artists.byId(track.artistId)?.sortAs,
            groupArtist = artists.byId(track.artistId)?.groupArtistId
                ?.let { artists.byId(it)?.name },
        )
    }

    suspend fun apply(trackId: Long, edits: TrackEdits): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val artistName = edits.artist.trim().ifBlank { UNKNOWN_ARTIST }
            val albumName = edits.album.trim().ifBlank { UNKNOWN_ALBUM }
            // A blank album artist means "same as the track artist", which is
            // what keeps a normal album together instead of splitting it.
            // On a compilation the album artist is what holds the album
            // together, so it must NOT fall back to this track's artist --
            // that is precisely what splits a compilation into one album per
            // guest performer.
            val albumArtistName = edits.albumArtist?.trim()?.ifBlank { null }
                ?: if (edits.compilation) VARIOUS_ARTISTS else artistName

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
            albums.setCompilation(albumId, edits.compilation)
            applySortArtist(artistId, artistName, edits.sortArtist)
            applyGroupArtist(artistId, edits.groupArtist)

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

    /**
     * Applies the ticked fields to every track in an album.
     *
     * Wrapped in a transaction because renaming the album moves all of its
     * tracks to a new content-derived id at once. Halfway through, half the
     * album would point at the new id and half at the old, splitting it in two
     * with no obvious way back.
     *
     * Titles and track numbers are deliberately absent: those are what make one
     * track different from another, and overwriting them across an album is
     * never what someone means by "bulk edit".
     */
    suspend fun applyToAlbum(albumId: Long, edits: AlbumBulkEdits): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (edits.isEmpty) return@runCatching 0
                val rows = tracks.entitiesForAlbum(albumId)
                if (rows.isEmpty()) return@runCatching 0

                val existingAlbum = albums.byId(albumId)

                db.withTransaction {
                    for (row in rows) {
                        val artistName = edits.artist
                            ?: artists.byId(row.artistId)?.name
                            ?: UNKNOWN_ARTIST
                        val albumName = edits.album ?: existingAlbum?.title ?: UNKNOWN_ALBUM
                        val compilation = edits.compilation ?: existingAlbum?.compilation ?: false
                        val albumArtistName = edits.albumArtist
                            ?: if (compilation) VARIOUS_ARTISTS else (row.albumArtist ?: artistName)

                        val newArtistId = Ids.artist(artistName)
                        val newAlbumArtistId = Ids.artist(albumArtistName)
                        val newAlbumId = Ids.album(albumArtistName, albumName)

                        artists.insertIgnore(
                            buildList {
                                add(ArtistEntity(newArtistId, artistName, Ids.normalise(artistName)))
                                if (newAlbumArtistId != newArtistId) {
                                    add(
                                        ArtistEntity(
                                            newAlbumArtistId,
                                            albumArtistName,
                                            Ids.normalise(albumArtistName),
                                        )
                                    )
                                }
                            }
                        )
                        albums.insertIgnore(
                            listOf(
                                AlbumEntity(
                                    id = newAlbumId,
                                    title = albumName,
                                    sortTitle = Ids.normalise(albumName),
                                    artistId = newAlbumArtistId,
                                    year = edits.year ?: existingAlbum?.year,
                                    // The new row inherits the cover, or renaming
                                    // an album would silently lose its artwork.
                                    artworkId = existingAlbum?.artworkId,
                                    addedAt = existingAlbum?.addedAt ?: System.currentTimeMillis(),
                                )
                            )
                        )

                        tracks.applyUserEdit(
                            id = row.id,
                            title = row.title,
                            artistId = newArtistId,
                            albumId = newAlbumId,
                            albumArtist = albumArtistName,
                            trackNo = row.trackNo,
                            discNo = edits.discNo ?: row.discNo,
                            year = edits.year ?: row.year,
                            genre = edits.genre?.ifBlank { null } ?: row.genre.takeIf { edits.genre == null },
                        )
                    }

                    edits.groupArtist?.let { groupInto ->
                        for (row in rows) {
                            val name = edits.artist
                                ?: artists.byId(row.artistId)?.name
                                ?: UNKNOWN_ARTIST
                            applyGroupArtist(Ids.artist(name), groupInto)
                        }
                    }

                    edits.sortArtist?.let { sortAs ->
                        for (row in rows) {
                            val name = edits.artist
                                ?: artists.byId(row.artistId)?.name
                                ?: UNKNOWN_ARTIST
                            applySortArtist(Ids.artist(name), name, sortAs)
                        }
                    }

                    // Applied once, after every track has landed on the new
                    // album id -- setting it per track would target rows that
                    // are about to be pruned.
                    edits.compilation?.let { flag ->
                        val name = edits.albumArtist
                            ?: if (flag) VARIOUS_ARTISTS else null
                        val title = edits.album ?: existingAlbum?.title
                        if (name != null && title != null) {
                            albums.setCompilation(Ids.album(name, title), flag)
                        }
                    }

                    albums.pruneOrphans()
                    artists.pruneOrphans()
                    albums.recomputeRollups()
                    artists.recomputeRollups()
                }
                rows.size
            }
        }

    /**
     * Writes the override into sortName, which is what every list already
     * orders by -- so an alias lands next to the main artist in the Artists
     * tab, in album-major track sorting and in the car, without a single query
     * knowing this feature exists. Blank clears it and the real name returns.
     */
    private suspend fun applySortArtist(artistId: Long, artistName: String, sortArtist: String?) {
        val override = sortArtist?.trim()?.ifBlank { null }
        artists.setSortAs(
            id = artistId,
            sortAs = override,
            sortName = Ids.normalise(override ?: artistName),
        )
    }

    /**
     * Folds an artist into another, creating the target if it does not exist.
     *
     * Two guards, both cheap and both necessary:
     *
     * Self-grouping is refused outright -- it would hide the artist from the
     * list while putting their records nowhere.
     *
     * Chains are flattened. Grouping A into B when B already sits inside C
     * files A under C directly, so no query ever has to walk a path, and a
     * loop cannot form: every target is, by construction, already top level.
     */
    private suspend fun applyGroupArtist(artistId: Long, groupArtist: String?) {
        val target = groupArtist?.trim()?.ifBlank { null }
        if (target == null) {
            artists.setGroupArtist(artistId, null)
            return
        }

        val targetId = Ids.artist(target)
        if (targetId == artistId) {
            artists.setGroupArtist(artistId, null)
            return
        }

        artists.insertIgnore(
            listOf(ArtistEntity(targetId, target, Ids.normalise(target)))
        )
        val resolved = artists.groupTargetOf(targetId)?.takeIf { it != artistId } ?: targetId
        artists.setGroupArtist(artistId, resolved)
    }

    private companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        const val UNKNOWN_TITLE = "Untitled"
        const val VARIOUS_ARTISTS = "Various Artists"
    }
}
