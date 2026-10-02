package app.orionmd.omniplay

import android.content.Context
import android.net.Uri
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Offline copies. A song or video from a cloud (or a picked folder) can be saved to the device so it plays with no
 * internet. Files go to the app's own external storage (removed when the app is uninstalled); cloud files are decrypted
 * as they're written, so they're normal, playable files. A small index (pref "dls") maps item id → local path.
 */
object Downloads {
    private fun dir(ctx: Context) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "offline").apply { mkdirs() }
    private fun store(ctx: Context) = Store(ctx)

    fun index(ctx: Context): JSONObject = try { JSONObject(store(ctx).getPref("dls", "{}")) } catch (_: Exception) { JSONObject() }
    private fun save(ctx: Context, o: JSONObject) = store(ctx).setPref("dls", o.toString())

    fun localPath(ctx: Context, id: Long): String? = index(ctx).optString(id.toString()).takeIf { it.isNotEmpty() && File(it).exists() }
    fun localUri(ctx: Context, id: Long): Uri? = localPath(ctx, id)?.let { Uri.fromFile(File(it)) }
    fun isDownloaded(ctx: Context, id: Long) = localPath(ctx, id) != null
    fun count(ctx: Context): Int = index(ctx).let { o -> o.keys().asSequence().count { File(o.optString(it)).exists() } }
    fun bytesUsed(ctx: Context): Long = dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun download(ctx: Context, item: Item, onDone: (Boolean) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (isDownloaded(ctx, item.i)) { onDone(true); return@withContext }
        val ext = item.n.substringAfterLast('.', if (item.c == 2) "mp4" else "mp3")
        val dest = File(dir(ctx), "${item.i}.$ext")
        val ok = try {
            when {
                item.isCloud -> { val (acct, fid) = Cloud.resolve(item.u) ?: return@withContext onDone(false); Cloud.open(acct, fid, 0).use { s -> dest.outputStream().use { s.input.copyTo(it) } }; true }
                item.u.startsWith("content://") || item.u.startsWith("file://") -> { ctx.contentResolver.openInputStream(Uri.parse(item.u))?.use { i -> dest.outputStream().use { i.copyTo(it) } }; true }
                else -> false
            }
        } catch (_: Exception) { dest.delete(); false }
        if (ok) { val o = index(ctx); o.put(item.i.toString(), dest.path); save(ctx, o)
            // sidecar subtitles / lyrics for offline too
            item.subs?.let { subs -> for (k in 0 until subs.length()) subs.optJSONObject(k)?.let { sub ->
                try { val su = sub.optString("u"); val sf = File(dir(ctx), "${item.i}_${k}_" + sub.optString("n"));
                    if (su.startsWith("omni://")) Cloud.resolve(su)?.let { (a, f) -> Cloud.open(a, f, 0).use { st -> sf.outputStream().use { st.input.copyTo(it) } } }
                    else ctx.contentResolver.openInputStream(Uri.parse(su))?.use { i -> sf.outputStream().use { i.copyTo(it) } }
                    sub.put("local", sf.path)
                } catch (_: Exception) {} }
            }
        }
        onDone(ok)
    }

    /**
     * Downloads a web file (a podcast episode) through Android's download manager — keeps going in the background, shows
     * a progress notification, follows redirects and retries. Blocks until finished (call from Dispatchers.IO);
     * [progress] gets 0..1 (or -1 while the size is unknown). Returns null on success, or the reason it failed.
     */
    fun downloadWithManager(ctx: Context, id: Long, url: String, ext: String, title: String, progress: (Float) -> Unit): String? {
        if (isDownloaded(ctx, id)) return null
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val dest = File(dir(ctx), "$id.$ext"); dest.delete()
        val req = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(title).setDescription("OmniPlay · saving for offline")
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(dest))
            .addRequestHeader("User-Agent", Net.UA)
            .setAllowedOverMetered(Store(ctx).getPref("podMetered", "1") == "1").setAllowedOverRoaming(false)
        val dlId = try { dm.enqueue(req) } catch (e: Exception) { return "the download couldn't start (${e.message})" }
        while (true) {
            Thread.sleep(700)
            val c = dm.query(android.app.DownloadManager.Query().setFilterById(dlId)) ?: return "the download was cancelled"
            c.use {
                if (!it.moveToFirst()) return "the download was cancelled"
                val status = it.getInt(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                val done = it.getLong(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (status) {
                    android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                        if (!dest.exists() || dest.length() == 0L) return "the file was empty"
                        val o = index(ctx); o.put(id.toString(), dest.path); save(ctx, o); progress(1f); return null
                    }
                    android.app.DownloadManager.STATUS_FAILED -> {
                        val r = it.getInt(it.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                        dest.delete()
                        return when (r) {
                            in 400..599 -> "the podcast host refused it (HTTP $r)"
                            android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE -> "the phone is out of storage space"
                            android.app.DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
                            else -> "the connection failed (code $r)"
                        }
                    }
                    else -> progress(if (total > 0) done.toFloat() / total else -1f)
                }
            }
        }
    }

    /** Saves a web file (e.g. a podcast episode) for offline under [id]. */
    fun saveUrl(ctx: Context, id: Long, url: String, ext: String): Boolean {
        if (isDownloaded(ctx, id)) return true
        val dest = File(dir(ctx), "$id.$ext")
        val ok = try { httpDownload(url, dest) } catch (_: Exception) { dest.delete(); false }
        if (ok) { val o = index(ctx); o.put(id.toString(), dest.path); save(ctx, o) }
        return ok
    }

    fun remove(ctx: Context, id: Long) {
        val o = index(ctx); o.optString(id.toString()).takeIf { it.isNotEmpty() }?.let { File(it).delete() }; o.remove(id.toString()); save(ctx, o)
        dir(ctx).listFiles()?.filter { it.name.startsWith("${id}_") }?.forEach { it.delete() }
    }
    fun clearAll(ctx: Context) { dir(ctx).deleteRecursively(); save(ctx, JSONObject()) }
}
