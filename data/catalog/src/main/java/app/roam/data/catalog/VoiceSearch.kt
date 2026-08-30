package app.roam.data.catalog

import app.roam.core.database.TrackListItem
import app.roam.core.model.Ids

/**
 * What the assistant was actually asked for.
 *
 * Google hands a voice request over ALREADY SPLIT: "play Wonderwall by Oasis"
 * arrives as a title and an artist in separate extras, with a focus saying
 * which kind of thing was meant. Parsing [raw] instead of reading those is the
 * single biggest thing that makes voice search feel broken, because "by" is a
 * word that appears in song titles.
 *
 * Every field is optional and often all of them are: an empty query is the
 * assistant saying "play something", which is a real request and not an error.
 */
data class VoiceQuery(
    val raw: String = "",
    val artist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val genre: String? = null,
) {
    /** Nothing to go on -- "play music". Shuffle everything. */
    val isEmpty: Boolean
        get() = raw.isBlank() && artist.isNullOrBlank() && album.isNullOrBlank() &&
            title.isNullOrBlank() && genre.isNullOrBlank()

    /**
     * The one term worth handing to a LIKE.
     *
     * Most specific first: a title narrows harder than an artist, and an artist
     * harder than nothing. Ranking sorts out the rest, so this only has to
     * gather plausible rows rather than pick between them.
     */
    val searchTerm: String
        get() = listOfNotNull(title, album, artist, genre)
            .firstOrNull { it.isNotBlank() }
            ?: raw.trim()

    companion object {
        /**
         * From the assistant's extras, as a plain map so this stays testable.
         *
         * The Bundle lives in the player module; nothing about the RULES needs
         * Android, and a rule that cannot be tested without a device is a rule
         * nobody will change with any confidence.
         */
        fun from(raw: String?, extras: Map<String, String?>): VoiceQuery = VoiceQuery(
            raw = raw.orEmpty(),
            artist = extras[EXTRA_ARTIST]?.takeIf { it.isNotBlank() },
            album = extras[EXTRA_ALBUM]?.takeIf { it.isNotBlank() },
            title = extras[EXTRA_TITLE]?.takeIf { it.isNotBlank() },
            genre = extras[EXTRA_GENRE]?.takeIf { it.isNotBlank() },
        )

        // android.provider.MediaStore constants, named here so :data:catalog
        // does not have to reach into the Android framework for four strings.
        const val EXTRA_ARTIST = "android.intent.extra.artist"
        const val EXTRA_ALBUM = "android.intent.extra.album"
        const val EXTRA_TITLE = "android.intent.extra.title"
        const val EXTRA_GENRE = "android.intent.extra.genre"
        const val EXTRA_FOCUS = "android.intent.extra.focus"
    }
}

/**
 * How well one track answers a spoken request.
 *
 * Voice RANKS where typing filters, and that is the whole difference. Someone
 * typing gets a list and picks; someone driving gets whatever came first, so
 * being roughly right is worse than useless -- it plays the wrong song and they
 * cannot look down to fix it.
 *
 * Deliberately not FTS. The structured extras mean the common case is matching
 * known fields against known columns rather than searching free text, and a
 * personal library is small enough that gathering candidates with LIKE and
 * scoring them here costs nothing measurable. FTS would buy ranking Roam can do
 * in Kotlin, at the price of a table that has to be kept in step with `tracks`
 * forever -- and an index that silently drifts is a worse failure than a slow
 * query, because it looks like missing music.
 */
object VoiceRanking {

    /**
     * Speech recognition does not produce punctuation, so neither does this.
     *
     * "Rock 'n' Roll Star" is heard as "rock n roll star", and normalising both
     * sides is what makes those meet.
     */
    private fun key(value: String?): String = Ids.normalise(value.orEmpty())

    /**
     * The same thing with the gaps closed.
     *
     * [Ids.normalise] turns punctuation into a SPACE, which is right for
     * hashing and wrong here: "D:Ream" becomes "d ream" while the microphone
     * hears "dream", and those never meet however they are compared. Squeezing
     * both makes them identical -- and it is the artist this whole naming
     * problem was raised about.
     */
    private fun squeezed(value: String?): String = key(value).replace(" ", "")

    /** Exact, then prefix, then contained. Zero when it is none of those. */
    private fun field(spoken: String?, actual: String, weight: Int): Int {
        if (spoken.isNullOrBlank()) return 0
        return maxOf(
            compare(key(spoken), key(actual), weight),
            // Never better than the spaced form scores, so a real word match
            // still outranks one that only works with the gaps removed.
            compare(squeezed(spoken), squeezed(actual), weight) * 2 / 3,
        )
    }

    private fun compare(want: String, have: String, weight: Int): Int = when {
        want.isEmpty() || have.isEmpty() -> 0
        have == want -> weight
        have.startsWith(want) || want.startsWith(have) -> weight * 2 / 3
        have.contains(want) || want.contains(have) -> weight / 3
        else -> 0
    }

    /**
     * Weights: title beats artist beats album.
     *
     * A named song is the most specific thing anybody asks for, and an album
     * name is the least -- it is often also an artist name or a track name, so
     * scoring it highly makes self-titled records win requests meant for the
     * song.
     */
    fun score(query: VoiceQuery, track: TrackListItem): Int {
        var total = 0
        total += field(query.title, track.title, TITLE_WEIGHT)
        total += field(query.artist, track.artistName, ARTIST_WEIGHT)
        total += field(query.album, track.albumTitle, ALBUM_WEIGHT)

        // The unstructured form, for assistants and head units that send only a
        // string. Scored against every field but worth less than a field the
        // caller actually named, so "play Yesterday" does not lose to an album
        // called Yesterday when the focus said song.
        if (query.title == null && query.artist == null && query.album == null) {
            val raw = query.raw
            total += field(raw, track.title, TITLE_WEIGHT)
            total += field(raw, track.artistName, ARTIST_WEIGHT)
            total += field(raw, track.albumTitle, ALBUM_WEIGHT)
        }

        // Naming two things and matching both is far stronger evidence than
        // matching either alone, and this is what puts the right recording of a
        // much-covered song first.
        val named = listOfNotNull(query.title, query.artist, query.album).size
        if (named > 1 && total > 0) {
            val matched = listOf(
                field(query.title, track.title, TITLE_WEIGHT),
                field(query.artist, track.artistName, ARTIST_WEIGHT),
                field(query.album, track.albumTitle, ALBUM_WEIGHT),
            ).count { it > 0 }
            if (matched == named) total += BOTH_MATCHED_BONUS
        }
        return total
    }

    /**
     * Best first, and nothing that did not match at all.
     *
     * The floor matters more than the order. Returning weak matches means a
     * misheard word plays something unrelated rather than nothing, and nothing
     * is the better answer -- it is obvious, and it invites asking again.
     */
    fun rank(query: VoiceQuery, candidates: List<TrackListItem>, limit: Int): List<TrackListItem> =
        candidates.asSequence()
            .map { it to score(query, it) }
            .filter { it.second >= MINIMUM_SCORE }
            .sortedWith(compareByDescending<Pair<TrackListItem, Int>> { it.second }
                // Stable and sensible when scores tie: album order, so asking
                // for a record plays it from the top rather than from track
                // nine.
                .thenBy { it.first.discNo ?: 0 }
                .thenBy { it.first.trackNo ?: 0 })
            .map { it.first }
            .take(limit)
            .toList()

    private const val TITLE_WEIGHT = 100
    private const val ARTIST_WEIGHT = 70
    private const val ALBUM_WEIGHT = 50
    private const val BOTH_MATCHED_BONUS = 40

    /**
     * Below a third of one exact field, this is not a match.
     *
     * A "contains" hit on the weakest field scores ALBUM_WEIGHT / 3, which is
     * about as thin as a real answer gets -- anything under it is coincidence.
     */
    private const val MINIMUM_SCORE = ALBUM_WEIGHT / 3
}
