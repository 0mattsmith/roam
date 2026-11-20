package app.roam.data.catalog.metadata

/**
 * What the downloader knows about a track it has just put on the source.
 *
 * Everything here was settled before the file was written -- the catalogue's
 * answer, confirmed in the placement dialog -- which is what makes it safe to
 * write down. A title guessed from a file name would not be: json outranks the
 * tags, so a recorded guess is never corrected (see DocTrackRow.isGuess).
 */
data class DownloadedTrack(
    /** As written on the source, e.g. `01 Supersonic.m4a`. */
    val fileName: String,
    /** Exactly as the catalogue spells it, with what the file name had to lose. */
    val title: String,
    /** Who performs it. */
    val artist: String,
    /** Without the year, which has a field of its own. */
    val albumTitle: String,
    /** Who the album files under -- the name on its folder. */
    val albumArtist: String,
    val year: Int?,
    val compilation: Boolean,
    /** Null when nothing knows it, rather than an invented one. */
    val trackNo: Int?,
)
