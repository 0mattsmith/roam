package app.roam.core.model

/**
 * Turning an artist or album name into a folder, and finding the folder it
 * already has.
 *
 * Both halves matter and they pull against each other. Writing needs a name a
 * filesystem and Drive will both accept; matching needs to see past the
 * differences that sanitising creates, so that "100 Hits: 80s Pop" finds the
 * folder somebody already made called "100 Hits - 80s Pop".
 *
 * What this must never do is match two DIFFERENT records. "100 Hits: 80s Pop"
 * and "100 Hits: 90s Pop" differ by one character and are different albums, so
 * digits are part of the identity and punctuation is not.
 */
object FolderNames {

    /**
     * Characters no folder may contain.
     *
     * The Windows set, which is the strict one, plus the separators. Drive
     * itself is more permissive, but a library that cannot be mounted or
     * rclone'd onto a Windows machine is a library with a trap in it.
     */
    private const val ILLEGAL = "/\\?*<>|\"\r\n\t"

    /**
     * A name a folder can actually be called.
     *
     * A colon becomes " - " because that is what people write by hand anyway --
     * "100 Hits: 80s Pop" and "100 Hits - 80s Pop" are the same album, and
     * picking the readable form means the folder looks deliberate rather than
     * mangled.
     *
     * A trailing dot is removed outright. Windows silently strips it, which
     * means the folder Roam thinks it created is not the folder that exists,
     * and on Drive the result is a folder that cannot be opened at all.
     */
    fun sanitise(name: String): String {
        var out = name.trim()
        // " - " rather than "-": a colon separates, and the spaces are what
        // keep "100 Hits - 80s Pop" reading as two parts rather than a
        // hyphenated word.
        out = out.replace(Regex("\\s*:\\s*"), " - ")
        out = out.filter { it !in ILLEGAL }
        out = out.replace(Regex("\\s+"), " ").trim()
        // Repeated: "Etc.." needs both gone, and a name that was only dots
        // becomes empty rather than a folder nobody can open.
        out = out.trimEnd('.', ' ').trim()
        return out
    }

    /**
     * What two spellings of one name have in common.
     *
     * Letters and digits only, lower case -- so the punctuation that sanitising
     * changes stops being a difference, while the digits that distinguish one
     * volume from another are kept exactly. "100 Hits: 80s Pop", "100 Hits -
     * 80s Pop" and "100 Hits 80s Pop" all key the same; "100 Hits: 90s Pop"
     * does not, and must not.
     */
    fun key(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /** A folder name split into what it is called and the year it announces. */
    data class Parsed(val name: String, val year: Int?)

    /**
     * Reads `Be Here Now (1997)` as a name and a year.
     *
     * Only a TRAILING parenthesised four-digit number counts. "Definitely Maybe
     * (Chasing the Sun Edition) (1994)" keeps the edition in its name and gives
     * up only the 1994, because an edition is part of what the record is called
     * and a year is not.
     */
    fun parse(folderName: String): Parsed {
        val match = Regex("^(.*?)\\s*\\((\\d{4})\\)\\s*$").find(folderName.trim())
            ?: return Parsed(folderName.trim(), null)
        return Parsed(match.groupValues[1].trim(), match.groupValues[2].toIntOrNull())
    }

    /** `Be Here Now` + 1997 -> `Be Here Now (1997)`, sanitised. */
    fun albumFolder(albumTitle: String, year: Int?): String {
        val name = sanitise(albumTitle)
        return if (year != null) "$name ($year)" else name
    }

    /**
     * The folder among [existing] that already holds this album, if any.
     *
     * Matching is on the key, so punctuation differences are invisible, and on
     * the year, so two pressings of one record stay apart. An unknown year on
     * either side is not a mismatch -- plenty of folders carry no year, and
     * refusing those would create a duplicate beside every one of them.
     *
     * Exact wins over near, and among near matches the one whose year agrees
     * wins: with both "Be Here Now" and "Be Here Now (1997)" sitting there,
     * knowing the year should pick the right one rather than either.
     */
    fun matchAlbum(albumTitle: String, year: Int?, existing: List<String>): String? {
        val wanted = key(sanitise(albumTitle))
        if (wanted.isEmpty()) return null

        val candidates = existing.filter { key(parse(it).name) == wanted }
        if (candidates.isEmpty()) return null

        // Exactly what would be written, if it is there.
        val ideal = albumFolder(albumTitle, year)
        candidates.firstOrNull { it.equals(ideal, ignoreCase = true) }?.let { return it }

        if (year != null) {
            // A folder announcing a DIFFERENT year is a different pressing and
            // must not be reused, however alike the names are.
            val agreeing = candidates.filter { parse(it).year.let { y -> y == null || y == year } }
            return agreeing.firstOrNull { parse(it).year == year } ?: agreeing.firstOrNull()
        }

        return candidates.firstOrNull()
    }

    /** The same, for an artist folder, where there is no year to weigh. */
    fun matchArtist(artistName: String, existing: List<String>): String? {
        val wanted = key(sanitise(artistName))
        if (wanted.isEmpty()) return null
        val ideal = sanitise(artistName)
        return existing.firstOrNull { it.equals(ideal, ignoreCase = true) }
            ?: existing.firstOrNull { key(it) == wanted }
    }

    /** What a compilation files under when nobody has said otherwise. */
    const val VARIOUS_ARTISTS = "Various Artists"
}
