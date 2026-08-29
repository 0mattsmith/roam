package app.roam.feature.downloader

import app.roam.core.model.FolderNames
import app.roam.data.catalog.metadata.ReleaseMatch
import app.roam.data.source.SourceProvider

/**
 * What somebody was asked before a download starts, and where it puts the file.
 *
 * Five questions, because five is what it takes to know which folder this
 * belongs in and no more. Everything else the metadata editor already does
 * better, afterwards, with the track in front of you.
 */
data class AlbumPlacement(
    /**
     * The performer. Never the filing name on a compilation.
     *
     * With [compilation] set the album artist is Various Artists and that is
     * what the folder takes, so this cannot send a track anywhere -- which is
     * why it is safe to leave blank on an album, where every track already
     * carries its own credit. What must not happen is a hundred artist folders
     * appearing for a hundred-track compilation.
     *
     * A SINGLE track off a compilation is the same question with the opposite
     * answer: one track is a compilation too, and here this field is the only
     * place its performer can come from, because blank falls through to
     * whatever the search result was called. The dialog says so; see
     * [trackArtist].
     */
    val artist: String = "",
    val album: String = "",
    /** Blank means "same as the artist", which is what a normal album means. */
    val albumArtist: String = "",
    val year: Int? = null,
    val compilation: Boolean = false,
) {
    /**
     * Who the album files under. The ALBUM ARTIST decides, not the flag.
     *
     * These were wired together and should not have been: a greatest-hits by
     * one act is a compilation with a perfectly good album artist, and filing
     * it under Various Artists is wrong. What must not happen -- a hundred
     * artist folders for a hundred-track collection -- is prevented by the
     * album artist saying "Various Artists", which is what the catalogues
     * actually report for one.
     *
     * So the flag is left to mean what it means in a tag, and the folder
     * follows the field that names a folder.
     */
    val filingArtist: String
        get() = when {
            albumArtist.isNotBlank() -> albumArtist.trim()
            compilation -> FolderNames.VARIOUS_ARTISTS
            artist.isNotBlank() -> artist.trim()
            else -> UNKNOWN_ARTIST
        }

    /** Whether this files under the shared folder rather than an artist's own. */
    val filesUnderVariousArtists: Boolean
        get() = FolderNames.key(filingArtist) == FolderNames.key(FolderNames.VARIOUS_ARTISTS)

    /** What goes in the track's own metadata, which is not the same question. */
    fun trackArtist(fallback: String): String =
        artist.trim().ifBlank { fallback.trim() }.ifBlank { UNKNOWN_ARTIST }

    val albumOrSingles: String get() = album.trim().ifBlank { SINGLES }

    /** Enough answered to place a file without guessing. */
    val isComplete: Boolean get() = album.isNotBlank() && (compilation || artist.isNotBlank() ||
        albumArtist.isNotBlank())

    companion object {
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val SINGLES = "Singles"

        /**
         * Filled in from the catalogue, which already knows all five.
         *
         * The dialog is a confirmation rather than a form: Discogs and
         * MusicBrainz are asked before anything is queued, and they carry the
         * artist, the title, the year and whether this is a various-artists
         * collection. Retyping what a catalogue already told us is how a
         * dialog becomes something people dismiss without reading.
         *
         * The artist is blanked ONLY when the credit really is "Various
         * Artists", never merely because the compilation flag is set. Keying
         * it on the flag emptied the field for every greatest-hits and every
         * release whose catalogue entry carries the flag loosely -- throwing
         * away a name the catalogue had supplied, on a screen whose whole job
         * is to show what it supplied.
         */
        fun from(match: ReleaseMatch): AlbumPlacement {
            val credit = match.artist.trim()
            val various = FolderNames.key(credit) ==
                FolderNames.key(FolderNames.VARIOUS_ARTISTS)

            return AlbumPlacement(
                artist = if (various) "" else credit,
                album = match.title,
                // Canonically spelled when it is the shared folder. Catalogues
                // write it several ways and this names a directory, so taking
                // the credit verbatim would put "various artists" beside
                // "Various Artists" the first time a source disagreed.
                albumArtist = if (various) FolderNames.VARIOUS_ARTISTS else credit,
                year = match.year,
                compilation = match.isCompilation,
            )
        }

        /**
         * Filled in from a search result, which knows much less.
         *
         * A flat row has a channel name where an artist belongs and no album at
         * all, so this is a starting point rather than an answer -- which is
         * exactly why the dialog exists for single tracks and could almost be
         * skipped for catalogue albums.
         */
        fun from(result: YoutubeResult): AlbumPlacement = AlbumPlacement(
            artist = result.artist,
            album = result.album.orEmpty(),
            albumArtist = result.artist,
            year = result.year,
            compilation = false,
        )
    }
}

/**
 * A download waiting on somebody to confirm where it goes.
 *
 * Requests are built up front and the placement applied on confirm, rather than
 * the dialog handing back a callback: this survives a rotation and a lambda
 * does not.
 */
data class PendingDownload(
    val placement: AlbumPlacement,
    val requests: List<DownloadRequest>,
) {
    val trackCount: Int get() = requests.size
}

/**
 * Turns a placement into the two folder names a download is written under.
 *
 * The reason this asks the source rather than just building a name: an album
 * may already have a home under a different spelling. "100 Hits: 80s Pop" and
 * "100 Hits - 80s Pop" are one record, and creating the second beside the first
 * gives you two half-albums that no amount of re-syncing will merge.
 *
 * Two lookups at most -- the artist's folders, then that artist's album folders
 * -- and only when something needs placing, so this is not on any hot path.
 */
suspend fun AlbumPlacement.folderSegments(
    provider: SourceProvider,
    root: String,
): List<String> {
    val wantedArtist = filingArtist
    val wantedAlbum = albumOrSingles

    // The artist first. Match past punctuation -- "AC/DC" cannot be a folder
    // name at all, so the one on the source is already spelled differently.
    val artistFolder = runCatching { provider.listFolders(root) }
        .getOrNull()
        ?.let { FolderNames.matchArtist(wantedArtist, it) }
        ?: FolderNames.sanitise(wantedArtist).ifBlank { AlbumPlacement.UNKNOWN_ARTIST }

    // Then the album, inside whichever artist folder that turned out to be. A
    // missing artist folder means a missing album folder, so there is nothing
    // to match against and the ideal name is the answer.
    val albumFolder = runCatching {
        provider.resolveFolder(root, listOf(artistFolder), create = false)
            ?.let { provider.listFolders(it) }
    }.getOrNull()
        ?.let { FolderNames.matchAlbum(wantedAlbum, year, it) }
        ?: FolderNames.albumFolder(wantedAlbum, year)

    return listOf(artistFolder, albumFolder)
}
