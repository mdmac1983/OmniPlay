package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Music you can get legally from the Discover screen:
 *  - "Get the full song": open a Deezer result in Deezer, or search for it in stores that sell DRM-free downloads.
 *  - Free downloads: full tracks that their artists offer for free download — Jamendo (Creative Commons; needs a free
 *    client id) and the Internet Archive's Live Music Archive (bands that allow sharing their concerts) and netlabel
 *    (Creative Commons) collections (no key).
 * Free downloads go through the download queue and are saved in Music → Media Hub → Free, where the library finds them.
 */
object FreeMusic {
    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 25000; c.setRequestProperty("User-Agent", Net.UA)
        try {
            if (c.responseCode !in 200..299) throw Exception("The service answered ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun objs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ---------- stores ----------
    /** (name, link) to get this song or album: Deezer itself, then stores selling DRM-free files. */
    fun storeLinks(o: JSONObject, album: Boolean): List<Pair<String, String>> {
        val artist = o.optString("a"); val q = enc(listOf(artist, if (album) o.optString("al").ifEmpty { o.optString("t") } else o.optString("t")).filter { it.isNotBlank() }.joinToString(" "))
        val l = ArrayList<Pair<String, String>>()
        if (o.optString("src") != "itunes") {
            if (album && o.optLong("alid") > 0) l += "Open in Deezer" to "https://www.deezer.com/album/${o.optLong("alid")}"
            else if (!album && o.optLong("id") > 0) l += "Open in Deezer" to "https://www.deezer.com/track/${o.optLong("id")}"
        }
        l += "Buy on Qobuz (hi-res FLAC)" to "https://www.qobuz.com/search?q=$q"
        l += "Buy on Bandcamp" to "https://bandcamp.com/search?q=$q"
        l += "Buy on 7digital" to "https://www.7digital.com/search?q=$q"
        l += "Buy on iTunes / Apple Music" to "https://music.apple.com/search?term=$q"
        l += "Buy on Amazon (MP3)" to "https://www.amazon.com/s?k=$q&i=digital-music"
        return l
    }

    // ---------- Deezer albums (search + track previews) ----------
    fun searchAlbums(q: String): List<JSONObject> = objs(JSONObject(get("https://api.deezer.com/search/album?q=${enc(q)}&limit=30")).optJSONArray("data")).map { x ->
        JSONObject().put("alid", x.optLong("id")).put("t", x.optString("title")).put("al", x.optString("title")).put("a", x.optJSONObject("artist")?.optString("name") ?: "")
            .put("cover", x.optString("cover_big")).put("n", x.optInt("nb_tracks"))
    }
    fun albumTracks(alid: Long): List<JSONObject> {
        val d = JSONObject(get("https://api.deezer.com/album/$alid"))
        val cover = d.optString("cover_big"); val al = d.optString("title")
        return objs(d.optJSONObject("tracks")?.optJSONArray("data")).map { x ->
            JSONObject().put("id", x.optLong("id")).put("t", x.optString("title")).put("a", x.optJSONObject("artist")?.optString("name") ?: "").put("al", al).put("alid", alid)
                .put("cover", cover).put("preview", x.optString("preview")).put("d", x.optInt("duration") * 1000L)
        }
    }

    // ---------- free downloads ----------
    /**
     * A free track: {src, key, t, a, al, cover, url (full song), d, lic}. [key] is unique per source and track, and
     * names the download.
     */
    fun jamendo(clientId: String, q: String): List<JSONObject> {
        if (clientId.isBlank()) return emptyList()
        val r = JSONObject(get("https://api.jamendo.com/v3.0/tracks/?client_id=${enc(clientId.trim())}&format=json&limit=40&audiodlformat=mp32&search=${enc(q)}"))
        r.optJSONObject("headers")?.let { h -> if (h.optString("status") == "failed") throw Exception(h.optString("error_message").ifEmpty { "Jamendo refused the client id" }) }
        return objs(r.optJSONArray("results")).filter { it.optBoolean("audiodownload_allowed", true) && it.optString("audiodownload").startsWith("https://") }.map { x ->
            JSONObject().put("src", "jamendo").put("key", "jam-" + x.optString("id")).put("t", x.optString("name")).put("a", x.optString("artist_name"))
                .put("al", x.optString("album_name")).put("cover", x.optString("image")).put("url", x.optString("audiodownload"))
                .put("stream", x.optString("audio")).put("d", x.optLong("duration") * 1000L).put("lic", x.optString("license_ccurl"))
        }
    }

    /** Internet Archive releases and live recordings (artists who allow free sharing, or Creative Commons). */
    fun archiveSearch(q: String): List<JSONObject> {
        // the Live Music Archive (bands that allow taping and sharing) and netlabels (Creative Commons releases) only
        val t = q.replace(Regex("[():\"]"), " ").trim()
        val query = "(creator:($t) OR title:($t) OR subject:($t)) AND (mediatype:etree OR (mediatype:audio AND collection:netlabels))"
        val url = "https://archive.org/advancedsearch.php?q=${enc(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=date&fl%5B%5D=collection&fl%5B%5D=mediatype&rows=30&output=json&sort%5B%5D=downloads+desc"
        return objs(JSONObject(get(url)).optJSONObject("response")?.optJSONArray("docs")).map { x ->
            fun s(k: String) = x.optJSONArray(k)?.optString(0) ?: x.optString(k)
            val coll = x.optJSONArray("collection")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(x.optString("collection"))
            JSONObject().put("src", "archive").put("ia", x.optString("identifier")).put("t", s("title")).put("a", s("creator")).put("date", s("date").take(10))
                .put("kind", if ("etree" in coll || x.optString("mediatype") == "etree") "Live recording" else "Release").put("cover", "https://archive.org/services/img/${x.optString("identifier")}")
        }
    }

    /** The MP3 tracks of an Internet Archive item, in order. */
    fun archiveTracks(ia: String): List<JSONObject> {
        val d = JSONObject(get("https://archive.org/metadata/${enc(ia)}"))
        val meta = d.optJSONObject("metadata")
        fun m(k: String) = meta?.optJSONArray(k)?.optString(0) ?: meta?.optString(k) ?: ""
        val artist = m("creator"); val album = m("title"); val lic = m("licenseurl")
        val files = objs(d.optJSONArray("files"))
        // prefer the VBR MP3 copies; else any MP3
        val mp3 = files.filter { it.optString("format") == "VBR MP3" }.ifEmpty { files.filter { it.optString("name").endsWith(".mp3", true) } }
        fun trackNo(f: JSONObject) = f.optString("track").substringBefore('/').toIntOrNull() ?: Int.MAX_VALUE
        return mp3.sortedWith(compareBy<JSONObject>({ trackNo(it) }, { it.optString("name") })).map { f ->
            val name = f.optString("name")
            val len = f.optString("length").let { l -> if (l.contains(':')) l.split(':').fold(0.0) { a, p -> a * 60 + (p.toDoubleOrNull() ?: 0.0) } else l.toDoubleOrNull() ?: 0.0 }
            JSONObject().put("src", "archive").put("key", "ia-$ia/$name").put("t", f.optString("title").ifEmpty { name.substringBeforeLast('.') })
                .put("a", f.optString("creator").ifEmpty { artist }).put("al", album).put("cover", "https://archive.org/services/img/$ia")
                .put("url", "https://archive.org/download/${enc(ia).replace("+", "%20")}/" + name.split('/').joinToString("/") { enc(it).replace("+", "%20") })
                .put("d", (len * 1000).toLong()).put("lic", lic)
        }
    }

    /** A library item for the download queue: saved as "Artist - Title.mp3" in Music → Media Hub → Free → Artist → Album. */
    fun item(o: JSONObject): Item {
        fun safe(x: String) = x.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "Unknown" }
        val ext = o.optString("url").substringBefore('?').substringAfterLast('.', "mp3").lowercase().takeIf { it in AUDIO_EXT } ?: "mp3"
        val name = safe(listOf(o.optString("a"), o.optString("t")).filter { it.isNotBlank() }.joinToString(" - ")) + ".$ext"
        return Item.of(JSONObject().put("i", idFor("free-" + o.optString("key"))).put("n", name).put("c", 3)
            .put("f", "Free/" + safe(o.optString("a")) + "/" + safe(o.optString("al").ifEmpty { "Singles" }))
            .put("src", "free").put("u", o.optString("url")).put("t", o.optString("t")).put("a", o.optString("a")).put("al", o.optString("al")))
    }
}
