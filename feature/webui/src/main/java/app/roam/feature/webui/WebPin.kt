package app.roam.feature.webui

/**
 * Whether a browser has shown the PIN, and the cookie that remembers it.
 *
 * Four digits is not a password and is not pretending to be one. It stops the
 * accident the spec names -- opening this on a phone and renaming an album --
 * and it means a device on the network cannot rewrite the library by finding
 * the port. Anything beyond that is Tailscale's job, not Roam's.
 */
object WebPin {

    const val COOKIE = "roam_pin"

    /**
     * Constant time, which is more care than four digits deserve and costs one
     * line. The habit is the point: the next thing compared here might be a
     * token, and this is where someone would copy the pattern from.
     */
    fun matches(expected: String?, offered: String?): Boolean {
        if (expected.isNullOrBlank() || offered == null) return false
        if (expected.length != offered.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor offered[i].code)
        return diff == 0
    }

    /**
     * Only digits, only four, and nothing else gets as far as the compare.
     *
     * A browser can send any cookie it likes; narrowing the shape first means
     * the PIN check is never handed a megabyte of header to walk.
     */
    fun sane(offered: String?): Boolean =
        offered != null && offered.length == 4 && offered.all { it.isDigit() }
}
