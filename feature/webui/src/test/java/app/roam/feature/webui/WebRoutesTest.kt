package app.roam.feature.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a URL means. All of it, because this is the part of the server that can
 * be tested without a socket, and the part most likely to be wrong.
 */
class WebRoutesTest {

    private fun get(uri: String, params: Map<String, String> = emptyMap()) =
        WebRoutes.parse("GET", uri, params)

    @Test
    fun `root and index are the same page`() {
        assertEquals(Route.Index, get("/"))
        assertEquals(Route.Index, get("/index.html"))
        // A trailing slash is a typo, not a different resource.
        assertEquals(Route.Index, get("//"))
    }

    @Test
    fun `a query string is not part of the path`() {
        assertEquals(Route.Index, get("/?reload=1"))
    }

    @Test
    fun `the three real assets are served and nothing else is`() {
        assertEquals(Route.Asset("app.js"), get("/app.js"))
        assertEquals(Route.Asset("style.css"), get("/style.css"))
        assertEquals(Route.NotFound, get("/secrets.txt"))
    }

    /**
     * The oldest mistake in serving files. An allow-list cannot be talked
     * round -- there is no encoding of "../../databases/roam.db" that is in it.
     */
    @Test
    fun `traversal is refused however it is spelled`() {
        assertEquals(Route.NotFound, get("/../databases/roam.db"))
        assertEquals(Route.NotFound, get("/web/../../app.js"))
        assertEquals(Route.NotFound, get("/..%2Fapp.js"))
        assertEquals(Route.NotFound, get("/subdir/app.js"))
        assertFalse(WebRoutes.isSafeAsset("../app.js"))
        assertFalse(WebRoutes.isSafeAsset("app.js/../../x"))
    }

    @Test
    fun `counts is a GET and only a GET`() {
        assertEquals(Route.Counts, get("/api/counts"))
        assertEquals(Route.NotFound, WebRoutes.parse("POST", "/api/counts"))
    }

    @Test
    fun `pin is a POST and only a POST`() {
        assertEquals(Route.Pin, WebRoutes.parse("POST", "/api/pin"))
        assertEquals(Route.NotFound, get("/api/pin"))
    }

    @Test
    fun `every job slug resolves, and nothing else does`() {
        for (job in QueueJob.entries) {
            val route = get("/api/job/${job.slug}")
            assertEquals(Route.Job(job, WebRoutes.DEFAULT_LIMIT, 0), route)
        }
        assertEquals(Route.NotFound, get("/api/job/no-such-job"))
        assertEquals(Route.NotFound, get("/api/job"))
    }

    @Test
    fun `paging is clamped rather than trusted`() {
        val huge = get("/api/job/no-year", mapOf("limit" to "100000")) as Route.Job
        assertEquals(WebRoutes.MAX_LIMIT, huge.limit)

        val zero = get("/api/job/no-year", mapOf("limit" to "0")) as Route.Job
        assertEquals(1, zero.limit)

        val nonsense = get("/api/job/no-year", mapOf("limit" to "lots")) as Route.Job
        assertEquals(WebRoutes.DEFAULT_LIMIT, nonsense.limit)

        // A negative offset is not a smaller page, it is a different query.
        val back = get("/api/job/no-year", mapOf("offset" to "-50")) as Route.Job
        assertEquals(0, back.offset)
    }

    /**
     * An artwork id is a sha256 prefix. Checking the shape is what keeps this
     * from being a file-read primitive that happens to take an id.
     */
    @Test
    fun `artwork ids must look like hashes`() {
        val ok = get("/api/artwork/a3f9c1b2d4e5f607") as Route.Artwork
        assertEquals("a3f9c1b2d4e5f607", ok.id)
        assertEquals(null, ok.size)

        assertEquals(Route.NotFound, get("/api/artwork/../../roam.db"))
        assertEquals(Route.NotFound, get("/api/artwork/A3F9C1B2D4E5F607"))
        assertEquals(Route.NotFound, get("/api/artwork/short"))
        assertEquals(Route.NotFound, get("/api/artwork/not-hex-at-all-xyz"))
    }

    @Test
    fun `a size is taken only when it is a positive number`() {
        val sized = get("/api/artwork/a3f9c1b2d4e5f607", mapOf("size" to "320")) as Route.Artwork
        assertEquals(320, sized.size)

        val negative = get("/api/artwork/a3f9c1b2d4e5f607", mapOf("size" to "-1")) as Route.Artwork
        assertEquals(null, negative.size)
    }

    /** The gate has to be reachable without having been through the gate. */
    @Test
    fun `only the page, its files and the pin are public`() {
        assertTrue(WebRoutes.isPublic(Route.Index))
        assertTrue(WebRoutes.isPublic(Route.Asset("app.js")))
        assertTrue(WebRoutes.isPublic(Route.Pin))

        assertFalse(WebRoutes.isPublic(Route.Counts))
        assertFalse(WebRoutes.isPublic(Route.Job(QueueJob.NO_YEAR, 50, 0)))
        assertFalse(WebRoutes.isPublic(Route.Artwork("a3f9c1b2d4e5f607", null)))
    }

    @Test
    fun `slugs are stable names, not positions`() {
        // A constant inserted in the middle must not renumber anyone's URL.
        assertEquals(QueueJob.NEEDS_LOOK, QueueJob.bySlug("needs-look"))
        assertEquals(QueueJob.NO_COVER, QueueJob.bySlug("no-cover"))
        assertEquals(null, QueueJob.bySlug("NEEDS_LOOK"))
        assertEquals(null, QueueJob.bySlug("0"))
    }
}
