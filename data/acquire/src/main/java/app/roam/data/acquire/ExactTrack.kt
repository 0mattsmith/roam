package app.roam.data.acquire

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * Whether a YouTube upload IS the track an album page asked for -- not a live
 * take, a remix, a cover or a sped-up edit that happens to share its name.
 *
 * An album page queues a search, not a video, and "Oasis Whatever" answers
 * with every recording of it anyone ever uploaded. Length alone let through
 * anything within fifteen seconds, which a live take often is. So a candidate
 * has to be the same SONG, by the same ACT, in the same PERFORMANCE, at the
 * same LENGTH -- and a refusal says which of those each one failed.
 *
 * Pure, so every rule is tested rather than found out from a folder of wrong
 * downloads.
 */
internal object ExactTrack {

    /** What the catalogue says the track is. */
    data class Wanted(
        val title: String,
        val artist: String,
        val album: String,
        val albumArtist: String = "",
        /** Null when the catalogue gave no length, and then length is not checked. */
        val durationMs: Long? = null,
    )

    /** Why an upload is not the track. Each one is a different conversation. */
    enum class Miss { OTHER_SONG, OTHER_ARTIST, OTHER_PERFORMANCE, OTHER_LENGTH }

    /** Null when the upload is the track. */
    fun miss(want: Wanted, candidate: YoutubeResult): Miss? {
        val readings = VideoClues.read(candidate)
        val titles = readings.map { it.title } + listOfNotNull(candidate.creditedTitle)
        if (titles.none { sameTitle(want.title, it) }) return Miss.OTHER_SONG

        val byTheAct = readings.any { reading ->
            sameTitle(want.title, reading.title) &&
                (sameAct(reading.artist, want.artist) || sameAct(reading.artist, want.albumArtist))
        }
        if (!byTheAct) return Miss.OTHER_ARTIST

        // YouTube Music naming the same album vouches for the performance. A
        // live album's tracks are live, and the catalogue seldom says so in
        // each title -- but only THAT album's live take is the one wanted, so
        // a live upload from anywhere else is still refused below.
        val fromAlbum = sameAlbum(want, candidate)
        if (!fromAlbum && !samePerformance(want, candidate)) return Miss.OTHER_PERFORMANCE

        if (!sameLength(want, candidate)) return Miss.OTHER_LENGTH
        return null
    }

    /**
     * The best upload that is the track, or null when none is: one from the
     * same album first, then the label's own audio over somebody's video, then
     * the closest length. Ties keep the search's order.
     */
    fun choose(want: Wanted, candidates: List<YoutubeResult>): YoutubeResult? =
        candidates.filter { miss(want, it) == null }
            .sortedWith(
                compareByDescending<YoutubeResult> { sameAlbum(want, it) }
                    .thenByDescending { isAlbumAudio(it) }
                    .thenBy { lengthGap(want, it) ?: Long.MAX_VALUE },
            )
            .firstOrNull()

    /**
     * What a refusal says: what came back instead. "No exact match" alone
     * sends someone off to check their connection; "2 live or other versions"
     * tells them the catalogue's track is simply not on YouTube as itself.
     */
    fun refusal(want: Wanted, candidates: List<YoutubeResult>, where: String? = null): String {
        val misses = candidates.map { it to miss(want, it) }
        val counts = misses.mapNotNull { it.second }.groupingBy { it }.eachCount()
        val closest = misses.filter { it.second == Miss.OTHER_LENGTH }
            .mapNotNull { it.first.durationSec }
            .minByOrNull { seconds -> want.durationMs?.let { abs(seconds * 1_000L - it) } ?: 0L }

        val parts = listOfNotNull(
            counts[Miss.OTHER_PERFORMANCE]?.let { n -> "$n live or other version${if (n == 1) "" else "s"}" },
            counts[Miss.OTHER_LENGTH]?.let { n ->
                "$n the wrong length" + (closest?.let { " (closest ${clock(it * 1_000L)})" } ?: "")
            },
            counts[Miss.OTHER_SONG]?.let { n -> if (n == 1) "1 a different song" else "$n different songs" },
            counts[Miss.OTHER_ARTIST]?.let { n -> "$n by someone else" },
        )
        val length = want.durationMs?.takeIf { it > 0 }?.let { " (${clock(it)})" }.orEmpty()
        val place = where?.let { " $it" }.orEmpty()
        return "No exact match for “${want.title}”$length$place — " + parts.joinToString(", ")
    }

    // ---- the four questions -------------------------------------------------

    /** The title as written, or failing that with brackets off on both sides. */
    private fun sameTitle(wanted: String, theirs: String): Boolean {
        val cleaned = VideoClues.cleanTitle(theirs)
        val exact = VideoClues.key(wanted)
        if (exact.isNotEmpty() && VideoClues.key(cleaned) == exact) return true
        val loose = VideoClues.key(VideoClues.searchable(wanted))
        return loose.isNotEmpty() && VideoClues.key(VideoClues.searchable(cleaned)) == loose
    }

    /**
     * Any act named on one side is named on the other: "Elton John x Dua
     * Lipa" is "Elton John & Dua Lipa". Whole names only, so a tribute band
     * is not the band.
     */
    private fun sameAct(theirs: String, ours: String): Boolean {
        val wanted = actKeys(ours)
        return wanted.isNotEmpty() && actKeys(theirs).any { it in wanted }
    }

    private fun actKeys(name: String): Set<String> =
        (listOf(name, RecordingPicker.mainCredit(name)) + VideoClues.splitArtists(name))
            .map(VideoClues::key)
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * No version words the catalogue's title does not have -- "Live",
     * "Acoustic", "Remix", "Sped Up" -- and none missing that it does have.
     *
     * Read from what is LEFT once the song's own name and the act's are taken
     * out, because "Live Forever" is a studio song and "Live" is a band. The
     * album's own words are allowed too: on a record called "Live at Leeds",
     * live is the point.
     */
    private fun samePerformance(want: Wanted, candidate: YoutubeResult): Boolean {
        val core = plain(VideoClues.searchable(want.title))
        val required = markers(without(plain(want.title), listOf(core)))
        val allowed = required + markers(plain(want.album))

        val names = listOf(core, plain(want.artist), plain(want.albumArtist))
        val theirs = plain(listOfNotNull(candidate.videoTitle, candidate.creditedTitle).joinToString(" "))
        val found = markers(without(theirs, names))

        return allowed.containsAll(found) && found.containsAll(required)
    }

    /**
     * Within [TOLERANCE_MS] of the catalogue. An upload whose length is not
     * known fails when the catalogue's is: "could not check" is not a pass.
     */
    private fun sameLength(want: Wanted, candidate: YoutubeResult): Boolean {
        if (want.durationMs == null || want.durationMs <= 0) return true
        val gap = lengthGap(want, candidate) ?: return false
        return gap <= TOLERANCE_MS
    }

    private fun lengthGap(want: Wanted, candidate: YoutubeResult): Long? {
        val wanted = want.durationMs?.takeIf { it > 0 } ?: return null
        val seconds = candidate.durationSec ?: return null
        return abs(seconds * 1_000L - wanted)
    }

    private fun sameAlbum(want: Wanted, candidate: YoutubeResult): Boolean {
        val theirs = candidate.album?.let(::albumKey).orEmpty()
        return theirs.isNotEmpty() && theirs == albumKey(want.album)
    }

    /** Remasters, deluxe editions and " - Single" are all the same album here. */
    private fun albumKey(album: String): String =
        VideoClues.key(VideoClues.searchable(VideoClues.cleanTitle(album)))

    /** The label's own upload: YouTube Music's auto-generated audio. */
    private fun isAlbumAudio(candidate: YoutubeResult): Boolean =
        candidate.description?.contains("Provided to YouTube by", ignoreCase = true) == true ||
            candidate.channel?.endsWith(" - Topic", ignoreCase = true) == true

    // ---- words --------------------------------------------------------------

    /** Lower case, no accents, words separated by single spaces and padded by one. */
    private fun plain(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        return " " + folded.replace(NOT_WORD, " ").trim() + " "
    }

    private fun without(text: String, phrases: List<String>): String =
        phrases.map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .fold(text) { rest, phrase ->
                var t = rest
                while (" $phrase " in t) t = t.replace(" $phrase ", " ")
                t
            }

    private fun markers(text: String): Set<String> =
        MARKERS.filterKeys { " $it " in text }.values.toSet()

    private fun clock(ms: Long): String =
        String.format(Locale.ROOT, "%d:%02d", ms / 60_000, (ms % 60_000) / 1_000)

    /**
     * Five seconds. The label's audio and the catalogue agree to within a
     * second or two, so this allows a remaster's longer fade -- and very
     * little else: a live take, an edit or an extended mix lands further out.
     */
    private const val TOLERANCE_MS = 5_000L

    private val MARKS = Regex("""\p{Mn}+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Every way of saying "on stage", counted as one: "En Vivo" on the album allows "Live". */
    private val LIVE = listOf(
        "live", "en vivo", "ao vivo", "en directo", "concert", "tour", "unplugged",
        "session", "sessions", "bbc", "tiny desk", "rehearsal", "soundcheck", "performance",
    )

    /** Versions that are not the record, each its own thing. */
    private val OTHER = listOf(
        "acoustic", "stripped", "piano", "orchestral", "symphonic",
        "remix", "rmx", "extended", "club mix", "mashup", "medley", "edit",
        "cover", "tribute", "karaoke", "instrumental", "a cappella", "acapella",
        "demo", "reprise", "mono",
        "sped up", "speed up", "slowed", "reverb", "nightcore", "8d", "bass boosted",
        "1 hour", "10 hours",
    )

    /** Phrase as it appears in [plain] text, to what it means. */
    private val MARKERS: Map<String, String> = LIVE.associateWith { "live" } + OTHER.associateWith { it }
}
