package app.roam.data.acquire

import app.roam.data.catalog.metadata.MusicBrainz
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Works out what a video is before anything is named after it.
 *
 * A video's title is whatever its uploader typed. It decides nothing here: it
 * and the description are read for clues ([VideoClues]), the clues are put to
 * MusicBrainz, and what is written to the file and the folder is what the
 * catalogue answers ([RecordingPicker]). Only when the catalogue has nothing
 * do the video's own words get used -- cleaned, and flagged as such.
 */
class TrackIdentifier @Inject constructor(
    private val youtube: YoutubeSource,
    private val musicBrainz: MusicBrainz,
) {

    suspend fun identify(video: YoutubeResult): VideoIdentity {
        // A flat search row has no description, and the description is the
        // best clue there is. Usually the row has been extracted by the time
        // anyone taps it; when it has not, that is done now.
        val full = if (video.extracted) video else extract(video) ?: video
        val guesses = VideoClues.read(full)
        val lengthMs = full.durationSec?.let { it * 1_000L }

        // A few readings at most. Each is a second of MusicBrainz's rate limit,
        // and a reading past the third is a guess about a guess.
        for (guess in guesses.take(MAX_LOOKUPS)) {
            val found = musicBrainz.searchRecordings(
                title = VideoClues.searchable(guess.title),
                artist = VideoClues.queryArtist(guess.artist),
            ).getOrElse {
                // Down or refusing: every further reading would fail the same
                // way, slowly. Say so rather than calling the song unknown.
                return unknown(full, guesses, reached = false)
            }
            val pick = RecordingPicker.pick(guess, found, lengthMs) ?: continue
            return VideoIdentity.Known(full, pick.recording, pick.release)
        }
        return unknown(full, guesses, reached = true)
    }

    /**
     * Bounded, because extracting queues behind every other use of yt-dlp.
     * The wait is for the lock rather than the work, so it can be cut short;
     * past it the flat row's clues are better than a spinner.
     */
    private suspend fun extract(video: YoutubeResult): YoutubeResult? =
        withTimeoutOrNull(EXTRACT_WAIT_MS) {
            youtube.enrich(listOf(video.videoId)).getOrNull()?.firstOrNull()
        }

    private fun unknown(
        video: YoutubeResult,
        guesses: List<VideoClues.Guess>,
        reached: Boolean,
    ): VideoIdentity.Unknown {
        val best = guesses.firstOrNull()
        return VideoIdentity.Unknown(
            video = video,
            title = best?.title ?: VideoClues.cleanTitle(video.videoTitle).ifEmpty { video.title },
            artist = best?.artist ?: video.artist,
            reached = reached,
        )
    }

    private companion object {
        const val MAX_LOOKUPS = 3
        const val EXTRACT_WAIT_MS = 20_000L
    }
}
