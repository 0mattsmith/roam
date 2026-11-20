package app.roam.feature.downloader

import java.text.Normalizer

/**
 * What a video says it is, read as questions for a catalogue.
 *
 * Nothing here becomes a tag. A video is called whatever its uploader typed --
 * "Oasis - Whatever (Official Video)" on a channel called OasisVEVO -- and the
 * only use for those words is to ask MusicBrainz the right question. The
 * answer comes back spelled the way the record is, and THAT is what is written.
 *
 * Pure, so every reading here is tested rather than discovered on a phone.
 */
internal object VideoClues {

    /** One reading of a video: a song to look for, by an act. */
    data class Guess(val title: String, val artist: String)

    fun read(video: YoutubeResult): List<Guess> = read(
        videoTitle = video.videoTitle,
        channel = video.channel,
        description = video.description,
        creditedTitle = video.creditedTitle,
        creditedArtist = video.creditedArtist,
    )

    /**
     * Every reading worth asking about, most trustworthy first, each once.
     *
     * What the description STATES outranks anything inferred. YouTube Music's
     * "Provided to YouTube by" block is the label's own delivery and "Music
     * video by X performing Y" is the label's template; both name the song and
     * the act outright. Only after them is the title taken apart -- with any
     * act the description or the channel names deciding which side of the dash
     * is which, because "Whatever - Oasis" turns up as well as the other way
     * round.
     */
    fun read(
        videoTitle: String,
        channel: String? = null,
        description: String? = null,
        creditedTitle: String? = null,
        creditedArtist: String? = null,
    ): List<Guess> {
        val text = description.orEmpty()
        val credited = if (creditedTitle.isNullOrBlank() || creditedArtist.isNullOrBlank()) {
            null
        } else {
            Guess(creditedTitle, creditedArtist)
        }
        val stated = listOfNotNull(providedTo(text), credited, musicVideoBy(text), videoFor(text))
        val named = stated.map { it.artist } + actsNamedIn(text) + channelNames(channel)

        return (stated + fromTitle(videoTitle, named))
            .map { Guess(cleanTitle(it.title), cleanArtist(it.artist)) }
            .filter { key(it.title).isNotEmpty() && key(it.artist).isNotEmpty() }
            .distinctBy { key(searchable(it.title)) to key(it.artist) }
    }

    /**
     * A song's title with what is never part of a song's name taken off:
     * "(Official Video)", "[HD]", "(Remastered 2009)", "ft. Somebody", quotes.
     *
     * What stays is anything that might be -- "(Live at Knebworth)", "(PNAU
     * Remix)" -- because the catalogue writes those into titles too, and
     * taking them off would match the studio take instead.
     */
    fun cleanTitle(title: String): String {
        val unfeatured = FEAT_TAIL.replace(removeBrackets(spaced(title)), "")
        return unquote(dropNoiseTail(unfeatured)).collapse()
    }

    /** An act's name with brackets, quotes and featured guests taken off. */
    fun cleanArtist(artist: String): String =
        unquote(FEAT_TAIL.replace(removeBrackets(spaced(artist)), "")).collapse()

    /**
     * The title as a phrase to search for: brackets off and anything after a
     * dash, unless that leaves nothing. A phrase matches INSIDE a longer title,
     * so this finds "Wonderwall" from "Wonderwall - Live", and finds the
     * catalogue's "(What's the Story) Morning Glory?" from "Morning Glory?".
     */
    fun searchable(title: String): String {
        val beforeDash = title.substringBefore(" - ").trim().ifEmpty { title }
        return ANY_BRACKETS.replace(beforeDash, " ").collapse().ifEmpty { title.collapse() }
    }

    /**
     * The acts in a credit, whatever joins them -- "&", ",", "x", "feat.".
     * Splits band names too ("Simon & Garfunkel"), which is harmless where
     * this is used: every part is still a phrase inside the real name.
     */
    fun splitArtists(artist: String): List<String> =
        ARTIST_JOINS.split(artist).map { it.trim() }.filter { key(it).isNotEmpty() }

    /**
     * The act to search for: the first one named. YouTube writes "A x B" or
     * "A, B" where the catalogue writes "A & B", and a phrase must match
     * word for word -- but the first act is inside both.
     */
    fun queryArtist(artist: String): String = splitArtists(artist).firstOrNull() ?: artist.trim()

    /**
     * What two spellings of one name have in common: no case, no accents, no
     * punctuation, and no "and" -- so "Beyonce" is "Beyoncé" and "Simon and
     * Garfunkel" is "Simon & Garfunkel".
     */
    fun key(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(AND_WORD, "")
            .filter { it.isLetterOrDigit() }

    /**
     * The act a channel belongs to, in the spellings worth trying:
     * "OasisVEVO" and "Oasis - Topic" are both Oasis. A name run together, as
     * VEVO channels are, is offered split as well -- "TheKillers" is "The
     * Killers" -- but after the name as it stands, because "OneRepublic" is
     * spelled that way on purpose.
     */
    fun channelNames(channel: String?): List<String> {
        var name = channel?.collapse().orEmpty()
        for (rule in CHANNEL_NOISE) name = rule.replace(name, "").trim()
        if (key(name).isEmpty()) return emptyList()
        val spaced = if (' ' in name) name else CAMEL.replace(name, "$1 $2")
        return listOf(name, spaced).distinct()
    }

    // ---- reading the description --------------------------------------------

    /**
     * YouTube Music's own block, which every auto-generated upload carries:
     *
     *     Provided to YouTube by Big Brother Recordings Ltd
     *
     *     Whatever · Oasis
     */
    private fun providedTo(text: String): Guess? {
        val lines = text.lines()
        val at = lines.indexOfFirst { it.trim().startsWith("Provided to YouTube by", ignoreCase = true) }
        if (at < 0) return null
        val line = lines.drop(at + 1).firstOrNull { it.isNotBlank() } ?: return null
        val parts = line.split('·').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) Guess(parts[0], parts[1]) else null
    }

    /** The VEVO template: "Music video by Oasis performing Whatever. (C) 1994 ..." */
    private fun musicVideoBy(text: String): Guess? =
        MUSIC_VIDEO_BY.find(text)?.let { Guess(it.groupValues[2], it.groupValues[1]) }

    /** "Official video for “Whatever” by Oasis." */
    private fun videoFor(text: String): Guess? =
        VIDEO_FOR.find(text)?.let { Guess(it.groupValues[1], it.groupValues[2]) }

    /**
     * Acts a label's template names without naming the song: "Listen to
     * Oasis:", "Follow Oasis", "Subscribe to the official Oasis YouTube
     * channel". Enough to say which half of the title is the act.
     */
    private fun actsNamedIn(text: String): List<String> =
        NAMED.flatMap { rule -> rule.findAll(text).map { it.groupValues[1] }.toList() }
            .map { it.substringBefore(" on ").trim() }
            .filter { it.length <= MAX_NAME && plausibleAct(it) }
            .distinct()

    private fun plausibleAct(name: String): Boolean {
        if (key(name).isEmpty() || "http" in name || name.any { it in QUOTE_CHARS }) return false
        return words(name).none { it in NOT_AN_ACT }
    }

    // ---- reading the title --------------------------------------------------

    /** The video title taken apart, with the acts named elsewhere to orient it. */
    private fun fromTitle(videoTitle: String, named: List<String>): List<Guess> {
        val title = withoutNoise(videoTitle)
        if (key(title).isEmpty()) return emptyList()

        val halves = dashHalves(title)
        if (halves != null) {
            val (left, right) = halves
            val leftNamed = named.any { sameAct(it, left) }
            val rightNamed = named.any { sameAct(it, right) }
            return when {
                leftNamed && !rightNamed -> listOf(Guess(right, left))
                rightNamed && !leftNamed -> listOf(Guess(left, right))
                // "Artist - Title" is the convention, so it is asked first.
                else -> listOf(Guess(right, left), Guess(left, right))
            }
        }
        quotedHalves(title)?.let { (song, act) -> return listOf(Guess(song, act)) }
        // No act in the title at all: the song, by each act named elsewhere.
        return named.map { Guess(title, it) }
    }

    private fun sameAct(name: String, side: String): Boolean {
        val wanted = key(cleanArtist(name))
        if (wanted.isEmpty()) return false
        return key(cleanArtist(side)) == wanted || splitArtists(side).any { key(it) == wanted }
    }

    /**
     * The whole title with its noise off, ready to be split. A bar separates
     * parts -- "Whatever | Official Video", "Oasis | Whatever" -- so a part
     * that is only noise is dropped and the rest are read as halves.
     */
    private fun withoutNoise(videoTitle: String): String {
        val parts = removeBrackets(spaced(videoTitle))
            .split(BAR)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isNoise(it, strict = true) }
        return dropNoiseTail(parts.joinToString(" - ")).collapse()
    }

    private fun dashHalves(title: String): Pair<String, String>? {
        val at = title.indexOf(" - ")
        if (at <= 0) return null
        val left = title.substring(0, at).trim()
        val right = title.substring(at + 3).trim()
        return if (key(left).isEmpty() || key(right).isEmpty()) null else left to right
    }

    /** `BLACKPINK 'Shut Down'`: the quoted part is the song, the rest the act. */
    private fun quotedHalves(title: String): Pair<String, String>? {
        val only = QUOTED.findAll(title).toList().singleOrNull() ?: return null
        val song = only.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val act = title.removeRange(only.range).trim(' ', '-', ':', '|').collapse()
        return if (key(act).isEmpty()) null else song to act
    }

    // ---- noise -------------------------------------------------------------

    /** Brackets whose content is noise or a guest credit, removed whole. */
    private fun removeBrackets(text: String): String =
        BRACKETED.replace(text) { match ->
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            if (isNoise(inner, strict = false) || CREDIT.containsMatchIn(inner)) " " else match.value
        }.collapse()

    /**
     * Trailing noise, peeled off until none is left: " - Remastered 2014",
     * " Official Video".
     */
    private fun dropNoiseTail(text: String): String {
        var t = text.collapse()
        while (true) {
            val dash = t.lastIndexOf(" - ")
            val shorter = if (dash > 0 && isNoise(t.substring(dash + 3), strict = true)) {
                t.substring(0, dash)
            } else {
                NOISE_TAIL.find(t)?.let { t.substring(0, it.range.first) }
            }?.trim()
            if (shorter.isNullOrEmpty() || shorter == t) return t
            t = shorter
        }
    }

    /**
     * Whether a fragment says nothing about which song this is.
     *
     * STRICT is for a fragment that could be a title in its own right -- the
     * far side of a dash, a part between bars. There a word like "Video" or a
     * number like "2002" is a real song's whole name, so it takes a word that
     * is never a title ("Official", "Remastered", "HD") to make it noise. In
     * brackets the bar is lower: "(Audio)" and "(1994)" are never the song.
     */
    private fun isNoise(fragment: String, strict: Boolean): Boolean {
        val words = words(fragment.lowercase().replace("m/v", "mv"))
        if (words.isEmpty()) return true
        val known = words.all { it in STRONG_NOISE || it in WEAK_NOISE || it.all(Char::isDigit) }
        return known && (!strict || words.any { it in STRONG_NOISE })
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }

    private fun unquote(text: String): String {
        val t = text.trim()
        if (t.length < 2) return t
        val enclosed = QUOTE_PAIRS.any { (open, close) -> t.first() == open && t.last() == close }
        return if (enclosed) t.substring(1, t.length - 1).trim() else t
    }

    private fun spaced(text: String): String = DASH.replace(text.collapse(), " - ")

    private fun String.collapse(): String = replace(WHITESPACE, " ").trim()

    private const val MAX_NAME = 60

    private val WHITESPACE = Regex("""\s+""")
    private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val MARKS = Regex("""\p{Mn}+""")
    private val AND_WORD = Regex("""\band\b""")
    private val DASH = Regex("""\s+[-–—‒―]+\s+""")
    private val BAR = Regex("""\s*[|｜]\s*""")
    private val CAMEL = Regex("""(\p{Ll})(\p{Lu})""")

    // Every bracket escaped, inside a class and out. Android's regex engine is
    // ICU's, which reads a bare "[" inside a class as the start of a nested one.
    private val BRACKETED = Regex("""\(([^()]*)\)|\[([^\[\]]*)\]|【([^】]*)】|\{([^\{\}]*)\}""")
    private val ANY_BRACKETS = Regex("""\([^()]*\)|\[[^\[\]]*\]|【[^】]*】|\{[^\{\}]*\}""")

    private val FEAT_TAIL = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val CREDIT = Regex(
        """^\s*(?:feat\.?|ft\.?|featuring|with|prod\.?(?:\s+by)?|produced\s+by)\s+\S""",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_TAIL = Regex(
        """\s+(?:official\s+(?:music\s+|lyrics?\s+|hd\s+)?(?:video|audio|visuali[sz]er)|(?:music|lyrics?)\s+video|hd|hq|4k|m/?v)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTIST_JOINS = Regex(
        """\s*[,&+×]\s*|\s+(?:and|x|vs\.?|feat\.?|ft\.?|featuring|with)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private val QUOTED = Regex(""""([^"]+)"|“([^”]+)”|‘([^’]+)’|「([^」]+)」|(?<![\p{L}\p{N}])'(.+?)'(?![\p{L}\p{N}])""")
    private val QUOTE_PAIRS = listOf('"' to '"', '“' to '”', '‘' to '’', '\'' to '\'', '「' to '」', '«' to '»')
    private val QUOTE_CHARS = setOf('"', '“', '”', '‘', '’', '「', '」', '«', '»')

    private val MUSIC_VIDEO_BY = Regex(
        """music video by (.+?) performing (.+?)\s*\.?\s*(?:\((?:c|p)\)|©|℗|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val VIDEO_FOR = Regex(
        """official (?:music |lyric )?video for [“"‘']?(.+?)[”"’']? (?:performed )?by (.+?)\s*(?:[.!,(]|\s(?:from|off|taken)\b|$)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
    )
    private val NAMED = listOf(
        Regex("""^\s*listen to (.+?)\s*:""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("""^\s*follow (.+?)\s*:?\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
        Regex("""subscribe to (?:the )?official (.+?) (?:youtube )?channel""", RegexOption.IGNORE_CASE),
        Regex("""^\s*watch more (.+?) videos""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)),
    )
    private val CHANNEL_NOISE = listOf(
        Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE),
        Regex("""\s*vevo$""", RegexOption.IGNORE_CASE),
        Regex("""[\s\-|(]*\bofficial\b(?:\s+(?:youtube\s+)?channel)?\)?$""", RegexOption.IGNORE_CASE),
        Regex("""^official\s+""", RegexOption.IGNORE_CASE),
    )

    /** Words that are never any part of a song's title. */
    private val STRONG_NOISE = setOf(
        "official", "officiel", "officielle", "oficial", "offizielles", "ufficiale",
        "remaster", "remastered", "remasterizado", "remasterizada", "remasterisé",
        "visualizer", "visualiser", "visualizador",
        "lyric", "lyrics", "letra", "letras", "paroles",
        "hd", "hq", "uhd", "4k", "8k", "1080p", "720p", "mv", "videoclip", "videoclipe",
    )

    /** Words that are noise beside the ones above, and could be a title alone. */
    private val WEAK_NOISE = setOf(
        "music", "musik", "musica", "música", "video", "vídeo", "clip", "audio", "áudio",
        "explicit", "clean", "version", "the", "a", "an", "with", "high", "quality",
        "color", "colour", "coded", "sub", "subs", "subtitles", "full", "new",
    )

    /** Words that mark a template line as naming something other than the act. */
    private val NOT_AN_ACT = setOf(
        "here", "now", "album", "playlist", "full", "more", "song", "songs", "track", "tracks",
        "video", "videos", "spotify", "apple", "amazon", "deezer", "tidal", "us", "this", "our",
        "my", "latest", "new", "instagram", "facebook", "twitter", "tiktok",
    )
}
