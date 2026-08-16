package app.roam.data.catalog.metadata

import app.roam.data.source.SourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics as a file sitting beside the music, which is how every other player
 * expects to find them.
 *
 * `03 Time - Pink Floyd.mp3` is accompanied by `03 Time - Pink Floyd.lrc` when
 * the words are timed and `.txt` when they are not. Kodi, Plex, Poweramp and
 * Navidrome all read exactly this, so lyrics Roam finds are not locked inside
 * Roam -- and lyrics someone else put there are found without a lookup.
 *
 * Same shape as the artwork rules deliberately (invariant 6c): the source wins
 * over the internet, and Roam never destroys what is already there (6d).
 */
@Singleton
class LyricFiles @Inject constructor() {

    /**
     * Reads whatever is beside the track, preferring timed words.
     *
     * Returns null when the folder cannot be resolved at all, which is a
     * different thing from an empty result: a tag that does not match a folder
     * name is common and must not be treated as "no lyrics exist".
     */
    suspend fun read(
        provider: SourceProvider,
        root: String,
        folderPath: String,
        fileName: String,
    ): FoundLyrics? = withContext(Dispatchers.IO) {
        val folder = provider.resolveFolder(root, folderPath.segments(), create = false)
            ?: return@withContext null

        val base = fileName.substringBeforeLast('.', fileName)

        // .lrc first: a folder holding both means someone has timed words and a
        // plain copy, and the timed ones are strictly more useful.
        val synced = provider.findInFolder(folder, listOf("$base.lrc"))
            ?.let { runCatching { provider.read(it.remoteId).decodeToString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        val plain = provider.findInFolder(folder, listOf("$base.txt"))
            ?.let { runCatching { provider.read(it.remoteId).decodeToString() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        // Timed words carry their own plain text, so a .lrc alone is enough --
        // the reader strips the stamps.
        val derivedPlain = plain ?: synced?.let { lrc ->
            LrcLib.parseLrc(lrc).joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
        }

        FoundLyrics(plain = derivedPlain, synced = synced).takeUnless { it.isEmpty }
    }

    /**
     * Writes lyrics beside the track, and only if nothing is there already.
     *
     * Never overwrites and never archives, unlike the cover editor. A cover is
     * replaced deliberately by someone looking at it; this runs on its own
     * behalf in the background, and quietly renaming a file in someone's music
     * folder is not something a background pass has any business doing.
     *
     * @return true when a file was actually written.
     */
    suspend fun write(
        provider: SourceProvider,
        root: String,
        folderPath: String,
        fileName: String,
        lyrics: FoundLyrics,
        cacheDir: File,
    ): Boolean = withContext(Dispatchers.IO) {
        if (lyrics.isEmpty) return@withContext false

        // create = false, exactly as the artist photo pass does. A track whose
        // folder cannot be resolved must not cause a stray folder to appear in
        // someone's music library.
        val segments = folderPath.segments()
        val folder = provider.resolveFolder(root, segments, create = false)
            ?: return@withContext false

        val base = fileName.substringBeforeLast('.', fileName)
        val body = lyrics.synced ?: lyrics.plain ?: return@withContext false
        val name = if (lyrics.synced != null) "$base.lrc" else "$base.txt"

        if (provider.findInFolder(folder, listOf(name)) != null) return@withContext false

        val temp = File(cacheDir, "lyrics/$name").apply {
            parentFile?.mkdirs()
            writeText(body)
        }
        try {
            provider.write(root, segments, name, temp)
            true
        } catch (e: Exception) {
            false
        } finally {
            temp.delete()
        }
    }

    /**
     * Empty segments are dropped: a track sitting directly in the root has a
     * folderPath of "", and "".split('/') is a list containing one blank, which
     * resolveFolder would go looking for as a folder with no name.
     */
    private fun String.segments(): List<String> =
        split('/').filter { it.isNotBlank() }
}
