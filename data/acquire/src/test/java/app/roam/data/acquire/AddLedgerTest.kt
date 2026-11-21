package app.roam.data.acquire

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icon at the end of a row is the whole answer to "is this mine yet", so
 * a wrong state is a lie told at a glance: a ring over a track that failed, or
 * a Play over one that is not there.
 */
class AddLedgerTest {

    private var next = 0

    private fun job(
        state: WorkInfo.State,
        url: String = "u1",
        album: String = "Definitely Maybe",
        albumArtist: String = "Oasis",
        trackNo: Int? = null,
        progress: Float = 0f,
        error: String? = null,
    ) = DownloadStatus(
        id = "job${next++}",
        title = "Track",
        artist = "Oasis",
        request = DownloadRequest(
            url = url,
            title = "Track",
            artist = "Oasis",
            album = album,
            trackNo = trackNo,
            albumArtist = albumArtist,
        ),
        state = state,
        progress = progress,
        error = error,
    )

    private fun ledger(
        vararg jobs: DownloadStatus,
        resolved: Map<String, Long> = emptyMap(),
        resolvedAlbums: Map<String, Long> = emptyMap(),
        syncing: Boolean = false,
    ) = AddLedger(jobs.toList(), resolved, resolvedAlbums, syncing)

    @Test
    fun `nothing asked for is a plus`() {
        assertEquals(AddState.NotAdded, ledger().forUrl("u1"))
    }

    @Test
    fun `waiting is queued, running is adding`() {
        assertEquals(AddState.Queued, ledger(job(WorkInfo.State.ENQUEUED)).forUrl("u1"))
        assertEquals(
            AddState.Adding(0.4f),
            ledger(job(WorkInfo.State.RUNNING, progress = 0.4f)).forUrl("u1"),
        )
    }

    @Test
    fun `landed is a ring until sync makes a row of it`() {
        val done = job(WorkInfo.State.SUCCEEDED)
        assertEquals(AddState.Syncing, ledger(done, syncing = true).forUrl("u1"))
        assertEquals(
            AddState.Ready(7),
            ledger(done, resolved = mapOf("u1" to 7L), syncing = true).forUrl("u1"),
        )
    }

    @Test
    fun `a ring never spins forever`() {
        // Synced, and still no row: the tags named it something else.
        assertEquals(AddState.NotFound, ledger(job(WorkInfo.State.SUCCEEDED)).forUrl("u1"))
    }

    @Test
    fun `a failure keeps its reason`() {
        val state = ledger(job(WorkInfo.State.FAILED, error = "Too long")).forUrl("u1")
        assertTrue(state is AddState.Failed)
        assertEquals("Too long", (state as AddState.Failed).reason)
    }

    @Test
    fun `a cancelled job reads as never asked`() {
        assertEquals(AddState.NotAdded, ledger(job(WorkInfo.State.CANCELLED)).forUrl("u1"))
    }

    @Test
    fun `cleared from the Adding list, a row that was ready stays ready`() {
        assertEquals(AddState.Ready(7), ledger(resolved = mapOf("u1" to 7L)).forUrl("u1"))
    }

    @Test
    fun `an album fills as its tracks land`() {
        val state = ledger(
            job(WorkInfo.State.SUCCEEDED, url = "a"),
            job(WorkInfo.State.RUNNING, url = "b", progress = 0.5f),
            job(WorkInfo.State.ENQUEUED, url = "c"),
            job(WorkInfo.State.ENQUEUED, url = "d"),
        ).forAlbum("Definitely Maybe", "Oasis")
        assertEquals(AddState.Adding(0.375f), state)
    }

    @Test
    fun `an album part landed is still adding while it waits for wifi`() {
        val state = ledger(
            job(WorkInfo.State.SUCCEEDED, url = "a"),
            job(WorkInfo.State.ENQUEUED, url = "b"),
        ).forAlbum("Definitely Maybe", "Oasis")
        assertEquals(AddState.Adding(0.5f), state)
    }

    @Test
    fun `one bad track turns an album red only once the rest has settled`() {
        val bad = job(WorkInfo.State.FAILED, url = "a")
        assertEquals(
            AddState.Adding(0f),
            ledger(bad, job(WorkInfo.State.RUNNING, url = "b"))
                .forAlbum("Definitely Maybe", "Oasis"),
        )
        assertTrue(
            ledger(bad, job(WorkInfo.State.SUCCEEDED, url = "b"))
                .forAlbum("Definitely Maybe", "Oasis") is AddState.Failed
        )
    }

    @Test
    fun `a ready album plays from its first track`() {
        val state = ledger(
            job(WorkInfo.State.SUCCEEDED, url = "b", trackNo = 2),
            job(WorkInfo.State.SUCCEEDED, url = "a", trackNo = 1),
            resolved = mapOf("a" to 1L, "b" to 2L),
        ).forAlbum("Definitely Maybe", "Oasis")
        assertEquals(AddState.Ready(1), state)
    }

    @Test
    fun `an album is found however its title was written`() {
        val state = ledger(job(WorkInfo.State.ENQUEUED, album = "DEFINITELY MAYBE"))
            .forAlbum("Definitely Maybe", "oasis")
        assertEquals(AddState.Queued, state)
    }

    @Test
    fun `a compilation is found by its album artist, not each track's`() {
        val state = ledger(job(WorkInfo.State.ENQUEUED, album = "Now 30", albumArtist = "Various Artists"))
            .forAlbum("Now 30", "Various Artists")
        assertEquals(AddState.Queued, state)
    }

    @Test
    fun `another album's jobs are not this one's`() {
        assertEquals(
            AddState.NotAdded,
            ledger(job(WorkInfo.State.ENQUEUED, album = "Be Here Now"))
                .forAlbum("Definitely Maybe", "Oasis"),
        )
    }
}
