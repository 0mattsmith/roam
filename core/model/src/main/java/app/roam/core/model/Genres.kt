package app.roam.core.model

/**
 * A track's genres, which are a list everywhere except in the database column.
 *
 * One splitter and one joiner, in `:core:model` where everything can reach
 * them. The document parser, the document writer and the edit form all move
 * between the two forms, and three implementations of "how do you split
 * Britpop; Indie Rock" would eventually disagree about whether a slash counts.
 *
 * A single column is a compromise, and a temporary one: a genre RULE has to
 * match "Rock" without also matching "Punk Rock", which a LIKE over a joined
 * string cannot do. The smart playlists will want a proper table. Until then
 * the separator is documented, round-trips exactly, and lives here.
 */
object Genres {

    /** What [join] writes. [split] accepts more, because old tags used more. */
    const val SEPARATOR = "; "

    /**
     * Shortest key a near-miss correction will touch.
     *
     * Six, because at four characters one edit is a quarter of the word and
     * genuinely different genres sit that close -- Folk and Funk, Rock and
     * Rap, Soul and Sou. Below this, only an exact key match counts.
     */
    private const val MIN_FUZZY_LENGTH = 6

    /**
     * Whatever a tag or a hand-written file offered, as a list.
     *
     * Semicolons, commas and slashes all appear in real libraries -- "Rock,
     * Pop", "Britpop; Indie", "Rock/Metal" -- and every one of them means the
     * same thing to the person who typed it.
     */
    fun split(value: String?): List<String> =
        value?.split(';', ',', '/')
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
            // Distinct by KEY rather than by lower case, so one field
            // holding both "Britpop" and "Brit-Pop" yields one genre rather
            // than two spellings of the same one.
            .distinctBy { key(it) }

    /**
     * What two spellings of the same genre have in common.
     *
     * Lower case, letters and digits only -- so "Brit pop", "Brit-Pop" and
     * "BritPop" all key to `britpop`, and the difference between them stops
     * being a difference. This is what makes a genre RULE work across a library
     * that was tagged by six different tools over fifteen years.
     */
    fun key(genre: String): String =
        genre.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The spelling to prefer, where collapsing punctuation does not settle it.
     *
     * [key] already merges the punctuation variants; this only decides WHICH of
     * them wins. Left to itself Roam would pick whichever spelling the library
     * happened to hold most of, and "Brit pop" outnumbering "Britpop" is not a
     * reason to prefer it.
     *
     * Several keys may point at one name: "R&B" and "RnB" do not collapse to
     * the same key on their own, because the ampersand is punctuation and the
     * n is a letter.
     */
    val CANONICAL: Map<String, String> = mapOf(
        "britpop" to "Britpop",
        "hiphop" to "Hip-Hop",
        "rb" to "R&B",
        "rnb" to "R&B",
        "rhythmandblues" to "R&B",
        "lofi" to "Lo-Fi",
        "postrock" to "Post-Rock",
        "postpunk" to "Post-Punk",
        "triphop" to "Trip-Hop",
        "synthpop" to "Synth-Pop",
        "drumandbass" to "Drum & Bass",
        "dnb" to "Drum & Bass",
        "singersongwriter" to "Singer-Songwriter",
        "rocknroll" to "Rock \'n\' Roll",
    )

    /**
     * One genre, spelled the way this library spells it.
     *
     * The table wins, then whatever [known] already uses, then what was typed.
     * Preferring the library's own spelling is the part that matters day to
     * day: it is what stops a collection ending up with "Indie Rock" and
     * "indie rock" as two entries that no rule can match together.
     */
    fun canonical(genre: String, known: List<String> = emptyList()): String {
        val trimmed = genre.trim()
        if (trimmed.isEmpty()) return trimmed
        val k = key(trimmed)

        CANONICAL[k]?.let { return it }
        known.firstOrNull { key(it) == k }?.let { return it }

        return nearest(k, known) ?: trimmed
    }

    /**
     * The one known genre this is almost certainly a misspelling of.
     *
     * Three guards, and every one of them is load-bearing, because getting this
     * wrong silently refiles somebody's music.
     *
     * **The library must not already know it.** Checked by the caller: if the
     * spelling is already in use it is deliberate, whatever it looks like.
     * "Folk" and "Funk" are one edit apart and both real.
     *
     * **Length.** At four characters a single edit is most of the word, which
     * is why the floor exists at all rather than for tidiness.
     *
     * **Exactly one candidate.** Two known genres equally close means Roam
     * cannot tell which was meant, and picking either is a coin toss played
     * with someone else's library.
     */
    private fun nearest(k: String, known: List<String>): String? {
        if (k.length < MIN_FUZZY_LENGTH) return null
        val close = known.filter { withinOneEdit(k, key(it)) }
        return close.singleOrNull()
    }

    /**
     * Whether two keys differ by at most one insertion, deletion or swap.
     *
     * Written out rather than a full Levenshtein matrix: the answer is only
     * ever needed for a distance of one, and this runs for every genre against
     * every known genre each time the editor opens.
     */
    private fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false

        val shorter = if (a.length <= b.length) a else b
        val longer = if (a.length <= b.length) b else a

        var i = 0
        var j = 0
        var edited = false
        while (i < shorter.length && j < longer.length) {
            if (shorter[i] == longer[j]) {
                i++
                j++
                continue
            }
            if (edited) return false
            edited = true
            // Same length means a substitution, so both advance; otherwise the
            // longer string has the extra character and only it advances.
            if (shorter.length == longer.length) i++
            j++
        }
        return true
    }

    /** [canonical] over a list, dropping the duplicates it creates. */
    fun canonicalise(genres: List<String>, known: List<String> = emptyList()): List<String> =
        genres.map { canonical(it, known) }
            .filter { it.isNotEmpty() }
            .distinctBy { key(it) }

    /** Back to the single column. Null rather than "" when there are none. */
    fun join(genres: List<String>): String? =
        genres.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { key(it) }
            .joinToString(SEPARATOR)
            .ifBlank { null }
}
