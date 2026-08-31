package app.roam.feature.downloader

import app.roam.core.model.FolderNames

/**
 * What a downloaded track is called on the source.
 *
 * `01 Supersonic.m4a` -- the number, a space, the title, the extension. Nothing
 * else. It used to append " - Artist", which is redundant everywhere it lands:
 * the file is already inside `Artist/Album (Year)/`, and on a compilation the
 * album artist is Various Artists, so the suffix named the wrong person anyway.
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
     */
    fun of(trackNo: Int, title: String, extension: String): String {
        val safeTitle = FolderNames.sanitise(title).ifBlank { UNKNOWN_TITLE }
        val safeExtension = extension.trim()
            .removePrefix(".")
            .filter { it.isLetterOrDigit() }
            .ifBlank { DEFAULT_EXTENSION }

        return buildString {
            if (trackNo > 0) append("%02d ".format(trackNo))
            append(safeTitle)
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
