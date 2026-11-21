package app.roam.data.acquire

import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Turns an album page's search into the one upload that is the track, or a
 * reason there is none.
 *
 * Every place is held to the same rules ([ExactTrack]), and asked in order of
 * how much its answer can be trusted to be the record:
 *
 * 1. YouTube Music's Songs shelf -- the labels' own audio, each upload named
 *    for the album it came from.
 * 2. A general YouTube search: official audio uploads, and whatever a label
 *    never delivered to Music, ranked among music videos and live sets.
 * 3. Bandcamp -- the artist's or label's own page, named for its album.
 * 4. The Internet Archive -- whole albums, one file per track.
 * 5. SoundCloud -- older catalogue, often; recent releases mostly previews.
 * 6. Audius, then Jamendo -- independent artists' own uploads.
 *
 * 3 to 6 are fallbacks and fail quietly. Only YouTube being unreachable makes
 * the whole lookup fail, because a verdict needs YouTube's answer in it.
 */
class TrackResolver @Inject constructor(
    private val youtube: YoutubeSource,
    private val others: OtherSources,
) {

    sealed interface Outcome {
        /**
         * Where the audio is, and -- for a plain file rather than a page --
         * its extension, so it is fetched as it stands.
         */
        data class Found(val url: String, val fileExtension: String? = null) : Outcome

        /** A verdict about the recordings, not the connection: never retried. */
        data class Refused(val reason: String) : Outcome
    }

    /**
     * Fails -- and is retried -- when YouTube could not be searched or nothing
     * came back from anywhere. "These came back and none is the track" is an
     * Outcome, because asking again returns the same uploads.
     */
    internal suspend fun resolve(query: String, want: ExactTrack.Wanted): Result<Outcome> = runCatching {
        val checked = mutableListOf<YoutubeResult>()

        fun verdict(found: List<YoutubeResult>): Outcome? {
            val fresh = found.filter { candidate -> checked.none { it.url == candidate.url } }
            checked += fresh
            return ExactTrack.choose(want, fresh)?.let { Outcome.Found(it.url, it.fileExtension) }
        }

        // Extracted in full before judging, even though flat rows carry a title
        // and a length: this decides whether a file is downloaded at all, and
        // only a full extraction names the album and carries the description.
        suspend fun extracted(rows: List<YoutubeResult>): List<YoutubeResult> {
            val fresh = rows.filter { row -> checked.none { it.url == row.url } }.take(CANDIDATES)
            return if (fresh.isEmpty()) emptyList() else youtube.enrich(fresh.map { it.videoId }).getOrThrow()
        }

        // A Songs search that fails is not the end: the general one may answer.
        verdict(extracted(youtube.searchSongs(query, CANDIDATES).getOrNull().orEmpty()))
            ?.let { return@runCatching it }

        val general = youtube.search(query, CANDIDATES)
        general.getOrNull()?.let { rows -> verdict(extracted(rows))?.let { return@runCatching it } }

        verdict(others.bandcamp(query, CANDIDATES))?.let { return@runCatching it }
        verdict(others.internetArchive(want, ARCHIVE_ITEMS))?.let { return@runCatching it }
        verdict(soundCloud(query))?.let { return@runCatching it }
        verdict(others.audius(query, CANDIDATES))?.let { return@runCatching it }
        verdict(others.jamendo(query, CANDIDATES))?.let { return@runCatching it }

        // THROWN, not refused, when YouTube could not be searched or nothing
        // came back from anywhere. That is a failed search and deserves a
        // retry; reporting it as a mismatch sends anyone reading the queue
        // looking in entirely the wrong place.
        general.exceptionOrNull()?.let { throw it }
        if (checked.isEmpty()) error("No search results for \"$query\"")

        Outcome.Refused(ExactTrack.refusal(want, checked, where = "anywhere Roam looked"))
    }.onFailure {
        if (it is CancellationException) throw it
    }

    /** SoundCloud through yt-dlp, quietly: a fallback that is down is just empty. */
    private suspend fun soundCloud(query: String): List<YoutubeResult> {
        val rows = youtube.searchSoundCloud(query, CANDIDATES).getOrNull().orEmpty()
        return youtube.extractLinks(rows.mapNotNull { it.link }.take(CANDIDATES)).getOrNull().orEmpty()
    }

    private companion object {
        /**
         * How many uploads to weigh from each place. Extracting costs a full
         * yt-dlp run each, so this stays small -- if the track is not in the
         * first handful, the query itself is wrong.
         */
        const val CANDIDATES = 5

        /** Archive items to open. Each is a whole album's file list, so few are needed. */
        const val ARCHIVE_ITEMS = 3
    }
}
