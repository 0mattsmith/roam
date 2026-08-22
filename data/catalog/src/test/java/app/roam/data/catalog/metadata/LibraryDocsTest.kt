package app.roam.data.catalog.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These files are written by hand and by an external editor, so the parser has
 * to survive anything and refuse rather than guess. Every case here is one a
 * real library will produce.
 */
class LibraryDocsTest {

    // ---- artist.json --------------------------------------------------------

    @Test
    fun `reads an artist`() {
        val doc = LibraryDocs.artist(
            """
            {
              "schema": 1,
              "artist_name": "Oasis",
              "active_from": 1991,
              "active_to": null,
              "debut_album": "Definitely Maybe",
              "debut_album_year": 1994,
              "total_studio_albums": 7,
              "artist_info": "Formed in Manchester in 1991.",
              "artist_image": "artist.jpg",
              "artist_logo": "logo.jpg",
              "sort_as": "Oasis"
            }
            """
        )!!
        assertEquals("Oasis", doc.artistName)
        assertEquals(1991, doc.activeFrom)
        assertEquals("Definitely Maybe", doc.debutAlbum)
        assertEquals("logo.jpg", doc.artistLogo)
        // Still active. Must be null, NOT the string "null".
        assertNull(doc.activeTo)
        // Absent entirely rather than explicitly null.
        assertNull(doc.artistBanner)
    }

    // ---- the org.json null trap ---------------------------------------------

    @Test
    fun `an explicit json null never becomes the string null`() {
        // optString on JSONObject.NULL returns "null" -- a real file named
        // null.lrc has been created by this exact mistake in other players.
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1,
              "album_artist": "Oasis",
              "album_title": "Definitely Maybe",
              "tracks": [
                { "file": "01 Track.mp3", "title": "Track", "lyrics": null }
              ]
            }
            """
        )!!
        assertNull(doc.tracks.single().lyrics)
    }

    // ---- album.json ---------------------------------------------------------

    @Test
    fun `reads an album and its entries`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1,
              "album_artist": "Oasis",
              "album_title": "Definitely Maybe (Deluxe Version)",
              "year": 1994,
              "original_year": 1994,
              "genre": "Britpop",
              "is_compilation": false,
              "total_discs": 3,
              "cover_art": "cover.jpg",
              "tracks": [
                {
                  "disc": 1, "track": 1,
                  "title": "Rock 'n' Roll Star",
                  "file": "Disc 1/01 Rock 'n' Roll Star.mp3",
                  "track_meta": "Disc 1/01 Rock 'n' Roll Star.json",
                  "lyrics": "Disc 1/01 Rock 'n' Roll Star.lrc"
                }
              ]
            }
            """
        )!!
        assertEquals("Oasis", doc.albumArtist)
        assertEquals(1994, doc.year)
        assertFalse(doc.isCompilation)
        assertEquals(3, doc.totalDiscs)

        val entry = doc.tracks.single()
        assertEquals("Disc 1/01 Rock 'n' Roll Star.mp3", entry.file)
        assertEquals("Disc 1/01 Rock 'n' Roll Star.lrc", entry.lyrics)
        assertEquals(1, entry.disc)
    }

    @Test
    fun `cover art defaults, disc defaults to one`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "A", "album_title": "B",
              "tracks": [ { "file": "01.mp3", "title": "T" } ]
            }
            """
        )!!
        assertEquals("cover.jpg", doc.coverArt)
        assertEquals(1, doc.tracks.single().disc)
    }

    @Test
    fun `an album without artist or title is not an album`() {
        // Refused rather than half-read: the folder path is a better answer
        // than a document missing the two things that identify it.
        assertNull(LibraryDocs.album("""{ "schema": 1, "album_title": "B" }"""))
        assertNull(LibraryDocs.album("""{ "schema": 1, "album_artist": "A" }"""))
    }

    @Test
    fun `an entry naming no file at all is dropped`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "A", "album_title": "B",
              "tracks": [
                { "title": "Nothing points at me" },
                { "file": "02.mp3", "title": "Real" }
              ]
            }
            """
        )!!
        assertEquals(1, doc.tracks.size)
        assertEquals("Real", doc.tracks.single().title)
    }

    // ---- a track that lives somewhere else -----------------------------------

    @Test
    fun `reads an external entry and uses its path as the locator`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "Oasis", "album_title": "The Masterplan",
              "tracks": [
                {
                  "disc": 1, "track": 4,
                  "title": "Half the World Away",
                  "file": null,
                  "external": {
                    "path": "Oasis/Some Other Album (1997)/10 Half the World Away.mp3",
                    "source": "drive",
                    "file_id": "1a2b3c",
                    "folder_id": "9x8y7z"
                  }
                }
              ]
            }
            """
        )!!
        val entry = doc.tracks.single()
        assertNull(entry.file)
        assertEquals("drive", entry.external!!.source)
        assertEquals("1a2b3c", entry.external!!.fileId)
        assertEquals(
            "Oasis/Some Other Album (1997)/10 Half the World Away.mp3",
            entry.locator,
        )
    }

    @Test
    fun `an external block with no path is not usable`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "A", "album_title": "B",
              "tracks": [ { "title": "T", "external": { "source": "drive" } } ]
            }
            """
        )!!
        assertTrue(doc.tracks.isEmpty())
    }

    // ---- track.json ---------------------------------------------------------

    @Test
    fun `reads a track and converts its duration`() {
        val doc = LibraryDocs.track(
            """
            {
              "schema": 1,
              "title": "Rock 'n' Roll Star",
              "track_number": 1,
              "disc_number": 1,
              "artist": "Oasis",
              "album": "Definitely Maybe",
              "album_artist": "Oasis",
              "year": 1994,
              "composer": "Noel Gallagher",
              "duration_seconds": 323,
              "audio_file": "01 Rock 'n' Roll Star.mp3",
              "lyrics_file": null
            }
            """
        )!!
        assertEquals(323_000L, doc.durationMs)
        assertEquals("Noel Gallagher", doc.composer)
        assertNull(doc.lyricsFile)
        // Absent, and absent must stay absent -- a duration is not a trim.
        assertNull(doc.startMs)
        assertNull(doc.endMs)
    }

    @Test
    fun `trim points are read when they are really there`() {
        val doc = LibraryDocs.track(
            """
            {
              "schema": 1, "title": "T",
              "start_at": "00:12", "end_at": "1:02:03"
            }
            """
        )!!
        assertEquals(12_000L, doc.startMs)
        assertEquals((3600 + 120 + 3) * 1000L, doc.endMs)
    }

    // ---- schema -------------------------------------------------------------

    @Test
    fun `a newer schema is refused rather than half read`() {
        assertNull(LibraryDocs.album("""{ "schema": 99, "album_artist": "A", "album_title": "B" }"""))
        assertNull(LibraryDocs.artist("""{ "schema": 99, "artist_name": "A" }"""))
    }

    @Test
    fun `no schema at all is refused`() {
        assertNull(LibraryDocs.album("""{ "album_artist": "A", "album_title": "B" }"""))
    }

    @Test
    fun `malformed json is refused, not thrown`() {
        assertNull(LibraryDocs.album("not json at all"))
        assertNull(LibraryDocs.album(""))
        assertNull(LibraryDocs.track("{ broken"))
    }

    @Test
    fun `numbers written as strings still read`() {
        // Hand-edited files do this constantly.
        val doc = LibraryDocs.album(
            """
            {
              "schema": "1", "album_artist": "A", "album_title": "B", "year": "1994",
              "tracks": [ { "file": "01.mp3", "title": "T", "track": "3" } ]
            }
            """
        )!!
        assertEquals(1994, doc.year)
        assertEquals(3, doc.tracks.single().track)
    }

    // ---- paths --------------------------------------------------------------

    @Test
    fun `paths compare the way people actually write them`() {
        val expected = "disc 1/01 track.mp3"
        assertEquals(expected, LibraryDocs.normalisePath("Disc 1/01 Track.mp3"))
        assertEquals(expected, LibraryDocs.normalisePath("Disc 1\\01 Track.mp3"))
        assertEquals(expected, LibraryDocs.normalisePath("./Disc 1/01 Track.mp3"))
        assertEquals(expected, LibraryDocs.normalisePath("  Disc 1/01 Track.mp3  "))
    }

    @Test
    fun `entries are indexed by locator`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "A", "album_title": "B",
              "tracks": [
                { "file": "Disc 1/01 One.mp3", "title": "One" },
                { "file": "Disc 1/02 Two.mp3", "title": "Two" }
              ]
            }
            """
        )!!
        val index = doc.byLocator()
        assertEquals("One", index["disc 1/01 one.mp3"]?.title)
        assertEquals("Two", index["disc 1/02 two.mp3"]?.title)
    }

    // ---- the clock ----------------------------------------------------------

    @Test
    fun `clock formats`() {
        assertEquals(0L, LibraryDocs.parseClock("00:00"))
        assertEquals(63_000L, LibraryDocs.parseClock("1:03"))
        assertEquals(3_723_000L, LibraryDocs.parseClock("01:02:03"))
    }

    @Test
    fun `a nonsense clock is null rather than zero`() {
        // Zero is a POSITION. Reading a typo as "start at the beginning" would
        // silently throw away a trim someone set on purpose.
        assertNull(LibraryDocs.parseClock("banana"))
        assertNull(LibraryDocs.parseClock("12"))
        assertNull(LibraryDocs.parseClock("1:2:3:4"))
        assertNull(LibraryDocs.parseClock("1:75"))
        assertNull(LibraryDocs.parseClock("-1:00"))
        assertNull(LibraryDocs.parseClock(""))
        assertNull(LibraryDocs.parseClock(null))
    }
}
