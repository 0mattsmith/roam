package app.roam.data.catalog.metadata

import app.roam.core.datastore.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
 * Discogs.
 *
 * Knows about nearly every version of everything ever pressed, which is
 * exactly what MusicBrainz is thin on -- rare pressings, white labels,
 * regional variants, most electronic and hip-hop twelve-inches.
 *
 * NEEDS A TOKEN. /database/search refuses unauthenticated requests outright,
 * so without one this source reports itself unavailable rather than failing
 * every search with a 401 nobody can act on. A personal access token from
 * Discogs' developer settings is enough; there is no OAuth dance to do.
 */
@Singleton
class Discogs @Inject constructor(
    private val settings: SettingsRepository,
) : ReleaseSource {

    override val source = MetadataSource.DISCOGS

    private val client = OkHttpClient()
    private val gate = Mutex()

    private suspend fun token(): String? =
        settings.settings.first().discogsToken?.trim()?.ifBlank { null }

    override suspend fun available(): Boolean = token() != null

    /**
     * 60 requests a minute with a token, throttled by source IP. One a second
     * stays well inside it, and every call goes through here so the budget is
     * shared rather than spent twice over by two callers each being careful.
     */
    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        val token = token() ?: return@withContext null
        gate.withLock {
            try {
                val request = Request.Builder()
                    .url(url)
                    // Both headers are required. Discogs rejects a default or
                    // absent User-Agent as well as a missing token.
                    .header("Authorization", "Discogs token=$token")
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

    override suspend fun searchReleases(
        query: String,
        limit: Int,
        compilationsOnly: Boolean,
    ): Result<List<ReleaseMatch>> =
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            // Discogs models "a collection of various artists" as a FORMAT
            // description rather than a release type, which is why this is a
            // format filter and MusicBrainz's is a secondary type.
            val filter = if (compilationsOnly) "&format=Compilation" else ""
            val url = "$BASE/database/search?type=release&per_page=$limit$filter&q=${query.encoded()}"
            val results = get(url)?.optJSONArray("results") ?: return@runCatching emptyList()

            (0 until results.length()).mapNotNull { i ->
                val entry = results.optJSONObject(i) ?: return@mapNotNull null
                // Search results title everything as "Artist - Album" in one
                // string; the release lookup has them separately.
                val combined = entry.optString("title")
                ReleaseMatch(
                    id = entry.optInt("id").takeIf { it > 0 }?.toString()
                        ?: return@mapNotNull null,
                    source = MetadataSource.DISCOGS,
                    title = combined.substringAfter(" - ", combined).trim(),
                    artist = combined.substringBefore(" - ", "").trim(),
                    year = entry.optString("year").take(4).toIntOrNull(),
                    // Absent from search; the tracklist arrives with the lookup.
                    trackCount = 0,
                    format = entry.optJSONArray("format")?.optString(0)?.ifBlank { null },
                    coverUrl = entry.optString("cover_image").ifBlank { null }
                        ?: entry.optString("thumb").ifBlank { null },
                )
            }
        }

    override suspend fun searchArtists(query: String, limit: Int): Result<List<ArtistMatch>> =
        runCatching {
            if (query.isBlank()) return@runCatching emptyList()
            val url = "$BASE/database/search?type=artist&per_page=$limit&q=${query.encoded()}"
            val results = get(url)?.optJSONArray("results") ?: return@runCatching emptyList()

            (0 until results.length()).mapNotNull { i ->
                val entry = results.optJSONObject(i) ?: return@mapNotNull null
                ArtistMatch(
                    id = entry.optInt("id").takeIf { it > 0 }?.toString()
                        ?: return@mapNotNull null,
                    source = MetadataSource.DISCOGS,
                    // Same disambiguation suffix as everywhere else -- theirs,
                    // not part of the name.
                    name = entry.optString("title").replace(SUFFIX, "").ifBlank { "Unknown" },
                    detail = null,
                    // Discogs does hold artist images, unlike MusicBrainz.
                    imageUrl = entry.optString("cover_image").ifBlank { null }
                        ?: entry.optString("thumb").ifBlank { null },
                )
            }
        }

    override suspend fun releasesForArtist(artistId: String, limit: Int): Result<List<ReleaseMatch>> =
        runCatching {
            val url = "$BASE/artists/$artistId/releases?per_page=$limit&sort=year&sort_order=desc"
            val releases = get(url)?.optJSONArray("releases") ?: return@runCatching emptyList()

            (0 until releases.length()).mapNotNull { i ->
                val entry = releases.optJSONObject(i) ?: return@mapNotNull null
                // A "master" groups every pressing of one record; the id we
                // need for a tracklist is the release's own.
                if (entry.optString("type") == "master") return@mapNotNull null
                ReleaseMatch(
                    id = entry.optInt("id").takeIf { it > 0 }?.toString()
                        ?: return@mapNotNull null,
                    source = MetadataSource.DISCOGS,
                    title = entry.optString("title").ifBlank { "Unknown" },
                    artist = entry.optString("artist").replace(SUFFIX, ""),
                    year = entry.optInt("year").takeIf { it > 0 },
                    trackCount = 0,
                    format = entry.optString("format").ifBlank { null },
                    coverUrl = entry.optString("thumb").ifBlank { null },
                )
            }
        }

    override suspend fun release(id: String): Result<ReleaseDetail?> = runCatching {
        val json = get("$BASE/releases/$id") ?: return@runCatching null

        val artist = json.optJSONArray("artists")?.let { artists ->
            (0 until artists.length()).joinToString("") { i ->
                val a = artists.optJSONObject(i)
                // Discogs disambiguates duplicate names with "Nirvana (2)";
                // the number is theirs, not part of the name.
                val name = a?.optString("name").orEmpty().replace(SUFFIX, "")
                name + a?.optString("join").orEmpty().let { if (it.isBlank()) "" else " $it " }
            }.trim()
        }.orEmpty()

        val entries = json.optJSONArray("tracklist")
        val tracks = buildList {
            for (i in 0 until (entries?.length() ?: 0)) {
                val entry = entries?.optJSONObject(i) ?: continue
                // Headings and index tracks have no position and are not songs.
                if (entry.optString("type_").let { it.isNotBlank() && it != "track" }) continue

                val (disc, position) = parsePosition(entry.optString("position"), size)
                add(
                    ReleaseTrack(
                        position = position,
                        discNo = disc,
                        title = entry.optString("title").ifBlank { "Unknown" },
                        artist = entry.optJSONArray("artists")
                            ?.optJSONObject(0)
                            ?.optString("name")
                            ?.replace(SUFFIX, "")
                            ?.ifBlank { null }
                            ?: artist,
                        durationMs = parseDuration(entry.optString("duration")),
                    )
                )
            }
        }

        ReleaseDetail(
            id = id,
            source = MetadataSource.DISCOGS,
            title = json.optString("title").ifBlank { "Unknown" },
            artist = artist,
            year = json.optInt("year").takeIf { it > 0 },
            coverUrl = json.optJSONArray("images")?.optJSONObject(0)?.optString("uri")
                ?.ifBlank { null },
            tracks = tracks,
        )
    }

    private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8")

    private companion object {
        const val BASE = "https://api.discogs.com"

        /** Discogs' duplicate-name marker: "Nirvana (2)". Never part of a name. */
        val SUFFIX = Regex("""\s*\(\d+\)$""")
        const val USER_AGENT = "Roam/1.0 +https://github.com/0mattsmith/roam"

        /** 60/min authenticated. One a second leaves room for a retry. */
        const val RATE_LIMIT_MS = 1_050L
    }
}
