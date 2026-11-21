package app.roam.data.acquire

import app.roam.core.model.FolderNames
import app.roam.data.catalog.metadata.RecordingMatch
import app.roam.data.catalog.metadata.ReleaseAppearance

/**
 * What a video turned out to be.
 *
 * Either MusicBrainz recognised it, and every field is spelled the way the
 * record is -- or it did not, and the video's own words are all there is,
 * cleaned of "(Official Video)" and put in front of someone to check.
 */
sealed interface VideoIdentity {
    /** The video, extracted in full where that was possible. */
    val video: YoutubeResult

    /** What the placement dialog opens on. */
    val placement: AlbumPlacement

    /** What is queued once the dialog is confirmed. */
    val request: DownloadRequest

    /**
     * The catalogue's answer. The video's title is gone by here: the title,
     * the credit, the album, the year and the track number are all
     * MusicBrainz's.
     */
    data class Known(
        override val video: YoutubeResult,
        val recording: RecordingMatch,
        val release: ReleaseAppearance?,
    ) : VideoIdentity {

        override val placement: AlbumPlacement
            get() {
                val credit = release?.artist?.ifBlank { null } ?: recording.artist
                val various = FolderNames.key(credit) == FolderNames.key(FolderNames.VARIOUS_ARTISTS)
                return AlbumPlacement(
                    artist = recording.artist,
                    album = albumTitle,
                    // The folder is the act's, not the guest list's: a single
                    // credited "Calvin Harris feat. Pharrell Williams" belongs
                    // with the rest of Calvin Harris.
                    albumArtist = if (various) FolderNames.VARIOUS_ARTISTS else RecordingPicker.mainCredit(credit),
                    year = release?.year,
                    compilation = release?.isCompilation == true,
                )
            }

        override val request: DownloadRequest
            get() = DownloadRequest(
                url = video.url,
                title = recording.title,
                artist = recording.artist,
                album = albumTitle,
                trackNo = release?.trackNumber,
                // Art the catalogue vouches for, so it is safe to seed
                // cover.jpg with -- unlike a video's 16:9 thumbnail, which
                // Roam could never take back off Drive (invariant 6d).
                coverUrl = release?.coverUrl,
            )

        /**
         * A single is named for itself the way a track with no album is --
         * "Whatever - Single" -- so the same song lands in the same folder
         * however it was found, and a single never merges into an album of
         * the same name.
         */
        private val albumTitle: String
            get() = when {
                release == null -> AlbumPlacement.singleOf(recording.title)
                release.primaryType.equals("Single", ignoreCase = true) &&
                    !release.title.trim().endsWith(AlbumPlacement.SINGLE_SUFFIX.trim(), ignoreCase = true) ->
                    AlbumPlacement.singleOf(release.title)
                else -> release.title
            }
    }

    /**
     * No answer: the video's own words, cleaned, for the dialog to show and
     * someone to correct. [reached] tells "MusicBrainz does not know this"
     * from "MusicBrainz could not be asked", which call for different advice.
     */
    data class Unknown(
        override val video: YoutubeResult,
        val title: String,
        val artist: String,
        val reached: Boolean,
    ) : VideoIdentity {

        private val asRead: YoutubeResult get() = video.copy(title = title, artist = artist)

        override val placement: AlbumPlacement get() = AlbumPlacement.from(asRead)

        override val request: DownloadRequest
            get() = DownloadRequest(
                url = video.url,
                title = title,
                artist = artist.ifBlank { AlbumPlacement.UNKNOWN_ARTIST },
                album = placement.album,
                // No cover: there is nothing to vouch for one. See Known.
            )
    }
}
