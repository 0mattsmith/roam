package app.roam.data.catalog

import app.roam.core.database.AlbumDao
import app.roam.core.database.AlbumEntity
import app.roam.core.database.ArtistDao
import app.roam.core.database.ArtistEntity
import app.roam.core.database.RoamDatabase
import app.roam.core.database.TrackDao
import app.roam.core.model.Genres
import app.roam.core.model.Ids
import app.roam.core.model.TagState
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
    /** First release of the material, where it differs. Drives decade rules. */
    val originalYear: Int?,
    /**
     * A list, because that is what the documents hold and what a genre rule has
     * to match against. Joined into the single `genre` column on the way to the
     * database and split on the way back, in exactly one place -- see [apply].
     */
    val genres: List<String>,
    val composer: String?,
    /** TIT1. The work a track belongs to. Carried, never interpreted. */
    val grouping: String?,
    /** Part two of TRCK and TPOS. "3 of 12", "1 of 3". */
    val trackTotal: Int?,
    val discTotal: Int?,
    val compilation: Boolean,
    /** Files this artist under another name for sorting. Blank means no override. */
    val sortArtist: String?,
    /** The rest of the sort orders. TSOT, TSOA, TSO2, TSOC. */
    val titleSort: String?,
    val albumSort: String?,
    val albumArtistSort: String?,
    val composerSort: String?,
    /** Folds this artist into another one entirely. Blank means standalone. */
    val groupArtist: String?,
    /**
     * Where playback starts and ends, in milliseconds. Null means the whole
     * track. Carried on the same form because that is where someone editing a
     * song expects to find them -- but written separately, since they are a
     * playback preference rather than metadata.
     */
    val startMs: Long? = null,
    val endMs: Long? = null,
    /**
     * Words, typed or pasted by hand. Like the trim points these ride on the
     * same form but are written separately -- lyrics say nothing about whether
     * the TAGS are right, so setting them must not mark the track userEdited
     * and stop the tag pass ever refreshing it.
     */
    val lyrics: String? = null,
)

/**
 * Who the values on screen belong to, highest claim first.
 *
 * The same order the reader and the tag pass enforce (invariant 6f), stated
 * once so the form can say it out loud rather than leaving someone to work out
 * why an edit did or did not stick.
 */
enum class MetadataSource {
    /** Typed into Roam. Nothing overwrites it. */
    USER,

    /** From `album.json` beside the music. Survives a reinstall. */
    DOCUMENT,

    /** Read out of the file itself. */
    TAGS,

    /** Guessed from the file and folder names, because nothing better exists. */
    PATH,
}

/**
 * The form's starting values, and where they came from.
 *
 * Provenance is not a field anyone edits, which is why it rides alongside
 * [TrackEdits] rather than inside it -- `apply` would otherwise be handed a
 * value it must remember to ignore.
 */
data class TrackEditState(
    val edits: TrackEdits,
    val source: MetadataSource,
    /**
     * Whether the file has been read yet, which [source] alone cannot say: a
     * track can be hand-edited or described by a document and still have tags
     * nobody has looked at.
     */
    val tagState: TagState,
    /** The file's own type, so a tag label can name the right frame or atom. */
    val mimeType: String?,
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

    /**
     * Current values, for populating the form, and where they came from.
     *
     * Genres are canonicalised on the way out: a track tagged "Brit pop" opens
     * showing "Britpop", because the chips are meant to show what the genre IS
     * rather than which of six spellings this particular file happens to carry.
     * The tidy is not itself a change -- the form does not open dirty over it --
     * but it rides along with the next save, and Consolidate does the library.
     */
    suspend fun current(trackId: Long): TrackEditState? = withContext(Dispatchers.IO) {
        val track = tracks.byId(trackId) ?: return@withContext null
        val known = tracks.genreStrings().flatMap { Genres.split(it) }
        val edits = TrackEdits(
            title = track.title,
            artist = artists.byId(track.artistId)?.name.orEmpty(),
            album = albums.byId(track.albumId)?.title.orEmpty(),
            albumArtist = track.albumArtist,
            trackNo = track.trackNo,
            discNo = track.discNo,
            year = track.year,
            originalYear = track.originalYear,
            genres = Genres.canonicalise(Genres.split(track.genre), known),
            composer = track.composer,
            grouping = track.grouping,
            trackTotal = track.trackTotal,
            discTotal = track.discTotal,
            compilation = albums.byId(track.albumId)?.compilation == true,
            sortArtist = artists.byId(track.artistId)?.sortAs,
            titleSort = track.titleSort,
            albumSort = albums.byId(track.albumId)?.sortAs,
            albumArtistSort = albums.byId(track.albumId)?.artistId?.let { artists.byId(it)?.sortAs },
            composerSort = track.composerSort,
            groupArtist = artists.byId(track.artistId)?.groupArtistId
                ?.let { artists.byId(it)?.name },
            startMs = track.startMs,
            endMs = track.endMs,
            lyrics = track.lyrics,
        )
        TrackEditState(
            edits = edits,
            // Read in the same order everything else enforces it, off one row
            // that was already fetched -- so the form's answer and the reader's
            // behaviour cannot drift apart.
            source = when {
                track.userEdited -> MetadataSource.USER
                track.fromDoc -> MetadataSource.DOCUMENT
                track.tagState == TagState.OK -> MetadataSource.TAGS
                else -> MetadataSource.PATH
            },
            tagState = track.tagState,
            mimeType = track.mimeType,
        )
    }

    suspend fun apply(trackId: Long, edits: TrackEdits): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Written first and on its own: setClip does NOT set userEdited,
            // because skipping an intro says nothing about whether the title is
            // right, and marking the track edited would stop the tag pass ever
            // refreshing it again.
            tracks.setClip(trackId, edits.startMs, edits.endMs)

            // Same reasoning, same separation: lyrics are not tags. Stamping
            // lyricsAttemptedAt is what stops the automatic lookup coming along
            // later and replacing what was typed here.
            //
            // ONLY when they actually changed. The form is populated with the
            // stored words, so writing unconditionally would drop syncedLyrics
            // to null every time someone corrected a title -- the timings would
            // silently vanish from a track whose lyrics nobody touched.
            val typed = edits.lyrics?.trim()?.takeIf { it.isNotBlank() }
            if (typed != tracks.lyricsForOnce(trackId)?.plain) {
                tracks.setLyrics(
                    id = trackId,
                    plain = typed,
                    // Hand-typed words and the old timings do not belong to each
                    // other, so the LRC goes rather than drifting against them.
                    synced = null,
                    at = System.currentTimeMillis(),
                )
            }

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

            val title = edits.title.trim().ifBlank { UNKNOWN_TITLE }
            // The one place the list becomes a string, using the shared
            // joiner so it round-trips through Genres.split unchanged.
            val genre = Genres.join(edits.genres)

            // Only when the METADATA actually moved. applyUserEdit sets
            // userEdited, and that flag is permanent in effect: it takes the
            // track out of the tag pass and out of album.json's reach for good.
            // Setting a trim point or pasting lyrics says nothing about whether
            // the title is right -- both are written above, on their own, for
            // exactly that reason -- and it must not be the thing that freezes
            // a track's tags forever.
            val stored = tracks.byId(trackId)
            val moved = stored == null ||
                stored.title != title ||
                stored.artistId != artistId ||
                stored.albumId != albumId ||
                stored.albumArtist != albumArtistName ||
                stored.trackNo != edits.trackNo ||
                stored.discNo != edits.discNo ||
                stored.year != edits.year ||
                stored.originalYear != edits.originalYear ||
                stored.genre != genre ||
                stored.composer != edits.composer ||
                stored.grouping != edits.grouping ||
                stored.titleSort != edits.titleSort ||
                stored.composerSort != edits.composerSort ||
                stored.trackTotal != edits.trackTotal ||
                stored.discTotal != edits.discTotal

            if (moved) {
                tracks.applyUserEdit(
                    id = trackId,
                    title = title,
                    artistId = artistId,
                    albumId = albumId,
                    albumArtist = albumArtistName,
                    trackNo = edits.trackNo,
                    trackTotal = edits.trackTotal,
                    discNo = edits.discNo,
                    discTotal = edits.discTotal,
                    year = edits.year,
                    originalYear = edits.originalYear,
                    genre = genre,
                    composer = edits.composer?.trim()?.ifBlank { null },
                    grouping = edits.grouping?.trim()?.ifBlank { null },
                    titleSort = edits.titleSort?.trim()?.ifBlank { null },
                    composerSort = edits.composerSort?.trim()?.ifBlank { null },
                )
            }
            albums.setCompilation(albumId, edits.compilation)
            // Same pairing artists have had all along: the override and the
            // column every ORDER BY reads, written together.
            val albumFiling = edits.albumSort?.trim()?.ifBlank { null }
            albums.setSortAs(albumId, albumFiling, Ids.normalise(albumFiling ?: albumName))
            applySortArtist(artistId, artistName, edits.sortArtist)
            // The album artist files separately when it is a different artist --
            // "Various Artists" on a compilation is nobody's sort name.
            if (albumArtistId != artistId) {
                applySortArtist(albumArtistId, albumArtistName, edits.albumArtistSort)
            }
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
                                    // And the revision with it, which matters in
                                    // one case: a cover REMOVED by hand leaves
                                    // cover.jpg on Drive, so a row that has no
                                    // record of having read it is a row the next
                                    // crawl helpfully fills back in.
                                    coverRevision = existingAlbum?.coverRevision,
                                    addedAt = existingAlbum?.addedAt ?: System.currentTimeMillis(),
                                )
                            )
                        )

                        // Every field the bulk form does not offer is written
                        // back AS IT WAS. applyUserEdit sets each column
                        // named in its UPDATE, so an omitted argument would
                        // not be left alone -- it would land as null. Renaming
                        // an album would then quietly strip the composer and
                        // the sort names off every track in it, which is
                        // invariant 3a wearing different clothes.
                        tracks.applyUserEdit(
                            id = row.id,
                            title = row.title,
                            artistId = newArtistId,
                            albumId = newAlbumId,
                            albumArtist = albumArtistName,
                            trackNo = row.trackNo,
                            trackTotal = row.trackTotal,
                            discNo = edits.discNo ?: row.discNo,
                            discTotal = row.discTotal,
                            year = edits.year ?: row.year,
                            originalYear = row.originalYear,
                            genre = edits.genre?.ifBlank { null } ?: row.genre.takeIf { edits.genre == null },
                            composer = row.composer,
                            grouping = row.grouping,
                            titleSort = row.titleSort,
                            composerSort = row.composerSort,
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
