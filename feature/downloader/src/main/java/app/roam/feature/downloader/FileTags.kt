package app.roam.feature.downloader

import org.json.JSONObject

/**
 * The tags a downloaded file is given before it goes up: the same answers its
 * `<track>.json` and `album.json` carry, written into the file in the file's
 * own format -- ID3 in an MP3, MP4 atoms in an M4A, Vorbis comments in the
 * rest -- so the track keeps them wherever it ends up: Plex, Kodi, a folder
 * copied off Drive.
 */
data class FileTags(
    val title: String,
    val artist: String,
    /** Null for the artist's pool of singles, which is not an album. */
    val album: String?,
    val albumArtist: String,
    val year: Int?,
    val trackNo: Int?,
    val compilation: Boolean,
) {
    /**
     * yt-dlp's own description of a download, holding these answers and
     * nothing else -- which is what lets its metadata step tag a file it did
     * not fetch (see [YoutubeSource.finish]).
     *
     * Deliberately absent: `webpage_url`, because when anything about an info
     * file goes wrong yt-dlp falls back to downloading that address afresh;
     * and any description, which it would write into every track's comment.
     * An answer that is not known is left out rather than written blank.
     */
    fun infoJson(ext: String, url: String): String {
        val info = JSONObject()
            .put("_type", "video")
            .put("id", "roam")
            .put("extractor", "generic")
            .put("extractor_key", "Generic")
            .put("url", url)
            .put("ext", ext)
            .put("vcodec", "none")
        info.putText("title", title)
        info.putText("track", title)
        info.putText("artist", artist)
        info.putText("album", album)
        info.putText("album_artist", albumArtist)
        trackNo?.takeIf { it > 0 }?.let { info.put("track_number", it) }
        // meta_ overrides what yt-dlp would derive: the date would otherwise
        // come from an upload date there is none of.
        year?.let { info.put("meta_date", it.toString()) }
        if (compilation) info.put("meta_compilation", "1")
        return info.toString()
    }

    private fun JSONObject.putText(key: String, value: String?) {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { put(key, it) }
    }
}
