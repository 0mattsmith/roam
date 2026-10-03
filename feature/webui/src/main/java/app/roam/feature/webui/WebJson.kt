package app.roam.feature.webui

import app.roam.core.database.QueueAlbumRow
import app.roam.core.database.QueueTrackRow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rows to JSON, and no further.
 *
 * org.json rather than kotlinx.serialization: it ships inside android.jar, so
 * it costs the APK nothing, and these are four shapes rather than a protocol.
 * Key ORDER does not matter here, which is the difference from `Json.kt` in
 * :data:catalog -- that one emits an ordered tree because album.json lives in
 * someone's Drive folder and a reordered rewrite is a diff on every line.
 *
 * Pure, so the whole response can be asserted in a unit test. org.json on
 * Android is real; on the JVM it is a stub that throws "Stub!", which is why
 * the module takes `testImplementation(libs.org.json)`.
 */
object WebJson {

    fun counts(counts: Map<QueueJob, Int>, libraryTracks: Int): String {
        val obj = JSONObject()
        val byJob = JSONObject()
        // Every job, including the empty ones. A zero is information -- it says
        // that job is DONE, where an absent key just looks like a bug.
        for (job in QueueJob.entries) byJob.put(job.slug, counts[job] ?: 0)
        obj.put("counts", byJob)
        obj.put("library", JSONObject().put("tracks", libraryTracks))
        return obj.toString()
    }

    fun tracks(job: QueueJob, total: Int, offset: Int, rows: List<QueueTrackRow>): String {
        val array = JSONArray()
        for (row in rows) {
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("artist", row.artistName)
                    .put("album", row.albumTitle)
                    .putOrNull("artworkId", row.artworkId)
                    .putOrNull("year", row.year)
                    .putOrNull("genre", row.genre)
                    .put("tagState", row.tagState.name)
                    .putOrNull("fileName", row.fileName)
            )
        }
        return envelope(job, total, offset, "tracks", array)
    }

    fun albums(job: QueueJob, total: Int, offset: Int, rows: List<QueueAlbumRow>): String {
        val array = JSONArray()
        for (row in rows) {
            array.put(
                JSONObject()
                    .put("id", row.id)
                    .put("title", row.title)
                    .put("artist", row.artistName)
                    .put("trackCount", row.trackCount)
                    .putOrNull("year", row.year)
                    .putOrNull("folderPath", row.folderPath)
            )
        }
        return envelope(job, total, offset, "albums", array)
    }

    fun error(message: String): String = JSONObject().put("error", message).toString()

    /**
     * The same wrapper for both, carrying what the front end needs to page.
     *
     * `total` is the count, not the page size, so the browser can say "41 of
     * 180" without a second request -- and the kind is named rather than
     * inferred, because a tracks page and an albums page render differently and
     * guessing from the first row breaks on an empty one.
     */
    private fun envelope(
        job: QueueJob,
        total: Int,
        offset: Int,
        kind: String,
        rows: JSONArray,
    ): String = JSONObject()
        .put("job", job.slug)
        .put("kind", kind)
        .put("total", total)
        .put("offset", offset)
        .put("rows", rows)
        .toString()

    /**
     * org.json turns a Kotlin null into the literal string "null" through
     * `put(String, Object)`, and JSONObject.NULL into a json null. Neither is
     * what the front end wants to check with `if (row.year)`, so an absent
     * value is simply an absent KEY.
     */
    private fun JSONObject.putOrNull(key: String, value: Any?): JSONObject =
        if (value == null) this else put(key, value)
}
