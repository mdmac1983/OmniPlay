package app.orionmd.omniplay

import android.content.Context
import java.io.File

/**
 * What Media Hub keeps on the device, by category (Settings → Saved on this device): look through it and clear it.
 * Everything here is in the app's private storage and encrypted; downloads saved to the phone's Music / Movies /
 * Pictures folders aren't counted here.
 */
object Storage {
    /** [view]: "images" (grid), "text" (tap to read), "titles" (a list of what's saved), or "" (no viewer). */
    class Cat(val key: String, val title: String, val desc: String, val view: String, val clearNote: String?, val files: (Context) -> List<File>)

    private fun list(d: File, f: (File) -> Boolean = { true }) = d.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") && !it.name.endsWith(".part") && f(it) } ?: emptyList()
    private fun walk(d: File?) = if (d != null && d.exists()) d.walkTopDown().filter { it.isFile }.toList() else emptyList()

    val CATS = listOf(
        Cat("thumbs", "Song & video pictures", "Covers inside your song files and frames from your videos.", "images",
            "They're made again as you browse.") { c -> list(File(c.filesDir, "art")) { !it.name.endsWith("_l.jpg") } },
        Cat("large", "Large player artwork", "Sharper copies for the Now Playing screen.", "images",
            "They're made again when you play.") { c -> list(File(c.filesDir, "art")) { it.name.endsWith("_l.jpg") } },
        Cat("covers", "Album covers (Deezer / iTunes)", "Covers found online for your songs and albums.", "images",
            "They download again as you browse (or with Sync all).") { c -> list(File(c.filesDir, "mcover")) },
        Cat("posters", "Movie & TV pictures", "Posters, backdrops, cast photos and episode pictures from TMDB.", "images",
            "They download again as you browse (or with Sync all).") { c -> list(File(c.filesDir, "tmdbimg")) },
        Cat("songinfo", "Song info & album details", "Genre, year, length and album track lists (Deezer / iTunes).", "titles",
            "Songs are looked up again automatically when online.") { c -> listOf(File(c.filesDir, "musicmeta.json"), File(c.filesDir, "albuminfo.json")).filter { it.exists() } },
        Cat("tmdbinfo", "Movie & TV info", "TMDB matches, details, cast, trailers and episode lists. Your own Fix match choices are kept when clearing.", "titles",
            "Videos are matched again automatically when online.") { c -> listOf(File(c.filesDir, "tmdb.json")).filter { it.exists() } + list(File(c.filesDir, "tmdbx")) },
        Cat("lyrics", "Lyrics", "Song lyrics from LRCLIB (and songs that have none).", "text",
            "Lyrics are looked up again when you play a song.") { c -> list(File(c.filesDir, "lyrics")) },
        Cat("subs", "Downloaded subtitles", "Subtitles from Wyzie Subs for your movies and episodes.", "text",
            "Download them again from the movie / show page or Sync all.") { c -> list(File(c.filesDir, "wsubs")) },
        Cat("offline", "Offline downloads", "Songs, videos and episodes saved in the app for offline playback. Manage them on the Offline page.", "",
            null) { c -> walk(File(c.getExternalFilesDir(null) ?: c.filesDir, "offline")) },
        Cat("eraser", "Photo eraser model", "Downloaded once for the Gallery's eraser.", "",
            "It downloads again (about 28 MB) the next time you use the eraser.") { c -> walk(File(c.filesDir, "models")) },
        Cat("logos", "Your skin logos", "Pictures you added to skins.", "images", null) { c -> list(File(c.filesDir, "skinlogos")) },
        Cat("library", "Library lists", "The list of files in each source, so the library opens instantly and works offline.", "",
            null) { c -> list(File(c.filesDir, "lib")) + list(c.filesDir) { it.name.startsWith("added_") } },
        Cat("temp", "Temporary files", "Short-lived copies (subtitles for the player, movie maker work files…).", "",
            "Nothing is lost.") { c -> walk(c.cacheDir) },
    )

    fun cat(key: String) = CATS.first { it.key == key }

    /** (count, bytes) per category. */
    fun scan(ctx: Context): Map<String, Pair<Int, Long>> = CATS.associate { c -> val f = c.files(ctx); c.key to (f.size to f.sumOf { it.length() }) }

    /** Clears one category. Movie & TV info keeps your own Fix match choices. */
    fun clear(ctx: Context, store: Store, key: String) {
        when (key) {
            "tmdbinfo" -> {
                store.updateTmdb { c -> c.keys().asSequence().toList().forEach { k -> if (c.optJSONObject(k)?.optBoolean("manual") != true) c.remove(k) } }
                File(ctx.filesDir, "tmdbx").listFiles()?.forEach { it.delete() }
            }
            else -> cat(key).files(ctx).forEach { it.delete() }
        }
        if (key in setOf("thumbs", "large", "covers", "posters")) ImageLoader.clear()
    }

    /** Readable text of a saved lyrics / subtitle file. */
    fun text(f: File): String {
        val s = Vault.readText(f) ?: return "(can't read this file)"
        if (f.parentFile?.name != "lyrics") return s
        return try {
            val o = org.json.JSONObject(s)
            when {
                o.optBoolean("none") -> "(no lyrics found for this song)"
                o.optBoolean("instrumental") -> "(instrumental)"
                else -> o.optString("plainLyrics").takeIf { it.isNotEmpty() && it != "null" } ?: o.optString("syncedLyrics")
            }
        } catch (_: Exception) { s }
    }
}
