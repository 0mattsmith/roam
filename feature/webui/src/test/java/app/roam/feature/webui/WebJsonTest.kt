package app.roam.feature.webui

import app.roam.core.database.QueueAlbumRow
import app.roam.core.database.QueueTrackRow
import app.roam.core.model.TagState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The responses, asserted as the front end will read them.
 *
 * Runs on the JVM only because `testImplementation(libs.org.json)` puts a real
 * org.json on the classpath -- the one inside android.jar is stubs that throw
 * "Stub!" at the first call.
 */
class WebJsonTest {

    private fun track(
        id: Long = 1,
        title: String = "Enjoy the Silence",
        artwork: String? = "a3f9c1b2d4e5f607",
        year: Int? = 1990,
        genre: String? = "Synth-pop",
        state: TagState = TagState.OK,
        fileName: String? = "07 Enjoy the Silence.mp3",
    ) = QueueTrackRow(
        id = id,
        title = title,
        artistName = "Depeche Mode",
        albumTitle = "Violator",
        artworkId = artwork,
        year = year,
        genre = genre,
        tagState = state,
        fileName = fileName,
    )

    /**
     * A zero says that job is DONE. An absent key looks like a bug, and the
     * front end would render nothing rather than a finished card.
     */
    @Test
    fun `every job appears, including the empty ones`() {
        val json = JSONObject(WebJson.counts(mapOf(QueueJob.NO_YEAR to 180), 2132))
        val counts = json.getJSONObject("counts")
        for (job in QueueJob.entries) assertTrue(job.slug, counts.has(job.slug))
        assertEquals(180, counts.getInt("no-year"))
        assertEquals(0, counts.getInt("needs-look"))
        assertEquals(2132, json.getJSONObject("library").getInt("tracks"))
    }

    @Test
    fun `a track row carries what the list renders`() {
        val json = JSONObject(WebJson.tracks(QueueJob.NEEDS_LOOK, 41, 0, listOf(track())))
        assertEquals("needs-look", json.getString("job"))
        assertEquals("tracks", json.getString("kind"))
        assertEquals(41, json.getInt("total"))
        assertEquals(0, json.getInt("offset"))

        val row = json.getJSONArray("rows").getJSONObject(0)
        assertEquals("Enjoy the Silence", row.getString("title"))
        assertEquals("Depeche Mode", row.getString("artist"))
        assertEquals("Violator", row.getString("album"))
        assertEquals(1990, row.getInt("year"))
        assertEquals("OK", row.getString("tagState"))
    }

    /**
     * Absent means the KEY is absent. org.json would otherwise write the
     * string "null" or a json null, and the front end checks `if (row.year)`.
     */
    @Test
    fun `a null is a missing key, never the word null`() {
        val json = JSONObject(
            WebJson.tracks(
                QueueJob.NO_YEAR, 1, 0,
                listOf(track(artwork = null, year = null, genre = null, fileName = null)),
            )
        )
        val row = json.getJSONArray("rows").getJSONObject(0)
        assertFalse(row.has("year"))
        assertFalse(row.has("genre"))
        assertFalse(row.has("artworkId"))
        assertFalse(row.has("fileName"))
        assertFalse(row.toString().contains("null"))
    }

    /**
     * Named rather than inferred from the first row: a tracks page and an
     * albums page render differently, and guessing breaks on an empty one.
     */
    @Test
    fun `the kind is stated even when there are no rows`() {
        val tracks = JSONObject(WebJson.tracks(QueueJob.FROZEN, 0, 0, emptyList()))
        assertEquals("tracks", tracks.getString("kind"))
        assertEquals(0, tracks.getJSONArray("rows").length())

        val albums = JSONObject(WebJson.albums(QueueJob.NO_COVER, 0, 0, emptyList()))
        assertEquals("albums", albums.getString("kind"))
    }

    @Test
    fun `an album row carries the folder a cover would go in`() {
        val json = JSONObject(
            WebJson.albums(
                QueueJob.NO_COVER, 12, 50,
                listOf(
                    QueueAlbumRow(
                        id = 9,
                        title = "Violator",
                        artistName = "Depeche Mode",
                        trackCount = 9,
                        year = 1990,
                        folderPath = "Depeche Mode/Violator",
                    )
                ),
            )
        )
        assertEquals(50, json.getInt("offset"))
        val row = json.getJSONArray("rows").getJSONObject(0)
        assertEquals("Depeche Mode/Violator", row.getString("folderPath"))
        assertEquals(9, row.getInt("trackCount"))
    }

    @Test
    fun `an error is json too, so a fetch can read it`() {
        assertEquals("Wrong PIN", JSONObject(WebJson.error("Wrong PIN")).getString("error"))
    }
}
