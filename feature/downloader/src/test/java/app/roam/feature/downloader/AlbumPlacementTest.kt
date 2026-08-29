package app.roam.feature.downloader

import app.roam.core.model.FolderNames
import app.roam.data.catalog.metadata.MetadataSource
import app.roam.data.catalog.metadata.ReleaseMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folder a download lands in, and what the dialog opens showing.
 *
 * The failure worth testing for is not a missing field -- somebody notices an
 * empty box. It is a WRONG folder: a studio album filed under Various Artists
 * looks fine on the screen that put it there and is only discovered later, on
 * a shelf where the artist has half their records.
 */
class AlbumPlacementTest {

    private fun match(
        artist: String,
        title: String = "An Album",
        year: Int? = 1990,
        compilation: Boolean = false,
    ) = ReleaseMatch(
        id = "1",
        source = MetadataSource.MUSICBRAINZ,
        title = title,
        artist = artist,
        year = year,
        trackCount = 10,
        format = "CD",
        coverUrl = null,
        isCompilation = compilation,
    )

    // ---- the flag does not decide the folder ---------------------------------

    @Test
    fun `a studio album files under its artist`() {
        val p = AlbumPlacement.from(match("Depeche Mode", "Violator"))
        assertEquals("Depeche Mode", p.filingArtist)
        assertEquals("Depeche Mode", p.artist)
        assertFalse(p.filesUnderVariousArtists)
    }

    @Test
    fun `a various-artists collection files under the shared folder`() {
        val p = AlbumPlacement.from(
            match(FolderNames.VARIOUS_ARTISTS, "100 Hits: 80s Pop", 2011, compilation = true)
        )
        assertEquals(FolderNames.VARIOUS_ARTISTS, p.filingArtist)
        assertTrue(p.filesUnderVariousArtists)
        // Blank on purpose: each track keeps its own credit, and there is no
        // one performer for the release.
        assertEquals("", p.artist)
    }

    @Test
    fun `a greatest hits is a compilation that keeps its artist`() {
        // The case the old rule got wrong. Compilation was read as
        // various-artists, so this filed under the shared folder and arrived
        // with the artist field emptied.
        val p = AlbumPlacement.from(match("Queen", "Greatest Hits", 1981, compilation = true))
        assertTrue(p.compilation)
        assertEquals("Queen", p.filingArtist)
        assertEquals("Queen", p.artist)
        assertFalse(p.filesUnderVariousArtists)
    }

    @Test
    fun `a wrongly flagged album still files correctly`() {
        // Catalogues carry the flag loosely. When they are wrong the cost has
        // to be one checkbox to untick, not a misfiled record and a form with
        // the artist thrown away.
        val p = AlbumPlacement.from(match("Depeche Mode", "Violator", compilation = true))
        assertEquals("Depeche Mode", p.filingArtist)
        assertEquals("Depeche Mode", p.artist)
    }

    @Test
    fun `various artists is spelled the one way whatever the source said`() {
        // This names a directory. Taking the credit verbatim puts "various
        // artists" beside "Various Artists" the first time a source disagrees.
        val p = AlbumPlacement.from(match("various artists", "Now 42", 1999, compilation = true))
        assertEquals(FolderNames.VARIOUS_ARTISTS, p.albumArtist)
        assertEquals(FolderNames.VARIOUS_ARTISTS, p.filingArtist)
    }

    // ---- a search result knows much less -------------------------------------

    @Test
    fun `a search result is never assumed to be a compilation`() {
        val p = AlbumPlacement.from(
            YoutubeResult(
                videoId = "abcdefghijk",
                title = "Enjoy the Silence",
                artist = "DepecheModeVEVO",
                album = null,
                year = null,
                durationSec = 260,
                thumbnailUrl = null,
            )
        )
        assertFalse(p.compilation)
        assertEquals("DepecheModeVEVO", p.filingArtist)
    }

    // ---- what the folder falls back to ---------------------------------------

    @Test
    fun `a ticked box with nothing else still has somewhere to go`() {
        val p = AlbumPlacement(album = "Mixtape", compilation = true)
        assertEquals(FolderNames.VARIOUS_ARTISTS, p.filingArtist)
    }

    @Test
    fun `no answers at all is not a crash`() {
        assertEquals(AlbumPlacement.UNKNOWN_ARTIST, AlbumPlacement().filingArtist)
        assertEquals(AlbumPlacement.SINGLES, AlbumPlacement().albumOrSingles)
    }

    // ---- the track's own credit ----------------------------------------------

    @Test
    fun `a blank artist keeps whatever credit the track arrived with`() {
        // What makes a single track off a compilation work: the folder is the
        // shared one and the track still says who actually performed it.
        val p = AlbumPlacement.from(
            match(FolderNames.VARIOUS_ARTISTS, "100 Hits", 2011, compilation = true)
        )
        assertEquals("Rick Astley", p.trackArtist("Rick Astley"))
    }

    @Test
    fun `a typed artist beats the credit it arrived with`() {
        val p = AlbumPlacement(artist = "Rick Astley", album = "100 Hits", compilation = true)
        assertEquals("Rick Astley", p.trackArtist("80sPopChannel"))
    }
}
