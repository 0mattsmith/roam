package app.roam.data.catalog

import app.roam.core.model.Genres
import app.roam.core.model.Ids

/**
 * Play it, or shuffle it.
 *
 * The verb is half of what was said and the old code threw it away. "Play
 * Oasis" and "Shuffle Oasis" resolve to the same TRACKS and want completely
 * different orders, and guessing wrong is the difference between a discography
 * in order and a discography in a heap.
 */
enum class VoiceAction { PLAY, SHUFFLE }

/**
 * What was asked for, once the verb and the filler are off.
 *
 * Deliberately a closed set. Voice has no list to pick from, so every request
 * has to land on something Roam can queue without a follow-up question --
 * which means guessing WRONG is the failure mode, not guessing vaguely.
 */
sealed interface VoiceTarget {
    /** "play my music", "play everything", or nothing at all. */
    data object Everything : VoiceTarget

    /** "loved tracks", "my favourites". */
    data object Loved : VoiceTarget

    /** "britpop", "shuffle my britpop tracks". Canonicalised before it gets here. */
    data class Genre(val name: String) : VoiceTarget

    /**
     * "80s", "the nineties". Inclusive years.
     *
     * A range rather than a tag, because both spellings of the same idea are
     * in use: plenty of libraries tag a decade as a genre and plenty do not,
     * and the year is the one every track has.
     */
    data class Decade(val from: Int, val to: Int) : VoiceTarget

    /**
     * Something with a name -- an artist, an album, a song, or an album AND an
     * artist. Which of those it turns out to be is decided by looking in the
     * library, not by parsing, because "Oasis" and "Parklife" are the same
     * shape of word and only the catalogue knows the difference.
     */
    data class Named(
        val text: String,
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
    ) : VoiceTarget
}

data class VoiceCommand(val action: VoiceAction, val target: VoiceTarget)

/**
 * Turns what the microphone heard into something queueable.
 *
 * Reads the assistant's structured extras first and falls back to picking the
 * sentence apart, because the extras are not always sent and are never wrong
 * when they are. Everything here is pure: no Android, no database, no network
 * -- the library lookup happens afterwards, in the caller, once this has
 * decided WHAT was asked for.
 */
object VoiceCommands {

    /**
     * @param knownGenres what the library actually holds, so "britpop" is
     * recognised as a genre and "parklife" is not. Passing an empty list is
     * safe and simply means no request is ever read as a genre.
     */
    fun parse(
        raw: String?,
        extras: Map<String, String?> = emptyMap(),
        knownGenres: List<String> = emptyList(),
    ): VoiceCommand {
        val spoken = VoiceQuery.from(raw, extras)
        val text = raw.orEmpty().trim()

        val (action, rest) = splitVerb(text)

        // The extras win when the assistant sent them, because they are
        // already split and cannot be misread -- "play Yesterday by Yesterday"
        // is unparseable and perfectly unambiguous in the extras.
        if (spoken.artist != null || spoken.album != null || spoken.title != null) {
            return VoiceCommand(
                action,
                VoiceTarget.Named(
                    text = rest.ifBlank { text },
                    artist = spoken.artist,
                    album = spoken.album,
                    title = spoken.title,
                ),
            )
        }

        // A genre sent as an extra is unambiguous; one guessed from the words
        // has to be checked against the library below.
        spoken.genre?.let { genre ->
            return VoiceCommand(action, VoiceTarget.Genre(Genres.canonical(genre, knownGenres)))
        }

        val cleaned = stripFiller(rest)

        if (cleaned.isBlank() || key(cleaned) in EVERYTHING) {
            return VoiceCommand(action, VoiceTarget.Everything)
        }
        if (key(cleaned) in LOVED) {
            return VoiceCommand(action, VoiceTarget.Loved)
        }
        decade(cleaned)?.let { return VoiceCommand(action, it) }

        // Checked against the library rather than a fixed list: a genre is
        // whatever this collection actually calls one, and a name that happens
        // to look like a genre but is not in it is far more likely to be a band.
        knownGenres.firstOrNull { Genres.key(it) == key(cleaned) }?.let {
            return VoiceCommand(action, VoiceTarget.Genre(it))
        }

        val (album, artist) = splitBy(cleaned)
        return VoiceCommand(
            action,
            VoiceTarget.Named(text = cleaned, artist = artist, album = album),
        )
    }

    /**
     * The verb, and what is left after it.
     *
     * Assistants vary in whether they hand the verb over at all, so a missing
     * one means PLAY rather than an error -- which is also what somebody
     * saying just "Oasis" into a car means.
     */
    private fun splitVerb(text: String): Pair<VoiceAction, String> {
        val lower = text.lowercase().trim()
        for (verb in SHUFFLE_VERBS) {
            if (lower == verb) return VoiceAction.SHUFFLE to ""
            if (lower.startsWith("$verb ")) {
                return VoiceAction.SHUFFLE to text.substring(verb.length).trim()
            }
        }
        for (verb in PLAY_VERBS) {
            if (lower == verb) return VoiceAction.PLAY to ""
            if (lower.startsWith("$verb ")) {
                return VoiceAction.PLAY to text.substring(verb.length).trim()
            }
        }
        return VoiceAction.PLAY to text
    }

    /**
     * Drops the words that carry no target: "shuffle MY britpop TRACKS".
     *
     * The two lists are separate because the positions are not
     * interchangeable, and treating them as one list is a real bug rather than
     * a tidiness point. "Songs" is filler at the END and a title word at the
     * START -- with one list, "play some Songs of Faith and Devotion" strips
     * "some", promotes "Songs" to the edge, strips that too, and asks the
     * library for "of Faith and Devotion".
     *
     * Only ever at the edges either way. Stripping from the middle would turn
     * that album into one nobody owns.
     */
    private fun stripFiller(text: String): String {
        var words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Never the last word standing. There are albums called "Songs", and
        // eating the only word turns a request for one into a request for the
        // entire library -- the loudest possible way to be wrong.
        while (words.size > 1 && key(words.first()) in LEADING_FILLER) words = words.drop(1)
        while (words.size > 1 && key(words.last()) in TRAILING_FILLER) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    /** "Heathen Chemistry by Oasis" -> album, artist. */
    private fun splitBy(text: String): Pair<String?, String?> {
        val marker = Regex("\\s+by\\s+", RegexOption.IGNORE_CASE).find(text) ?: return null to null
        val before = text.substring(0, marker.range.first).trim()
        val after = text.substring(marker.range.last + 1).trim()
        if (before.isEmpty() || after.isEmpty()) return null to null
        return before to after
    }

    private fun decade(text: String): VoiceTarget.Decade? {
        val start = DECADES[key(text)] ?: return null
        return VoiceTarget.Decade(start, start + 9)
    }

    private fun key(text: String): String = Ids.normalise(text).replace(" ", "")

    private val SHUFFLE_VERBS = listOf("shuffle", "shuffle play", "play shuffled", "randomise", "randomize")
    private val PLAY_VERBS = listOf("play", "put on", "listen to", "start")

    /** Determiners, which only ever precede the thing asked for. */
    private val LEADING_FILLER = setOf("my", "some", "the", "a", "all")

    /** Nouns people append: "my britpop TRACKS", "play 80s MUSIC". */
    private val TRAILING_FILLER = setOf("tracks", "track", "songs", "song", "stuff", "please")

    /** Everything, however it was asked for. "music" alone means all of it. */
    private val EVERYTHING = setOf("music", "everything", "library", "anything", "mymusic", "mylibrary")

    private val LOVED = setOf("loved", "lovedtracks", "favourites", "favorites", "faves", "hearted", "likes", "liked")

    /**
     * Both spellings of every decade, because people say either.
     *
     * "20s" is read as the 2020s rather than the 1920s -- a personal library is
     * far likelier to hold one than the other, and being wrong here costs one
     * re-ask rather than anything worse.
     */
    private val DECADES = mapOf(
        "50s" to 1950, "1950s" to 1950, "fifties" to 1950,
        "60s" to 1960, "1960s" to 1960, "sixties" to 1960,
        "70s" to 1970, "1970s" to 1970, "seventies" to 1970,
        "80s" to 1980, "1980s" to 1980, "eighties" to 1980,
        "90s" to 1990, "1990s" to 1990, "nineties" to 1990,
        "00s" to 2000, "2000s" to 2000, "noughties" to 2000, "aughts" to 2000,
        "10s" to 2010, "2010s" to 2010, "twentytens" to 2010,
        "20s" to 2020, "2020s" to 2020, "twenties" to 2020,
    )
}
