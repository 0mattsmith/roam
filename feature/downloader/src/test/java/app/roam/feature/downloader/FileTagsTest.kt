package app.roam.feature.downloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The info file yt-dlp tags a finished download from. What it holds becomes
 * the file's tags, so the names have to be the ones yt-dlp writes from -- and
 * what it must NOT hold matters as much.
 */
class FileTagsTest {

    private val single = FileTags(
        title = "Whatever",
        artist = "Oasis",
        album = "Whatever - Single",
        albumArtist = "Oasis",
        year = 1994,
        trackNo = 1,
        compilation = false,
    )

    @Test
    fun `the answers go in under the names yt-dlp writes tags from`() {
        val info = JSONObject(single.infoJson("mp3", "https://archive.org/download/x/01.mp3"))
        assertEquals("Whatever", info.getString("track"))
        assertEquals("Whatever", info.getString("title"))
        assertEquals("Oasis", info.getString("artist"))
        assertEquals("Whatever - Single", info.getString("album"))
        assertEquals("Oasis", info.getString("album_artist"))
        assertEquals(1, info.getInt("track_number"))
        assertEquals("1994", info.getString("meta_date"))
        assertEquals("mp3", info.getString("ext"))
        assertFalse(info.has("meta_compilation"))
    }

    @Test
    fun `nothing that sends yt-dlp back to the network or into every comment`() {
        val info = JSONObject(single.infoJson("m4a", "https://music.youtube.com/watch?v=abcdefghijk"))
        assertFalse(info.has("webpage_url"))
        assertFalse(info.has("description"))
    }

    @Test
    fun `a compilation says so, and an unknown answer is left out rather than blank`() {
        val pooled = FileTags(
            title = "Whatever",
            artist = "Oasis",
            album = null,
            albumArtist = " ",
            year = null,
            trackNo = 0,
            compilation = true,
        )
        val info = JSONObject(pooled.infoJson("opus", "https://x"))
        assertEquals("1", info.getString("meta_compilation"))
        assertFalse(info.has("album"))
        assertFalse(info.has("album_artist"))
        assertFalse(info.has("track_number"))
        assertFalse(info.has("meta_date"))
    }
}
