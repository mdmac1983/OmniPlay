package app.orionmd.omniplay

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.orionmd.omniplay.cloud.Cloud
import app.orionmd.omniplay.cloud.CloudFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the library for each source: this device's media (MediaStore), a folder picked with Android's folder picker
 * (SAF), or a cloud folder (recursively listed). Everything becomes an [Item] and is saved as one encrypted index file,
 * so browsing and offline playback don't need the folder to be rescanned.
 */
object Library {
    fun read(store: Store, sourceId: String): List<Item> {
        val raw = store.readLibrary(sourceId); if (raw.isEmpty()) return emptyList()
        return try { JSONObject(raw).optJSONArray("items")?.let { a -> (0 until a.length()).map { fixSong(Item.of(a.getJSONObject(it))) } } ?: emptyList() } catch (_: Exception) { emptyList() }
    }

    private val UNKNOWN = setOf("<unknown>", "unknown", "unknown artist", "unknown album", "")
    private fun clean(s: String?) = s?.trim()?.takeIf { it.lowercase() !in UNKNOWN }
    private val NUM = Regex("^\\s*\\d{1,3}\\s*[-._)]\\s*")

    /**
     * Songs without an artist tag usually carry it in the title or file name: "Artist - Title". Split those so the
     * online look-ups (Deezer / iTunes / lyrics) search for the right artist and song.
     */
    fun fixSong(x: Item): Item {
        if (x.c != 3) return x
        var a = clean(x.a); var t = clean(x.t); val al = clean(x.al)
        if (a == null) {
            val src = (t ?: x.n.substringBeforeLast('.')).replace('_', ' ').replace(NUM, "")
            val parts = src.split(" - ", " – ", " — ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { a = parts[0].trim(); t = parts[1].trim() }
        }
        return if (a == x.a && t == x.t && al == x.al) x else x.copy(a = a, t = t, al = al)
    }
    fun write(store: Store, sourceId: String, items: List<Item>) {
        store.writeLibrary(sourceId, JSONObject().put("items", JSONArray(items.map { it.json() })).put("at", System.currentTimeMillis()).toString())
    }

    /** Scan whatever [src] points at. Returns the items (also written to the index). */
    fun scan(ctx: Context, store: Store, src: Source, progress: (Int) -> Unit = {}): List<Item> {
        val items = when (src.kind) {
            "device" -> scanDevice(ctx, src.id, progress)
            "folder" -> scanFolder(ctx, src, progress)
            "cloud" -> scanCloud(store, src, progress)
            else -> emptyList()
        }
        val stamped = if (src.kind == "device") items else stampAdded(store, src, items)
        write(store, src.id, stamped)
        return stamped
    }

    /**
     * Cloud and folder files: their date becomes the date they were added to the library (see [Added]) — the phone's
     * own media store already records that, so device files keep theirs.
     */
    private fun stampAdded(store: Store, src: Source, items: List<Item>): List<Item> {
        val f = java.io.File(store.filesDir, "added_${src.id}.json")
        val known = Added.parse(Vault.readText(f))
        val ids = items.map { it.i }
        // pCloud file ids go up with every upload: the upload order for files already there
        val pcloud = src.kind == "cloud" && Cloud.account(src.acct)?.provider == "pcloud"
        val seq = if (pcloud) items.map { java.net.URLDecoder.decode(it.u.substringAfterLast('/'), "UTF-8").removePrefix("f").toLongOrNull() ?: 0L } else null
        val d = Added.dates(ids, items.map { it.cr }, seq, known, System.currentTimeMillis())
        Vault.writeText(f, Added.json(known))
        return items.mapIndexed { k, x -> if (x.cr == d[k]) x else x.copy(cr = d[k]) }
    }

    // ---------- device (MediaStore) ----------
    private fun scanDevice(ctx: Context, srcId: String, progress: (Int) -> Unit): List<Item> {
        val out = ArrayList<Item>()
        val r = ctx.contentResolver
        // audio
        run {
            val col = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED, MediaStore.Audio.Media.RELATIVE_PATH)
            r.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, col, "${MediaStore.Audio.Media.IS_MUSIC}!=0", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build().toString()
                    val path = (col.getOrNull(10)?.let { runCatching { c.getString(10) }.getOrNull() } ?: "").trim('/')
                    out.add(Item(i = idFor("d-a-$id"), n = c.getString(1) ?: "audio", c = 3, s = c.getLong(8), f = path, src = srcId, u = uri,
                        cr = c.getLong(9) * 1000, t = c.getStringOrNull(2), a = c.getStringOrNull(3), al = c.getStringOrNull(4), g = null,
                        tr = (c.getIntOrNull(7) ?: 0) % 1000, d = c.getLong(5), y = c.getIntOrNull(6) ?: 0, th = true,
                        k = null, show = null, se = 0, ep = 0, pt = null, py = 0, concert = false))
                }
            }
        }
        // video
        run {
            val col = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED, MediaStore.Video.Media.RELATIVE_PATH)
            r.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, col, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0); val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(id.toString()).build().toString()
                    val path = (runCatching { c.getString(5) }.getOrNull() ?: "").trim('/')
                    val o = JSONObject().put("n", c.getString(1) ?: "video").put("f", path)
                    Tmdb.parse(o)
                    out.add(Item(i = idFor("d-v-$id"), n = c.getString(1) ?: "video", c = 2, s = c.getLong(3), f = path, src = srcId, u = uri,
                        cr = c.getLong(4) * 1000, t = null, a = null, al = null, g = null, tr = 0, d = c.getLong(2), y = 0, th = true,
                        k = o.optString("k").ifEmpty { null }, show = o.optString("show").ifEmpty { null }, se = o.optInt("se"), ep = o.optInt("ep"),
                        pt = o.optString("pt").ifEmpty { null }, py = o.optInt("py"), concert = o.optBoolean("concert")))
                }
            }
        }
        progress(out.size)
        return out
    }

    // ---------- SAF folder ----------
    private fun scanFolder(ctx: Context, src: Source, progress: (Int) -> Unit): List<Item> {
        val tree = Uri.parse(src.tree)
        val r = ctx.contentResolver
        val out = ArrayList<Item>()
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val sidecars = HashMap<String, ArrayList<Triple<String, String, String>>>()   // folder+base -> (ext, name, uri)
        data class Pending(val base: String, val folder: String, val item: Item)
        val pending = ArrayList<Pending>()
        val queue = ArrayDeque<Pair<String, String>>().apply { add(DocumentsContract.getTreeDocumentId(tree) to "") }
        while (queue.isNotEmpty()) {
            val (dir, path) = queue.removeFirst()
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            r.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue; val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { queue.add(docId to (if (path.isEmpty()) name else "$path/$name")); continue }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId).toString()
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val base = name.substringBeforeLast('.').substringBefore('.').lowercase()
                    if (ext in SUB_EXT || ext == "lrc") { sidecars.getOrPut("$path|$base") { ArrayList() }.add(Triple(ext, name, uri)); continue }
                    val cat = categoryOf(name, mime); if (cat == 0) continue
                    val id = idFor("f-${src.id}-$docId")
                    val o = JSONObject().put("n", name).put("f", path); if (cat == 2) Tmdb.parse(o)
                    val item = Item(i = id, n = name, c = cat, s = if (c.isNull(3)) 0 else c.getLong(3), f = path, src = src.id, u = uri,
                        cr = if (c.isNull(4)) 0 else c.getLong(4), t = null, a = null, al = null, g = null, tr = 0, d = 0, y = 0, th = true,
                        k = o.optString("k").ifEmpty { null }, show = o.optString("show").ifEmpty { null }, se = o.optInt("se"), ep = o.optInt("ep"),
                        pt = o.optString("pt").ifEmpty { null }, py = o.optInt("py"), concert = o.optBoolean("concert"))
                    pending.add(Pending(base, path, item))
                }
            }
            progress(pending.size)
        }
        for (p in pending) out.add(attachSidecars(p.item, sidecars["${p.folder}|${p.base}"]))
        return out
    }

    // ---------- cloud ----------
    private fun scanCloud(store: Store, src: Source, progress: (Int) -> Unit): List<Item> {
        val acct = Cloud.account(src.acct) ?: return emptyList()
        val out = ArrayList<Item>()
        val queue = ArrayDeque<Pair<String, String>>().apply { add(src.folderId to "") }
        val subsByBase = HashMap<String, ArrayList<Triple<String, String, String>>>()
        data class Pending(val base: String, val folder: String, val item: Item)
        val pending = ArrayList<Pending>()
        var guard = 0
        while (queue.isNotEmpty() && guard < 20000) {
            guard++
            val (fid, path) = queue.removeFirst()
            val files = try { Cloud.list(acct, fid) } catch (_: Exception) { continue }
            for (cf in files) {
                if (cf.isDir) { queue.add(cf.id to (if (path.isEmpty()) cf.name else "$path/${cf.name}")); continue }
                val ext = cf.name.substringAfterLast('.', "").lowercase()
                val base = cf.name.substringBeforeLast('.').substringBefore('.').lowercase()
                val uri = Cloud.uriFor(acct.id, cf.id)
                if (ext in SUB_EXT || ext == "lrc") { subsByBase.getOrPut("$path|$base") { ArrayList() }.add(Triple(ext, cf.name, uri)); continue }
                val cat = categoryOf(cf.name); if (cat == 0) continue
                val o = JSONObject().put("n", cf.name).put("f", path); if (cat == 2) Tmdb.parse(o)
                val item = Item(i = idFor("c-${src.id}-${cf.id}"), n = cf.name, c = cat, s = cf.size, f = path, src = src.id, u = uri,
                    cr = cf.modified, t = null, a = null, al = null, g = null, tr = 0, d = 0, y = 0, th = true,
                    k = o.optString("k").ifEmpty { null }, show = o.optString("show").ifEmpty { null }, se = o.optInt("se"), ep = o.optInt("ep"),
                    pt = o.optString("pt").ifEmpty { null }, py = o.optInt("py"), concert = o.optBoolean("concert"))
                pending.add(Pending(base, path, item))
            }
            progress(pending.size)
        }
        for (p in pending) out.add(attachSidecars(p.item, subsByBase["${p.folder}|${p.base}"]))
        return out
    }

    private fun attachSidecars(item: Item, side: List<Triple<String, String, String>>?): Item {
        if (side == null || side.isEmpty()) return item
        if (item.c == 3) {
            val lrc = side.firstOrNull { it.first == "lrc" }?.third
            return if (lrc != null) item.copy(lrc = lrc) else item
        }
        val subs = JSONArray()
        side.filter { it.first in SUB_EXT }.forEach { (_, name, uri) ->
            val lang = Regex("\\.([a-z]{2,3})\\.[a-z]+$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1) ?: ""
            subs.put(JSONObject().put("u", uri).put("n", name).put("lang", lang))
        }
        return if (subs.length() > 0) item.copy(subs = subs) else item
    }
}

private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)?.trim()?.ifEmpty { null }
private fun android.database.Cursor.getIntOrNull(i: Int): Int? = if (isNull(i)) null else getInt(i)
