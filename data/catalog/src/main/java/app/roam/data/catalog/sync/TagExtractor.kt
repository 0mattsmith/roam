package app.roam.data.catalog.sync

import app.roam.data.source.RemoteFile
import app.roam.data.source.SourceProvider
import javax.inject.Inject

data class ExtractedTags(
    val title: String?, val artist: String?, val album: String?, val albumArtist: String?,
    val trackNo: Int?, val trackTotal: Int?, val discNo: Int?, val discTotal: Int?,
    val year: Int?, val genre: String?, val durationMs: Long?,
    val artwork: ByteArray?,
    val inferredFromPath: Boolean,
)

/**
 * Reads tags WITHOUT downloading the file.
 *
 * ID3v2 sits at the head of an MP3; FLAC's VORBIS_COMMENT and PICTURE blocks
 * likewise. So a 1 MB ranged read covers the tag plus a typical embedded
 * cover. A 10k-track library would otherwise be an 80 GB download.
 */
class TagExtractor @Inject constructor() {

    suspend fun extract(source: SourceProvider, file: RemoteFile): ExtractedTags {
        val head = source.readRange(file.remoteId, 0, HEAD_BYTES)

        // TODO(phase1): feed `head` to Media3 extractors via a ByteArrayDataSource.
        //   Mp3Extractor / FlacExtractor / Mp4Extractor emit Metadata entries:
        //     TextInformationFrame  TIT2 TPE1 TALB TPE2 TDRC TRCK TPOS TCON
        //     ApicFrame.pictureData  <- the embedded cover
        //
        // M4A CAVEAT: on a non-faststart file the moov atom is at the END.
        //   If no moov appears in `head`, re-read the LAST 512 KB instead.
        //   Skip this and about a third of an AAC library comes back untagged.

        return inferFromPath(file)
    }

    /**
     * Fallback for untagged files. Matches the Music/Artist/Album/NN title - artist.ext
     * convention. Rows land as TagState.PATH_INFERRED so the phone app can
     * surface a "Fix metadata" chip on exactly what guessed.
     */
    fun inferFromPath(file: RemoteFile): ExtractedTags {
        val stem = file.name.substringBeforeLast('.')
        val m = FILENAME.find(stem)
        val album = file.pathSegments.lastOrNull()
        val artist = file.pathSegments.getOrNull(file.pathSegments.size - 2)
        return ExtractedTags(
            title = m?.groups?.get("title")?.value?.trim() ?: stem,
            artist = m?.groups?.get("artist")?.value?.trim() ?: artist,
            album = album,
            albumArtist = artist,
            trackNo = m?.groups?.get("track")?.value?.toIntOrNull(),
            trackTotal = null, discNo = null, discTotal = null,
            year = null, genre = null, durationMs = null,
            artwork = null,
            inferredFromPath = true,
        )
    }

    companion object {
        const val HEAD_BYTES = 1L * 1024 * 1024
        const val HEAD_BYTES_RETRY = 4L * 1024 * 1024
        val FILENAME = Regex("""^(?<track>\d{1,3})[\s._-]+(?<title>.+?)(?:\s+-\s+(?<artist>.+))?$""")
    }
}
