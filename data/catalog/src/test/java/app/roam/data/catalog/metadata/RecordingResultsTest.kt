package app.roam.data.catalog.metadata

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A recording search, read into plain values. The shape is the search API's
 * own, which differs from a release lookup in exactly the places a parser
 * written against the other one would miss: the track array is "track", the
 * position is an offset, and a release names its artist only when it differs.
 */
class RecordingResultsTest {

    private val sample = JSONObject(
        """
        {
          "count": 2,
          "recordings": [
            {
              "id": "rec-1",
              "score": 100,
              "title": "Whatever",
              "length": 381000,
              "video": null,
              "artist-credit": [
                { "name": "Oasis", "artist": { "id": "a-1", "name": "Oasis" } }
              ],
              "releases": [
                {
                  "id": "rel-single",
                  "title": "Whatever",
                  "status": "Official",
                  "release-group": { "id": "rg-single", "primary-type": "Single" },
                  "date": "1994-12-18",
                  "media": [
                    {
                      "position": 1,
                      "track": [ { "id": "t-1", "number": "1", "title": "Whatever", "length": 381000 } ],
                      "track-count": 4,
                      "track-offset": 0
                    }
                  ]
                },
                {
                  "id": "rel-now",
                  "title": "Now That's What I Call Music! 30",
                  "status": "Official",
                  "artist-credit": [ { "name": "Various Artists", "artist": { "name": "Various Artists" } } ],
                  "release-group": {
                    "id": "rg-now",
                    "primary-type": "Album",
                    "secondary-types": [ "Compilation" ]
                  },
                  "date": "1995",
                  "media": [ { "position": 2, "track": [ { "number": "7" } ], "track-offset": 6 } ]
                },
                {
                  "id": "rel-vinyl",
                  "title": "Whatever",
                  "release-group": { "primary-type": null },
                  "media": [ { "track": [ { "number": "B2" } ] } ]
                }
              ]
            },
            {
              "id": "rec-2",
              "title": "Feels",
              "artist-credit": [
                { "name": "Calvin Harris", "joinphrase": " feat. ", "artist": { "name": "Calvin Harris" } },
                { "name": "Jay Z", "joinphrase": " & ", "artist": { "name": "JAY-Z" } },
                { "name": "Katy Perry", "artist": { "name": "Katy Perry" } }
              ]
            }
          ]
        }
        """.trimIndent()
    )

    private val parsed = RecordingResults.parse(sample)
    private val whatever = parsed.first()

    @Test
    fun `a recording keeps its title, credit and length`() {
        assertEquals(2, parsed.size)
        assertEquals("Whatever", whatever.title)
        assertEquals("Oasis", whatever.artist)
        assertEquals(381_000L, whatever.lengthMs)
    }

    @Test
    fun `a release with no credit of its own reads as blank, not as the recording's`() {
        // Blank means "same as the recording" -- the picker fills it in. Copying
        // it here would make a compilation and an album indistinguishable.
        assertEquals("", whatever.releases[0].artist)
        assertEquals("Various Artists", whatever.releases[1].artist)
    }

    @Test
    fun `the track number comes from the offset, not the disc`() {
        assertEquals(1, whatever.releases[0].trackNumber)
        // Disc 2, seventh track: the medium's position is the DISC.
        assertEquals(7, whatever.releases[1].trackNumber)
    }

    @Test
    fun `a vinyl number is a last resort`() {
        assertEquals(2, whatever.releases[2].trackNumber)
    }

    @Test
    fun `types and dates are read where given and null where not`() {
        val single = whatever.releases[0]
        assertEquals("Single", single.primaryType)
        assertEquals(1994, single.year)
        assertEquals("Official", single.status)
        assertTrue(whatever.releases[1].isCompilation)
        assertEquals(1995, whatever.releases[1].year)

        val vinyl = whatever.releases[2]
        // A JSON null must not arrive as the word "null".
        assertNull(vinyl.primaryType)
        assertNull(vinyl.status)
        assertNull(vinyl.date)
    }

    @Test
    fun `the cover is the release group's where there is one`() {
        assertEquals(
            "https://coverartarchive.org/release-group/rg-single/front-500",
            whatever.releases[0].coverUrl,
        )
        assertEquals(
            "https://coverartarchive.org/release/rel-vinyl/front-500",
            whatever.releases[2].coverUrl,
        )
    }

    @Test
    fun `every act in a credit is named, both ways it is spelled`() {
        val feels = parsed[1]
        assertEquals("Calvin Harris feat. Jay Z & Katy Perry", feels.artist)
        assertEquals(listOf("Calvin Harris", "Jay Z", "JAY-Z", "Katy Perry"), feels.artistNames)
        assertTrue(feels.releases.isEmpty())
    }
}
