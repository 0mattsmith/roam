package app.roam.feature.player

/**
 * Where Play next and Add to queue put tracks in a SHUFFLED play order.
 *
 * Media3's default shuffle order puts an inserted item at a random point, so
 * with shuffle on a naive Play next plays the song at some point -- which is
 * not next. This is the order the service sets instead, kept pure so it can be
 * tested: nothing here knows what a player is.
 */
object ShuffleInsert {

    /**
     * @param order the play order AFTER the insert, as playlist indices, with
     * the new items wherever Media3 happened to put them.
     * @param inserted the playlist indices just added.
     * @param current the playlist index playing now.
     * @param next true for Play next (straight after [current]), false for
     * Add to queue (after everything still to play).
     * @return the same indices, with the inserted ones moved and played in the
     * order they were inserted. Everything else keeps its place relative to
     * everything else.
     */
    fun reorder(order: List<Int>, inserted: IntRange, current: Int, next: Boolean): List<Int> {
        val rest = order.filter { it !in inserted }
        val added = inserted.toList()
        if (!next) return rest + added
        val at = rest.indexOf(current)
        // Not found should not happen -- the current item is always in the
        // order. If it does, the tracks asked for still play first.
        if (at < 0) return added + rest
        return rest.subList(0, at + 1) + added + rest.subList(at + 1, rest.size)
    }
}
