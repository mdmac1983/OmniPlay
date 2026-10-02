package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Music video info — the music-video equivalent of TMDB. Both sources are free and need no account:
 *  - iTunes / Apple Music music-video search: official title and artist, release year, genre, length, 16:9 artwork.
 *  - TheAudioDB (public key): director, record label, album, description, YouTube link.
 * Matches are stored with the TMDB matches under "mv:<file id>".
 */
object MvDb {
    private const val AUDIODB = "https://www.theaudiodb.com/api/v1/json/123"

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", Net.UA)
        try { return when (c.responseCode) { 200 -> c.inputStream.bufferedReader().readText(); 404 -> null; else -> throw Exception("HTTP ${c.responseCode}") } }
        finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun norm(s: String) = s.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), " ").trim()

    private val SEP = Regex("\\s+[-–—]+\\s+")

    /** Guesses (artist, title) from the file name, or the folder it's in: "Artist - Title (Official Video).mp4". */
    fun guess(o: JSONObject): Pair<String, String> {
        val name = Lyrics.cleanTitle(o.optString("n").substringBeforeLast('.').replace(Regex("\\[[^]]*]"), "").replace(Regex("(?i)\\(\\d+ ?x ?\\d+\\)|\\b(1080p|720p|480p|2160p|4k|hd)\\b"), ""))
            .replace(Regex("\\s{2,}"), " ").trim()
        val parts = name.split(SEP, limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) return parts[0].trim() to parts[1].trim()
        val folder = o.optString("f").split('/').lastOrNull { it.isNotBlank() } ?: ""
        val generic = Regex("(?i)music ?videos?|videos?|mv|clips?")
        return (if (folder.isNotBlank() && !generic.matches(folder.trim())) folder.trim() else "") to name
    }

    fun match(o: JSONObject): JSONObject? {
        val (artist, title) = guess(o)
        if (title.isBlank()) return null
        val a1 = if (artist.isNotEmpty()) firstArtist(artist) else ""
        // a busy service (iTunes answers 403 when asked too often) mustn't stop the other lookups
        return (try { itunes(a1, title) } catch (_: Exception) { null }) ?: if (a1.isNotEmpty()) try { audioDbOnly(a1, title) } catch (_: Exception) { null } else null
    }

    private fun itunes(artist: String, title: String): JSONObject? {
        val arr = get("https://itunes.apple.com/search?media=musicVideo&entity=musicVideo&limit=15&term=" + enc("$artist $title".trim()))
            ?.let { JSONObject(it).optJSONArray("results") } ?: JSONArray()
        val na = norm(artist); val nt = norm(title)
        var best: JSONObject? = null; var bestScore = 0
        for (k in 0 until arr.length()) {
            val r = arr.getJSONObject(k)
            val ra = norm(firstArtist(r.optString("artistName"))); val rt = norm(r.optString("trackName").replace(Regex("\\s*[(\\[].*"), ""))
            var s = 0
            if (rt == nt) s += 6 else if (rt.startsWith(nt) || nt.startsWith(rt)) s += 4 else if (rt.contains(nt) || nt.contains(rt)) s += 2
            if (na.isEmpty()) { if (s > 0 && nt.contains(ra)) s += 3 }
            else if (ra == na) s += 5 else if (ra.contains(na) || na.contains(ra)) s += 3 else s -= 4
            if (r.optString("trackName").contains(Regex("(?i)\\b(live|remix|lyric|karaoke|behind the scenes|announcement)\\b")) && !nt.contains(Regex("live|remix|lyric"))) s -= 2
            if (s > bestScore) { bestScore = s; best = r }
        }
        val b = best?.takeIf { bestScore >= 7 } ?: return null
        return complete(slim(b))
    }

    private fun slim(b: JSONObject) = JSONObject().put("type", "mv").put("src", "itunes").put("id", b.optLong("trackId"))
        .put("title", b.optString("trackName")).put("artist", b.optString("artistName"))
        .put("year", b.optString("releaseDate").take(4)).put("genres", b.optString("primaryGenreName"))
        .put("dur", b.optLong("trackTimeMillis")).put("runtime", (b.optLong("trackTimeMillis") / 60000).toInt())
        .put("art", b.optString("artworkUrl100").replace(Regex("/\\d+x\\d+bb\\.jpg$"), "/1280x720bb.jpg"))
        .put("url", b.optString("trackViewUrl")).put("rating", 0.0).put("poster", "").put("overview", "").put("cert", if (b.optString("trackExplicitness") == "explicit") "EXPLICIT" else "")

    /** Adds TheAudioDB details to a chosen iTunes result. */
    fun complete(m: JSONObject): JSONObject { try { audioDb(m) } catch (_: Exception) {}; m.put("detailed", true); return m }

    /** Free-text search for "Fix match": music videos on iTunes. */
    fun search(q: String): List<JSONObject> {
        val arr = get("https://itunes.apple.com/search?media=musicVideo&entity=musicVideo&limit=20&term=" + enc(q.trim()))
            ?.let { JSONObject(it).optJSONArray("results") } ?: return emptyList()
        return (0 until arr.length()).map { slim(arr.getJSONObject(it)) }
    }

    /** Not on iTunes: TheAudioDB alone (only when it knows a music video for that song). */
    private fun audioDbOnly(artist: String, title: String): JSONObject? {
        val t = get("$AUDIODB/searchtrack.php?s=${enc(artist)}&t=${enc(title)}")?.let { JSONObject(it).optJSONArray("track") } ?: return null
        val r = (0 until t.length()).map { t.getJSONObject(it) }.firstOrNull { it.optString("strMusicVid").let { v -> v.isNotBlank() && v != "null" } } ?: return null
        if (norm(firstArtist(r.optString("strArtist"))) != norm(artist)) return null
        fun s(k: String) = r.optString(k).trim().replace(Regex("â€.?"), "-").takeIf { it.isNotBlank() && it != "null" } ?: ""
        val dur = s("intDuration").toLongOrNull() ?: 0L
        val out = JSONObject().put("type", "mv").put("src", "audiodb").put("id", s("idTrack").toLongOrNull() ?: 0L)
            .put("title", s("strTrack")).put("artist", s("strArtist")).put("year", "").put("genres", s("strGenre"))
            .put("dur", dur).put("runtime", (dur / 60000).toInt()).put("art", s("strTrackThumb").ifEmpty { s("strMusicVidScreen1") })
            .put("url", "").put("rating", 0.0).put("poster", "").put("overview", "").put("cert", "")
        audioDb(out); out.put("detailed", true)
        return out
    }

    /** Extra details from TheAudioDB (director, label, album, description, YouTube link). */
    private fun audioDb(m: JSONObject) {
        val t = get("$AUDIODB/searchtrack.php?s=${enc(m.optString("artist"))}&t=${enc(m.optString("title"))}")?.let { JSONObject(it).optJSONArray("track") } ?: return
        val r = (0 until t.length()).map { t.getJSONObject(it) }.firstOrNull { it.optString("strMusicVid").isNotBlank() } ?: t.optJSONObject(0) ?: return
        fun s(k: String) = r.optString(k).trim().replace(Regex("â€.?"), "-").takeIf { it.isNotBlank() && it != "null" } ?: ""
        m.put("director", s("strMusicVidDirector")).put("label", s("strMusicVidCompany")).put("album", s("strAlbum"))
            .put("youtube", s("strMusicVid")).put("views", r.optString("intMusicVidViews").toLongOrNull() ?: 0L)
        s("strDescriptionEN").takeIf { it.isNotEmpty() }?.let { m.put("overview", it) }
        if (m.optString("genres").isEmpty()) m.put("genres", s("strGenre"))
        s("strGenre").takeIf { it.isNotEmpty() && !m.optString("genres").contains(it, true) }?.let { m.put("style", it) }
    }
}
