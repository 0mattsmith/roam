package app.roam.data.acquire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tag is the only copy of a request that a retry has, so whatever it
 * drops, a retried job does without -- which is how a retried compilation
 * track used to leave its album for a folder of its own.
 */
class DownloadTagTest {

    @Test
    fun `a request survives the round trip, placement included`() {
        val request = DownloadRequest(
            url = "ytsearch1:Blur Song 2",
            title = "Song 2",
            artist = "Blur",
            album = "Now 37",
            trackNo = 4,
            durationMs = 122_000,
            coverUrl = "https://example.org/c.jpg",
            albumArtist = "Various Artists",
            year = 1997,
            compilation = true,
        )
        assertEquals(request, DownloadTag.decode(DownloadTag.encode(request)))
    }

    @Test
    fun `blank optional fields come back as absent`() {
        val request = DownloadRequest(url = "u", title = "t", artist = "a", album = "b")
        assertEquals(request, DownloadTag.decode(DownloadTag.encode(request)))
    }

    @Test
    fun `a tag from an older build still decodes`() {
        val old = DownloadTag.PREFIX +
            listOf("u", "t", "a", "b", "3", "1000", "").joinToString("\u001F")
        val request = DownloadTag.decode(old)!!
        assertEquals(3, request.trackNo)
        assertEquals("", request.albumArtist)
        assertNull(request.year)
        assertEquals(false, request.compilation)
    }

    @Test
    fun `other tags are not requests`() {
        assertNull(DownloadTag.decode("roam_download_all"))
        assertNull(DownloadTag.decode(DownloadTag.PREFIX + "too\u001Fshort"))
    }
}
