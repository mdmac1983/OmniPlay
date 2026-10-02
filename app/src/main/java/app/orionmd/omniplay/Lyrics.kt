package app.orionmd.omniplay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Song lyrics from LRCLIB (lrclib.net — free, no account). Time-synced lyrics are preferred so the player can follow
 * along line by line; otherwise plain text. Results (and "not found") are saved encrypted on the device, so a song is
 * looked up only once and its lyrics also show offline.
 */
object Lyrics {
    class L(val plain: String, val synced: List<Pair<Long, String>>, val instrumental: Boolean) {
        val isEmpty get() = plain.isBlank() && synced.isEmpty() && !instrumental
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun file(ctx: Context, id: Long) = File(dir(ctx), "$id.json")
    fun savedCount(ctx: Context) = dir(ctx).listFiles()?.count { f -> try { Vault.readText(f)?.let { !JSONObject(it).optBoolean("none") } == true } catch (_: Exception) { false } } ?: 0
    fun clear(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    /** Saved result: null = never looked up (or a miss old enough to try again), L with isEmpty = not found. */
    fun cached(ctx: Context, id: Long): L? {
        val o = try { Vault.readText(file(ctx, id))?.let { JSONObject(it) } } catch (_: Exception) { null } ?: return null
        if (o.optBoolean("none")) return if (System.currentTimeMillis() - o.optLong("t") > 14L * 86_400_000) null else L("", emptyList(), false)
        return of(o)
    }
    fun has(ctx: Context, id: Long) = file(ctx, id).exists()

    private fun of(o: JSONObject) = L(o.optString("plainLyrics").takeIf { it != "null" } ?: "", parseLrc(o.optString("syncedLyrics").takeIf { it != "null" } ?: ""), o.optBoolean("instrumental"))

    /** Looks the song up online and saves the answer. Throws on network trouble (nothing is saved then). */
    fun fetch(ctx: Context, id: Long, title: String, artist: String, album: String, durSec: Long): L {
        val t = cleanTitle(title); val a = firstArtist(artist).trim()
        var best: JSONObject? = null
        if (t.isNotEmpty() && a.isNotEmpty()) {
            val q = "track_name=${enc(t)}&artist_name=${enc(a)}" + (if (album.isNotBlank()) "&album_name=${enc(album)}" else "") + (if (durSec > 0) "&duration=$durSec" else "")
            best = get("https://lrclib.net/api/get?$q")?.let { JSONObject(it) }?.takeIf { it.has("id") }
        }
        if (best == null && t.isNotEmpty()) {
            val q = if (a.isNotEmpty()) "track_name=${enc(t)}&artist_name=${enc(a)}" else "q=${enc(t)}"
            val arr = get("https://lrclib.net/api/search?$q")?.let { JSONArray(it) } ?: JSONArray()
            val cands = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { durSec <= 0 || it.optDouble("duration", 0.0) <= 0 || abs(it.optDouble("duration") - durSec) < 8 }
            best = cands.firstOrNull { it.optString("syncedLyrics").let { s -> s.isNotEmpty() && s != "null" } } ?: cands.firstOrNull()
        }
        val out = best ?: JSONObject().put("none", true).put("t", System.currentTimeMillis())
        Vault.writeText(file(ctx, id), out.toString())
        return if (best == null) L("", emptyList(), false) else of(best)
    }

    /** Lyrics from a .lrc file next to the song (device folder or cloud): saved like a downloaded result. */
    fun saveLrc(ctx: Context, id: Long, text: String): L {
        val synced = parseLrc(text)
        val o = JSONObject().put("id", 0).put("syncedLyrics", if (synced.isNotEmpty()) text else "null").put("plainLyrics", if (synced.isEmpty()) text else synced.joinToString("\n") { it.second })
        Vault.writeText(file(ctx, id), o.toString())
        return of(o)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    /** Body of a 200 answer, null for 404 (not found); throws for anything else so we retry later. */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", Net.UA)
        try {
            return when (c.responseCode) { 200 -> c.inputStream.bufferedReader().readText(); 404 -> null; else -> throw Exception("HTTP ${c.responseCode}") }
        } finally { c.disconnect() }
    }

    private val JUNK = Regex("(?i)\\s*[(\\[][^)\\]]*(official|video|audio|lyric|visuali[sz]er|remaster|hd|hq|explicit|clean|live at|mv)[^)\\]]*[)\\]]")
    private val FEAT = Regex("(?i)\\s*[(\\[]?\\s*\\b(feat\\.?|ft\\.?|featuring)\\s.*$")
    private val NUM = Regex("^\\s*\\d{1,3}\\s*[-._)]\\s*")
    fun cleanTitle(t: String) = t.replace(JUNK, "").replace(FEAT, "").replace(NUM, "").replace('_', ' ').trim()

    private val TAG = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    fun parseLrc(s: String): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (line in s.lines()) {
            val tags = TAG.findAll(line).toList(); if (tags.isEmpty()) continue
            val text = line.substring(tags.last().range.last + 1).trim()
            for (m in tags) {
                val frac = m.groupValues[3]; val ms = if (frac.isEmpty()) 0 else frac.padEnd(3, '0').take(3).toLong()
                out.add((m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms) to text)
            }
        }
        return out.sortedBy { it.first }
    }
}
