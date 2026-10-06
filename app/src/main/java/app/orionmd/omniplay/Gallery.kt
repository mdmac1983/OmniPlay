package app.orionmd.omniplay

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** One photo or video in the phone's gallery. */
data class GItem(
    val key: String, val uri: String, val name: String, val bucket: String, val bucketId: String, val date: Long,
    val w: Int, val h: Int, val video: Boolean, val dur: Long, val size: Long, val mime: String, val trashedUntil: Long = 0,
)

/** A device album (a folder) or one of your own albums. */
data class GAlbum(val key: String, val name: String, val items: List<GItem>, val custom: Boolean)

/** A group combining several albums. */
data class GGroup(val id: String, val name: String, val albums: List<String>)

/**
 * The Gallery's data: every photo and video in the phone's media store (Pictures, DCIM, Downloads, WhatsApp…),
 * device albums (folders), your own albums, groups of albums, custom covers and the trash bin.
 * Own albums / groups / covers / view rotations are saved in settings and refer to items by their content URI.
 * The trash bin is Android's own (Android 11+): trashed items are kept 30 days, then the system deletes them.
 */
object Gallery {
    private val imgUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val vidUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun load(ctx: Context, trashed: Boolean = false): List<GItem> {
        if (trashed && Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<GItem>()
        for (video in listOf(false, true)) {
            val base = if (video) vidUri else imgUri
            val cols = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.BUCKET_ID, MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DURATION)
            if (trashed) cols += MediaStore.MediaColumns.DATE_EXPIRES
            val c = try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val args = Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.MediaColumns.DATE_ADDED))
                        putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                    }
                    ctx.contentResolver.query(base, cols.toTypedArray(), args, null)
                } else ctx.contentResolver.query(base, cols.toTypedArray(), null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")
            } catch (_: Exception) { null } ?: continue
            c.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val taken = if (it.isNull(4)) 0L else it.getLong(4)
                    val added = if (it.isNull(5)) 0L else it.getLong(5) * 1000
                    out.add(GItem((if (video) "v" else "i") + id, ContentUris.withAppendedId(base, id).toString(), it.getString(1) ?: "",
                        it.getString(2) ?: "Other", it.getString(3) ?: "", if (taken > 0) taken else added,
                        if (it.isNull(6)) 0 else it.getInt(6), if (it.isNull(7)) 0 else it.getInt(7), video,
                        if (it.isNull(10)) 0 else it.getLong(10), if (it.isNull(8)) 0 else it.getLong(8), it.getString(9) ?: "",
                        if (trashed && !it.isNull(11)) it.getLong(11) * 1000 else 0))
                }
            }
        }
        return out.sortedByDescending { it.date }
    }

    // ---------- albums ----------
    fun deviceAlbums(items: List<GItem>): List<GAlbum> =
        items.groupBy { it.bucketId }.map { (id, l) -> GAlbum("b:$id", l.first().bucket, l, false) }.sortedByDescending { it.items.size }

    fun customAlbums(st: AppState, items: List<GItem>): List<GAlbum> {
        // your own albums keep showing their photos even when the folder they live in is hidden
        val byUri = (st.gallery.all ?: items).associateBy { it.uri }
        return jsonList(st.pref("gAlbums", "[]")).map { o ->
            val uris = o.optJSONArray("uris")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            GAlbum("c:" + o.optString("id"), o.optString("name"), uris.mapNotNull { byUri[it] }, true)
        }
    }
    fun allAlbums(st: AppState, items: List<GItem>) = customAlbums(st, items) + deviceAlbums(items)

    fun createAlbum(st: AppState, name: String, uris: List<String>): String {
        val id = System.currentTimeMillis().toString(36)
        val l = jsonList(st.pref("gAlbums", "[]")) + JSONObject().put("id", id).put("name", name.trim().ifEmpty { "Album" }).put("uris", JSONArray(uris.distinct()))
        st.setPref("gAlbums", JSONArray(l).toString()); return "c:$id"
    }
    fun addToAlbum(st: AppState, key: String, uris: List<String>) = editAlbums(st) { o -> if ("c:" + o.optString("id") == key) {
        val cur = o.optJSONArray("uris")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        o.put("uris", JSONArray((cur + uris).distinct())) } ; o }
    fun removeFromAlbum(st: AppState, key: String, uris: List<String>) = editAlbums(st) { o -> if ("c:" + o.optString("id") == key) {
        val cur = o.optJSONArray("uris")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        o.put("uris", JSONArray(cur - uris.toSet())) }; o }
    fun renameAlbum(st: AppState, key: String, name: String) = editAlbums(st) { o -> if ("c:" + o.optString("id") == key) o.put("name", name); o }
    fun deleteAlbum(st: AppState, key: String) = st.setPref("gAlbums", JSONArray(jsonList(st.pref("gAlbums", "[]")).filter { "c:" + it.optString("id") != key }).toString())
    private fun editAlbums(st: AppState, f: (JSONObject) -> JSONObject) = st.setPref("gAlbums", JSONArray(jsonList(st.pref("gAlbums", "[]")).map(f)).toString())

    // ---------- groups ----------
    fun groups(st: AppState): List<GGroup> = jsonList(st.pref("gGroups", "[]")).map { o ->
        GGroup(o.optString("id"), o.optString("name"), o.optJSONArray("albums")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
    }
    fun saveGroup(st: AppState, g: GGroup) {
        val l = groups(st).filter { it.id != g.id } + g
        st.setPref("gGroups", JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("albums", JSONArray(it.albums)) }).toString())
    }
    fun deleteGroup(st: AppState, id: String) { val l = groups(st).filter { it.id != id }
        st.setPref("gGroups", JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("albums", JSONArray(it.albums)) }).toString()) }

    // ---------- folders shown in the Gallery ----------
    /** Device folders (bucket IDs) left out of the Gallery. New folders are shown until you hide them. */
    fun hidden(st: AppState): Set<String> = try { JSONArray(st.pref("gHidden", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (_: Exception) { emptySet() }
    fun setHidden(st: AppState, ids: Set<String>) { st.setPref("gHidden", JSONArray(ids.toList()).toString()); st.applyGalleryFilter() }

    // ---------- covers & view rotation ----------
    fun cover(st: AppState, a: GAlbum): GItem? {
        val u = try { JSONObject(st.pref("gCovers", "{}")).optString(a.key) } catch (_: Exception) { "" }
        return a.items.firstOrNull { it.uri == u } ?: a.items.firstOrNull()
    }
    fun setCover(st: AppState, albumKey: String, uri: String) {
        val o = try { JSONObject(st.pref("gCovers", "{}")) } catch (_: Exception) { JSONObject() }
        st.setPref("gCovers", o.put(albumKey, uri).toString())
    }
    fun rotation(st: AppState, uri: String): Int = try { JSONObject(st.pref("gRot", "{}")).optInt(uri) } catch (_: Exception) { 0 }
    fun rotate(st: AppState, uri: String, by: Int) {
        val o = try { JSONObject(st.pref("gRot", "{}")) } catch (_: Exception) { JSONObject() }
        val r = ((o.optInt(uri) + by) % 360 + 360) % 360
        if (r == 0) o.remove(uri) else o.put(uri, r); st.setPref("gRot", o.toString())
    }

    // ---------- trash (Android 11+: the system trash, 30 days) ----------
    val hasTrash get() = Build.VERSION.SDK_INT >= 30
    fun trashRequest(ctx: Context, uris: List<String>, trash: Boolean): android.content.IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createTrashRequest(ctx.contentResolver, uris.map { Uri.parse(it) }, trash).intentSender else null
    fun deleteRequest(ctx: Context, uris: List<String>): android.content.IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(ctx.contentResolver, uris.map { Uri.parse(it) }).intentSender else null
    /** Android 10 and older: delete straight away (after the app's own confirmation). */
    fun deleteNow(ctx: Context, uris: List<String>): Int = uris.sumOf { try { ctx.contentResolver.delete(Uri.parse(it), null, null) } catch (_: Exception) { 0 } }

    private fun jsonList(s: String): List<JSONObject> = try { JSONArray(s).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } } catch (_: Exception) { emptyList() }
}

// ---------- Gallery state on AppState ----------
class GalleryState {
    /** everything in the media store, including hidden folders */
    var all by androidx.compose.runtime.mutableStateOf<List<GItem>?>(null)
    /** what the Gallery shows (hidden folders left out) */
    var items by androidx.compose.runtime.mutableStateOf<List<GItem>?>(null)
    var trash by androidx.compose.runtime.mutableStateOf<List<GItem>>(emptyList())
    /** the list the viewer pages through */
    var viewList by androidx.compose.runtime.mutableStateOf<List<GItem>>(emptyList())
    val sel = androidx.compose.runtime.mutableStateListOf<String>()
    var tick by androidx.compose.runtime.mutableIntStateOf(0)
}

fun AppState.loadGallery() {
    scope.launch {
        val (a, t) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Gallery.load(ctx) to Gallery.load(ctx, trashed = true) }
        gallery.all = a; gallery.trash = t; applyGalleryFilter()
    }
}

fun AppState.applyGalleryFilter() {
    val a = gallery.all ?: return
    // folders hidden from the gallery, and folders locked with the PIN (unless shown right now)
    val hide = Gallery.hidden(this) + (if (Lock.privateOpen) emptySet() else Lock.folders(store).filter { it.startsWith("bucket:") }.map { it.substring(7) })
    gallery.items = if (hide.isEmpty()) a else a.filter { it.bucketId !in hide }
    gallery.sel.retainAll(gallery.items.orEmpty().map { it.key }.toSet())
    gallery.tick++
}

/** After items left the gallery: drop them from the open viewer and the selection, and reload. */
private fun AppState.gone(items: List<GItem>) {
    val keys = items.map { it.key }.toSet()
    gallery.viewList = gallery.viewList.filter { it.key !in keys }
    gallery.sel.clear(); loadGallery()
}

/** Moves items to the trash (Android 11+, kept 30 days) or deletes them after confirming (older Android). */
fun AppState.trashItems(items: List<GItem>) {
    if (items.isEmpty()) return
    val s = Gallery.trashRequest(ctx, items.map { it.uri }, true)
    if (s != null) host.confirmMedia(s) { ok -> if (ok) { say("Moved ${items.size} to trash — kept for 30 days"); gone(items) } }
    else dialog = Dlg.Confirm("Delete ${items.size} item${if (items.size == 1) "" else "s"}?", "This Android version has no trash bin, so they're deleted permanently.", true) {
        scope.launch { deleteLegacy(items) } }
}

/**
 * Android 10 and older: deletes one by one. On Android 10, a photo another app saved needs your OK first (the system
 * asks, then the delete is tried again).
 */
private suspend fun AppState.deleteLegacy(items: List<GItem>) {
    var n = 0
    for (x in items) {
        val u = Uri.parse(x.uri)
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { ctx.contentResolver.delete(u, null, null) to null }
            catch (e: SecurityException) { 0 to e }
        }
        if (r.first > 0) { n++; continue }
        val e = r.second
        if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) {
            val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c -> host.confirmMedia(e.userAction.actionIntent.intentSender) { c.resumeWith(Result.success(it)) } }
            if (ok && kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { ctx.contentResolver.delete(u, null, null) } catch (_: Exception) { 0 } } > 0) n++
        }
    }
    say(if (n == items.size) "Deleted $n" else if (n == 0) "Couldn't delete — Android didn't allow it" else "Deleted $n of ${items.size}")
    if (n > 0) gone(items)
}
fun AppState.restoreItems(items: List<GItem>) {
    Gallery.trashRequest(ctx, items.map { it.uri }, false)?.let { s -> host.confirmMedia(s) { if (it) { say("Restored ${items.size}"); gone(items) } } }
}
fun AppState.deleteForever(items: List<GItem>) {
    if (items.isEmpty()) return
    Gallery.deleteRequest(ctx, items.map { it.uri })?.let { s -> host.confirmMedia(s) { if (it) { say("Deleted ${items.size} permanently"); gone(items) } } }
}
fun AppState.shareItems(items: List<GItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.map { Uri.parse(it.uri) })
    val i = if (uris.size == 1) android.content.Intent(android.content.Intent.ACTION_SEND).setType(if (items[0].video) "video/*" else "image/*").putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
        else android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
    try { host.launch(android.content.Intent.createChooser(i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { say("No app to share with") }
}
fun AppState.playGalleryVideo(g: GItem) {
    val q = org.json.JSONArray().put(JSONObject().put("i", idFor(g.uri)).put("t", g.name).put("u", g.uri))
    try { host.launch(android.content.Intent(ctx, VideoActivity::class.java).putExtra("queue", q.toString()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
}
