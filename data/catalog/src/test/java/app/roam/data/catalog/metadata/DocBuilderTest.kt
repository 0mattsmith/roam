package app.roam.data.catalog.metadata

import app.roam.core.database.DocTrackRow
import app.roam.core.model.TagState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These documents are shared with an external editor and edited by hand, so the
 * ways this can go wrong are all quiet ones: a field silently dropped on a save,
 * a key order that makes every diff look total, a path that names nothing.
 */
class DocBuilderTest {

    /**
     * Named arguments throughout, deliberately. This projection has grown three
     * times and positional construction breaks silently when two adjacent
     * fields share a type -- which, with five nullable Strings in a row, is now
     * most of it.
     */
    private fun row(
        id: Long = 1,
        title: String = "Rock 'n' Roll Star",
        artist: String = "Oasis",
        albumArtist: String = "Oasis",
        album: String = "Definitely Maybe",
        compilation: Boolean = false,
        discTotal: Int = 1,
        albumSort: String? = null,
        artistSort: String? = null,
        albumArtistSort: String? = null,
        trackNo: Int? = 1,
        trackTotal: Int? = null,
        discNo: Int? = 1,
        year: Int? = 1994,
        originalYear: Int? = null,
        genre: String? = "Britpop",
        composer: String? = null,
        grouping: String? = null,
        titleSort: String? = null,
        composerSort: String? = null,
        durationMs: Long = 323_000,
        startMs: Long? = null,
        endMs: Long? = null,
        fileName: String? = "01 Rock.mp3",
        folderPath: String? = "Oasis/Definitely Maybe (1994)",
        tagState: TagState = TagState.OK,
        userEdited: Boolean = false,
        fromDoc: Boolean = false,
        lyricsAttemptedAt: Long? = null,
    ) = DocTrackRow(
        id = id,
        title = title,
        artistName = artist,
        albumArtistName = albumArtist,
        albumTitle = album,
        compilation = compilation,
        discTotal = discTotal,
        albumSort = albumSort,
        artistSort = artistSort,
        albumArtistSort = albumArtistSort,
        trackNo = trackNo,
        trackTotal = trackTotal,
        discNo = discNo,
        year = year,
        originalYear = originalYear,
        genre = genre,
        composer = composer,
        grouping = grouping,
        titleSort = titleSort,
        composerSort = composerSort,
        durationMs = durationMs,
        startMs = startMs,
        endMs = endMs,
        fileName = fileName,
        folderPath = folderPath,
        tagState = tagState,
        userEdited = userEdited,
        fromDoc = fromDoc,
        lyricsAttemptedAt = lyricsAttemptedAt,
    )

    // ---- where the index belongs --------------------------------------------

    @Test
    fun `one folder is the album folder`() {
        assertEquals(
            listOf("Oasis", "Definitely Maybe (1994)"),
            DocBuilder.albumFolder(listOf(row(), row(id = 2))),
        )
    }

    @Test
    fun `disc subfolders resolve to their parent`() {
        val folder = DocBuilder.albumFolder(
            listOf(
                row(folderPath = "Oasis/Definitely Maybe (1994)/Disc 1"),
                row(id = 2, folderPath = "Oasis/Definitely Maybe (1994)/Disc 2"),
            )
        )
        assertEquals(listOf("Oasis", "Definitely Maybe (1994)"), folder)
    }

    @Test
    fun `a shared prefix that is not a shared folder is not one`() {
        // Segment by segment, not character by character. These two share
        // fourteen characters and no folder at all.
        val folder = DocBuilder.albumFolder(
            listOf(
                row(folderPath = "Oasis/Definitely Maybe"),
                row(id = 2, folderPath = "Oasis/Definitely Maybe Live"),
            )
        )
        assertEquals(listOf("Oasis"), folder)
    }

    @Test
    fun `tracks with nothing in common have nowhere to be indexed`() {
        val folder = DocBuilder.albumFolder(
            listOf(row(folderPath = "Oasis/A"), row(id = 2, folderPath = "Blur/B"))
        )
        assertTrue(folder.isEmpty())
    }

    @Test
    fun `a locator carries the disc subfolder and the track doc sits beside the audio`() {
        val album = listOf("Oasis", "Definitely Maybe (1994)")
        val deep = row(folderPath = "Oasis/Definitely Maybe (1994)/Disc 1", fileName = "02 Shaker.mp3")
        assertEquals("Disc 1/02 Shaker.mp3", DocBuilder.locator(album, deep))
        assertEquals("01 Rock.json", DocBuilder.trackDocName("01 Rock.mp3"))
        assertEquals("Disc 1", DocBuilder.below(album, deep))
    }

    @Test
    fun `a row with no filename cannot be named`() {
        assertNull(DocBuilder.locator(listOf("Oasis"), row(fileName = null)))
    }

    // ---- what comes out -----------------------------------------------------

    @Test
    fun `an album document reads back as the album it described`() {
        val json = DocBuilder.albumDocument(null, listOf(row(), row(id = 2, trackNo = 2, title = "Shakermaker", fileName = "02 Shaker.mp3")))
        val parsed = LibraryDocs.album(json)!!

        assertEquals("Oasis", parsed.albumArtist)
        assertEquals("Definitely Maybe", parsed.albumTitle)
        assertEquals(1994, parsed.year)
        assertEquals(listOf("Britpop"), parsed.genres)
        assertEquals(2, parsed.tracks.size)
        assertEquals("01 Rock.mp3", parsed.tracks.first().file)
        assertEquals("01 Rock.json", parsed.tracks.first().trackMeta)
        assertEquals("Shakermaker", parsed.tracks[1].title)
    }

    @Test
    fun `keys come out in the documented order`() {
        // Not cosmetic. org.json's key order differs between its JVM and
        // Android implementations, so a document that inherited it would
        // reshuffle on every save and every diff would look total.
        val json = DocBuilder.albumDocument(null, listOf(row()))
        val order = Regex("^  \"(\\w+)\"", RegexOption.MULTILINE).findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "schema", "album_artist", "album_title", "year", "genres",
                "is_compilation", "total_discs", "total_tracks", "cover_art", "tracks",
            ),
            order,
        )
        assertTrue("must end with a newline", json.endsWith("\n"))
    }

    @Test
    fun `the track artist is only stated when it differs from the album's`() {
        val plain = LibraryDocs.album(DocBuilder.albumDocument(null, listOf(row())))!!
        assertNull(plain.tracks.single().artist)

        val guest = row(artist = "Ringo Starr", albumArtist = "Various Artists", compilation = true)
        val comp = LibraryDocs.album(DocBuilder.albumDocument(null, listOf(guest)))!!
        assertEquals("Ringo Starr", comp.tracks.single().artist)
        assertTrue(comp.isCompilation)
    }

    // ---- not dropping other people's work -----------------------------------

    @Test
    fun `fields this version does not understand survive a rewrite`() {
        val existing = JSONObject(
            """
            {
              "schema": 1, "album_artist": "Oasis", "album_title": "Old title",
              "cover_art": "front.jpg",
              "mood": "swaggering",
              "credits": { "producer": "Owen Morris" },
              "tracks": [
                { "file": "01 Rock.mp3", "title": "Old", "lyrics": "01 Rock.lrc", "bpm": 122 }
              ]
            }
            """
        )
        val json = DocBuilder.albumDocument(existing, listOf(row()))
        val out = JSONObject(json)

        // Roam's own fields are updated...
        assertEquals("Definitely Maybe", out.getString("album_title"))
        // ...the cover it does not manage is kept as found...
        assertEquals("front.jpg", out.getString("cover_art"))
        // ...and everything it has never heard of is still there.
        assertEquals("swaggering", out.getString("mood"))
        assertEquals("Owen Morris", out.getJSONObject("credits").getString("producer"))

        val entry = out.getJSONArray("tracks").getJSONObject(0)
        assertEquals("Rock 'n' Roll Star", entry.getString("title"))
        assertEquals(122, entry.getInt("bpm"))
        // Lyrics paths are preserved, never invented: Roam cannot see whether a
        // .lrc is beside the file without asking, and a path naming nothing is
        // worse than no path at all.
        assertEquals("01 Rock.lrc", entry.getString("lyrics"))
    }

    @Test
    fun `an entry that moved to a different file does not inherit the old one's extras`() {
        val existing = JSONObject(
            """
            {
              "schema": 1, "album_artist": "Oasis", "album_title": "Definitely Maybe",
              "tracks": [ { "file": "old name.mp3", "lyrics": "old name.lrc" } ]
            }
            """
        )
        val out = JSONObject(DocBuilder.albumDocument(existing, listOf(row())))
        val entry = out.getJSONArray("tracks").getJSONObject(0)
        assertEquals("01 Rock.mp3", entry.getString("file"))
        assertTrue("a renamed file must not carry the old entry's lyrics", !entry.has("lyrics"))
    }

    @Test
    fun `a track document keeps what it does not manage and states what it does`() {
        val existing = JSONObject("""{ "schema": 1, "composer": "Noel Gallagher", "isrc": "GBAAA9400123" }""")
        val out = JSONObject(
            DocBuilder.trackDocument(existing, row(startMs = 5_000, endMs = 300_000, lyricsAttemptedAt = 0L))
        )
        assertEquals("Noel Gallagher", out.getString("composer"))
        assertEquals("GBAAA9400123", out.getString("isrc"))
        assertEquals("Rock 'n' Roll Star", out.getString("title"))
        assertEquals("Definitely Maybe", out.getString("album"))
        assertEquals(323, out.getInt("duration_seconds"))
        assertEquals("0:05", out.getString("start_at"))
        assertEquals("5:00", out.getString("end_at"))
        assertEquals("1970-01-01", out.getString("lyrics_checked"))
    }

    @Test
    fun `a track nobody has looked for lyrics on does not claim otherwise`() {
        val out = JSONObject(DocBuilder.trackDocument(null, row()))
        assertTrue(!out.has("lyrics_checked"))
        assertTrue(!out.has("start_at"))
    }

    // ---- the fields the tag formats have -------------------------------------

    @Test
    fun `a track document carries every field a tag format has a name for`() {
        val out = JSONObject(
            DocBuilder.trackDocument(
                null,
                row(
                    composer = "Noel Gallagher",
                    composerSort = "Gallagher, Noel",
                    grouping = "Britpop Era",
                    titleSort = "Rock and Roll Star",
                    artistSort = "Oasis",
                    albumSort = "Definitely Maybe",
                    albumArtistSort = "Oasis",
                    trackTotal = 11,
                    discTotal = 3,
                ),
            )
        )
        assertEquals("Noel Gallagher", out.getString("composer"))
        assertEquals("Gallagher, Noel", out.getString("composer_sort"))
        assertEquals("Britpop Era", out.getString("grouping"))
        assertEquals("Rock and Roll Star", out.getString("title_sort"))
        assertEquals("Oasis", out.getString("artist_sort"))
        assertEquals("Definitely Maybe", out.getString("album_sort"))
        assertEquals("Oasis", out.getString("album_artist_sort"))
        assertEquals(11, out.getInt("total_tracks"))
        assertEquals(3, out.getInt("total_discs"))
        assertEquals(false, out.getBoolean("is_compilation"))

        val back = LibraryDocs.track(out.toString())!!
        assertEquals("Gallagher, Noel", back.composerSort)
        assertEquals("Britpop Era", back.grouping)
        assertEquals(11, back.totalTracks)
    }

    @Test
    fun `an album document states how it files, when it differs`() {
        val plain = LibraryDocs.album(DocBuilder.albumDocument(null, listOf(row())))!!
        assertNull(plain.albumSort)

        val filed = LibraryDocs.album(
            DocBuilder.albumDocument(null, listOf(row(albumSort = "Masterplan, The")))
        )!!
        assertEquals("Masterplan, The", filed.albumSort)
    }

    @Test
    fun `a field Roam has no value for falls back to what the document said`() {
        // Composer was preserved-only before the column existed. A hand-written
        // document that has one must not lose it just because Roam's row is
        // still blank.
        val existing = JSONObject("""{ "schema": 1, "composer": "Noel Gallagher" }""")
        val out = JSONObject(DocBuilder.trackDocument(existing, row(composer = null)))
        assertEquals("Noel Gallagher", out.getString("composer"))
    }

    // ---- who has vouched for the values --------------------------------------

    @Test
    fun `a hand-typed correction is never treated as a guess`() {
        // The rule that got this wrong: a track someone had just carefully
        // fixed was refused by the writer, because applyUserEdit does not touch
        // tagState and the guard asked about tagState alone. An edit is the
        // strongest claim there is.
        assertFalse(row(tagState = TagState.PENDING, userEdited = true).isGuess)
        assertFalse(row(tagState = TagState.FAILED, userEdited = true).isGuess)
    }

    @Test
    fun `a document and the file's own tags both count as vouched for`() {
        assertFalse(row(tagState = TagState.FAILED, fromDoc = true).isGuess)
        assertFalse(row(tagState = TagState.OK).isGuess)
    }

    @Test
    fun `nothing but the filename is a guess, and that is what gets refused`() {
        assertTrue(row(tagState = TagState.PENDING).isGuess)
        assertTrue(row(tagState = TagState.FAILED).isGuess)
        assertTrue(row(tagState = TagState.PATH_INFERRED).isGuess)
    }

    // ---- previous artwork ---------------------------------------------------

    private val withHistory = JSONObject(
        """
        {
          "schema": 1, "album_artist": "Oasis", "album_title": "Definitely Maybe",
          "cover_art": "cover.jpg",
          "previous_artwork": ["cover1.jpg", "cover2.jpg"],
          "tracks": []
        }
        """
    )

    @Test
    fun `the artwork history is carried across a rewrite, not rebuilt`() {
        // Nothing in the catalogue knows which covers a folder has held, so
        // rebuilding this from Roam's own tables would silently empty it.
        val out = JSONObject(DocBuilder.albumDocument(withHistory, listOf(row())))
        val history = out.getJSONArray("previous_artwork")
        assertEquals(2, history.length())
        assertEquals("cover1.jpg", history.getString(0))
        assertEquals("cover2.jpg", history.getString(1))
    }

    @Test
    fun `retiring a cover appends it, keeping the order it happened in`() {
        val json = DocBuilder.withRetiredArtwork(withHistory, "cover3.jpg", DocBuilder.ALBUM_KEYS)!!
        val history = JSONObject(json).getJSONArray("previous_artwork")
        assertEquals(3, history.length())
        // Appended, never sorted. The order IS the history, and reordering
        // would make every save show the whole list as changed.
        assertEquals("cover3.jpg", history.getString(2))

        // ...in the documented position, so the diff is the one line.
        val order = Regex("^  \"(\\w+)\"", RegexOption.MULTILINE).findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(order.indexOf("cover_art") + 1, order.indexOf("previous_artwork"))
    }

    @Test
    fun `a document with no history yet gains one`() {
        val bare = JSONObject("""{ "schema": 1, "album_artist": "A", "album_title": "B" }""")
        val out = JSONObject(DocBuilder.withRetiredArtwork(bare, "cover1.jpg", DocBuilder.ALBUM_KEYS)!!)
        assertEquals(1, out.getJSONArray("previous_artwork").length())
    }

    @Test
    fun `nothing to record means nothing is written`() {
        // No document: replacing a cover must not CREATE one. New files
        // appearing on someone's Drive because they picked a different picture
        // is a surprise, and surprises on their own storage cost trust.
        assertNull(DocBuilder.withRetiredArtwork(null, "cover1.jpg", DocBuilder.ALBUM_KEYS))
        // Already listed: an upload with no change is still an upload.
        assertNull(DocBuilder.withRetiredArtwork(withHistory, "COVER1.JPG", DocBuilder.ALBUM_KEYS))
        assertNull(DocBuilder.withRetiredArtwork(withHistory, "  ", DocBuilder.ALBUM_KEYS))
    }

    @Test
    fun `patching the history leaves everything else exactly as it was`() {
        val rich = JSONObject(
            """
            {
              "schema": 1, "album_artist": "Oasis", "album_title": "Definitely Maybe",
              "mood": "swaggering",
              "tracks": [ { "file": "01 Rock.mp3", "title": "Rock 'n' Roll Star" } ]
            }
            """
        )
        val out = JSONObject(DocBuilder.withRetiredArtwork(rich, "cover1.jpg", DocBuilder.ALBUM_KEYS)!!)
        assertEquals("Oasis", out.getString("album_artist"))
        assertEquals("swaggering", out.getString("mood"))
        assertEquals("Rock 'n' Roll Star", out.getJSONArray("tracks").getJSONObject(0).getString("title"))
        // Not invented: this document never claimed a cover name, and a patch
        // is not the place to start.
        assertTrue(!out.has("cover_art"))
    }

    @Test
    fun `the parser reads the history and shrugs off rubbish in it`() {
        val doc = LibraryDocs.album(
            """
            {
              "schema": 1, "album_artist": "A", "album_title": "B",
              "previous_artwork": ["cover1.jpg", "", null, "  ", "cover2.jpg"],
              "tracks": []
            }
            """
        )!!
        assertEquals(listOf("cover1.jpg", "cover2.jpg"), doc.previousArtwork)
        assertTrue(LibraryDocs.album("""{ "schema": 1, "album_artist": "A", "album_title": "B" }""")!!
            .previousArtwork.isEmpty())
    }

    // ---- the small pieces ---------------------------------------------------

    @Test
    fun `genres split the way the reader joins them`() {
        assertEquals(listOf("Britpop", "Indie Rock"), DocBuilder.splitGenres("Britpop; Indie Rock"))
        assertEquals(listOf("Britpop"), DocBuilder.splitGenres("Britpop, britpop"))
        assertTrue(DocBuilder.splitGenres(null).isEmpty())
        assertTrue(DocBuilder.splitGenres("  ").isEmpty())
    }

    @Test
    fun `clock times round-trip through the parser`() {
        assertEquals("0:05", DocBuilder.clock(5_000))
        assertEquals("1:23", DocBuilder.clock(83_000))
        assertEquals("1:01:40", DocBuilder.clock(3_700_000))
        assertNull(DocBuilder.clock(null))

        // Compared with == rather than assertEquals: a nullable Long against a
        // literal picks the Object overload, which is correct but easy to
        // misread as the primitive one.
        assertTrue(LibraryDocs.parseClock(DocBuilder.clock(83_000)) == 83_000L)
        assertTrue(LibraryDocs.parseClock(DocBuilder.clock(3_700_000)) == 3_700_000L)
    }

    @Test
    fun `a document round-trips without drifting`() {
        // Written, read, written again: the second pass must be byte-identical
        // or every save would show a diff and nobody could tell a real change
        // from noise.
        val rows = listOf(row(), row(id = 2, trackNo = 2, fileName = "02 Shaker.mp3", title = "Shakermaker"))
        val once = DocBuilder.albumDocument(null, rows)
        val twice = DocBuilder.albumDocument(JSONObject(once), rows)
        assertEquals(once, twice)
    }
}
