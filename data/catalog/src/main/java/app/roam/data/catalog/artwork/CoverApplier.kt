package app.roam.data.catalog.artwork

import app.roam.core.database.AlbumDao
import app.roam.core.database.TrackDao
import app.roam.core.model.ArtworkSource
import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import javax.inject.Inject

/** What a pass did, for the sync result line. */
data class CoverReport(val found: Int = 0, val applied: Int = 0, val failed: Int = 0)

/**
 * Album covers, read from the folder the music is in.
 *
 * This did not exist, and its absence was invisible because the invariant
 * claimed it did. Artist photos have always read `artist.jpg` from the artist's
 * folder; `cover.jpg` in an ALBUM folder was written by the downloader, written
 * by the cover editor, and never once read back. An album took its picture from
 * an embedded APIC frame or from nowhere.
 *
 * Which meant the file was authoritative in principle and ignored in practice:
 * replace a cover from a laptop and Roam kept showing whatever was embedded;
 * download an album and the cover the downloader had just seeded beside it was
 * only ever used if a track happened to carry the same image inside itself.
 *
 * PRECEDENCE, in order: what `album.json` NAMES in its `cover_art` field, then
 * the conventional filenames, then whatever was embedded in a track.
 *
 * The document comes first for the same reason it outranks tags in 6f -- it is
 * the only thing that STATES an answer, where a ranked list of filenames is
 * guessing. A folder holding both cover.jpg and folder.jpg is ordinary, and
 * without the document the picture would depend on a list's running order.
 *
 * Which is why this writes unconditionally where `TagWorker` writes only into a
 * null. A picture chosen by hand is uploaded as `cover.jpg`, so it wins here on
 * its own merits rather than by being pinned to a row nothing refreshes -- and
 * a pin was always the weaker claim, because it could not notice the file
 * changing underneath it.
 *
 * Costs no requests to FIND anything: the crawl already lists every file in
 * every folder and now carries the covers out, exactly as it does `album.json`.
 * Only a cover whose revision has moved is actually downloaded.
 */
class CoverApplier @Inject constructor(
    private val albums: AlbumDao,
    private val tracks: TrackDao,
    private val artwork: ArtworkStore,
) {

    /**
     * @param declared album folder -> the cover file its `album.json` names,
     * straight out of the document pass. Absent for a folder with no document,
     * which is when the filename convention decides instead.
     */
    suspend fun apply(
        provider: SourceProvider,
        found: List<RemoteFile>,
        declared: Map<String, String> = emptyMap(),
        force: Boolean = false,
    ): CoverReport {
        if (found.isEmpty()) return CoverReport()

        val seen = albums.coverRevisions().associate { it.id to it.coverRevision }
        var applied = 0
        var failed = 0

        // One cover per folder, and the document picks it when there is one.
        // Falling back to the ranked filenames is for albums with no
        // album.json -- there, taking whichever the listing emitted first would
        // make the picture depend on Drive's paging.
        val best = found
            .filter { it.folderPath.isNotEmpty() }
            .groupBy { it.folderPath }
            .mapValues { (folder, files) ->
                val named = declared[folder]
                files.firstOrNull { named != null && it.name.equals(named, ignoreCase = true) }
                    ?: files.minByOrNull { rank(it.name) }
            }

        for ((folder, file) in best) {
            if (file == null) continue
            for (albumId in albumsUnder(provider.sourceId, folder)) {
                // The revision is the whole skip. It is also what makes
                // "Remove cover" stick: that clears the row and leaves the file
                // alone, so without a record of having already read this exact
                // one the next crawl would put the picture straight back.
                if (!force && seen[albumId] == file.revision && seen.containsKey(albumId)) continue

                val bytes = runCatching { provider.read(file.remoteId) }.getOrNull()
                if (bytes == null) {
                    failed++
                    continue
                }
                val id = runCatching { artwork.put(bytes, ArtworkSource.FOLDER_JPG) }.getOrNull()
                if (id == null) {
                    failed++
                    continue
                }
                albums.setCoverFromFolder(albumId, id, file.revision)
                applied++
            }
        }
        return CoverReport(found = found.size, applied = applied, failed = failed)
    }

    /**
     * Albums whose tracks live at or below this folder.
     *
     * At OR below, because a deluxe edition keeps its discs in subfolders while
     * the cover sits at the album root. The SQL narrows with LIKE and Kotlin
     * decides, for the reason `DocMatcher` does the same: `_` is a
     * single-character wildcard in LIKE and real folder names contain it, so a
     * neighbouring album would otherwise take this one's picture.
     */
    private suspend fun albumsUnder(sourceId: String, folder: String): Set<Long> =
        tracks.albumsUnderFolder(sourceId, folder)
            .filter { row ->
                val path = row.folderPath.orEmpty()
                path == folder || path.startsWith("$folder/")
            }
            .map { it.albumId }
            .toSet()

    /**
     * Position in the preference list; anything unrecognised sorts last.
     *
     * Only consulted when no document names a cover. One gap worth knowing: a
     * `cover_art` naming something outside [ArtworkFiles.ALBUM_NAMES] is not
     * carried by the crawl at all, so it cannot be honoured without a request
     * per album. The default is `cover.jpg` and the list is wide, so this is
     * rare -- but it is a limitation rather than a decision.
     */
    private fun rank(name: String): Int =
        ArtworkFiles.ALBUM_NAMES.indexOf(name.lowercase()).takeIf { it >= 0 } ?: Int.MAX_VALUE
}
