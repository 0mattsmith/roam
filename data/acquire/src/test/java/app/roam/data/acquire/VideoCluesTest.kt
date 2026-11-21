package app.roam.data.acquire

import app.roam.data.acquire.VideoClues.Guess
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A video's own words, read as questions for the catalogue. None of these
 * strings is ever written as a tag; what matters is that the question asked
 * is the right one, and that the noise never reaches it.
 */
class VideoCluesTest {

    private fun first(
        title: String,
        channel: String? = null,
        description: String? = null,
        creditedTitle: String? = null,
        creditedArtist: String? = null,
    ): Guess = VideoClues.read(title, channel, description, creditedTitle, creditedArtist).first()

    // ---- the title ----------------------------------------------------------

    @Test
    fun `an official video is the song by the act before the dash`() {
        assertEquals(Guess("Whatever", "Oasis"), first("Oasis - Whatever (Official Video)"))
    }

    @Test
    fun `the act the channel names decides which half is which`() {
        assertEquals(Guess("Whatever", "Oasis"), first("Whatever - Oasis", channel = "Oasis - Topic"))
    }

    @Test
    fun `with nothing to say which half is the act, both orders are asked`() {
        assertEquals(
            listOf(Guess("Oasis", "Whatever"), Guess("Whatever", "Oasis")),
            VideoClues.read("Whatever - Oasis"),
        )
    }

    @Test
    fun `a title with no act in it takes the act the description names`() {
        val guess = first(
            "Whatever (Official Video)",
            channel = "Big Brother Recordings",
            description = "Listen to Oasis: https://oasis.lnk.to/listenYD\nFollow Oasis\nFacebook: https://x",
        )
        assertEquals(Guess("Whatever", "Oasis"), guess)
    }

    @Test
    fun `a quoted title is the song and the rest is the act`() {
        assertEquals(Guess("Shut Down", "BLACKPINK"), first("BLACKPINK 'Shut Down' M/V"))
    }

    @Test
    fun `a bar separates the parts of a title like a dash`() {
        assertEquals(Guess("Whatever", "Oasis"), first("Oasis | Whatever | Official Video"))
    }

    @Test
    fun `a song can be called Video, or 2002`() {
        // Only a word that is never a title makes the far side of a dash noise.
        assertEquals(Guess("Video", "India.Arie"), first("India.Arie - Video"))
        assertEquals(Guess("2002", "Anne-Marie"), first("Anne-Marie - 2002 [Official Video]"))
    }

    // ---- the description ----------------------------------------------------

    @Test
    fun `YouTube Music's own block outranks the title`() {
        val description = """
            Provided to YouTube by Big Brother Recordings Ltd

            Whatever · Oasis

            Time Flies... 1994-2009

            ℗ 2010 Big Brother Recordings Ltd
        """.trimIndent()
        assertEquals(
            Guess("Whatever", "Oasis"),
            first("Whatever (Remastered)", channel = "Oasis - Topic", description = description),
        )
    }

    @Test
    fun `the VEVO template names both halves, full stops and all`() {
        val guess = first(
            "Mr Brightside",
            channel = "TheKillersVEVO",
            description = "Music video by The Killers performing Mr. Brightside. (C) 2004 The Island Def Jam Music Group",
        )
        assertEquals(Guess("Mr. Brightside", "The Killers"), guess)
    }

    @Test
    fun `the label's own phrasing is read too`() {
        val guess = first("Bad Habits", description = "Official video for “Bad Habits” by Ed Sheeran.")
        assertEquals(Guess("Bad Habits", "Ed Sheeran"), guess)
    }

    @Test
    fun `YouTube Music's credit is read when it gives one`() {
        assertEquals(
            Guess("Whatever", "Oasis"),
            first("Whatever", creditedTitle = "Whatever", creditedArtist = "Oasis"),
        )
    }

    @Test
    fun `the same reading from two places is asked once`() {
        val guesses = VideoClues.read(
            "Oasis - Whatever (Official Video)",
            creditedTitle = "Whatever",
            creditedArtist = "Oasis",
        )
        assertEquals(1, guesses.count { it == Guess("Whatever", "Oasis") })
    }

    @Test
    fun `a line about the playlist is not an act`() {
        val guesses = VideoClues.read(
            "Whatever",
            description = "Listen to the full album here: https://x\nFollow us on Instagram:",
        )
        assertEquals(emptyList<Guess>(), guesses)
    }

    // ---- cleaning -----------------------------------------------------------

    @Test
    fun `what is never part of a song's name comes off`() {
        assertEquals("Whatever", VideoClues.cleanTitle("Whatever (Official Music Video)"))
        assertEquals("Whatever", VideoClues.cleanTitle("Whatever [HD]"))
        assertEquals("Whatever", VideoClues.cleanTitle("Whatever (Remastered 2014)"))
        assertEquals("Whatever", VideoClues.cleanTitle("Whatever - 2014 Remaster"))
        assertEquals("Whatever", VideoClues.cleanTitle("Whatever Official Video"))
        assertEquals("Whatever", VideoClues.cleanTitle("“Whatever”"))
        assertEquals(
            "Feels",
            VideoClues.cleanTitle("Feels (Official Video) ft. Pharrell Williams, Katy Perry, Big Sean"),
        )
        assertEquals("Cold Heart", VideoClues.cleanTitle("Cold Heart (with Dua Lipa)"))
    }

    @Test
    fun `what might be part of a song's name stays`() {
        assertEquals("Wonderwall (Live at Knebworth)", VideoClues.cleanTitle("Wonderwall (Live at Knebworth)"))
        assertEquals("Cold Heart (PNAU Remix)", VideoClues.cleanTitle("Cold Heart (PNAU Remix) [Official Video]"))
        assertEquals("(What's the Story) Morning Glory?", VideoClues.cleanTitle("(What's the Story) Morning Glory?"))
    }

    @Test
    fun `a channel name is the act behind it`() {
        assertEquals(listOf("Oasis"), VideoClues.channelNames("OasisVEVO"))
        assertEquals(listOf("Oasis"), VideoClues.channelNames("Oasis - Topic"))
        assertEquals(listOf("Oasis"), VideoClues.channelNames("Oasis Official"))
        // Run together on purpose or by VEVO: both spellings, as it stands first.
        assertEquals(listOf("TheKillers", "The Killers"), VideoClues.channelNames("TheKillersVEVO"))
    }

    @Test
    fun `spellings that differ only in accents, case or an ampersand are one key`() {
        assertEquals(VideoClues.key("Beyoncé"), VideoClues.key("BEYONCE"))
        assertEquals(VideoClues.key("Simon & Garfunkel"), VideoClues.key("Simon and Garfunkel"))
        assertEquals(VideoClues.key("Don't Look Back in Anger"), VideoClues.key("Don’t Look Back In Anger"))
    }

    @Test
    fun `the search phrase drops brackets and anything after a dash`() {
        assertEquals("Wonderwall", VideoClues.searchable("Wonderwall (Live at Knebworth)"))
        assertEquals("Wonderwall", VideoClues.searchable("Wonderwall - Live"))
        assertEquals("Morning Glory?", VideoClues.searchable("(What's the Story) Morning Glory?"))
        assertEquals("(Untitled)", VideoClues.searchable("(Untitled)"))
    }

    @Test
    fun `the act searched for is the first one named`() {
        assertEquals("Elton John", VideoClues.queryArtist("Elton John x Dua Lipa"))
        assertEquals("Calvin Harris", VideoClues.queryArtist("Calvin Harris, Pharrell Williams"))
        assertEquals("Oasis", VideoClues.queryArtist("Oasis"))
    }
}
