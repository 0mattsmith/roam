package app.roam.feature.downloader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The places after YouTube, read into candidates. The fixtures are the shapes
 * each one answers in; what matters is that the right file is the one picked,
 * and that anything unexpected reads as nothing rather than as an error.
 */
class SourceResultsTest {

    // ---- the Internet Archive -----------------------------------------------

    private val item = JSONObject(
        """
        {
          "metadata": { "identifier": "oasis-dm", "title": "Oasis - Definitely Maybe", "creator": ["Oasis"] },
          "files": [
            { "name": "01 - Rock 'n' Roll Star.flac", "source": "original", "format": "Flac",
              "title": "Rock 'n' Roll Star", "artist": "Oasis", "album": "Definitely Maybe", "length": "323.45" },
            { "name": "01 - Rock 'n' Roll Star.mp3", "source": "derivative", "format": "VBR MP3",
              "original": "01 - Rock 'n' Roll Star.flac", "length": "05:23" },
            { "name": "01 - Rock 'n' Roll Star.ogg", "source": "derivative", "format": "Ogg Vorbis",
              "original": "01 - Rock 'n' Roll Star.flac" },
            { "name": "Disc 2/05_Whatever.mp3", "source": "original", "format": "128Kbps MP3", "length": "381" },
            { "name": "cover.jpg", "source": "original", "format": "JPEG" },
            { "name": "oasis-dm_archive.torrent", "source": "metadata", "format": "Archive BitTorrent" }
          ]
        }
        """.trimIndent()
    )

    private val files = SourceResults.archiveFiles("oasis-dm", item)

    @Test
    fun `one candidate per track, whatever formats the archive made of it`() {
        assertEquals(2, files.size)
    }

    @Test
    fun `the archive's own MP3 is fetched rather than the lossless upload`() {
        val star = files.first { it.title == "Rock 'n' Roll Star" }
        assertEquals("mp3", star.fileExtension)
        assertEquals(
            "https://archive.org/download/oasis-dm/01%20-%20Rock%20%27n%27%20Roll%20Star.mp3",
            star.url,
        )
    }

    @Test
    fun `the tags are read from whichever copy has them`() {
        val star = files.first { it.title == "Rock 'n' Roll Star" }
        assertEquals("Oasis", star.creditedArtist)
        assertEquals("Definitely Maybe", star.album)
        assertEquals(323, star.durationSec)
    }

    @Test
    fun `an untagged file is named from its path and filed under the item`() {
        val whatever = files.first { it.title == "Whatever" }
        // The act's name is taken off the item's title to leave the album.
        assertEquals("Definitely Maybe", whatever.album)
        assertEquals(381, whatever.durationSec)
        assertEquals("https://archive.org/download/oasis-dm/Disc%202/05_Whatever.mp3", whatever.url)
    }

    @Test
    fun `items come from an advanced search`() {
        val found = JSONObject("""{"response":{"numFound":2,"docs":[{"identifier":"a"},{"identifier":"b"}]}}""")
        assertEquals(listOf("a", "b"), SourceResults.archiveItems(found))
    }

    // ---- Bandcamp -----------------------------------------------------------

    @Test
    fun `Bandcamp's search yields track pages only, tracking parameters off`() {
        val found = JSONObject(
            """
            {"auto":{"results":[
              {"type":"b","name":"Oasis","item_url_path":"https://oasis.bandcamp.com"},
              {"type":"t","name":"Whatever","band_name":"Oasis","item_url_path":"https://oasis.bandcamp.com/track/whatever?from=search"},
              {"type":"a","name":"Whatever","item_url_path":"https://oasis.bandcamp.com/album/whatever"},
              {"type":"t","name":"Half the World Away","item_url_root":"https://oasis.bandcamp.com","item_url_path":"/track/half-the-world-away"}
            ]}}
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "https://oasis.bandcamp.com/track/whatever",
                "https://oasis.bandcamp.com/track/half-the-world-away",
            ),
            SourceResults.bandcampLinks(found),
        )
    }

    @Test
    fun `an answer in another shape is no answer`() {
        assertTrue(SourceResults.bandcampLinks(JSONObject("""{"results":[]}""")).isEmpty())
        assertTrue(SourceResults.archiveFiles("x", JSONObject("{}")).isEmpty())
    }

    // ---- Audius and Jamendo -------------------------------------------------

    @Test
    fun `Audius tracks that anyone can stream`() {
        val found = JSONObject(
            """
            {"data":[
              {"id":"D7KyD","title":"Whatever","duration":381,"user":{"name":"Oasis"}},
              {"id":"Gated","title":"Whatever","duration":381,"user":{"name":"Oasis"},"is_stream_gated":true},
              {"id":"Off","title":"Whatever","duration":381,"user":{"name":"Oasis"},"is_streamable":false}
            ]}
            """.trimIndent()
        )
        val tracks = SourceResults.audiusTracks(found, "Roam")
        assertEquals(1, tracks.size)
        assertEquals("https://api.audius.co/v1/tracks/D7KyD/stream?app_name=Roam", tracks.single().url)
        assertEquals("Oasis", tracks.single().creditedArtist)
        assertEquals(381, tracks.single().durationSec)
    }

    @Test
    fun `Jamendo tracks only where the artist allows downloading`() {
        val found = JSONObject(
            """
            {"headers":{"status":"success","code":0},"results":[
              {"id":"887202","name":"Press Record","duration":"192","artist_name":"We Are FM","album_name":"Season One",
               "audiodownload":"https://prod-1.storage.jamendo.com/download/track/887202/mp32/","audiodownload_allowed":true},
              {"id":"887203","name":"No Words","duration":"338","artist_name":"We Are FM",
               "audiodownload":"","audiodownload_allowed":false}
            ]}
            """.trimIndent()
        )
        val tracks = SourceResults.jamendoTracks(found)
        assertEquals(1, tracks.size)
        assertEquals("Season One", tracks.single().album)
        assertEquals(192, tracks.single().durationSec)
        assertEquals("mp3", tracks.single().fileExtension)
    }

    @Test
    fun `a Jamendo error is no answer`() {
        val refused = JSONObject("""{"headers":{"status":"failed","code":5},"results":[]}""")
        assertTrue(SourceResults.jamendoTracks(refused).isEmpty())
    }

    // ---- shared -------------------------------------------------------------

    @Test
    fun `lengths in every form the archive writes them`() {
        assertEquals(323, SourceResults.seconds("323.45"))
        assertEquals(323, SourceResults.seconds("05:23"))
        assertEquals(3723, SourceResults.seconds("1:02:03"))
        assertNull(SourceResults.seconds(""))
        assertNull(SourceResults.seconds("about four minutes"))
    }

    @Test
    fun `a file's name without its number is its title`() {
        assertEquals("Rock 'n' Roll Star", SourceResults.titleFromName("01 - Rock 'n' Roll Star.flac"))
        assertEquals("Wonderwall", SourceResults.titleFromName("Disc 1/03_Wonderwall.mp3"))
        // A song called 2002 keeps its name.
        assertEquals("2002", SourceResults.titleFromName("2002.mp3"))
        assertEquals("2002", SourceResults.titleFromName("01 2002.mp3"))
    }
}
