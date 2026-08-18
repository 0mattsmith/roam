package app.roam.feature.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Share text is the least predictable input Roam takes, and getting it wrong is
 * silent: the share sheet offers Roam, the user taps it, and nothing happens.
 */
class YoutubeLinkTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `watch links, in all their forms`() {
        assertEquals(id, YoutubeLink.videoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YoutubeLink.videoId("https://m.youtube.com/watch?v=$id"))
        assertEquals(id, YoutubeLink.videoId("https://music.youtube.com/watch?v=$id"))
    }

    @Test
    fun `short links, which is what the share sheet actually sends`() {
        assertEquals(id, YoutubeLink.videoId("https://youtu.be/$id"))
        assertEquals(id, YoutubeLink.videoId("https://youtu.be/$id?si=AbCdEfGhIjKl"))
    }

    @Test
    fun `shorts, embeds and live`() {
        assertEquals(id, YoutubeLink.videoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YoutubeLink.videoId("https://www.youtube.com/embed/$id"))
        assertEquals(id, YoutubeLink.videoId("https://www.youtube.com/live/$id"))
    }

    @Test
    fun `trailing parameters do not swallow the id`() {
        assertEquals(id, YoutubeLink.videoId("https://www.youtube.com/watch?v=$id&t=42s"))
        assertEquals(id, YoutubeLink.videoId("https://music.youtube.com/watch?v=$id&list=RDAMVM"))
    }

    @Test
    fun `a link buried in shared text is still found`() {
        // What YouTube actually puts on the clipboard: a title, a newline, then
        // the link with a tracking parameter.
        assertEquals(id, YoutubeLink.videoId("Never Gonna Give You Up\nhttps://youtu.be/$id?si=xyz"))
        assertEquals(id, YoutubeLink.videoId("look at this https://www.youtube.com/watch?v=$id ok"))
    }

    @Test
    fun `ids containing underscore and dash survive`() {
        assertEquals("a_b-c1D2E3F", YoutubeLink.videoId("https://youtu.be/a_b-c1D2E3F"))
    }

    @Test
    fun `anything that is not YouTube is refused`() {
        // Claiming text/plain means the share sheet offers Roam for every
        // selection on the device, so this is the COMMON case, not an edge one.
        assertNull(YoutubeLink.videoId("https://open.spotify.com/track/abc"))
        assertNull(YoutubeLink.videoId("just some text someone shared"))
        assertNull(YoutubeLink.videoId("Hello World"))
        assertNull(YoutubeLink.videoId(""))
        assertNull(YoutubeLink.videoId(null))
    }

    @Test
    fun `the watch url is what yt-dlp gets`() {
        assertEquals("https://music.youtube.com/watch?v=$id", YoutubeLink.watchUrl(id))
    }
}
