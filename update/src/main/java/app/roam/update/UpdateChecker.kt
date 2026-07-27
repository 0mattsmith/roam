package app.roam.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import javax.inject.Inject

@Serializable
data class GhAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url") val url: String,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tag: String,
    val name: String = "",
    val body: String = "",
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String = "",
    val assets: List<GhAsset> = emptyList(),
)

interface GitHubApi {
    /**
     * /releases/latest excludes drafts and pre-releases. release.yml always
     * publishes with --latest and --prerelease=false, so this single endpoint
     * is guaranteed to return every build -- no pagination, no filtering.
     */
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun latest(@Path("owner") owner: String, @Path("repo") repo: String): GhRelease
}

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
    val sha256Url: String?,
)

/**
 * The GitHub Releases API is the update feed -- no extra backend, no manifest
 * file to keep in sync.
 *
 * Compare on versionCode, never on the tag string: "v1.10.0" sorts BELOW
 * "v1.9.0" lexically, which ships an update that silently never installs.
 * push.ps1 writes the machine-readable trailer this parses.
 */
class UpdateChecker @Inject constructor(
    private val api: GitHubApi,
) {
    suspend fun check(currentVersionCode: Int, abi: String): AvailableUpdate? {
        val release = api.latest(OWNER, REPO)

        val code = VERSION_CODE_TRAILER.find(release.body)
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code <= currentVersionCode) return null

        // ABI splits produce several APKs. Prefer the exact match, fall back
        // to the universal build.
        val apk = release.assets.firstOrNull { it.name.endsWith("-$abi.apk") }
            ?: release.assets.firstOrNull { it.name.endsWith("-universal.apk") }
            ?: return null

        return AvailableUpdate(
            versionName = release.tag.removePrefix("v"),
            versionCode = code,
            notes = release.body.substringBefore("<!-- roam:").trim(),
            apkUrl = apk.url,
            apkName = apk.name,
            sizeBytes = apk.size,
            sha256Url = release.assets.firstOrNull { it.name == apk.name + ".sha256" }?.url,
        )
    }

    companion object {
        const val OWNER = "0mattsmith"
        const val REPO = "roam"
        const val BASE_URL = "https://api.github.com/"
        val VERSION_CODE_TRAILER = Regex("""<!--\s*roam:versionCode=(\d+)\s*-->""")
    }
}
