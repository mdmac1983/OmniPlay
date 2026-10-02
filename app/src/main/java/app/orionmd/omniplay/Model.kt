package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

/*
 * Library data. Every song and video — on the device, in a picked folder or in a cloud folder — becomes an [Item].
 * Items are stored as compact JSON (short field names) in one encrypted index file per source.
 */

private fun JSONObject.str(k: String): String? = optString(k, "").takeIf { has(k) && it.isNotEmpty() && it != "null" }

/** One song or video. [u] is what the players open: a content:// URI on the device or omni://<account>/<file> in a cloud. */
data class Item(
    val i: Long, val n: String, val c: Int, val s: Long, val f: String, val src: String, val u: String, val cr: Long,
    val t: String?, val a: String?, val al: String?, val g: String?, val tr: Int, val d: Long, val y: Int, val th: Boolean,
    val k: String?, val show: String?, val se: Int, val ep: Int, val pt: String?, val py: Int, val concert: Boolean,
    /** cloud sidecar files next to a video: subtitles [{u,n,lang}] ; next to a song: a .lrc */
    val subs: JSONArray? = null, val lrc: String? = null,
) {
    val title get() = t ?: n.substringBeforeLast('.')
    val artist get() = a ?: "Unknown artist"
    val album get() = al ?: "Unknown album"
    val akey get() = (a ?: "").trim().lowercase()
    val albKey get() = if (al != null) "al:" + al.trim().lowercase() + "|" + firstArtist(a ?: "").lowercase() else "na:$akey"
    val folder get() = f.ifEmpty { "(root)" }
    val isAudio get() = c == 3
    val isVideo get() = c == 2
    val isCloud get() = u.startsWith("omni://")

    fun json(): JSONObject = JSONObject().put("i", i).put("n", n).put("c", c).put("s", s).put("f", f).put("src", src).put("u", u).put("cr", cr)
        .apply {
            t?.let { put("t", it) }; a?.let { put("a", it) }; al?.let { put("al", it) }; g?.let { put("g", it) }
            if (tr > 0) put("tr", tr); if (d > 0) put("d", d); if (y > 0) put("y", y); put("th", if (th) 1 else 0)
            k?.let { put("k", it) }; show?.let { put("show", it) }; if (se > 0) put("se", se); if (ep > 0) put("ep", ep)
            pt?.let { put("pt", it) }; if (py > 0) put("py", py); if (concert) put("concert", true)
            subs?.let { put("subs", it) }; lrc?.let { put("lrc", it) }
        }

    /** What the music player queue holds for this song. */
    fun track(cover: String?): JSONObject = JSONObject().put("i", i).put("n", n).put("t", title).put("a", a ?: "").put("al", al ?: "")
        .put("u", u).put("th", if (th) 1 else 0).put("src", src).apply { if (d > 0) put("d", d); cover?.let { put("cv", it) }; lrc?.let { put("lrc", it) } }

    companion object {
        fun of(o: JSONObject) = Item(
            i = o.optLong("i"), n = o.optString("n"), c = o.optInt("c"), s = o.optLong("s"), f = o.optString("f"), src = o.optString("src"),
            u = o.optString("u"), cr = o.optLong("cr"), t = o.str("t"), a = o.str("a"), al = o.str("al"), g = o.str("g"), tr = o.optInt("tr"),
            d = o.optLong("d"), y = o.optInt("y"), th = o.optInt("th") == 1, k = o.str("k"), show = o.str("show"), se = o.optInt("se"),
            ep = o.optInt("ep"), pt = o.str("pt"), py = o.optInt("py"), concert = o.optBoolean("concert"),
            subs = o.optJSONArray("subs"), lrc = o.str("lrc"),
        )
    }
}

/** Where items come from: this device's media, a folder picked with Android's folder picker, or a cloud folder. */
data class Source(
    val id: String, val kind: String, val name: String, val tree: String = "", val acct: String = "", val folderId: String = "",
    val path: String = "", val count: Int = 0, val lastScan: Long = 0, val sync: Boolean = false,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("name", name).put("tree", tree).put("acct", acct)
        .put("folderId", folderId).put("path", path).put("count", count).put("lastScan", lastScan).put("sync", sync)
    companion object {
        fun of(o: JSONObject) = Source(o.optString("id"), o.optString("kind"), o.optString("name"), o.optString("tree"), o.optString("acct"),
            o.optString("folderId"), o.optString("path"), o.optInt("count"), o.optLong("lastScan"), o.optBoolean("sync"))
    }
}

/** Online music info (Deezer / iTunes) for one song. */
data class Meta(val cv: String?, val g: String?, val y: Int, val d: Long, val alb: String?)

fun parseMeta(o: JSONObject): Map<Long, Meta> {
    val m = HashMap<Long, Meta>()
    for (k in o.keys()) {
        val v = o.optJSONObject(k) ?: continue
        if (v.optBoolean("none") || !k.startsWith("s:")) continue
        val id = k.removePrefix("s:").toLongOrNull() ?: continue
        m[id] = Meta(v.str("cv"), v.str("g"), v.optString("y").take(4).toIntOrNull() ?: 0, v.optLong("d"), v.str("alb"))
    }
    return m
}

/** A TMDB match (movie or TV show). */
data class Match(
    val json: JSONObject, val title: String, val year: String, val rating: Double, val poster: String, val backdrop: String,
    val overview: String, val tagline: String, val genres: String, val runtime: Int, val seasons: Int, val detailed: Boolean,
) {
    companion object {
        fun of(o: JSONObject): Match? = if (o.optBoolean("none")) null else Match(o, o.optString("title"), o.optString("year"),
            o.optDouble("rating", 0.0), o.optString("poster"), o.optString("backdrop"), o.optString("overview"),
            o.optString("tagline"), o.optString("genres"), o.optInt("runtime"), o.optInt("seasons"), o.optBoolean("detailed"))
    }
}

/** Stable 62-bit id from a string key (FNV-1a). */
fun idFor(key: String): Long {
    var h = -0x340d631b7bdddcdbL
    for (ch in key) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
    return h and 0x3FFF_FFFF_FFFF_FFFFL
}

val AUDIO_EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aif", "aiff", "ape", "mka", "amr", "3ga", "m4b")
val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "ts", "m2ts", "mts", "wmv", "flv", "mpg", "mpeg", "vob", "ogv")
val SUB_EXT = setOf("srt", "vtt", "ass", "ssa")
fun categoryOf(name: String, mime: String? = null): Int {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext in VIDEO_EXT || mime?.startsWith("video/") == true -> 2
        ext in AUDIO_EXT || mime?.startsWith("audio/") == true -> 3
        else -> 0
    }
}

// ---------- formatting ----------
private val nf = NumberFormat.getIntegerInstance(Locale.getDefault())
fun fmt(n: Number): String = nf.format(n)
fun bytes(n: Long): String = when {
    n > 1_000_000_000_000 -> String.format(Locale.US, "%.2f TB", n / 1e12)
    n > 1_000_000_000 -> String.format(Locale.US, "%.2f GB", n / 1e9)
    n > 1_000_000 -> String.format(Locale.US, "%.1f MB", n / 1e6)
    else -> "${Math.round(n / 1e3)} KB"
}
fun ago(ms: Long): String {
    if (ms <= 0) return "never"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago" }
}
fun mmss(ms: Long): String { val s = maxOf(0, ms) / 1000; val h = s / 3600; return if (h > 0) "$h:${(s % 3600 / 60).toString().padStart(2, '0')}:${(s % 60).toString().padStart(2, '0')}" else "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
fun hms(ms: Long): String { val h = ms / 3600000; val m = ms % 3600000 / 60000; return if (h > 0) "${fmt(h)} h $m min" else "$m min" }
fun epLabel(x: Item) = if (x.se > 0) "S${x.se.toString().padStart(2, '0')}E${x.ep.toString().padStart(2, '0')}" else ""
val natural: Comparator<String> = Comparator { a, b -> naturalCompare(a, b) }
fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
            var je = j; while (je < b.length && b[je].isDigit()) je++
            val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb); if (c != 0) return c
            i = ie; j = je
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private val FIRST_ARTIST = Regex("(?i),| feat\\.? | ft\\.? | & | x ")
fun firstArtist(a: String) = a.split(FIRST_ARTIST)[0].trim()

class AlbumGroup(val key: String, val name: String) {
    var art: Item? = null; var year = 0; var artist = ""
    val items = ArrayList<Item>()
}
class NamedGroup(val key: String, val name: String) { var art: Item? = null; val items = ArrayList<Item>() }
class ShowGroup(val key: String, val name: String, val first: Item) { val eps = ArrayList<Item>() }
