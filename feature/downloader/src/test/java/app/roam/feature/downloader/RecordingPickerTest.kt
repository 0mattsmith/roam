package app.roam.feature.downloader

import app.roam.data.catalog.metadata.RecordingMatch
import app.roam.data.catalog.metadata.ReleaseAppearance
import app.roam.feature.downloader.VideoClues.Guess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which recording a video is, which release it files under, and what that
 * becomes on Drive. The release rules are the ones a person would apply by
 * eye; these tests are the cases where a simpler rule picks something wrong.
 */
class RecordingPickerTest {

    private fun release(
        id: String,
        title: String,
        date: String?,
        type: String? = "Album",
        secondary: List<String> = emptyList(),
        status: String? = "Official",
        artist: String = "",
        track: Int? = 1,
    ) = ReleaseAppearance(
        id = id,
        groupId = "rg-$id",
        title = title,
        artist = artist,
        date = date,
        status = status,
        primaryType = type,
        secondaryTypes = secondary,
        trackNumber = track,
        coverUrl = "https://coverartarchive.org/release-group/rg-$id/front-500",
    )

    private fun recording(
        title: String = "Whatever",
        artist: String = "Oasis",
        names: List<String> = listOf(artist),
        lengthMs: Long? = 381_000,
        id: String = "rec",
        releases: List<ReleaseAppearance>,
    ) = RecordingMatch(
        id = id,
        title = title,
        artist = artist,
        artistNames = names,
        lengthMs = lengthMs,
        releases = releases,
    )

    private val whatever = Guess("Whatever", "Oasis")

    // ---- which release ------------------------------------------------------

    @Test
    fun `a single that never made an album files as the single`() {
        val found = recording(
            releases = listOf(
                release("now30", "Now 30", "1995", secondary = listOf("Compilation"), artist = "Various Artists", track = 7),
                // The deluxe edition that finally gathered it, twenty years on.
                release("deluxe", "Definitely Maybe", "2014-05-19", track = 14),
                release("single", "Whatever", "1994-12-18", type = "Single"),
                release("clocks", "Stop the Clocks", "2006-11-20", secondary = listOf("Compilation")),
            ),
        )
        assertEquals("single", RecordingPicker.pick(whatever, listOf(found), null)?.release?.id)
    }

    @Test
    fun `a single shortly before its album files under the album`() {
        val found = recording(
            title = "Live Forever",
            releases = listOf(
                release("single", "Live Forever", "1994-08-08", type = "Single"),
                release("album", "Definitely Maybe", "1994-08-29", track = 5),
                release("remaster", "Definitely Maybe", "2014-05-19", track = 5),
            ),
        )
        val pick = RecordingPicker.pick(Guess("Live Forever", "Oasis"), listOf(found), null)
        assertEquals("album", pick?.release?.id)
    }

    @Test
    fun `promos only count when nothing official exists`() {
        val found = recording(
            releases = listOf(
                release("promo", "Whatever", "1994-11-01", type = "Single", status = "Promotion"),
                release("single", "Whatever", "1994-12-18", type = "Single"),
            ),
        )
        assertEquals("single", RecordingPicker.pick(whatever, listOf(found), null)?.release?.id)
    }

    @Test
    fun `a song only ever collected files under its first collection`() {
        val found = recording(
            releases = listOf(
                release("later", "Time Flies", "2010-06-14", secondary = listOf("Compilation")),
                release("first", "Stop the Clocks", "2006-11-20", secondary = listOf("Compilation")),
            ),
        )
        assertEquals("first", RecordingPicker.pick(whatever, listOf(found), null)?.release?.id)
    }

    // ---- which recording ----------------------------------------------------

    @Test
    fun `the length that agrees with the video decides between versions`() {
        val studio = recording(
            id = "studio",
            title = "Wonderwall",
            lengthMs = 258_000,
            releases = listOf(release("morning", "(What's the Story) Morning Glory?", "1995-10-02", track = 3)),
        )
        val live = recording(
            id = "live",
            title = "Wonderwall",
            lengthMs = 301_000,
            releases = listOf(release("knebworth", "Knebworth 1996", "2021-11-19", secondary = listOf("Live"))),
        )
        val found = listOf(studio, live)
        assertEquals(
            "live",
            RecordingPicker.pick(Guess("Wonderwall (Live at Knebworth)", "Oasis"), found, 302_000)?.recording?.id,
        )
        assertEquals("studio", RecordingPicker.pick(Guess("Wonderwall", "Oasis"), found, 259_000)?.recording?.id)
    }

    @Test
    fun `a music video running long still finds the song`() {
        val found = recording(releases = listOf(release("single", "Whatever", "1994-12-18", type = "Single")))
        assertEquals("single", RecordingPicker.pick(whatever, listOf(found), 500_000)?.release?.id)
    }

    @Test
    fun `a tribute band is not the band`() {
        val cover = recording(
            artist = "Oasis Tribute Band",
            releases = listOf(release("x", "Whatever", "2001", type = "Single")),
        )
        assertNull(RecordingPicker.pick(whatever, listOf(cover), null))
    }

    @Test
    fun `a longer title containing the guess is not the song`() {
        val other = recording(
            title = "Whatever It Takes",
            releases = listOf(release("x", "Whatever It Takes", "2001", type = "Single")),
        )
        assertNull(RecordingPicker.pick(whatever, listOf(other), null))
    }

    @Test
    fun `a remix is found as itself before the original`() {
        val names = listOf("Elton John", "Dua Lipa")
        val original = recording(
            id = "orig",
            title = "Cold Heart",
            artist = "Elton John & Dua Lipa",
            names = names,
            releases = listOf(release("a", "The Lockdown Sessions", "2021-10-22")),
        )
        val remix = recording(
            id = "remix",
            title = "Cold Heart (PNAU remix)",
            artist = "Elton John & Dua Lipa",
            names = names,
            releases = listOf(release("b", "Cold Heart (PNAU remix)", "2021-08-13", type = "Single")),
        )
        val guess = Guess("Cold Heart (PNAU Remix)", "Elton John x Dua Lipa")
        assertEquals("remix", RecordingPicker.pick(guess, listOf(original, remix), null)?.recording?.id)
    }

    @Test
    fun `a guest's upload is still the song`() {
        val feels = recording(
            title = "Feels",
            artist = "Calvin Harris feat. Pharrell Williams, Katy Perry & Big Sean",
            names = listOf("Calvin Harris", "Pharrell Williams", "Katy Perry", "Big Sean"),
            releases = listOf(release("fwb", "Funk Wav Bounces Vol. 1", "2017-06-30", track = 2)),
        )
        val pick = RecordingPicker.pick(Guess("Feels", "Pharrell Williams"), listOf(feels), null)
        assertEquals("fwb", pick?.release?.id)
    }

    @Test
    fun `the main credit stops at the first guest`() {
        assertEquals(
            "Calvin Harris",
            RecordingPicker.mainCredit("Calvin Harris feat. Pharrell Williams, Katy Perry & Big Sean"),
        )
        assertEquals("Simon & Garfunkel", RecordingPicker.mainCredit("Simon & Garfunkel"))
    }

    // ---- what it becomes ----------------------------------------------------

    private val video = YoutubeResult(
        videoId = "abcdefghijk",
        title = "Oasis - Whatever (Official Video)",
        artist = "OasisVEVO",
        album = null,
        year = 2009,
        durationSec = 381,
        thumbnailUrl = null,
    )

    @Test
    fun `a known single is named for itself, filed under the act, with its cover`() {
        val single = release("single", "Whatever", "1994-12-18", type = "Single")
        val known = VideoIdentity.Known(video, recording(releases = listOf(single)), single)

        assertEquals("Whatever", known.request.title)
        assertEquals("Oasis", known.request.artist)
        assertEquals(1, known.request.trackNo)
        assertEquals("https://coverartarchive.org/release-group/rg-single/front-500", known.request.coverUrl)
        assertEquals("Whatever - Single", known.placement.album)
        assertEquals("Oasis", known.placement.filingArtist)
        // The catalogue's year, not the year the video went up.
        assertEquals(1994, known.placement.year)
    }

    @Test
    fun `a guest list never names the folder`() {
        val single = release("s", "Feels", "2017-06-16", type = "Single")
        val credit = "Calvin Harris feat. Pharrell Williams"
        val found = recording(
            title = "Feels",
            artist = credit,
            names = listOf("Calvin Harris", "Pharrell Williams"),
            releases = listOf(single),
        )
        val known = VideoIdentity.Known(video, found, single)
        assertEquals("Calvin Harris", known.placement.filingArtist)
        assertEquals(credit, known.placement.artist)
    }

    @Test
    fun `a various-artists release files there and keeps the performer`() {
        val now = release("now", "Now 30", "1995", secondary = listOf("Compilation"), artist = "various artists", track = 7)
        val known = VideoIdentity.Known(video, recording(releases = listOf(now)), now)
        assertEquals("Various Artists", known.placement.filingArtist)
        assertTrue(known.placement.compilation)
        assertEquals("Oasis", known.placement.artist)
        assertEquals(7, known.request.trackNo)
    }

    @Test
    fun `an unknown video keeps its cleaned words and gets no cover`() {
        val unknown = VideoIdentity.Unknown(video, title = "Whatever", artist = "Oasis", reached = true)
        assertEquals("Whatever", unknown.request.title)
        assertEquals("Oasis", unknown.request.artist)
        assertEquals("Whatever - Single", unknown.placement.album)
        assertNull(unknown.request.coverUrl)
    }
}
