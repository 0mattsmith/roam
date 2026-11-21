package app.roam.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Play next with shuffle on is the case that goes wrong silently: the song is
 * queued, and then plays at some random point, and nothing says why.
 */
class ShuffleInsertTest {

    @Test
    fun `play next goes straight after the current track in the shuffled order`() {
        // Playlist of five, shuffled 3 1 4 0 2, playing index 1. Two tracks
        // inserted at 2..3 pushed the old 2, 3 and 4 along to 4, 5 and 6, and
        // Media3 dropped the new ones in at random.
        val after = listOf(5, 2, 1, 6, 0, 3, 4)
        assertEquals(
            listOf(5, 1, 2, 3, 6, 0, 4),
            ShuffleInsert.reorder(after, inserted = 2..3, current = 1, next = true),
        )
    }

    @Test
    fun `add to queue goes after everything still to play`() {
        val after = listOf(3, 5, 1, 4, 0, 2)
        assertEquals(
            listOf(3, 1, 4, 0, 2, 5),
            ShuffleInsert.reorder(after, inserted = 5..5, current = 1, next = false),
        )
    }

    @Test
    fun `an album played next keeps its own order`() {
        val after = listOf(0, 4, 2, 3, 1)
        assertEquals(
            listOf(0, 1, 2, 3, 4),
            ShuffleInsert.reorder(after, inserted = 1..3, current = 0, next = true),
        )
    }

    @Test
    fun `the current track last in the order still has next straight after it`() {
        // Two tracks shuffled 1 0, playing 0 -- the last to play. One inserted
        // at index 1 pushed the old 1 to 2, and landed first.
        val after = listOf(1, 2, 0)
        assertEquals(
            listOf(2, 0, 1),
            ShuffleInsert.reorder(after, inserted = 1..1, current = 0, next = true),
        )
    }

    @Test
    fun `nothing is lost or repeated`() {
        val after = listOf(6, 2, 0, 5, 1, 4, 3)
        val result = ShuffleInsert.reorder(after, inserted = 3..4, current = 6, next = true)
        assertEquals(after.sorted(), result.sorted())
    }
}
