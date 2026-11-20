package app.roam.data.catalog.sync

import app.roam.data.source.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a folder layout says when the file itself says nothing -- which is
 * always, for anything the downloader added before it wrote documents.
 */
class PathInferenceTest {

    private val extractor = TagExtractor()

    private fun file(name: String, vararg path: String) = RemoteFile(
        remoteId = "id",
        name = name,
        pathSegments = path.toList(),
        sizeBytes = 1,
        mimeType = "audio/mp4",
        revision = "r1",
        modifiedAt = 0,
    )

    @Test
    fun `a year on the folder is the album's year, not part of its title`() {
        val tags = extractor.inferFromPath(
            file("02 Wonderwall.m4a", "Oasis", "(What's the Story) Morning Glory (1995)")
        )
        assertEquals("(What's the Story) Morning Glory", tags.album)
        assertEquals(1995, tags.year)
    }

    @Test
    fun `an edition stays in the title and only the year comes off`() {
        val tags = extractor.inferFromPath(
            file("01 Rock.mp3", "Oasis", "Definitely Maybe (Chasing the Sun Edition) (1994)")
        )
        assertEquals("Definitely Maybe (Chasing the Sun Edition)", tags.album)
        assertEquals(1994, tags.year)
    }

    @Test
    fun `a folder with no year is the album exactly as named`() {
        val tags = extractor.inferFromPath(file("01 Rock.mp3", "Oasis", "Definitely Maybe"))
        assertEquals("Definitely Maybe", tags.album)
        assertNull(tags.year)
    }

    @Test
    fun `a folder that is only a year keeps its name rather than having none`() {
        val tags = extractor.inferFromPath(file("01 Track.mp3", "Someone", "(1999)"))
        assertEquals("(1999)", tags.album)
        assertNull(tags.year)
    }

    @Test
    fun `the artist folder files the album and the number leads the name`() {
        val tags = extractor.inferFromPath(file("01 Supersonic.m4a", "Oasis", "Definitely Maybe (1994)"))
        assertEquals("Oasis", tags.albumArtist)
        assertEquals("Supersonic", tags.title)
        assertEquals(1, tags.trackNo)
    }

    @Test
    fun `a compilation's performer rides in the file name`() {
        val tags = extractor.inferFromPath(
            file("03 Song - Performer.m4a", "Various Artists", "Now 48 (2001)")
        )
        assertEquals("Performer", tags.artist)
        assertEquals("Various Artists", tags.albumArtist)
        assertEquals("Now 48", tags.album)
        assertEquals(2001, tags.year)
    }
}
