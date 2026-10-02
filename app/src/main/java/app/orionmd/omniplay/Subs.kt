package app.orionmd.omniplay

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online subtitles from Wyzie Subs (sub.wyzie.io, free API key in Settings → Playback → Subtitles).
 * Looked up by the movie's / show's TMDB id (+ season and episode), downloaded once and saved encrypted on the device,
 * like lyrics. The video player gets a temporary plain copy while it plays.
 */
object Subs {
    private const val BASE = "https://sub.wyzie.io/search"

    fun dir(store: Store) = File(store.filesDir, "wsubs").apply { mkdirs() }
    private fun file(store: Store, id: Long, lang: String) = File(dir(store), "${id}_$lang.srt")
    fun saved(store: Store, id: Long, lang: String) = file(store, id, lang).exists()
    fun count(store: Store) = dir(store).listFiles()?.size ?: 0
    fun clear(store: Store) { dir(store).listFiles()?.forEach { it.delete() } }

    private fun http(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", Net.UA)
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw Exception("Wyzie Subs rejected the key — check it in Settings")
            if (code == 429) throw Exception("Wyzie Subs daily limit reached")
            if (code !in 200..299) throw Exception("Wyzie Subs error $code")
            return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    /**
     * Finds and saves subtitles for one video. [tmdbId] is the movie's or show's TMDB id; season / episode for TV.
     * Returns true when a subtitle is saved (or already was).
     */
    fun fetch(store: Store, key: String, itemId: Long, tmdbId: Int, season: Int, episode: Int, lang: String, hi: Boolean): Boolean {
        if (key.isBlank() || tmdbId <= 0) return false
        if (saved(store, itemId, lang)) return true
        val url = "$BASE?id=$tmdbId&language=$lang&format=srt&key=${java.net.URLEncoder.encode(key.trim(), "UTF-8")}" +
            (if (season > 0 && episode > 0) "&season=$season&episode=$episode" else "")
        val arr = JSONArray(http(url))
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("url").startsWith("https://") }
        if (list.isEmpty()) return false
        // best: the wanted hearing-impaired setting, SRT, most downloaded
        val best = list.sortedWith(compareBy({ it.optBoolean("isHearingImpaired") != hi }, { it.optString("format", "srt") != "srt" }, { -it.optInt("downloadCount") })).first()
        val text = http(best.getString("url"))
        if (text.isBlank() || !text.contains("-->")) return false
        Vault.writeBytes(file(store, itemId, lang), text.toByteArray())
        return true
    }

    /** A plain temporary copy for the video player (in the app's cache; replaced each time). */
    fun playable(store: Store, cacheDir: File, id: Long, lang: String): File? {
        val f = file(store, id, lang); if (!f.exists()) return null
        val bytes = Vault.readBytes(f) ?: return null
        val out = File(File(cacheDir, "wsubs").apply { mkdirs() }, "${id}_$lang.srt")
        out.writeBytes(bytes); return out
    }
}
