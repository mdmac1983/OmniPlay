package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Free online music metadata — no account or API key needed.
 *  1) Deezer  (api.deezer.com)      — fast: cover art (1000px), song length, album → genre + release year
 *  2) iTunes  (itunes.apple.com)    — fallback when Deezer has no match: art (600px), genre, year, length
 * Results are stored per file in files/musicmeta.json:
 *   "s:<fileid>" → {d: length ms, g: genre, y: year, cv: cover url, alb: album title, src: "deezer"|"itunes"}  or {none:true}
 * Cover images are cached in files/mcover so they show offline.
 */
object MusicMeta {
    private val FEAT = Regex("(?i)\\s*[(\\[]?\\s*(feat\\.?|ft\\.?|featuring|with)\\s.*$")
    private val BRACKETS = Regex("\\s*[(\\[][^)\\]]*[)\\]]")
    private val NONWORD = Regex("[^a-z0-9]+")

    fun norm(s: String): String = NONWORD.replace(BRACKETS.replace(FEAT.replace(s.lowercase(), ""), ""), " ").trim()
    private val ALIASES = mapOf("mgk" to "machine gun kelly", "jxdn" to "jaden hossler", "nf" to "nf")
    private fun firstArtist(a: String) = a.split(Regex("(?i),|&|/|;| x | and | feat\\.? | ft\\.? "))[0].trim()

    private fun get(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", Net.UA)
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** true when the online artist plausibly equals ours (handles "mgk" vs "Machine Gun Kelly" via title-exact rule). */
    private fun artistOk(ours: String, theirs: String): Boolean {
        val a0 = norm(firstArtist(ours)); val b = norm(theirs)
        if (a0.isEmpty()) return true
        val a = ALIASES[a0] ?: a0
        if (ALIASES[a0] != null && (b == a0 || b.contains(a0))) return true
        return a == b || b.contains(a) || a.contains(b) ||
            a.replace(" ", "") == b.replace(" ", "")
    }

    private val albumCache = HashMap<Long, JSONObject>()
    private var lastItunes = 0L
    private var itunesPausedUntil = 0L

    private fun deezer(artist: String, title: String): JSONObject? {
        val res = get("https://api.deezer.com/search?q=${enc(firstArtist(artist) + " " + title)}&limit=10").optJSONArray("data") ?: return null
        val nt = norm(title)
        var best: JSONObject? = null
        for (k in 0 until res.length()) {
            val r = res.getJSONObject(k)
            val rt = norm(r.optString("title"))
            if (rt != nt && !rt.startsWith(nt) && !nt.startsWith(rt)) continue
            val ra = r.optJSONObject("artist")?.optString("name") ?: ""
            if (artistOk(artist, ra)) { best = r; break }
            if (best == null && rt == nt && k == 0) best = r   // exact title, top hit (e.g. alias artist names)
        }
        val r = best ?: return null
        val alb = r.optJSONObject("album") ?: JSONObject()
        val o = JSONObject().put("src", "deezer")
            .put("d", r.optInt("duration") * 1000)
            .put("cv", alb.optString("cover_xl").ifEmpty { alb.optString("cover_big") })
            .put("alb", alb.optString("title"))
        val aid = alb.optLong("id")
        if (aid > 0) {
            val info = albumCache.getOrPut(aid) {
                try {
                    val a = get("https://api.deezer.com/album/$aid")
                    val g = a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name") ?: ""
                    JSONObject().put("g", g).put("y", a.optString("release_date").take(4))
                } catch (e: Exception) { JSONObject() }
            }
            if (info.optString("g").isNotEmpty()) o.put("g", info.optString("g"))
            if (info.optString("y").isNotEmpty()) o.put("y", info.optString("y"))
        }
        return o
    }

    private fun itunes(artist: String, title: String): JSONObject? {
        // Apple allows roughly 20 requests/minute; if it pushes back (403/429) skip iTunes for 2 minutes
        if (System.currentTimeMillis() < itunesPausedUntil) return null
        val wait = 4000 - (System.currentTimeMillis() - lastItunes)
        if (wait > 0) Thread.sleep(wait)
        lastItunes = System.currentTimeMillis()
        val res = try {
            get("https://itunes.apple.com/search?term=${enc(firstArtist(artist) + " " + title)}&entity=song&limit=10").optJSONArray("results") ?: return null
        } catch (e: java.io.IOException) { itunesPausedUntil = System.currentTimeMillis() + 120_000; return null }
        val nt = norm(title)
        for (k in 0 until res.length()) {
            val r = res.getJSONObject(k)
            val rt = norm(r.optString("trackName"))
            if (rt != nt && !rt.startsWith(nt)) continue
            if (!artistOk(artist, r.optString("artistName"))) continue
            return JSONObject().put("src", "itunes")
                .put("d", r.optInt("trackTimeMillis"))
                .put("g", r.optString("primaryGenreName"))
                .put("y", r.optString("releaseDate").take(4))
                .put("cv", r.optString("artworkUrl100").replace("100x100bb", "600x600bb"))
                .put("alb", r.optString("collectionName"))
        }
        return null
    }

    fun lookup(artist: String, title: String): JSONObject? {
        if (title.isBlank()) return null
        val d = try { deezer(artist, title) } catch (e: Exception) { null }
        if (d != null && d.optString("g").isNotEmpty()) return d
        val i = try { itunes(artist, title) } catch (e: Exception) { null }
        if (d == null) return i
        // Deezer matched but had no genre → borrow genre/year from iTunes if found
        if (i != null) { if (i.optString("g").isNotEmpty()) d.put("g", i.optString("g")); if (d.optString("y").isEmpty()) d.put("y", i.optString("y")) }
        return d
    }

    // ---------- cover cache ----------
    private val ALLOWED = listOf("dzcdn.net", "mzstatic.com", "theaudiodb.com", "img.youtube.com", "deezer.com", "archive.org", "jamendo.com")
    /** Podcast artwork hosts (added as podcasts are shown; https only). */
    val extraHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    fun coverAllowed(url: String) = try { val h = URL(url).host; ALLOWED.any { h.endsWith(it) } || (url.startsWith("https://") && h in extraHosts) } catch (e: Exception) { false }

    fun coverFile(store: Store, url: String): File {
        val md = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        return File(store.mcoverDir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    fun cover(store: Store, url: String): File? {
        if (!coverAllowed(url)) return null
        val f = coverFile(store, url)
        if (f.exists()) return f
        return if (Net.download(url, f)) f else null
    }

    // ---------- albums, artists and the catalog (Deezer first, iTunes as fallback) ----------

    /**
     * Full album info for the album page: cover, year, label, genre, track list (with 30-second previews).
     * {src, id, title, artist, cover, year, label, genre, fans, link, tracks:[{n, t, d, preview}]}
     */
    fun album(artist: String, album: String): JSONObject? {
        if (album.isBlank()) return null
        try {
            val res = get("https://api.deezer.com/search/album?q=${enc(firstArtist(artist) + " " + album)}&limit=10").optJSONArray("data")
            val na = norm(album)
            var best: JSONObject? = null
            if (res != null) for (k in 0 until res.length()) {
                val r = res.getJSONObject(k)
                val rt = norm(r.optString("title"))
                if (rt != na && !rt.startsWith(na) && !na.startsWith(rt)) continue
                if (artist.isBlank() || artistOk(artist, r.optJSONObject("artist")?.optString("name") ?: "")) { best = r; break }
            }
            if (best != null) {
                val a = get("https://api.deezer.com/album/${best.optLong("id")}")
                val tracks = JSONArray()
                a.optJSONObject("tracks")?.optJSONArray("data")?.let { t -> for (k in 0 until t.length()) { val x = t.getJSONObject(k)
                    tracks.put(JSONObject().put("n", k + 1).put("t", x.optString("title")).put("d", x.optInt("duration") * 1000L).put("preview", x.optString("preview"))
                        .put("a", x.optJSONObject("artist")?.optString("name") ?: "")) } }
                return JSONObject().put("src", "deezer").put("id", a.optLong("id")).put("title", a.optString("title"))
                    .put("artist", a.optJSONObject("artist")?.optString("name") ?: artist).put("cover", a.optString("cover_xl").ifEmpty { a.optString("cover_big") })
                    .put("year", a.optString("release_date").take(4)).put("released", a.optString("release_date")).put("label", a.optString("label"))
                    .put("genre", a.optJSONObject("genres")?.optJSONArray("data")?.optJSONObject(0)?.optString("name") ?: "")
                    .put("fans", a.optInt("fans")).put("link", a.optString("link")).put("tracks", tracks).put("duration", a.optInt("duration") * 1000L)
            }
        } catch (_: Exception) {}
        // iTunes
        try {
            val res = get("https://itunes.apple.com/search?term=${enc(firstArtist(artist) + " " + album)}&entity=album&limit=10").optJSONArray("results") ?: return null
            val na = norm(album)
            val r = (0 until res.length()).map { res.getJSONObject(it) }.firstOrNull { val rt = norm(it.optString("collectionName")); (rt == na || rt.startsWith(na) || na.startsWith(rt)) && artistOk(artist, it.optString("artistName")) } ?: return null
            val l = get("https://itunes.apple.com/lookup?id=${r.optLong("collectionId")}&entity=song").optJSONArray("results") ?: JSONArray()
            val tracks = JSONArray()
            for (k in 0 until l.length()) { val x = l.getJSONObject(k); if (x.optString("wrapperType") != "track") continue
                tracks.put(JSONObject().put("n", x.optInt("trackNumber")).put("t", x.optString("trackName")).put("d", x.optLong("trackTimeMillis")).put("preview", x.optString("previewUrl")).put("a", x.optString("artistName"))) }
            return JSONObject().put("src", "itunes").put("id", r.optLong("collectionId")).put("title", r.optString("collectionName")).put("artist", r.optString("artistName"))
                .put("cover", r.optString("artworkUrl100").replace("100x100bb", "1000x1000bb")).put("year", r.optString("releaseDate").take(4)).put("released", r.optString("releaseDate").take(10))
                .put("label", r.optString("copyright")).put("genre", r.optString("primaryGenreName")).put("link", r.optString("collectionViewUrl")).put("tracks", tracks)
        } catch (_: Exception) { return null }
    }

    /** Artist info: picture, fans, albums and top tracks (Deezer). {id, name, picture, fans, albums:[{title, cover, year, id}], top:[{t, al, cover, preview, d}]} */
    fun artist(name: String): JSONObject? = try {
        val res = get("https://api.deezer.com/search/artist?q=${enc(firstArtist(name))}&limit=5").optJSONArray("data")
        val a = (0 until (res?.length() ?: 0)).map { res!!.getJSONObject(it) }.firstOrNull { artistOk(name, it.optString("name")) }
        if (a == null) null else {
            val id = a.optLong("id")
            val top = JSONArray(); get("https://api.deezer.com/artist/$id/top?limit=10").optJSONArray("data")?.let { t -> for (k in 0 until t.length()) { val x = t.getJSONObject(k)
                top.put(JSONObject().put("t", x.optString("title")).put("al", x.optJSONObject("album")?.optString("title") ?: "").put("cover", x.optJSONObject("album")?.optString("cover_big") ?: "")
                    .put("preview", x.optString("preview")).put("d", x.optInt("duration") * 1000L)) } }
            val albums = JSONArray(); get("https://api.deezer.com/artist/$id/albums?limit=50").optJSONArray("data")?.let { t -> for (k in 0 until t.length()) { val x = t.getJSONObject(k)
                albums.put(JSONObject().put("title", x.optString("title")).put("cover", x.optString("cover_big")).put("year", x.optString("release_date").take(4)).put("id", x.optLong("id")).put("type", x.optString("record_type"))) } }
            JSONObject().put("id", id).put("name", a.optString("name")).put("picture", a.optString("picture_xl").ifEmpty { a.optString("picture_big") })
                .put("fans", a.optInt("nb_fan")).put("nbAlbums", a.optInt("nb_album")).put("top", top).put("albums", albums).put("link", a.optString("link"))
        }
    } catch (_: Exception) { null }

    /** Catalog search (Deezer songs, 30-second previews). [{id, t, a, al, cover, preview, d}] */
    fun searchCatalog(q: String): List<JSONObject> = try {
        val r = get("https://api.deezer.com/search?q=${enc(q)}&limit=40").optJSONArray("data") ?: JSONArray()
        (0 until r.length()).map { r.getJSONObject(it) }.map { x ->
            JSONObject().put("id", x.optLong("id")).put("t", x.optString("title")).put("a", x.optJSONObject("artist")?.optString("name") ?: "")
                .put("al", x.optJSONObject("album")?.optString("title") ?: "").put("cover", x.optJSONObject("album")?.optString("cover_big") ?: "")
                .put("alid", x.optJSONObject("album")?.optLong("id") ?: 0L)
                .put("preview", x.optString("preview")).put("d", x.optInt("duration") * 1000L)
        }.filter { it.optString("preview").startsWith("https://") }
    } catch (_: Exception) {
        try {
            val r = get("https://itunes.apple.com/search?term=${enc(q)}&entity=song&limit=40").optJSONArray("results") ?: JSONArray()
            (0 until r.length()).map { r.getJSONObject(it) }.map { x ->
                JSONObject().put("src", "itunes").put("id", x.optLong("trackId")).put("t", x.optString("trackName")).put("a", x.optString("artistName")).put("al", x.optString("collectionName"))
                    .put("cover", x.optString("artworkUrl100").replace("100x100bb", "600x600bb")).put("preview", x.optString("previewUrl")).put("d", x.optLong("trackTimeMillis"))
            }.filter { it.optString("preview").startsWith("https://") }
        } catch (_: Exception) { emptyList() }
    }
}
