package app.roam.data.catalog

import app.roam.core.model.FolderNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a download lands.
 *
 * The failure that matters is not a missing folder -- that gets created and
 * somebody notices. It is a WRONG match: filing "100 Hits: 90s Pop" into the
 * 80s folder, or a 2016 remaster into the 1997 one, where nothing looks broken
 * and the album is simply in the wrong place forever.
 */
class FolderNamesTest {

    // ---- what a folder may be called ----------------------------------------

    @Test
    fun `a colon becomes a dash, because that is what people write anyway`() {
        assertEquals("100 Hits - 80s Pop", FolderNames.sanitise("100 Hits: 80s Pop"))
        assertEquals("100 Hits - 80s Pop", FolderNames.sanitise("100 Hits:80s Pop"))
    }

    @Test
    fun `a trailing dot goes, because Drive cannot open what it makes`() {
        assertEquals("Etc", FolderNames.sanitise("Etc."))
        assertEquals("Etc", FolderNames.sanitise("Etc..."))
        // Windows strips it silently, so the folder Roam thinks it made is not
        // the folder that exists.
        assertEquals("The Doors", FolderNames.sanitise("The Doors. "))
    }

    @Test
    fun `a slash would make a subfolder, so it does not survive`() {
        assertEquals("ACDC", FolderNames.sanitise("AC/DC"))
        assertEquals("Who's Next", FolderNames.sanitise("Who's Next?"))
    }

    @Test
    fun `punctuation people meant to keep is kept`() {
        assertEquals("Rock 'n' Roll Star (Live)", FolderNames.sanitise("Rock 'n' Roll Star (Live)"))
        assertEquals("Be Here Now", FolderNames.sanitise("Be  Here   Now"))
    }

    // ---- the number is part of the name -------------------------------------

    @Test
    fun `a folder that differs only in punctuation is the same album`() {
        assertEquals(
            "100 Hits - 80s Pop",
            FolderNames.matchAlbum("100 Hits: 80s Pop", null, listOf("100 Hits - 80s Pop")),
        )
        assertEquals(
            "100 Hits 80s Pop",
            FolderNames.matchAlbum("100 Hits: 80s Pop", null, listOf("100 Hits 80s Pop")),
        )
    }

    @Test
    fun `a folder that differs by a digit is a different album`() {
        // The whole reason digits stay in the key. One character apart, and
        // filing one into the other is invisible until you go looking.
        assertNull(FolderNames.matchAlbum("100 Hits: 90s Pop", null, listOf("100 Hits - 80s Pop")))
        assertNull(FolderNames.matchAlbum("Now 42", null, listOf("Now 41")))
    }

    // ---- years ---------------------------------------------------------------

    @Test
    fun `knowing the year finds the folder that announces it`() {
        assertEquals(
            "Be Here Now (1997)",
            FolderNames.matchAlbum("Be Here Now", 1997, listOf("Be Here Now (1997)")),
        )
    }

    @Test
    fun `a folder with no year is not a mismatch`() {
        // Plenty of folders carry no year, and refusing them would put a
        // duplicate beside every one.
        assertEquals(
            "Be Here Now",
            FolderNames.matchAlbum("Be Here Now", 1997, listOf("Be Here Now")),
        )
        assertEquals(
            "Be Here Now (1997)",
            FolderNames.matchAlbum("Be Here Now", null, listOf("Be Here Now (1997)")),
        )
    }

    @Test
    fun `a different year is a different pressing`() {
        assertNull(FolderNames.matchAlbum("Be Here Now", 1997, listOf("Be Here Now (2016)")))
    }

    @Test
    fun `the folder that names the year beats the one that does not`() {
        assertEquals(
            "Be Here Now (1997)",
            FolderNames.matchAlbum("Be Here Now", 1997, listOf("Be Here Now", "Be Here Now (1997)")),
        )
    }

    @Test
    fun `an edition is part of the name and only the year is stripped`() {
        val parsed = FolderNames.parse("Definitely Maybe (Chasing the Sun Edition) (1994)")
        assertEquals("Definitely Maybe (Chasing the Sun Edition)", parsed.name)
        assertEquals(1994, parsed.year)
    }

    // ---- writing --------------------------------------------------------------

    @Test
    fun `the folder that would be written`() {
        assertEquals("100 Hits - 80s Pop (2011)", FolderNames.albumFolder("100 Hits: 80s Pop", 2011))
        assertEquals("Be Here Now", FolderNames.albumFolder("Be Here Now", null))
    }

    @Test
    fun `a name with nothing left in it matches nothing`() {
        // Better than matching everything, which is what an empty key would do.
        assertEquals("", FolderNames.sanitise("..."))
        assertNull(FolderNames.matchAlbum("...", null, listOf("Whatever")))
        assertNull(FolderNames.matchArtist("", listOf("Oasis")))
    }

    @Test
    fun `an artist folder matches past its punctuation too`() {
        assertEquals("ACDC", FolderNames.matchArtist("AC/DC", listOf("ACDC")))
        assertEquals("Oasis", FolderNames.matchArtist("oasis", listOf("Oasis")))
    }
}
