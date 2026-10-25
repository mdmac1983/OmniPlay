package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Podcasts from Podcast Index (podcastindex.org, free API key + secret in Settings → 🔑 API keys & accounts).
 * Search shows and music podcasts, see what's trending, subscribe, and play episodes in the music player
 * (with the visualizer). Subscriptions are saved on the device.
 */
object Podcasts {
    private const val BASE = "https://api.podcastindex.org/api/1.0"

    data class Show(val id: Long, val title: String, val author: String, val image: String, val description: String, val episodes: Int)
    data class Episode(val id: Long, val title: String, val url: String, val image: String, val date: Long, val duration: Long, val description: String, val orig: String = url)

    fun hasKeys(st: AppState) = st.store.secret("podKey").isNotBlank() && st.store.secret("podSecret").isNotBlank()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Signed GET (Podcast Index: X-Auth-Key, X-Auth-Date, Authorization = sha1(key + secret + date)). */
    private fun get(key: String, secret: String, path: String): JSONObject {
        val date = (System.currentTimeMillis() / 1000).toString()
        val c = URL("$BASE$path").openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", Net.UA)
        c.setRequestProperty("X-Auth-Key", key.trim()); c.setRequestProperty("X-Auth-Date", date)
        c.setRequestProperty("Authorization", sha1(key.trim() + secret.trim() + date))
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw Exception("Podcast Index rejected the key or secret — check them in Settings")
            if (code !in 200..299) throw Exception("Podcast Index error $code")
            return JSONObject(text)
        } finally { c.disconnect() }
    }
    private fun https(u: String) = if (u.startsWith("http://")) "https://" + u.removePrefix("http://") else u
    private fun image(u: String): String = https(u).also { if (it.startsWith("https://")) try { MusicMeta.extraHosts.add(URL(it).host) } catch (_: Exception) {} }

    private fun shows(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { k -> a!!.optJSONObject(k)?.let { o ->
        Show(o.optLong("id"), o.optString("title").trim(), o.optString("author").ifBlank { o.optString("ownerName") }.trim(),
            image(o.optString("artwork").ifBlank { o.optString("image") }), o.optString("description").replace(Regex("<[^>]+>"), "").trim(), o.optInt("episodeCount"))
    } }.filter { it.id > 0 && it.title.isNotEmpty() }

    fun search(st: AppState, term: String, music: Boolean = false): List<Show> =
        shows(get(st.store.secret("podKey"), st.store.secret("podSecret"), (if (music) "/search/music/byterm?q=" else "/search/byterm?q=") + enc(term) + "&max=40").optJSONArray("feeds"))
    fun trending(st: AppState): List<Show> = shows(get(st.store.secret("podKey"), st.store.secret("podSecret"), "/podcasts/trending?max=30&lang=en").optJSONArray("feeds"))

    fun episodes(st: AppState, feedId: Long, fallbackImage: String): List<Episode> {
        val a = get(st.store.secret("podKey"), st.store.secret("podSecret"), "/episodes/byfeedid?id=$feedId&max=100").optJSONArray("items") ?: JSONArray()
        return (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { o ->
            Episode(o.optLong("id"), o.optString("title").trim(), https(o.optString("enclosureUrl")), image(o.optString("image").ifBlank { o.optString("feedImage") }).ifBlank { fallbackImage },
                o.optLong("datePublished") * 1000, o.optLong("duration") * 1000, o.optString("description").replace(Regex("<[^>]+>"), "").trim(), o.optString("enclosureUrl"))
        } }.filter { it.url.startsWith("https://") }
    }

    // ---------- subscriptions (saved on the device) ----------
    fun subscriptions(st: AppState): List<Show> = try { JSONArray(st.pref("podSubs", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Show(it.optLong("id"), it.optString("title"), it.optString("author"), image(it.optString("image")), "", it.optInt("episodes")) } } } catch (_: Exception) { emptyList() }
    fun isSubscribed(st: AppState, id: Long) = subscriptions(st).any { it.id == id }
    fun toggle(st: AppState, s: Show) {
        val l = subscriptions(st).filter { it.id != s.id }.toMutableList()
        val adding = !isSubscribed(st, s.id)
        if (adding) l.add(0, s)
        st.setPref("podSubs", JSONArray(l.map { JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).put("image", it.image).put("episodes", it.episodes) }).toString())
        st.say(if (adding) "Subscribed to ${s.title}" else "Unsubscribed from ${s.title}")
    }

    /** Plays episodes (newest first from [start]) in the music player. */
    fun play(st: AppState, show: Show, eps: List<Episode>, start: Int) {
        val q = JSONArray()
        for (e in eps) q.put(JSONObject().put("i", e.id).put("t", e.title).put("n", e.title).put("a", show.title).put("al", "Podcast")
            .put("u", e.url).put("th", 0).apply { if (e.image.isNotEmpty()) put("cv", e.image).put("pcv", true); if (e.duration > 0) put("d", e.duration) })
        Player.playQueue(st.ctx, "podcasts", q, start.coerceIn(0, maxOf(0, eps.size - 1)))
        st.pollPlayer()
    }
}
