package app.roam.data.catalog.metadata

import app.roam.core.database.FolderTrackRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Matching is the part that fails quietly. A wrong answer here does not throw
 * -- it writes one track's title onto another, or writes nothing and leaves the
 * index looking ignored. Every case below is one a real library produces.
 */
class DocMatcherTest {

    private val album = "Oasis/Definitely Maybe (1994)"

    private fun row(id: Long, folder: String?, name: String?, fromDoc: Boolean = false) =
        FolderTrackRow(id = id, fileName = name, folderPath = folder, fromDoc = fromDoc)

    // ---- locators -----------------------------------------------------------

    @Test
    fun `a track in the album folder is named by its filename alone`() {
        assertEquals("01 rock.mp3", DocMatcher.relativeLocator(album, album, "01 Rock.mp3"))
    }

    @Test
    fun `a track in a disc subfolder keeps the subfolder in its locator`() {
        // What a deluxe edition actually looks like, and why this is a path
        // rather than a filename.
        assertEquals(
            "disc 1/01 rock.mp3",
            DocMatcher.relativeLocator(album, "$album/Disc 1", "01 Rock.mp3"),
        )
    }

    @Test
    fun `a sibling folder sharing a prefix is not below the album`() {
        // Without the slash in the prefix test, "Definitely Maybe (1994) Live"
        // would be swallowed by "Definitely Maybe (1994)".
        assertNull(DocMatcher.relativeLocator(album, "$album Live", "01.mp3"))
    }

    @Test
    fun `folders above and beside the album do not match`() {
        assertNull(DocMatcher.relativeLocator(album, "Oasis", "01.mp3"))
        assertNull(DocMatcher.relativeLocator(album, "Blur/Parklife (1994)", "01.mp3"))
    }

    @Test
    fun `backslashes, trailing slashes and case are all tolerated`() {
        // An editor on Windows writes backslashes; Drive is not consistent
        // about case and neither are people.
        assertEquals("01.mp3", DocMatcher.relativeLocator(album, album.replace('/', '\\'), "01.mp3"))
        assertEquals("01.mp3", DocMatcher.relativeLocator(album, "$album/", "01.mp3"))
        assertEquals("01.mp3", DocMatcher.relativeLocator(album.uppercase(), album, "01.MP3"))
    }

    @Test
    fun `a row with no filename names nothing`() {
        // Rows predating schema 13 have null paths until the next crawl. They
        // must match nothing rather than matching everything.
        assertNull(DocMatcher.relativeLocator(album, album, null))
        assertNull(DocMatcher.relativeLocator(album, album, "   "))
        assertNull(DocMatcher.relativeLocator(album, null, "01.mp3"))
    }

    @Test
    fun `an index at the library root still locates its tracks`() {
        assertEquals("01.mp3", DocMatcher.relativeLocator("", "", "01.mp3"))
        assertEquals("oasis/01.mp3", DocMatcher.relativeLocator("", "Oasis", "01.mp3"))
    }

    // ---- matching -----------------------------------------------------------

    /**
     * An index naming exactly these files. Built with a plain quoted string
     * rather than a raw one nested inside another -- that parses, but only
     * just, and this is a test whose job is to be obviously right.
     */
    private fun doc(vararg files: String): AlbumDoc {
        val entries = files.joinToString(",") { "{ \"file\": \"$it\", \"title\": \"T\" }" }
        return LibraryDocs.album(
            "{ \"schema\": 1, \"album_artist\": \"Oasis\", " +
                "\"album_title\": \"Definitely Maybe\", \"tracks\": [$entries] }"
        )!!
    }

    @Test
    fun `entries match the files they name, across disc folders`() {
        val d = doc("01 Rock.mp3", "Disc 1/02 Shakermaker.mp3")
        val result = DocMatcher.matchLocal(
            album, d,
            listOf(
                row(1, album, "01 Rock.mp3"),
                row(2, "$album/Disc 1", "02 Shakermaker.mp3"),
            ),
        )
        assertEquals(listOf(1L, 2L), result.matched.map { it.trackId })
        assertTrue(result.orphaned.isEmpty())
    }

    @Test
    fun `a track the index does not mention is left completely alone`() {
        // The rule that makes this safe to ship: a file with no entry behaves
        // exactly as it did before any of this existed.
        val result = DocMatcher.matchLocal(album, doc("01 Rock.mp3"), listOf(row(9, album, "99 Extra.mp3")))
        assertTrue(result.matched.isEmpty())
        assertTrue(result.orphaned.isEmpty())
    }

    @Test
    fun `a track the index used to mention is handed back to its tags`() {
        // Otherwise it stays frozen on values no file claims any more, and the
        // tag pass never touches it again.
        val result = DocMatcher.matchLocal(
            album, doc("01 Rock.mp3"),
            listOf(row(9, album, "99 Extra.mp3", fromDoc = true)),
        )
        assertTrue(result.matched.isEmpty())
        assertEquals(listOf(9L), result.orphaned)
    }

    @Test
    fun `a neighbouring album dragged in by a LIKE wildcard is never released`() {
        // The query uses LIKE, where an underscore matches any single
        // character -- so "Definitely_Maybe" pulls back "DefinitelyXMaybe" too.
        // Clearing THAT album's fromDoc would be a silent corruption, so a row
        // that is not genuinely below the folder is skipped outright.
        val result = DocMatcher.matchLocal(
            "Oasis/Definitely_Maybe", doc("01 Rock.mp3"),
            listOf(row(5, "Oasis/DefinitelyXMaybe/Disc 1", "01 Rock.mp3", fromDoc = true)),
        )
        assertTrue(result.matched.isEmpty())
        assertTrue(result.orphaned.isEmpty())
    }

    @Test
    fun `a row whose path is unknown is neither matched nor released`() {
        // Null paths predate schema 13. Roam cannot say what the file is, and
        // "cannot say" must not resolve to "no longer described".
        val result = DocMatcher.matchLocal(
            album, doc("01 Rock.mp3"),
            listOf(row(6, null, null, fromDoc = true)),
        )
        assertTrue(result.matched.isEmpty())
        assertTrue(result.orphaned.isEmpty())
    }

    @Test
    fun `an entry naming a file that is not there matches nothing and breaks nothing`() {
        val result = DocMatcher.matchLocal(album, doc("01 Gone.mp3"), listOf(row(1, album, "01 Rock.mp3")))
        assertTrue(result.matched.isEmpty())
        assertTrue(result.orphaned.isEmpty())
    }

    // ---- entries pointing outside their album -------------------------------

    private val withExternal = LibraryDocs.album(
        """
        {
          "schema": 1, "album_artist": "Oasis", "album_title": "The Masterplan",
          "tracks": [
            { "file": "01 Acquiesce.mp3", "title": "Acquiesce" },
            {
              "title": "Half the World Away",
              "external": { "path": "Oasis/Whatever (1994)/03 Half the World Away.mp3" }
            }
          ]
        }
        """
    )!!

    @Test
    fun `external paths are collected tidied but not case folded`() {
        // They go to a query, where the stored path has its real case.
        assertEquals(
            listOf("Oasis/Whatever (1994)/03 Half the World Away.mp3"),
            DocMatcher.externalPaths(withExternal),
        )
    }

    @Test
    fun `an external entry matches the row at that path`() {
        val matches = DocMatcher.matchExternal(
            withExternal,
            listOf(row(7, "Oasis/Whatever (1994)", "03 Half the World Away.mp3")),
        )
        assertEquals(1, matches.size)
        assertEquals(7L, matches.single().trackId)
        assertEquals("Half the World Away", matches.single().entry.title)
    }

    @Test
    fun `a row at a different path is not claimed by an external entry`() {
        val matches = DocMatcher.matchExternal(
            withExternal,
            listOf(row(8, "Oasis/Whatever (1994)", "01 Whatever.mp3")),
        )
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `a document with no external entries asks for nothing`() {
        assertTrue(DocMatcher.externalPaths(doc("01 Rock.mp3")).isEmpty())
        assertTrue(DocMatcher.matchExternal(doc("01 Rock.mp3"), listOf(row(1, album, "01 Rock.mp3"))).isEmpty())
    }
}
