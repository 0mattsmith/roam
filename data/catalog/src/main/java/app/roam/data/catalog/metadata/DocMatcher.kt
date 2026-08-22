package app.roam.data.catalog.metadata

import app.roam.core.database.FolderTrackRow

/**
 * Which track each `album.json` entry is talking about.
 *
 * Pure, and separated from the applier for one reason: this is the part that
 * can be wrong in ways nobody notices. A mismatch does not throw -- it silently
 * writes one track's title onto another, or writes nothing at all and leaves
 * the index looking like it was ignored. So it is a function over plain data
 * with tests, rather than a few lines buried in a coroutine.
 *
 * Entries are matched on the FILE, never on a title or a track number. Those
 * are exactly what an edit changes, and matching on the thing being corrected
 * is how a correction fails to apply.
 */
data class DocMatch(val trackId: Long, val entry: AlbumTrackEntry)

data class DocMatches(
    val matched: List<DocMatch>,
    /**
     * Tracks the document used to describe and no longer mentions.
     *
     * Their fromDoc flag has to be cleared or they stay frozen on values no
     * file claims any more -- the tag pass would never touch them again and
     * nothing on screen would explain why.
     */
    val orphaned: List<Long>,
)

object DocMatcher {

    /**
     * Where a track sits relative to the album folder its index lives in.
     *
     * A deluxe edition keeps its discs in subfolders, and the index names them
     * `Disc 1/01 Track.mp3` -- so this is a path, not a filename. Null when the
     * track is not below [albumFolder] at all, which is not an error: an
     * `external` entry is handled by the other pass.
     */
    fun relativeLocator(albumFolder: String, folderPath: String?, fileName: String?): String? {
        // Captured to locals: both are nullable properties from another module,
        // where Kotlin cannot smart cast across the module boundary.
        val name = fileName?.trim().orEmpty()
        if (name.isEmpty()) return null

        val album = LibraryDocs.normalisePath(albumFolder)
        val folder = LibraryDocs.normalisePath(folderPath.orEmpty())

        val below = when {
            folder == album -> ""
            album.isEmpty() -> folder
            folder.startsWith("$album/") -> folder.substring(album.length + 1)
            // Not below the album folder. A prefix match alone would have
            // "Oasis/Definitely Maybe" swallow "Oasis/Definitely Maybe Live",
            // which is why the slash above is part of the test.
            else -> return null
        }

        val leaf = LibraryDocs.normalisePath(name)
        return if (below.isEmpty()) leaf else "$below/$leaf"
    }

    /**
     * Matches the tracks found below an album folder against its index.
     *
     * A track with no entry is left completely alone unless it was previously
     * described by this document. That is the rule that makes the whole feature
     * safe to ship: a library with no json behaves exactly as it does today,
     * and so does every track a json happens not to mention.
     */
    fun matchLocal(
        albumFolder: String,
        doc: AlbumDoc,
        rows: List<FolderTrackRow>,
    ): DocMatches {
        val byLocator = doc.byLocator()
        val matched = ArrayList<DocMatch>(rows.size)
        val orphaned = ArrayList<Long>()

        for (row in rows) {
            // Not below the album folder, so none of this document's business.
            // The query that produced these rows uses LIKE, where an underscore
            // in a folder name is a single-character wildcard -- so it can hand
            // back a neighbouring album, and releasing ITS tracks would be a
            // silent corruption rather than a missed match.
            val locator = relativeLocator(albumFolder, row.folderPath, row.fileName) ?: continue

            val entry = byLocator[locator]
            when {
                entry != null -> matched += DocMatch(row.id, entry)
                row.fromDoc -> orphaned += row.id
            }
        }
        return DocMatches(matched, orphaned)
    }

    /**
     * The full paths, from the library root, that this document's `external`
     * entries point at. Tidied but not case folded -- this is what goes to the
     * query, where the stored path has its real case.
     */
    fun externalPaths(doc: AlbumDoc): List<String> =
        doc.tracks.mapNotNull { it.external?.path }
            .map { LibraryDocs.tidyPath(it) }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * Matches rows fetched by full path back to the entries that named them.
     *
     * The query narrows and this decides, rather than the query deciding: SQL
     * would have to agree with [LibraryDocs.normalisePath] about separators and
     * case, and two implementations of that would eventually disagree.
     */
    fun matchExternal(doc: AlbumDoc, rows: List<FolderTrackRow>): List<DocMatch> {
        val byPath = doc.tracks
            .mapNotNull { entry -> entry.external?.path?.let { LibraryDocs.normalisePath(it) to entry } }
            .toMap()
        if (byPath.isEmpty()) return emptyList()

        return rows.mapNotNull { row ->
            val folder = row.folderPath.orEmpty()
            val name = row.fileName?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null

            val full = if (folder.isEmpty()) name else "$folder/$name"
            byPath[LibraryDocs.normalisePath(full)]?.let { DocMatch(row.id, it) }
        }
    }
}
