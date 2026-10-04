package app.orionmd.omniplay

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Saving cloud (and folder) files for offline, with live progress, pause / resume and resume-after-restart.
 *
 * Each file downloads into "<id>.<ext>.part" next to the offline copies; pausing (or losing the connection, or closing
 * the app) keeps the part, and resuming asks the cloud for the rest from that byte onward (HTTP Range; MEGA decrypts
 * from there). Two files download at once; a dropped connection is retried a few times by itself. The queue is saved,
 * so unfinished downloads come back after a restart. While anything is downloading, [DlService] shows a notification
 * with the overall percentage and keeps it going in the background.
 */
object DlQueue {
    enum class State { QUEUED, RUNNING, PAUSED, FAILED }
    data class Entry(val id: Long, val title: String, val item: String, val state: State, val done: Long, val total: Long,
                     val error: String = "", val speed: Long = 0) {
        val pct get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    /** What the screens show (observable). */
    val entries = mutableStateMapOf<Long, Entry>()
    /** Called after a file finished (so the library can mark it as saved). */
    var onFinished: ((Long, Boolean) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Long, Job>()
    private const val PARALLEL = 2
    private lateinit var app: Context

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        // unfinished downloads from last time: running ones carry on, paused ones stay paused
        try {
            val a = JSONArray(Store(app).getPref("dlq", "[]"))
            for (k in 0 until a.length()) { val o = a.getJSONObject(k)
                val st = State.valueOf(o.optString("state", "QUEUED")).let { if (it == State.RUNNING) State.QUEUED else it }
                entries[o.getLong("id")] = Entry(o.getLong("id"), o.optString("title"), o.optString("item"), st, partSize(o.getLong("id"), o.optString("item")), o.optLong("total", -1))
            }
        } catch (_: Exception) {}
        pump()
    }

    private fun dir() = File(app.getExternalFilesDir(null) ?: app.filesDir, "offline").apply { mkdirs() }
    private fun ext(item: Item) = item.n.substringAfterLast('.', if (item.c == 2) "mp4" else "mp3")
    private fun part(id: Long, itemJson: String) = File(dir(), "$id.${ext(Item.of(JSONObject(itemJson)))}.part")
    private fun partSize(id: Long, itemJson: String) = try { part(id, itemJson).length() } catch (_: Exception) { 0L }

    private fun persist() {
        val a = JSONArray()
        entries.values.forEach { e -> a.put(JSONObject().put("id", e.id).put("title", e.title).put("item", e.item).put("state", e.state.name).put("total", e.total)) }
        Store(app).setPref("dlq", a.toString())
    }
    private val lock = Any()
    private fun set(e: Entry) = synchronized(lock) { entries[e.id] = e }

    // ---------- actions ----------
    fun enqueue(ctx: Context, items: List<Item>, title: (Item) -> String = { it.title }): Int {
        init(ctx); var n = 0
        for (x in items) {
            if (Downloads.isDownloaded(app, x.i) || entries.containsKey(x.i)) continue
            if (!(x.isCloud || x.u.startsWith("content://") || x.u.startsWith("file://"))) continue
            set(Entry(x.i, title(x), x.json().toString(), State.QUEUED, partSize(x.i, x.json().toString()), -1)); n++
        }
        persist(); pump(); return n
    }
    fun pause(id: Long) { jobs.remove(id)?.cancel(); entries[id]?.let { set(it.copy(state = State.PAUSED, speed = 0)) }; persist(); pump() }
    fun resume(id: Long) { entries[id]?.let { set(it.copy(state = State.QUEUED, error = "")) }; persist(); pump() }
    fun toggle(id: Long) { val e = entries[id] ?: return; if (e.state == State.RUNNING || e.state == State.QUEUED) pause(id) else resume(id) }
    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        entries.remove(id)?.let { e -> try { part(e.id, e.item).delete() } catch (_: Exception) {} }
        persist(); pump()
    }
    fun pauseAll() { entries.keys.toList().forEach { id -> if (entries[id]?.state != State.PAUSED) pause(id) } }
    fun resumeAll() { entries.values.toList().forEach { if (it.state == State.PAUSED || it.state == State.FAILED) set(it.copy(state = State.QUEUED, error = "")) }; persist(); pump() }

    /** Overall progress for the notification: (files left, percent or -1). */
    fun overall(): Pair<Int, Int> {
        val l = entries.values.toList(); val known = l.filter { it.total > 0 }
        val pct = if (known.isEmpty()) -1 else (known.sumOf { it.done } * 100 / known.sumOf { it.total }).toInt()
        return l.size to pct
    }

    // ---------- worker ----------
    @Synchronized private fun pump() {
        val running = entries.values.count { it.state == State.RUNNING }
        val next = entries.values.filter { it.state == State.QUEUED }.take((PARALLEL - running).coerceAtLeast(0))
        next.forEach { e -> set(e.copy(state = State.RUNNING)); jobs[e.id] = scope.launch { run(e.id) } }
        if (entries.values.any { it.state == State.RUNNING || it.state == State.QUEUED }) DlService.start(app) else DlService.update(app)
    }

    private suspend fun run(id: Long) {
        var tries = 0
        while (true) {
            val e = entries[id] ?: return
            val item = Item.of(JSONObject(e.item))
            val p = part(id, e.item)
            try {
                copy(item, p, id)
                // finished: move into place and record it
                val dest = File(dir(), "$id.${ext(item)}"); dest.delete()
                if (!p.renameTo(dest)) { p.copyTo(dest, true); p.delete() }
                Downloads.register(app, item, dest)
                entries.remove(id); jobs.remove(id); persist()
                onFinished?.invoke(id, true)
                pump(); return
            } catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (ex: Exception) {
                tries++
                val msg = when {
                    ex is java.net.UnknownHostException || ex is java.net.SocketTimeoutException || ex is java.io.IOException && ex !is java.io.FileNotFoundException -> "connection lost"
                    ex is app.orionmd.omniplay.cloud.CloudError && ex.code == 401 -> "sign-in expired — reconnect the cloud account"
                    ex is app.orionmd.omniplay.cloud.CloudError && ex.code == 404 -> "file not found in the cloud"
                    else -> ex.message ?: "failed"
                }
                if (tries <= 3 && msg == "connection lost") { set(e.copy(error = "connection lost — retrying…", speed = 0)); delay(3000L * tries); continue }
                set(e.copy(state = State.FAILED, error = msg, speed = 0)); jobs.remove(id); persist()
                onFinished?.invoke(id, false)
                pump(); return
            }
        }
    }

    /** Appends to [p] from where it stopped; updates progress about 4× a second. */
    private suspend fun copy(item: Item, p: File, id: Long) {
        val offset = p.length()
        val (input, total) = when {
            item.isCloud -> { val (acct, fid) = Cloud.resolve(item.u) ?: throw Exception("cloud account missing"); Cloud.open(acct, fid, offset).let { it.input to it.length } }
            else -> {
                val u = Uri.parse(item.u)
                val size = try { app.contentResolver.openAssetFileDescriptor(u, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
                val ins = app.contentResolver.openInputStream(u) ?: throw Exception("can't open the file")
                var left = offset; while (left > 0) { val s = ins.skip(left); if (s <= 0) break; left -= s }
                ins to size
            }
        }
        if (total in 1..offset) { input.close(); return }   // already complete
        entries[id]?.let { set(it.copy(total = total, done = offset)) }; persist()
        input.use { ins ->
            FileOutputStream(p, true).use { out ->
                val buf = ByteArray(128 * 1024)
                var done = offset; var lastT = System.currentTimeMillis(); var lastB = done
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    val now = System.currentTimeMillis()
                    if (now - lastT >= 250) {
                        val sp = (done - lastB) * 1000 / (now - lastT).coerceAtLeast(1)
                        entries[id]?.let { set(it.copy(done = done, total = total, speed = sp, error = "")) }
                        lastT = now; lastB = done; DlService.update(app)
                    }
                }
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw kotlinx.coroutines.CancellationException("paused")
            }
        }
        if (total > 0 && p.length() < total) throw java.io.IOException("connection lost")
    }
}
