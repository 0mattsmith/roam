package app.roam.feature.player

/**
 * Browse-tree addressing. Every MediaItem id round-trips through here, so the
 * service can answer onGetChildren without keeping state.
 */
sealed interface MediaId {
    val raw: String

    data object Root : MediaId { override val raw = "root" }

    data object Home : MediaId { override val raw = "home" }
    data object Artists : MediaId { override val raw = "artists" }
    data object Albums : MediaId { override val raw = "albums" }
    data object Loved : MediaId { override val raw = "loved" }

    data object RecentlyAdded : MediaId { override val raw = "home/recent_added" }
    data object RecentlyPlayed : MediaId { override val raw = "home/recent_played" }

    data class Artist(val id: Long) : MediaId { override val raw = "artist/$id" }
    data class Album(val id: Long) : MediaId { override val raw = "album/$id" }
    data class Track(val id: Long) : MediaId { override val raw = "track/$id" }

    /** Playable action rows -- "Shuffle everything", "Shuffle all by X". */
    data object ShuffleAll : MediaId { override val raw = "action/shuffle_all" }
    data object ShuffleLoved : MediaId { override val raw = "action/shuffle_loved" }
    data class ShuffleArtist(val id: Long) : MediaId { override val raw = "action/shuffle_artist/$id" }
    data class ShuffleAlbum(val id: Long) : MediaId { override val raw = "action/shuffle_album/$id" }

    companion object {
        fun parse(raw: String): MediaId = when {
            raw == "root" -> Root
            raw == "home" -> Home
            raw == "artists" -> Artists
            raw == "albums" -> Albums
            raw == "loved" -> Loved
            raw == "home/recent_added" -> RecentlyAdded
            raw == "home/recent_played" -> RecentlyPlayed
            raw == "action/shuffle_all" -> ShuffleAll
            raw == "action/shuffle_loved" -> ShuffleLoved
            raw.startsWith("action/shuffle_artist/") -> ShuffleArtist(raw.substringAfterLast('/').toLong())
            raw.startsWith("action/shuffle_album/") -> ShuffleAlbum(raw.substringAfterLast('/').toLong())
            raw.startsWith("artist/") -> Artist(raw.substringAfterLast('/').toLong())
            raw.startsWith("album/") -> Album(raw.substringAfterLast('/').toLong())
            raw.startsWith("track/") -> Track(raw.substringAfterLast('/').toLong())
            else -> Root
        }
    }
}
