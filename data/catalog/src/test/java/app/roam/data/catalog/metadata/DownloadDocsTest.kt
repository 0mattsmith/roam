package app.roam.data.catalog.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the downloader writes beside a file it adds. The failures that matter
 * are quiet ones: a year back in the title, an entry dropped from an index
 * another track already wrote, a field the desktop editor added that is gone.
 */
class DownloadDocsTest {

    private val stamp = "2026-11-19T10:00:00Z"

    private fun track(
        fileName: String = "02 Wonderwall.m4a",
        title: String = "Wonderwall",
        artist: String = "Oasis",
        album: String = "(What's the Story) Morning Glory?",
        albumArtist: String = "Oasis",
        year: Int? = 1995,
        compilation: Boolean = false,
        trackNo: Int? = 2,
    ) = DownloadedTrack(
        fileName = fileName,
        title = title,
        artist = artist,
        albumTitle = album,
        albumArtist = albumArtist,
        year = year,
        compilation = compilation,
        trackNo = trackNo,
    )

    @Test
    fun `a new index keeps the year out of the title and the question mark in it`() {
        val doc = JSONObject(DocBuilder.withDownloadedEntry(null, track(), stamp))
        assertEquals("(What's the Story) Morning Glory?", doc.getString("album_title"))
        assertEquals(1995, doc.getInt("year"))
        assertEquals("Oasis", doc.getString("album_artist"))

        val entry = doc.getJSONArray("tracks").getJSONObject(0)
        assertEquals("02 Wonderwall.m4a", entry.getString("file"))
        assertEquals("02 Wonderwall.json", entry.getString("track_meta"))
        assertEquals(2, entry.getInt("track"))
        assertFalse("the artist is only stated when it differs", entry.has("artist"))
    }

    @Test
    fun `what it writes reads back as an album document`() {
        val parsed = LibraryDocs.album(DocBuilder.withDownloadedEntry(null, track(), stamp))
        assertNotNull(parsed)
        assertEquals("(What's the Story) Morning Glory?", parsed!!.albumTitle)
        assertEquals(1995, parsed.year)
        assertEquals("02 Wonderwall.m4a", parsed.tracks.single().file)
    }

    @Test
    fun `an existing document keeps its album and everything it does not know`() {
        val existing = JSONObject(
            """
            {"schema": 1, "album_artist": "Oasis", "album_title": "Morning Glory (Remastered)",
             "year": 2014, "desktop_note": "mine",
             "tracks": [{"file": "01 Hello.m4a", "title": "Hello", "lyrics": "01 Hello.lrc", "rating": 5}]}
            """.trimIndent()
        )
        val doc = JSONObject(DocBuilder.withDownloadedEntry(existing, track(), stamp))
        assertEquals("Morning Glory (Remastered)", doc.getString("album_title"))
        assertEquals(2014, doc.getInt("year"))
        assertEquals("mine", doc.getString("desktop_note"))

        val tracks = doc.getJSONArray("tracks")
        assertEquals(2, tracks.length())
        assertEquals(5, tracks.getJSONObject(0).getInt("rating"))
        assertEquals("01 Hello.lrc", tracks.getJSONObject(0).getString("lyrics"))
        assertEquals("02 Wonderwall.m4a", tracks.getJSONObject(1).getString("file"))
    }

    @Test
    fun `downloading the same file again replaces its entry rather than adding one`() {
        val first = JSONObject(DocBuilder.withDownloadedEntry(null, track(title = "Wonderwal"), stamp))
        val again = JSONObject(DocBuilder.withDownloadedEntry(first, track(), stamp))
        val tracks = again.getJSONArray("tracks")
        assertEquals(1, tracks.length())
        assertEquals("Wonderwall", tracks.getJSONObject(0).getString("title"))
    }

    @Test
    fun `a compilation entry says who performs it`() {
        val doc = JSONObject(
            DocBuilder.withDownloadedEntry(
                null,
                track(
                    fileName = "03 Song - Performer.m4a",
                    title = "Song",
                    artist = "Performer",
                    album = "Now 48",
                    albumArtist = "Various Artists",
                    compilation = true,
                    trackNo = 3,
                ),
                stamp,
            )
        )
        assertTrue(doc.getBoolean("is_compilation"))
        assertEquals("Performer", doc.getJSONArray("tracks").getJSONObject(0).getString("artist"))
    }

    @Test
    fun `the track's own file repeats the album and keeps what it already said`() {
        val existing = JSONObject("""{"schema": 1, "title": "Wonderwal", "lyrics_checked": "2026-08-22"}""")
        val doc = JSONObject(DocBuilder.downloadedTrackDocument(existing, track(), stamp))
        assertEquals("Wonderwall", doc.getString("title"))
        assertEquals("(What's the Story) Morning Glory?", doc.getString("album"))
        assertEquals(1995, doc.getInt("year"))
        assertEquals("02 Wonderwall.m4a", doc.getString("audio_file"))
        assertEquals("2026-08-22", doc.getString("lyrics_checked"))
    }

    @Test
    fun `keys come out in the documented order however they went in`() {
        val existing = JSONObject("""{"tracks": [], "album_title": "X", "album_artist": "Y", "schema": 1}""")
        val text = DocBuilder.withDownloadedEntry(existing, track(), stamp)
        assertTrue(text.indexOf("\"schema\"") < text.indexOf("\"album_artist\""))
        assertTrue(text.indexOf("\"album_artist\"") < text.indexOf("\"album_title\""))
        assertTrue(text.indexOf("\"album_title\"") < text.indexOf("\"tracks\""))
    }
}
