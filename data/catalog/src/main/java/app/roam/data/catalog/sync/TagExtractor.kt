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
        val fallback = inferFromPath(file)
        val parsed = readTags(source, file.remoteId, file.sizeBytes)
        return if (parsed == null) fallback else merge(parsed, fallback)
    }

    /**
     * The ranged read and the parse, without any path fallback.
     *
     * Shared with the tag pass, which has a track id rather than a RemoteFile
     * and does its own merging -- two copies of the tail-read rule is exactly
     * how one of them ends up not having it.
     */
    suspend fun readTags(
        source: SourceProvider,
        remoteId: String,
        sizeBytes: Long,
    ): ParsedTags? {
        val head = runCatching { source.readRange(remoteId, 0, HEAD_BYTES) }
            .getOrNull() ?: return null

        TagParser.parse(head)?.let { return it }

        // On an M4A that never went through faststart the moov atom is at the
        // very END of the file, past all the audio. Without this second read
        // roughly a third of an AAC library comes back untagged.
        if (!TagParser.needsTailRead(head) || sizeBytes <= TAIL_BYTES) return null

        return runCatching { source.readRange(remoteId, sizeBytes - TAIL_BYTES, TAIL_BYTES) }
            .getOrNull()
            ?.let { TagParser.parse(it) }
    }

    /**
     * Tags win, the path fills the gaps.
     *
     * A file tagged with a title and nothing else should still land in the
     * right album, because the folder it sits in genuinely does say so. The row
     * only counts as inferred when the container told us nothing at all --
     * `inferredFromPath` drives the "Fix metadata" chip, and flagging a
     * properly tagged file because it happened to lack a genre would point that
     * chip at thousands of tracks that are already correct.
     */
    private fun merge(tags: ParsedTags, path: ExtractedTags) = ExtractedTags(
        title = tags.title ?: path.title,
        artist = tags.artist ?: path.artist,
        album = tags.album ?: path.album,
        // Falls back to the artist, not to the folder: an album artist that
        // disagrees with the track artist is meaningful, and inventing one from
        // a directory name would mark half a library as compilations.
        albumArtist = tags.albumArtist ?: tags.artist ?: path.albumArtist,
        trackNo = tags.trackNo ?: path.trackNo,
        trackTotal = tags.trackTotal,
        discNo = tags.discNo,
        discTotal = tags.discTotal,
        year = tags.year,
        genre = tags.genre,
        durationMs = tags.durationMs,
        artwork = tags.artwork,
        inferredFromPath = false,
    )

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

        /** Enough to hold a moov atom and its cover on a non-faststart M4A. */
        const val TAIL_BYTES = 512L * 1024
        val FILENAME = Regex("""^(?<track>\d{1,3})[\s._-]+(?<title>.+?)(?:\s+-\s+(?<artist>.+))?$""")
    }
}
