package app.roam.feature.webui

/**
 * The jobs the queue page offers, by their slug in the URL.
 *
 * A slug rather than an ordinal for the same reason settings store enums by
 * name: a constant inserted in the middle would silently renumber every
 * bookmark. The labels and the explanations live in the front end, because
 * copy is not the server's business and changing a word should not recompile
 * anything.
 */
enum class QueueJob(val slug: String) {
    NEEDS_LOOK("needs-look"),
    NO_YEAR("no-year"),
    NO_GENRE("no-genre"),
    NO_COVER("no-cover"),
    FROZEN("frozen"),
    MISSING("missing");

    companion object {
        fun bySlug(slug: String): QueueJob? = entries.firstOrNull { it.slug == slug }
    }
}

/** Everything the server can be asked for. */
sealed interface Route {
    /** The one HTML page. */
    data object Index : Route

    /** A static file out of the module's assets. */
    data class Asset(val name: String) : Route

    /** Every count at once, which is the whole landing page. */
    data object Counts : Route

    /** The rows behind one count. */
    data class Job(val job: QueueJob, val limit: Int, val offset: Int) : Route

    /** Cover bytes, straight off disk. */
    data class Artwork(val id: String, val size: Int?) : Route

    /** The PIN being offered. */
    data object Pin : Route

    /** Nothing here. Also what a path that tries to escape assets/ becomes. */
    data object NotFound : Route
}

/**
 * Turns a request into a [Route], and nothing else.
 *
 * Pure and separate from the server so that it can be tested without a socket
 * -- the server is the part that cannot be unit-tested on the JVM, so as
 * little as possible lives in it. Deciding what a URL means is the part most
 * likely to be wrong, and it is all here.
 */
object WebRoutes {

    /** Beyond this a browser is asking for more than it can show at once. */
    const val MAX_LIMIT = 200
    const val DEFAULT_LIMIT = 50

    /** Served without a PIN: the page that asks for the PIN, and its styling. */
    fun isPublic(route: Route): Boolean =
        route is Route.Index || route is Route.Asset || route is Route.Pin

    fun parse(method: String, uri: String, params: Map<String, String> = emptyMap()): Route {
        val path = uri.substringBefore('?').trimEnd('/').ifEmpty { "/" }

        if (path == "/" || path == "/index.html") return Route.Index

        if (!path.startsWith("/api/")) {
            // An asset, and the name is checked rather than trusted. "../"
            // inside a path the AssetManager will happily follow is the oldest
            // mistake in serving files, and the guard belongs here where it is
            // visible, not at the open() call where it looks like a detail.
            val name = path.removePrefix("/")
            return if (isSafeAsset(name)) Route.Asset(name) else Route.NotFound
        }

        val rest = path.removePrefix("/api/")
        val segments = rest.split('/')

        return when {
            rest == "counts" && method == "GET" -> Route.Counts

            rest == "pin" && method == "POST" -> Route.Pin

            segments.size == 2 && segments[0] == "job" && method == "GET" -> {
                val job = QueueJob.bySlug(segments[1]) ?: return Route.NotFound
                Route.Job(
                    job = job,
                    limit = params["limit"]?.toIntOrNull()
                        ?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT,
                    // Negative is not a smaller page, it is a different query.
                    offset = params["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            }

            segments.size == 2 && segments[0] == "artwork" && method == "GET" -> {
                val id = segments[1]
                // Artwork ids are hex sha256. Checking the SHAPE is what stops
                // this being a file read primitive with an id-shaped argument.
                if (!id.matches(HEX)) Route.NotFound
                else Route.Artwork(id, params["size"]?.toIntOrNull()?.takeIf { it > 0 })
            }

            else -> Route.NotFound
        }
    }

    private val HEX = Regex("^[0-9a-f]{16,64}$")

    /**
     * A plain filename in a known set, not a path.
     *
     * Allow-listing the handful of files that exist is stricter than
     * sanitising, and it costs nothing: a front end with no build step has no
     * file the author did not type. It does mean a new asset has to be added
     * here as well as written, which [WebRoutesTest] checks against the real
     * assets directory so the two cannot drift.
     */
    fun isSafeAsset(name: String): Boolean = name in ASSETS

    val ASSETS = setOf(
        "app.js",
        "style.css",
        // Installable as a windowed app. The manifest and the icons are public
        // for the same reason index.html is: a browser reads them before it has
        // any idea what a PIN is, and an install that showed a broken icon
        // would be worse than no install.
        "manifest.webmanifest",
        "icon.svg",
        "icon-192.png",
        "icon-512.png",
        "icon-maskable-512.png",
        "apple-touch-icon.png",
    )

    /**
     * Content type by extension.
     *
     * Here rather than in the server because it is a decision, and everything
     * decidable in this module is testable. `application/manifest+json` is the
     * registered type and the one Chrome wants -- served as plain json the
     * manifest is fetched and then ignored, which looks exactly like not having
     * written one.
     */
    fun mime(name: String): String = when {
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".webmanifest") -> "application/manifest+json"
        name.endsWith(".html") -> "text/html"
        else -> "application/octet-stream"
    }
}
