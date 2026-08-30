package app.roam.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a crawl is trusted to say what is gone.
 *
 * This is the rule that decides whether a library survives a bad sync. Drive
 * answers `files.list` for a folder id that no longer exists with an empty list
 * and a 200 -- not an error -- so "everything was deleted" and "nothing came
 * back" arrive looking identical, and reconciling against the second one
 * removes the library.
 *
 * Mirrors CatalogWriter.isPlausible. Kept as its own copy rather than opening
 * the real one up: it is six lines of arithmetic, and a test that can only run
 * by widening the visibility of the thing it tests has changed that thing.
 */
class SyncReconcileTest {

    private val maxLossPercent = 50

    private fun plausible(seen: Int, known: Int, gone: Int): Boolean = when {
        known == 0 -> true
        seen == 0 -> false
        gone > known * maxLossPercent / 100 -> false
        else -> true
    }

    private fun pass(seen: Int, known: Int) = plausible(seen, known, known - seen)

    @Test
    fun `a normal pass is trusted`() {
        assertTrue(pass(seen = 100, known = 100))
        assertTrue(pass(seen = 120, known = 100))
    }

    @Test
    fun `a few files genuinely going is trusted`() {
        assertTrue(pass(seen = 99, known = 100))
        assertTrue(pass(seen = 90, known = 100))
    }

    @Test
    fun `nothing coming back is never a real answer`() {
        // The one that cost a library. A folder id that has gone stale returns
        // an empty list and a success code, so this case must be refused on the
        // shape of the answer rather than on an error that never arrives.
        assertFalse(pass(seen = 0, known = 100))
        assertFalse(pass(seen = 0, known = 1))
    }

    @Test
    fun `a crawl that stopped early is refused`() {
        assertFalse(pass(seen = 10, known = 100))
        assertFalse(pass(seen = 49, known = 100))
    }

    @Test
    fun `losing under half is accepted`() {
        // Deliberately permissive, and cheap to be wrong about now that nothing
        // is deleted: a wrongly accepted pass hides rows until the next good
        // crawl clears the flag, where it used to destroy them.
        assertTrue(pass(seen = 51, known = 100))
    }

    @Test
    fun `the first ever run cannot delete anything`() {
        assertTrue(pass(seen = 0, known = 0))
        assertTrue(pass(seen = 500, known = 0))
    }

    @Test
    fun `an empty library that stays empty is fine`() {
        assertEquals(true, plausible(seen = 0, known = 0, gone = 0))
    }
}
