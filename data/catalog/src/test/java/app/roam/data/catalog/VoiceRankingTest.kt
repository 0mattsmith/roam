package app.roam.data.catalog

import app.roam.core.database.TrackListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a spoken request plays.
 *
 * Voice ranks where typing filters, and that is the whole difference: someone
 * typing gets a list and picks from it, someone driving gets whatever came
 * first. So the failure that matters is not an empty result -- that is obvious
 * and invites asking again -- it is confidently playing the wrong song.
 */
class VoiceRankingTest {

    private var nextId = 1L

    private fun track(title: String, artist: String, album: String, no: Int = 1) = TrackListItem(
        id = nextId++,
        remoteId = "r${nextId}",
        title = title,
        artistName = artist,
        albumTitle = album,
        albumArtistName = artist,
        compilation = false,
        albumId = 1,
        albumArtworkId = null,
        albumYear = null,
        albumDiscTotal = 1,
        trackNo = no,
        discNo = 1,
        durationMs = 200_000,
        artworkId = null,
        loved = false,
        startMs = null,
        endMs = null,
    )

    private val wonderwall = track("Wonderwall", "Oasis", "(What's the Story) Morning Glory?", 3)
    private val wonderwallCover = track("Wonderwall", "Ryan Adams", "Love Is Hell", 4)
    private val liveForever = track("Live Forever", "Oasis", "Definitely Maybe", 3)
    private val things = track("Things Can Only Get Better", "D:Ream", "D:Ream On Volume 1", 2)

    private val library = listOf(wonderwall, wonderwallCover, liveForever, things)

    private fun best(query: VoiceQuery) =
        VoiceRanking.rank(query, library, limit = 5).firstOrNull()

    // ---- the structured extras are the point ---------------------------------

    @Test
    fun `naming the artist as well picks the right recording`() {
        // Both are called Wonderwall. Only the extras tell them apart, which is
        // the whole reason for reading them instead of parsing the sentence.
        assertEquals(wonderwall, best(VoiceQuery(title = "Wonderwall", artist = "Oasis")))
        assertEquals(wonderwallCover, best(VoiceQuery(title = "Wonderwall", artist = "Ryan Adams")))
    }

    @Test
    fun `matching two named fields beats matching one`() {
        val both = VoiceRanking.score(VoiceQuery(title = "Wonderwall", artist = "Oasis"), wonderwall)
        val one = VoiceRanking.score(VoiceQuery(title = "Wonderwall", artist = "Oasis"), wonderwallCover)
        assertTrue("both-matched must outrank one", both > one)
    }

    @Test
    fun `an artist on their own finds their tracks`() {
        val found = VoiceRanking.rank(VoiceQuery(artist = "Oasis"), library, limit = 5)
        assertTrue(found.contains(wonderwall))
        assertTrue(found.contains(liveForever))
        assertTrue("someone else's record must not be in there", !found.contains(things))
    }

    // ---- what the microphone actually hears -----------------------------------

    @Test
    fun `punctuation nobody can say still matches`() {
        // The case this was raised about. Ids.normalise turns the colon into a
        // space, so the stored form is "d ream" while speech gives "dream".
        assertEquals(things, best(VoiceQuery(artist = "Dream", title = "Things Can Only Get Better")))
        assertEquals(things, best(VoiceQuery(raw = "dream")))
    }

    @Test
    fun `an apostrophe heard as nothing still matches`() {
        assertEquals(
            wonderwall,
            best(VoiceQuery(album = "Whats the Story Morning Glory", artist = "Oasis")),
        )
    }

    @Test
    fun `a leading article is not a difference`() {
        val beatles = track("Something", "The Beatles", "Abbey Road")
        assertEquals(
            beatles,
            VoiceRanking.rank(VoiceQuery(artist = "Beatles"), listOf(beatles) + library, 5).first(),
        )
    }

    // ---- refusing is an answer -------------------------------------------------

    @Test
    fun `nothing like it returns nothing`() {
        // Better than a weak guess: silence is obvious and invites asking
        // again, where the wrong song playing cannot be fixed at the wheel.
        assertTrue(VoiceRanking.rank(VoiceQuery(title = "Smells Like Teen Spirit"), library, 5).isEmpty())
        assertTrue(VoiceRanking.rank(VoiceQuery(raw = "zzzzzzzz"), library, 5).isEmpty())
    }

    @Test
    fun `an empty request is recognised rather than searched`() {
        // "Play music". Handled by the caller as shuffle-everything; what
        // matters here is that it is not mistaken for a search for "".
        assertTrue(VoiceQuery().isEmpty)
        assertTrue(VoiceQuery(raw = "   ").isEmpty)
        assertTrue(!VoiceQuery(raw = "oasis").isEmpty)
    }

    // ---- the unstructured fallback --------------------------------------------

    @Test
    fun `a bare string is matched against every field`() {
        assertEquals(liveForever, best(VoiceQuery(raw = "Live Forever")))
        assertTrue(VoiceRanking.rank(VoiceQuery(raw = "Oasis"), library, 5).contains(wonderwall))
    }

    @Test
    fun `results come back in album order when they tie`() {
        // Asking for a record plays it from the top, not from track nine.
        val one = track("A", "Band", "Record", 1)
        val two = track("B", "Band", "Record", 2)
        val ranked = VoiceRanking.rank(VoiceQuery(album = "Record"), listOf(two, one), 5)
        assertEquals(listOf(one, two), ranked)
    }

    // ---- what the assistant sends ---------------------------------------------

    @Test
    fun `extras are read rather than the sentence parsed`() {
        val query = VoiceQuery.from(
            raw = "play wonderwall by oasis",
            extras = mapOf(
                VoiceQuery.EXTRA_TITLE to "Wonderwall",
                VoiceQuery.EXTRA_ARTIST to "Oasis",
                VoiceQuery.EXTRA_ALBUM to "",
            ),
        )
        assertEquals("Wonderwall", query.title)
        assertEquals("Oasis", query.artist)
        // Blank is absent, not a field to match on.
        assertEquals(null, query.album)
        assertEquals("Wonderwall", query.searchTerm)
    }

    @Test
    fun `the search term is the most specific thing named`() {
        assertEquals("Wonderwall", VoiceQuery(title = "Wonderwall", artist = "Oasis").searchTerm)
        assertEquals("Oasis", VoiceQuery(artist = "Oasis").searchTerm)
        assertEquals("britpop", VoiceQuery(raw = "britpop").searchTerm)
    }
}
