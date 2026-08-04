package app.roam.data.catalog.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MusicBrainz, and Cover Art Archive alongside it.
 *
 * The canonical source for what an album actually contains -- which is a
 * different question from what YouTube happens to have. Used both to fill in a
 * downloaded track's tags and to show the real tracklist for an album,
 * including the tracks that are missing from the library.
 *
 * TWO RULES, both enforced by their infrastructure rather than by etiquette:
 * one request per second, and a User-Agent that identifies the application.
 * Break either and the answer becomes 503 for everyone on your address.
 */
@Singleton
class MusicBrainz @Inject constructor() : ReleaseSource {

    override val source = MetadataSource.MUSICBRAINZ

    /** No account, no token, nothing to set up. Always usable. */
    override suspend fun available(): Boolean = true


    private val client = OkHttpClient()

    /**
     * Every call goes through here, so the rate limit holds across callers.
     * The delay is AFTER the request rather than before, so the first lookup in
     * a session is not made to wait for a limit nobody has used yet.
     */
    private val gate = Mutex()

    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        gate.withLock {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    response.body?.string()?.let { JSONObject(it) }
                }
            } finally {
                delay(RATE_LIMIT_MS)
            }
        }
    }

    /**
     * Candidate releases for a free-text query.
     *
     * Deliberately returns several. The same album exists as an original, a
     * remaster, a deluxe edition and three regional pressings with different
     * tracklists, and only the person looking at them knows which they own.
     */
    override suspend fun searchReleases(
        query: String,
        limit: Int,
        compilationsOnly: Boolean,
    ): Result<List<ReleaseMatch>> =
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            // Lucene syntax, which is what the search endpoint speaks. The
            // secondary type is what distinguishes a various-artists
            // collection from a studio album of the same name.
            val lucene = if (compilationsOnly) {
                "${query.trim()} AND secondarytype:compilation"
            } else {
                query.trim()
            }
            val url = "$BASE/release/?query=${lucene.encoded()}&fmt=json&limit=$limit"
            val json = get(url) ?: return@runCatching emptyList()
            val releases = json.optJSONArray("releases") ?: return@runCatching emptyList()

            (0 until releases.length()).mapNotNull { i ->
                val release = releases.optJSONObject(i) ?: return@mapNotNull null
                val id = release.optString("id").ifBlank { return@mapNotNull null }
                ReleaseMatch(
                    id = id,
                    source = MetadataSource.MUSICBRAINZ,
                    title = release.optString("title").ifBlank { "Unknown" },
                    artist = release.creditedArtist(),
                    year = release.optString("date").take(4).toIntOrNull(),
                    trackCount = release.optInt("track-count"),
                    format = release.optJSONArray("media")
                        ?.optJSONObject(0)
                        ?.optString("format")
                        ?.ifBlank { null },
                    coverUrl = coverUrl(id),
                )
            }
        }

    override suspend fun searchArtists(query: String, limit: Int): Result<List<ArtistMatch>> =
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            val url = "$BASE/artist/?query=${query.trim().encoded()}&fmt=json&limit=$limit"
            val artists = get(url)?.optJSONArray("artists") ?: return@runCatching emptyList()

            (0 until artists.length()).mapNotNull { i ->
                val artist = artists.optJSONObject(i) ?: return@mapNotNull null
                ArtistMatch(
                    id = artist.optString("id").ifBlank { return@mapNotNull null },
                    source = MetadataSource.MUSICBRAINZ,
                    name = artist.optString("name").ifBlank { "Unknown" },
                    // The disambiguation is the whole point of showing a list:
                    // three bands called Nirvana, and only one is the one.
                    detail = listOfNotNull(
                        artist.optString("disambiguation").ifBlank { null },
                        artist.optString("country").ifBlank { null },
                    ).joinToString(" · ").ifBlank { null },
                    // MusicBrainz holds no images of its own.
                    imageUrl = null,
                )
            }
        }

    override suspend fun releasesForArtist(artistId: String, limit: Int): Result<List<ReleaseMatch>> =
        runCatching {
            // The BROWSE endpoint, not search: it takes an artist id directly
            // and returns everything credited to them rather than guessing
            // from a name that half a dozen acts share.
            val url = "$BASE/release?artist=$artistId&fmt=json&limit=$limit"
            val releases = get(url)?.optJSONArray("releases") ?: return@runCatching emptyList()

            (0 until releases.length()).mapNotNull { i ->
                val release = releases.optJSONObject(i) ?: return@mapNotNull null
                val id = release.optString("id").ifBlank { return@mapNotNull null }
                ReleaseMatch(
                    id = id,
                    source = MetadataSource.MUSICBRAINZ,
                    title = release.optString("title").ifBlank { "Unknown" },
                    artist = release.creditedArtist(),
                    year = release.optString("date").take(4).toIntOrNull(),
                    trackCount = release.optJSONArray("media")
                        ?.optJSONObject(0)?.optInt("track-count") ?: 0,
                    format = release.optJSONArray("media")
                        ?.optJSONObject(0)?.optString("format")?.ifBlank { null },
                    coverUrl = coverUrl(id),
                )
            }.sortedByDescending { it.year ?: 0 }
        }

    /** The tracklist, flattened across discs but keeping the disc number. */
    override suspend fun release(mbid: String): Result<ReleaseDetail?> = runCatching {
        val url = "$BASE/release/$mbid?inc=recordings+artist-credits&fmt=json"
        val json = get(url) ?: return@runCatching null

        val media = json.optJSONArray("media")
        val tracks = buildList {
            for (d in 0 until (media?.length() ?: 0)) {
                val disc = media?.optJSONObject(d) ?: continue
                val discNo = disc.optInt("position", d + 1)
                val entries = disc.optJSONArray("tracks") ?: continue

                for (t in 0 until entries.length()) {
                    val track = entries.optJSONObject(t) ?: continue
                    add(
                        ReleaseTrack(
                            position = track.optInt("position", t + 1),
                            discNo = discNo,
                            title = track.optString("title").ifBlank { "Unknown" },
                            // The track's own credit where it has one; a
                            // compilation's tracks each name a different act.
                            artist = track.creditedArtist()
                                .ifBlank { json.creditedArtist() },
                            durationMs = track.optLong("length").takeIf { it > 0 },
                        )
                    )
                }
            }
        }

        ReleaseDetail(
            id = mbid,
            source = MetadataSource.MUSICBRAINZ,
            coverUrl = coverUrl(mbid),
            title = json.optString("title").ifBlank { "Unknown" },
            artist = json.creditedArtist(),
            year = json.optString("date").take(4).toIntOrNull(),
            tracks = tracks,
        )
    }

    /**
     * artist-credit is an ARRAY, because a track can be credited to several
     * acts with joining words between them ("Queen feat. David Bowie"). Taking
     * element zero loses the feature; joining them keeps what was written.
     */
    private fun JSONObject.creditedArtist(): String {
        val credits = optJSONArray("artist-credit") ?: return ""
        return buildString {
            for (i in 0 until credits.length()) {
                val credit = credits.optJSONObject(i) ?: continue
                append(credit.optString("name").ifBlank { credit.optJSONObject("artist")?.optString("name").orEmpty() })
                append(credit.optString("joinphrase"))
            }
        }.trim()
    }

    /**
     * Cover Art Archive is keyed on the SAME release id, so no second lookup
     * is needed -- the URL either resolves or 404s and the image simply does
     * not load.
     */
    private fun coverUrl(mbid: String) = "https://coverartarchive.org/release/$mbid/front-500"

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2"

        /**
         * A real contact address is part of the requirement, not decoration --
         * an anonymous agent gets blocked rather than throttled.
         */
        const val USER_AGENT = "Roam/1.0 ( https://github.com/0mattsmith/roam )"

        /** One per second, with a little headroom for clock jitter. */
        const val RATE_LIMIT_MS = 1_100L
    }
}
