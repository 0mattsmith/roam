package app.roam.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a sentence said at a steering wheel turns into.
 *
 * Voice has no list to pick from, so every request has to land on something
 * queueable without a follow-up question. That makes a WRONG guess the failure
 * mode rather than a vague one -- "play Oasis" filling the car with a
 * compilation that mentions them is worse than it playing nothing.
 */
class VoiceCommandsTest {

    private val genres = listOf("Britpop", "Alternative Rock", "Electronic", "Hip-Hop")

    private fun parse(said: String, extras: Map<String, String?> = emptyMap()) =
        VoiceCommands.parse(said, extras, genres)

    // ---- the verb is half of what was said -----------------------------------

    @Test
    fun `play and shuffle are different requests`() {
        assertEquals(VoiceAction.PLAY, parse("play Oasis").action)
        assertEquals(VoiceAction.SHUFFLE, parse("shuffle Oasis").action)
    }

    @Test
    fun `a missing verb means play`() {
        // Assistants differ in whether they hand the verb over, and somebody
        // saying just "Oasis" into a car means play it.
        assertEquals(VoiceAction.PLAY, parse("Oasis").action)
    }

    @Test
    fun `the verb is not mistaken for the thing asked for`() {
        assertEquals(VoiceTarget.Named("Oasis"), parse("play Oasis").target)
        assertEquals(VoiceTarget.Named("Oasis"), parse("shuffle Oasis").target)
        assertEquals(VoiceTarget.Named("Oasis"), parse("put on Oasis").target)
    }

    // ---- the whole library ----------------------------------------------------

    @Test
    fun `play my music means everything`() {
        assertEquals(VoiceTarget.Everything, parse("play my music").target)
        assertEquals(VoiceTarget.Everything, parse("play everything").target)
        assertEquals(VoiceTarget.Everything, parse("shuffle my library").target)
        assertEquals(VoiceTarget.Everything, parse("play music").target)
    }

    @Test
    fun `an empty request is still a request`() {
        // "Play" on its own, and whatever an assistant sends when it heard a
        // verb and nothing else.
        assertEquals(VoiceTarget.Everything, parse("play").target)
        assertEquals(VoiceTarget.Everything, parse("").target)
    }

    // ---- loved ----------------------------------------------------------------

    @Test
    fun `loved is recognised however it is said`() {
        assertEquals(VoiceTarget.Loved, parse("shuffle loved tracks").target)
        assertEquals(VoiceTarget.Loved, parse("play my favourites").target)
        assertEquals(VoiceTarget.Loved, parse("shuffle my loved songs").target)
        assertEquals(VoiceAction.SHUFFLE, parse("shuffle loved tracks").action)
    }

    // ---- decades ---------------------------------------------------------------

    @Test
    fun `a decade becomes a year range`() {
        assertEquals(VoiceTarget.Decade(1980, 1989), parse("play 80s").target)
        assertEquals(VoiceTarget.Decade(1990, 1999), parse("shuffle 90s").target)
        assertEquals(VoiceTarget.Decade(1990, 1999), parse("play the nineties").target)
        assertEquals(VoiceTarget.Decade(2000, 2009), parse("play 00s").target)
    }

    @Test
    fun `both spellings of a decade agree`() {
        assertEquals(parse("play 80s").target, parse("play 1980s").target)
        assertEquals(parse("play 80s").target, parse("play eighties").target)
    }

    // ---- genres ----------------------------------------------------------------

    @Test
    fun `a genre the library actually holds is recognised`() {
        assertEquals(VoiceTarget.Genre("Britpop"), parse("shuffle my britpop tracks").target)
        assertEquals(VoiceTarget.Genre("Britpop"), parse("play britpop").target)
        assertEquals(VoiceTarget.Genre("Hip-Hop"), parse("play hip hop").target)
    }

    @Test
    fun `a word that is not a genre in THIS library is a name`() {
        // The check is against the collection, not a fixed list. Somebody with
        // no jazz who says "play Jazz" almost certainly means the band.
        val target = VoiceCommands.parse("play Jazz", emptyMap(), knownGenres = genres).target
        assertTrue(target is VoiceTarget.Named)
    }

    @Test
    fun `an empty genre list never misreads anything as a genre`() {
        val target = VoiceCommands.parse("play britpop", emptyMap(), knownGenres = emptyList()).target
        assertTrue(target is VoiceTarget.Named)
    }

    // ---- names ------------------------------------------------------------------

    @Test
    fun `X by Y is split into an album and an artist`() {
        val target = parse("play Heathen Chemistry by Oasis").target as VoiceTarget.Named
        assertEquals("Heathen Chemistry", target.album)
        assertEquals("Oasis", target.artist)
    }

    @Test
    fun `a by in the middle of a title does not split it`() {
        // "Stand By Me" is one name. The split needs something on both sides
        // AND is only taken when nothing better is on offer.
        val target = parse("play Nothing by Nobody by The Band").target as VoiceTarget.Named
        assertEquals("Nothing", target.album)
        assertEquals("Nobody by The Band", target.artist)
    }

    @Test
    fun `filler words are removed from the edges and nowhere else`() {
        // "Songs of Faith and Devotion" is an album -- stripping from the
        // middle would turn it into one nobody owns.
        val target = parse("play some Songs of Faith and Devotion").target as VoiceTarget.Named
        assertEquals("Songs of Faith and Devotion", target.text)
    }

    // ---- the extras beat the sentence, always -----------------------------------

    @Test
    fun `structured extras are used rather than parsed words`() {
        val target = parse(
            "play wonderwall by oasis",
            mapOf(VoiceQuery.EXTRA_TITLE to "Wonderwall", VoiceQuery.EXTRA_ARTIST to "Oasis"),
        ).target as VoiceTarget.Named

        assertEquals("Wonderwall", target.title)
        assertEquals("Oasis", target.artist)
    }

    @Test
    fun `an extra genre is trusted without checking the library`() {
        assertEquals(
            VoiceTarget.Genre("Britpop"),
            parse("play something", mapOf(VoiceQuery.EXTRA_GENRE to "britpop")).target,
        )
    }

    @Test
    fun `the verb still applies when the extras decide the target`() {
        val command = parse(
            "shuffle heathen chemistry by oasis",
            mapOf(VoiceQuery.EXTRA_ALBUM to "Heathen Chemistry", VoiceQuery.EXTRA_ARTIST to "Oasis"),
        )
        assertEquals(VoiceAction.SHUFFLE, command.action)
        assertEquals("Heathen Chemistry", (command.target as VoiceTarget.Named).album)
    }

    // ---- everything the user asked for, in one go --------------------------------

    @Test
    fun `every spoken example resolves as intended`() {
        val expected = listOf(
            "Play Oasis" to (VoiceAction.PLAY to "named"),
            "Play Heathen Chemistry by Oasis" to (VoiceAction.PLAY to "named"),
            "Shuffle Oasis" to (VoiceAction.SHUFFLE to "named"),
            "Shuffle Heathen Chemistry by Oasis" to (VoiceAction.SHUFFLE to "named"),
            "Shuffle loved tracks" to (VoiceAction.SHUFFLE to "loved"),
            "Play my music" to (VoiceAction.PLAY to "everything"),
            "Play 80s" to (VoiceAction.PLAY to "decade"),
            "Shuffle my Britpop tracks" to (VoiceAction.SHUFFLE to "genre"),
        )
        for ((said, want) in expected) {
            val command = parse(said)
            val kind = when (command.target) {
                VoiceTarget.Everything -> "everything"
                VoiceTarget.Loved -> "loved"
                is VoiceTarget.Genre -> "genre"
                is VoiceTarget.Decade -> "decade"
                is VoiceTarget.Named -> "named"
            }
            assertEquals(said, want.first, command.action)
            assertEquals(said, want.second, kind)
        }
    }
}
