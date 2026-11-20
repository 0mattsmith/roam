package app.roam.feature.downloader

import app.roam.feature.downloader.ExactTrack.Miss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether an upload is the track an album page asked for. The cases are the
 * ones that used to get through on length alone -- a live take a few seconds
 * out, a sped-up edit, a cover -- and the ones a cruder rule would refuse
 * wrongly: a studio song called "Live Forever", a band called Live, a live
 * album that really is live.
 */
class ExactTrackTest {

    private val whatever = ExactTrack.Wanted(
        title = "Whatever",
        artist = "Oasis",
        album = "Whatever - Single",
        albumArtist = "Oasis",
        durationMs = 381_000,
    )

    /** The label's own upload, as YouTube Music lists it. */
    private fun song(
        title: String,
        album: String,
        seconds: Int,
        artist: String = "Oasis",
        id: String = "$title/$album/$seconds",
    ) = YoutubeResult(
        videoId = id,
        title = title,
        artist = artist,
        album = album,
        year = null,
        durationSec = seconds,
        thumbnailUrl = null,
        videoTitle = title,
        channel = "$artist - Topic",
        description = "Provided to YouTube by Big Brother Recordings Ltd\n\n$title · $artist\n\n$album",
        creditedTitle = title,
        creditedArtist = artist,
        extracted = true,
    )

    /** Somebody's video, with nothing but a title and a channel to go on. */
    private fun video(title: String, channel: String, seconds: Int, id: String = "$title/$seconds") =
        YoutubeResult(
            videoId = id,
            title = title,
            artist = channel,
            album = null,
            year = null,
            durationSec = seconds,
            thumbnailUrl = null,
            videoTitle = title,
            channel = channel,
            extracted = true,
        )

    // ---- the performance ----------------------------------------------------

    @Test
    fun `the album's own audio is the track`() {
        assertNull(ExactTrack.miss(whatever, song("Whatever", album = "Whatever", seconds = 381)))
    }

    @Test
    fun `a live take is refused even at the right length`() {
        val live = song("Whatever (Live at Knebworth)", album = "Knebworth 1996", seconds = 383)
        assertEquals(Miss.OTHER_PERFORMANCE, ExactTrack.miss(whatever, live))
    }

    @Test
    fun `remixes, covers and sped-up uploads are other versions`() {
        for (title in listOf("Oasis - Whatever (Sped Up)", "Oasis - Whatever (Acoustic)", "Oasis - Whatever (Remix)")) {
            assertEquals(title, Miss.OTHER_PERFORMANCE, ExactTrack.miss(whatever, video(title, "Oasis", 381)))
        }
    }

    @Test
    fun `a live album's own live tracks are the ones wanted, and no other live take`() {
        val want = ExactTrack.Wanted("Wonderwall", "Oasis", "Familiar to Millions", "Oasis", 270_000)
        val fromTheAlbum = song("Wonderwall (Live at Wembley Stadium, July 2000)", album = "Familiar to Millions", seconds = 271)
        val anotherNight = video("Oasis - Wonderwall (Live at Knebworth)", "Oasis", 271)

        assertNull(ExactTrack.miss(want, fromTheAlbum))
        assertEquals(Miss.OTHER_PERFORMANCE, ExactTrack.miss(want, anotherNight))
    }

    @Test
    fun `Live Forever is a studio song`() {
        val want = ExactTrack.Wanted("Live Forever", "Oasis", "Definitely Maybe", "Oasis", 276_000)
        assertNull(ExactTrack.miss(want, song("Live Forever", album = "Stop the Clocks", seconds = 277)))
        assertEquals(
            Miss.OTHER_PERFORMANCE,
            ExactTrack.miss(want, video("Oasis - Live Forever (Live at Glastonbury 1994)", "Oasis", 278)),
        )
    }

    @Test
    fun `a band called Live is not a live take`() {
        val want = ExactTrack.Wanted("Lightning Crashes", "Live", "Throwing Copper", "Live", 325_000)
        assertNull(ExactTrack.miss(want, video("LIVE - Lightning Crashes (Official Video)", "LIVE", 326)))
    }

    @Test
    fun `a version the catalogue names must be the version found`() {
        val demo = whatever.copy(title = "Whatever (Demo)", album = "Definitely Maybe (Deluxe)", durationMs = null)
        assertEquals(Miss.OTHER_PERFORMANCE, ExactTrack.miss(demo, song("Whatever", album = "Whatever", seconds = 381)))
        assertNull(ExactTrack.miss(demo, song("Whatever (Demo)", album = "The Masterplan", seconds = 300)))
    }

    // ---- the song, the act, the length --------------------------------------

    @Test
    fun `somebody else's recording of the song is refused`() {
        val cover = song("Whatever", album = "Covers", seconds = 381, artist = "Some Covers Band")
        assertEquals(Miss.OTHER_ARTIST, ExactTrack.miss(whatever, cover))
    }

    @Test
    fun `a longer title is another song`() {
        assertEquals(Miss.OTHER_SONG, ExactTrack.miss(whatever, song("Whatever It Takes", album = "x", seconds = 381)))
    }

    @Test
    fun `five seconds out is still the record, six is not`() {
        assertNull(ExactTrack.miss(whatever, song("Whatever", album = "Whatever", seconds = 386)))
        assertEquals(Miss.OTHER_LENGTH, ExactTrack.miss(whatever, song("Whatever", album = "Whatever", seconds = 387)))
    }

    @Test
    fun `with no length from the catalogue, the other three still decide`() {
        val unknown = whatever.copy(durationMs = null)
        assertNull(ExactTrack.miss(unknown, song("Whatever", album = "Whatever", seconds = 999)))
        assertEquals(
            Miss.OTHER_PERFORMANCE,
            ExactTrack.miss(unknown, song("Whatever (Live)", album = "Knebworth 1996", seconds = 381)),
        )
    }

    @Test
    fun `a compilation track is by its performer, not by Various Artists`() {
        val want = whatever.copy(album = "Now That's What I Call Music! 30", albumArtist = "Various Artists")
        assertNull(ExactTrack.miss(want, song("Whatever", album = "Whatever", seconds = 381)))
    }

    // ---- choosing and refusing ----------------------------------------------

    @Test
    fun `the same album wins, then the label's audio, then the closest length`() {
        val clip = video("Oasis - Whatever (Official Video)", "Oasis", 381, id = "clip")
        val collected = song("Whatever", album = "Stop the Clocks", seconds = 382, id = "clocks")
        val single = song("Whatever", album = "Whatever", seconds = 383, id = "single")

        assertEquals("single", ExactTrack.choose(whatever, listOf(clip, collected, single))?.videoId)
        assertEquals("clocks", ExactTrack.choose(whatever, listOf(clip, collected))?.videoId)
    }

    @Test
    fun `a refusal says what came back instead`() {
        val found = listOf(
            song("Whatever (Live at Knebworth)", album = "Knebworth 1996", seconds = 383),
            video("Oasis - Whatever (Acoustic)", "Oasis", 300),
            song("Whatever", album = "Whatever", seconds = 410),
            song("Whatever It Takes", album = "x", seconds = 381),
        )
        assertNull(ExactTrack.choose(whatever, found))
        assertEquals(
            "No exact match for “Whatever” (6:21) — 2 live or other versions, " +
                "1 the wrong length (closest 6:50), 1 a different song",
            ExactTrack.refusal(whatever, found),
        )
    }

    @Test
    fun `a refusal names where it looked`() {
        val found = listOf(song("Whatever (Live at Knebworth)", album = "Knebworth 1996", seconds = 383))
        assertEquals(
            "No exact match for “Whatever” (6:21) anywhere Roam looked — 1 live or other version",
            ExactTrack.refusal(whatever, found, where = "anywhere Roam looked"),
        )
    }
}
