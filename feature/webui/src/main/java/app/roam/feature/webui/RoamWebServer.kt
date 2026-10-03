package app.roam.feature.webui

import android.content.res.AssetManager
import app.roam.core.common.WebAddress
import app.roam.core.database.QueueDao
import app.roam.data.catalog.artwork.ArtworkStore
import fi.iki.elonen.NanoHTTPD
// Imported by name rather than relied on through the supertype. Kotlin does
// not bring a Java superclass's nested types and statics into a subclass's
// scope the way Java does, and the failure reads as "unresolved reference" on
// something that is plainly inherited.
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.ByteArrayInputStream
import java.io.FileInputStream

/**
 * The server. Deliberately the thinnest part of this module.
 *
 * Everything decidable lives next door and is unit-tested -- [WebRoutes] for
 * what a URL means, [WebJson] for what comes back, [WebPin] for whether to
 * answer at all. What is left here is sockets, and sockets cannot be tested
 * on the JVM, so there should be as little of them as possible.
 *
 * STAGE ONE IS READ ONLY. The one POST is the PIN. When writes land they go
 * through `TrackEditor` and `ArtworkEditor` and nothing else: those set
 * `userEdited` only when the metadata actually moved, insert parent rows
 * before pointing at them because ids are content-derived, and wrap an album
 * rename in a transaction. A handler reaching for a DAO would reimplement
 * every one of those bugs somewhere nobody looks. [QueueDao] is read-only by
 * construction, which is why it is the only one injected.
 *
 * Runs on NanoHTTPD's own threads, so the blocking DAO calls here are correct
 * rather than tolerated -- Room refuses a blocking query on the main thread,
 * so this cannot drift onto the wrong one without failing loudly.
 */
class RoamWebServer(
    private val assets: AssetManager,
    private val queue: QueueDao,
    private val artwork: ArtworkStore,
    /**
     * Read fresh on every request rather than captured.
     *
     * Settings can rotate the PIN while the server is up, and the point of
     * rotating it is that the old one stops working immediately.
     */
    private val pin: () -> String?,
) : NanoHTTPD(WebAddress.PORT) {

    override fun serve(session: IHTTPSession): Response {
        val params = session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
        val route = WebRoutes.parse(session.method.name, session.uri, params)

        if (!WebRoutes.isPublic(route) && !authorised(session)) {
            // 401 and not 403: the browser has not shown the PIN yet, which is
            // a different thing from having shown the wrong one, and the front
            // end puts up the gate on exactly this.
            return json(Response.Status.UNAUTHORIZED, WebJson.error("PIN required"))
        }

        return try {
            when (route) {
                Route.Index -> asset("index.html")
                is Route.Asset -> asset(route.name)
                Route.Counts -> json(Response.Status.OK, counts())
                is Route.Job -> json(Response.Status.OK, job(route))
                is Route.Artwork -> cover(route)
                Route.Pin -> offeredPin(session)
                Route.NotFound -> json(Response.Status.NOT_FOUND, WebJson.error("No such thing"))
            }
        } catch (t: Throwable) {
            // A thrown handler would otherwise be NanoHTTPD's own HTML error
            // page, which a fetch() parses as json and fails on confusingly.
            json(Response.Status.INTERNAL_ERROR, WebJson.error(t.message ?: "Failed"))
        }
    }

    private fun authorised(session: IHTTPSession): Boolean {
        val offered = session.cookies.read(WebPin.COOKIE)
        return WebPin.sane(offered) && WebPin.matches(pin(), offered)
    }

    /**
     * The gate. Sets the cookie only on a correct PIN.
     *
     * The header is written by hand rather than through `session.cookies`,
     * because NanoHTTPD's Cookie has no path and a cookie with no Path is
     * scoped by the browser to the DIRECTORY of the request -- /api/. That
     * happens to cover every call stage one makes, so it would work by luck
     * and break the first time a route lives anywhere else.
     *
     * Session-scoped on purpose: no expiry, so it lasts as long as the browser
     * is open and a rotated PIN cannot be remembered past it. HttpOnly because
     * the front end never reads it -- the only thing that asks whether a
     * browser is let in is the 401, which is the server's answer, not a guess
     * the page makes about its own cookies.
     */
    private fun offeredPin(session: IHTTPSession): Response {
        // parseBody is what populates parameters for a POST. Without it the
        // body is still sitting in the socket and the map is empty.
        runCatching { session.parseBody(HashMap()) }
        // takeIf rather than an if-return, so what survives is non-null and
        // the cookie cannot be handed a null it would write as "null".
        val offered = session.parameters["pin"]?.firstOrNull()?.trim()
            ?.takeIf { WebPin.sane(it) && WebPin.matches(pin(), it) }
            ?: return json(Response.Status.FORBIDDEN, WebJson.error("Wrong PIN"))

        return json(Response.Status.OK, """{"ok":true}""")
            .apply { addHeader("Set-Cookie", WebPin.cookie(offered)) }
    }

    private fun counts(): String = WebJson.counts(
        counts = mapOf(
            QueueJob.NEEDS_LOOK to queue.needsLookCount(),
            QueueJob.NO_YEAR to queue.noYearCount(),
            QueueJob.NO_GENRE to queue.noGenreCount(),
            QueueJob.NO_COVER to queue.noCoverCount(),
            QueueJob.FROZEN to queue.frozenCount(),
            QueueJob.MISSING to queue.missingCount(),
        ),
        libraryTracks = queue.libraryCount(),
    )

    private fun job(route: Route.Job): String {
        val limit = route.limit
        val offset = route.offset
        return when (route.job) {
            QueueJob.NEEDS_LOOK -> WebJson.tracks(
                route.job, queue.needsLookCount(), offset, queue.needsLook(limit, offset)
            )
            QueueJob.NO_YEAR -> WebJson.tracks(
                route.job, queue.noYearCount(), offset, queue.noYear(limit, offset)
            )
            QueueJob.NO_GENRE -> WebJson.tracks(
                route.job, queue.noGenreCount(), offset, queue.noGenre(limit, offset)
            )
            QueueJob.FROZEN -> WebJson.tracks(
                route.job, queue.frozenCount(), offset, queue.frozen(limit, offset)
            )
            QueueJob.MISSING -> WebJson.tracks(
                route.job, queue.missingCount(), offset, queue.missing(limit, offset)
            )
            QueueJob.NO_COVER -> WebJson.albums(
                route.job, queue.noCoverCount(), offset, queue.noCover(limit, offset)
            )
        }
    }

    /**
     * Straight off disk, and the only thing here that is allowed to be cached.
     *
     * An artwork id is the sha256 of the bytes, so the content at one id can
     * never change -- which makes `immutable` true rather than optimistic, and
     * saves re-sending two hundred covers every time the page is reloaded.
     */
    private fun cover(route: Route.Artwork): Response {
        val file = artwork.file(route.id, route.size)
        if (!file.exists()) {
            return json(Response.Status.NOT_FOUND, WebJson.error("No artwork"))
        }
        val type = if (file.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(
            Response.Status.OK, type, FileInputStream(file), file.length()
        ).apply { addHeader("Cache-Control", "public, max-age=31536000, immutable") }
    }

    private fun asset(name: String): Response {
        val bytes = runCatching { assets.open("$ASSET_DIR/$name").use { it.readBytes() } }
            .getOrNull()
            ?: return json(Response.Status.NOT_FOUND, WebJson.error("No such file"))
        return newFixedLengthResponse(
            Response.Status.OK, WebRoutes.mime(name), ByteArrayInputStream(bytes), bytes.size.toLong()
        ).apply { addHeader("Cache-Control", "no-cache") }
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json", body).apply {
            // The library is nobody's business but this browser's, and a cached
            // queue is a wrong queue the moment anything is edited.
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    companion object {
        /** Everything under assets/web/, and nothing above it. */
        private const val ASSET_DIR = "web"
    }
}
