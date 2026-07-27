package app.roam.feature.player

/**
 * Content-style hints. These are the raw extras keys behind
 * androidx.media3.session.MediaConstants -- kept literal here so the values
 * are visible when you are debugging why a node rendered as a list.
 */
object CarConstants {
    const val EXTRA_CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
    const val EXTRA_CONTENT_STYLE_PLAYABLE  = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
    const val EXTRA_CONTENT_STYLE_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

    const val STYLE_LIST = 1
    const val STYLE_GRID = 2
    const val STYLE_CATEGORY_LIST = 3
    const val STYLE_CATEGORY_GRID = 4

    /**
     * The head unit tells YOU how many root tabs it can show. Do not hardcode 4 --
     * simpler units advertise fewer and your tabs silently vanish.
     */
    const val ROOT_HINT_CHILDREN_LIMIT = "androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT"
    const val DEFAULT_ROOT_TABS = 4

    /** Keeps prev/next from being pushed out by custom actions. */
    const val SLOT_RESERVATION_PREV = "androidx.media3.session.SLOT_RESERVATION_SEEK_TO_PREV"
    const val SLOT_RESERVATION_NEXT = "androidx.media3.session.SLOT_RESERVATION_SEEK_TO_NEXT"

    // Custom actions, in priority order. Visible slot count varies by screen
    // width; anything beyond falls into the overflow menu.
    const val ACTION_LOVE = "app.roam.LOVE"
    const val ACTION_SHUFFLE_QUEUE = "app.roam.SHUFFLE_QUEUE"
    const val ACTION_REPEAT = "app.roam.REPEAT"

    /** Android Auto truncates long lists while moving. Page rather than dump. */
    const val PAGE_SIZE = 100
}
