package app.roam.data.acquire

import app.roam.data.catalog.metadata.RecordingMatch
import app.roam.data.catalog.metadata.ReleaseAppearance
import kotlin.math.abs

/**
 * Which recording a video is, and which release it files under.
 *
 * Two different questions. The first is a match: same song, same act, and
 * where the catalogue holds several versions, the one whose length agrees with
 * the video. The second is a choice, because the same recording is on the
 * single, the album, the remaster and every compilation that ever licensed
 * it -- and a file can only live in one folder.
 */
internal object RecordingPicker {

    data class Pick(val recording: RecordingMatch, val release: ReleaseAppearance?)

    /**
     * The recording this guess describes, or null when nothing found is it.
     *
     * Null is an answer, and a better one than a near miss: a song filed
     * under a cover version's album is wrong in a way nobody notices until
     * they go looking for the original.
     */
    fun pick(guess: VideoClues.Guess, found: List<RecordingMatch>, videoMs: Long?): Pick? {
        val byArtist = found.filter { sameArtist(guess.artist, it) }

        // The title as written first, so "Cold Heart (PNAU Remix)" finds the
        // remix. Only failing that, brackets off on both sides -- which is how
        // "Wonderwall (Live at Knebworth)" finds the catalogue's "Wonderwall",
        // and leaves the length to say which performance it was.
        val exact = VideoClues.key(guess.title)
        val loose = VideoClues.key(VideoClues.searchable(guess.title))
        val candidates = byArtist.filter { exact.isNotEmpty() && VideoClues.key(it.title) == exact }
            .ifEmpty {
                byArtist.filter {
                    loose.isNotEmpty() && VideoClues.key(VideoClues.searchable(it.title)) == loose
                }
            }
        if (candidates.isEmpty()) return null

        // A PREFERENCE, never a filter. A music video runs long -- an intro,
        // a skit, a minute of credits -- and still is the song; but where a
        // recording does agree with it, that settles studio against live.
        val near = if (videoMs == null) {
            emptyList()
        } else {
            candidates.filter { r -> r.lengthMs?.let { abs(it - videoMs) <= LENGTH_TOLERANCE_MS } == true }
        }
        val pool = near.ifEmpty { candidates }

        val chosen = chooseRelease(pool.flatMap { recording -> recording.releases.map { recording to it } })
        return chosen?.let { (recording, release) -> Pick(recording, release) } ?: Pick(pool.first(), null)
    }

    /**
     * Whether a recording is by the act a video names: the whole credit, the
     * act before any "feat.", or any one act it names. A video by one of
     * several acts is still that song -- "Pharrell Williams" uploading a
     * track credited to "Calvin Harris feat. Pharrell Williams" -- but a
     * name merely CONTAINED in a credit is not: a tribute band is not the band.
     */
    fun sameArtist(clue: String, recording: RecordingMatch): Boolean {
        val credited = (recording.artistNames + recording.artist + mainCredit(recording.artist))
            .map(VideoClues::key)
            .filter { it.isNotEmpty() }
            .toSet()
        if (VideoClues.key(clue) in credited) return true
        return VideoClues.splitArtists(clue).any { VideoClues.key(it) in credited }
    }

    /**
     * "Calvin Harris feat. Pharrell Williams" -> "Calvin Harris". A duo or a
     * band is left whole: "Simon & Garfunkel" is one act and one folder.
     */
    fun mainCredit(credit: String): String =
        credit.split(FEATURING, limit = 2).first().trim().ifEmpty { credit.trim() }

    /**
     * The release a recording files under.
     *
     * Official releases only, wherever there are any: promos and bootlegs are
     * real but nobody's idea of where a song lives. Then the first STUDIO
     * ALBUM it appeared on -- but only if that album came out within
     * [ALBUM_GRACE_YEARS] of the song's first release, because "the album"
     * stops being the answer when it is a deluxe edition twenty years later.
     * A single released a month ahead of its album still files under the
     * album; a non-album single stays the single.
     *
     * Failing that, the earliest release of the plainest kind: single, EP or
     * album before anything with a secondary type; live after those;
     * compilations last of all, since a greatest-hits is where a song is
     * collected rather than where it was released.
     */
    private fun chooseRelease(
        all: List<Pair<RecordingMatch, ReleaseAppearance>>,
    ): Pair<RecordingMatch, ReleaseAppearance>? {
        if (all.isEmpty()) return null
        val official = all.filter { it.second.status.equals("Official", ignoreCase = true) }.ifEmpty { all }

        val earliest = official.mapNotNull { it.second.year }.minOrNull()
        val album = official.filter { isStudioAlbum(it.second) }.minWithOrNull(byDate)
        val albumYear = album?.second?.year
        if (album != null && (albumYear == null || earliest == null || albumYear - earliest <= ALBUM_GRACE_YEARS)) {
            return album
        }
        return official.minWithOrNull(compareBy<Pair<RecordingMatch, ReleaseAppearance>> { rank(it.second) }.then(byDate))
    }

    private fun isStudioAlbum(release: ReleaseAppearance): Boolean =
        release.primaryType.equals("Album", ignoreCase = true) && release.secondaryTypes.isEmpty()

    private fun rank(release: ReleaseAppearance): Int {
        val secondary = release.secondaryTypes.map { it.lowercase() }
        return when {
            secondary.any { it == "compilation" || it == "dj-mix" || it.startsWith("mixtape") } -> 3
            "live" in secondary -> 2
            secondary.isNotEmpty() -> 1
            release.primaryType?.lowercase()?.let { it in PLAIN_TYPES } == true -> 0
            else -> 4
        }
    }

    /**
     * Earliest first, the undated last. A partial date sorts after a full one
     * in the same year -- arbitrary, but it has to be something.
     */
    private val byDate = compareBy<Pair<RecordingMatch, ReleaseAppearance>> { (_, release) ->
        release.date?.let { (it + "-99-99").take(10) } ?: "9999-99-99"
    }

    private val PLAIN_TYPES = setOf("album", "single", "ep")

    private val FEATURING = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+""", RegexOption.IGNORE_CASE)

    /** The same allowance the album page gives a catalogue length. */
    private const val LENGTH_TOLERANCE_MS = 15_000L

    /**
     * Two years. A lead single comes out months before its album, sometimes
     * the year before; a deluxe edition that finally gathers a stray single
     * comes out a decade later.
     */
    private const val ALBUM_GRACE_YEARS = 2
}
