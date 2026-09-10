package app.roam.feature.downloader

import app.roam.core.model.FolderNames

/**
 * What a downloaded track is called on the source.
 *
 * `01 Supersonic.m4a` -- the number, a space, the title, the extension.
 *
 * A COMPILATION gets one thing more: `01 Never Gonna Give You Up - Rick
 * Astley.mp3`. The rule behind that is worth stating, because it is not "more
 * information is better" -- the artist is included exactly when the FOLDER does
 * not already say it. On a normal album the file sits inside `Oasis/Definitely
 * Maybe (1994)/` and the suffix would only repeat the parent; on a compilation
 * the folder is Various Artists and the filename is the sole place the
 * performer appears at all.
 *
 * It matters more than tidiness. This is the convention the rest of the library
 * already uses, so downloads stop being the odd ones out -- and plenty of car
 * head units and older MP3 players sort by FILENAME rather than by tags, which
 * makes the leading number the actual track order rather than decoration.
 */
internal object TrackFileName {

    /**
     * @param trackNo zero or less when nothing knows it, in which case the
     * number is left off rather than invented. A wrong track number is worse
     * than none: it silently reorders an album on any player that sorts by
     * name, and nothing about the file says why.
     * @param artist the performer, passed ONLY for a compilation. Null
     * everywhere else, so the decision lives at the one call site that knows
     * whether the folder has already answered the question.
     */
    fun of(trackNo: Int, title: String, extension: String, artist: String? = null): String {
        val safeTitle = FolderNames.sanitise(title).ifBlank { UNKNOWN_TITLE }
        val safeArtist = FolderNames.sanitise(artist.orEmpty())
        val safeExtension = extension.trim()
            .removePrefix(".")
            .filter { it.isLetterOrDigit() }
            .ifBlank { DEFAULT_EXTENSION }

        return buildString {
            if (trackNo > 0) append("%02d ".format(trackNo))
            append(safeTitle)
            // Dropped when it sanitises away to nothing, rather than leaving a
            // trailing " - " that looks like the name was truncated.
            if (safeArtist.isNotEmpty()) {
                append(" - ")
                append(safeArtist)
            }
            append('.')
            append(safeExtension)
        }
    }

    const val UNKNOWN_TITLE = "Untitled"

    /**
     * What YouTube serves for audio-only far more often than not, and the right
     * guess when a fetch produced a file with no extension at all.
     */
    const val DEFAULT_EXTENSION = "m4a"
}
