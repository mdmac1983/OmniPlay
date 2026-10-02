package app.orionmd.omniplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Things only an Activity can do (folder picker, opening links / the video screen). */
interface Host {
    fun openTree(cb: (Uri?) -> Unit)
    fun launch(intent: Intent)
}

sealed class Page {
    data class Album(val key: String, val name: String) : Page()
    data class Artist(val name: String) : Page()
    data class FolderPage(val name: String) : Page()
    data class GenrePage(val name: String) : Page()
    data class Movie(val item: Item) : Page()
    data class Show(val key: String) : Page()
    data class PlaylistPage(val id: String) : Page()
    data class PodcastShow(val show: Podcasts.Show) : Page()
    data class CloudBrowse(val provider: String, val acctId: String, val folderId: String, val path: String) : Page()
    object Podcasts : Page()
    object Discover : Page()
    object Settings : Page()
    object Downloads : Page()
}

sealed class Dlg {
    data object SkinPick : Dlg()
    data object VisPick : Dlg()
    data object Sleep : Dlg()
    data class AddToPlaylist(val ids: List<Long>, val queue: Boolean = false) : Dlg()
    data class Confirm(val title: String, val msg: String, val danger: Boolean = false, val ok: () -> Unit) : Dlg()
    data class Message(val title: String, val msg: String) : Dlg()
    data class TextInput(val title: String, val label: String, val initial: String, val password: Boolean = false, val ok: (String) -> Unit) : Dlg()
    data class Choice(val title: String, val msg: String, val options: List<Pair<String, () -> Unit>>) : Dlg()
    data class FixMatch(val key: String, val query: String, val type: String, val year: Int) : Dlg()
    data class ConnectWebDav(val provider: String) : Dlg()
    data class CloudPassword(val provider: String) : Dlg()
    data object AddSource : Dlg()
}

data class PState(
    val playing: Boolean = false, val preparing: Boolean = false, val error: String = "", val index: Int = -1, val size: Int = 0,
    val repeat: String = "off", val sourceId: String = "", val pos: Long = 0, val dur: Long = 0, val track: JSONObject? = null,
)

class AppState(val ctx: Context, val host: Host) {
    val store = Store(ctx)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var prefGen by mutableIntStateOf(0)

    // ---------- library ----------
    var sources by mutableStateOf(listOf<Source>())
    var allItems by mutableStateOf(listOf<Item>())
    var meta by mutableStateOf(mapOf<Long, Meta>())
    var tmdb by mutableStateOf(mapOf<String, JSONObject>())
    var loading by mutableStateOf(false)
    var online by mutableStateOf(true)
    var scanProg by mutableStateOf<Pair<String, Int>?>(null)

    fun songs() = allItems.filter { it.c == 3 }
    fun videos() = allItems.filter { it.c == 2 }

    // ---------- navigation / ui ----------
    var tab by mutableStateOf("home")
    val pages = androidx.compose.runtime.mutableStateListOf<Page>()
    var sheetOpen by mutableStateOf(false)
    var soundOpen by mutableStateOf(false)
    var dialog by mutableStateOf<Dlg?>(null)
    var toast by mutableStateOf<Pair<String, Long>?>(null)
    var player by mutableStateOf(PState())
    var query by mutableStateOf("")
    val settingsOpen = mutableStateMapOf<String, Boolean>()
    fun openSettingsCat(k: String) { settingsOpen[k] = true }
    fun toggleSettingsCat(k: String) { settingsOpen[k] = settingsOpen[k] != true }

    fun pref(k: String, d: String) = store.getPref(k, d)
    fun setPref(k: String, v: String) { store.setPref(k, v); prefGen++ }
    fun say(msg: String) { toast = msg to System.currentTimeMillis() }
    fun open(p: Page) { pages.add(p) }
    fun back(): Boolean { return if (pages.isNotEmpty()) { pages.removeAt(pages.size - 1); true } else false }
    fun openUrl(u: String) { try { host.launch(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { say("No app to open that link") } }

    // ---------- lifecycle ----------
    fun start() {
        Cloud.init(store)
        ImageLoader.init(ctx, store)
        online = Net.online(ctx)
        loadSources()
        loadLibrary()
        loadHistory()
        Sound.load(store)
        Profiles.watch(ctx, this)
        if (pref("sync", "0") == "1") app.orionmd.omniplay.cloud.SyncWorker.schedule(ctx, pref("syncHours", "24").toLongOrNull() ?: 24, pref("syncWifiOnly", "1") == "1")
        countLyrics()
    }
    fun onResume() { val o = Net.online(ctx); if (o && !online) ImageLoader.bump(); online = o; refreshDownloads() }

    fun loadSources() {
        sources = try { JSONArray(pref("sources", "[]")).let { a -> (0 until a.length()).map { Source.of(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
        // first run: offer the device library automatically
        if (sources.isEmpty() && pref("noDevice", "0") != "1" && !sources.any { it.kind == "device" }) {
            sources = listOf(Source("device", "device", "This device", count = 0))
            saveSources()
        }
    }
    private fun saveSources() { setPref("sources", JSONArray(sources.map { it.json() }).toString()) }
    fun addSource(s: Source) { sources = sources.filter { it.id != s.id } + s; saveSources() }
    fun removeSource(id: String) { sources = sources.filter { it.id != id }; saveSources(); store.libFile(id).delete(); loadLibrary() }
    fun updateSource(id: String, f: (Source) -> Source) { sources = sources.map { if (it.id == id) f(it) else it }; saveSources() }

    fun loadLibrary() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val items = ArrayList<Item>()
                for (s in sources) items.addAll(Library.read(store, s.id))
                val m = parseMeta(store.musicMeta())
                val t = HashMap<String, JSONObject>(); val tc = store.tmdbCache(); tc.keys().forEach { k -> tc.optJSONObject(k)?.let { t[k] = it } }
                Triple(items, m, t)
            }
            allItems = loaded.first; meta = loaded.second; tmdb = loaded.third
        }
    }

    /** Rescan one source (or all). */
    fun sync(id: String? = null) {
        val list = if (id == null) sources else sources.filter { it.id == id }
        scope.launch {
            for (s in list) {
                scanProg = s.name to 0
                val items = withContext(Dispatchers.IO) { Library.scan(ctx, store, s) { n -> scanProg = s.name to n } }
                updateSource(s.id) { it.copy(count = items.size, lastScan = System.currentTimeMillis()) }
            }
            scanProg = null
            loadLibrary()
            say("Updated the library")
        }
    }

    // ---------- device / folder / cloud sources ----------
    fun addDevice() { addSource(Source("device", "device", "This device")); sync("device") }
    fun addFolder() {
        host.openTree { uri ->
            if (uri == null) return@openTree
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Folder"
            val s = Source("f" + System.currentTimeMillis().toString(36), "folder", name, tree = uri.toString())
            addSource(s); sync(s.id)
        }
    }
    fun addCloudFolder(acctId: String, folderId: String, path: String, sync: Boolean) {
        val acct = Cloud.account(acctId) ?: return
        val name = (Cloud.provider(acct.provider)?.name ?: "Cloud") + (if (path.isNotEmpty()) " · ${path.substringAfterLast('/')}" else "")
        val s = Source("c" + System.currentTimeMillis().toString(36), "cloud", name, acct = acctId, folderId = folderId, path = path, sync = sync)
        addSource(s); sync(s.id)
        if (sync) app.orionmd.omniplay.cloud.SyncWorker.schedule(ctx, pref("syncHours", "24").toLongOrNull() ?: 24, pref("syncWifiOnly", "1") == "1")
    }

    // ---------- play counts / history ----------
    var plays by mutableStateOf(emptyMap<Long, Int>()); private set
    var resumeTick by mutableIntStateOf(0)
    private var lastTrack = 0L
    fun loadHistory() { plays = try { JSONObject(pref("plays", "{}")).let { o -> o.keys().asSequence().associate { it.toLong() to o.getInt(it) } } } catch (_: Exception) { emptyMap() } }
    private fun notePlayed(id: Long) { if (id == 0L) return; val m = plays.toMutableMap(); m[id] = (m[id] ?: 0) + 1; plays = m; setPref("plays", JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } }.toString()) }

    // ---------- music player ----------
    fun playList(list: List<Item>, idx: Int) {
        if (list.isEmpty()) return
        val q = JSONArray()
        list.forEach { q.put(it.track(meta[it.i]?.cv)) }
        Player.playQueue(ctx, list.getOrNull(idx)?.src ?: "", q, idx.coerceIn(0, list.size - 1))
        pollPlayer(); sheetOpen = true
    }
    fun playItem(x: Item, list: List<Item>) = playList(list, list.indexOf(x).coerceAtLeast(0))
    fun shuffleQueue() {
        val qn = Player.queue; if (qn.length() < 2) return
        val l = (0 until qn.length()).map { qn.getJSONObject(it) }.toMutableList()
        val cur = l.removeAt(player.index.coerceIn(0, l.size - 1)); l.shuffle()
        val nq = JSONArray().put(cur); l.forEach { nq.put(it) }
        Player.playQueue(ctx, player.sourceId, nq, 0); say("Queue shuffled")
    }
    fun cycleRepeat() { val order = listOf("off", "all", "one"); val n = order[(order.indexOf(player.repeat) + 1) % 3]; Player.repeat = n; player = player.copy(repeat = n); say("Repeat: $n") }
    fun stopPlayer() { Player.stop(); player = PState(); sheetOpen = false; say("Player stopped") }

    private var polling = false
    fun pollPlayer() {
        if (polling) return; polling = true
        scope.launch {
            while (true) {
                val o = Player.state()
                o.optJSONObject("track")?.optLong("i")?.takeIf { it != 0L && it != lastTrack && o.optBoolean("playing") }?.let { lastTrack = it; notePlayed(it) }
                player = PState(o.optBoolean("playing"), o.optBoolean("preparing"), o.optString("error"), o.optInt("index", -1), o.optInt("size"),
                    o.optString("repeat", "off"), o.optString("sourceId"), o.optLong("pos"), o.optLong("dur"), o.optJSONObject("track"))
                if (player.track == null && !player.preparing) { polling = false; break }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    fun playerArt(t: JSONObject?, large: Boolean = false): Img? {
        if (t == null) return null
        t.optString("cv").takeIf { it.isNotEmpty() }?.let { return Img.Cover(it) }
        return if (t.optInt("th") == 1) Img.Art(t.optLong("i"), t.optString("u"), 3, large) else null
    }
    fun playerAlt(t: JSONObject?): Img? = t?.takeIf { it.optString("cv").isEmpty() }?.let { meta[it.optLong("i")]?.cv }?.let { Img.Cover(it) }

    // pictures for library items
    fun artReq(x: Item?, large: Boolean = false): Img? {
        if (x == null) return null
        meta[x.i]?.cv?.let { return Img.Cover(it) }
        return if (x.th) Img.Art(x.i, x.u, x.c, large) else null
    }
    fun artAlt(x: Item?): Img? = x?.let { meta[it.i]?.cv?.let { c -> Img.Cover(c) } }
    fun durOf(x: Item) = if (x.d > 0) x.d else meta[x.i]?.d ?: 0L
    fun genreOf(x: Item) = x.g ?: meta[x.i]?.g

    // ---------- video ----------
    fun openVideo(x: Item, list: List<Item> = videos()) {
        val start = list.indexOf(x).coerceAtLeast(0)
        val ordered = list.subList(start, list.size)
        val q = JSONArray()
        ordered.forEach { v -> q.put(JSONObject().put("i", v.i).put("t", vtitle(v)).put("u", v.u).apply { v.subs?.let { put("subs", it) } }) }
        try { host.launch(Intent(ctx, VideoActivity::class.java).putExtra("queue", q.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { say("Couldn't open the video") }
    }
    fun vtitle(x: Item): String { val m = match(x); return when (x.k) { "tv" -> m?.title ?: x.show ?: x.pt ?: x.title; "movie" -> m?.title ?: x.pt ?: x.title; else -> x.pt ?: x.title } }

    // ---------- lyrics ----------
    val lyrics = mutableStateMapOf<Long, Lyrics.L>()
    private val lyricsBusy = HashSet<Long>()
    var lyricsSaved by mutableIntStateOf(-1)
    var showLyrics by mutableStateOf(pref("showlyrics", "0") == "1")
    fun toggleLyrics() { showLyrics = !showLyrics; if (showLyrics) showVis = false; setPref("showlyrics", if (showLyrics) "1" else "0") }
    fun lyricsFor(t: JSONObject, durMs: Long) {
        val id = t.optLong("i"); if (id == 0L || id in lyrics || id in lyricsBusy) return
        lyricsBusy.add(id)
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                Lyrics.cached(ctx, id)?.let { return@withContext it }
                val x = allItems.firstOrNull { it.i == id }
                // .lrc sidecar first
                x?.lrc?.let { lrc -> try { val txt = if (lrc.startsWith("omni://")) { val (a, f) = Cloud.resolve(lrc)!!; String(Cloud.open(a, f, 0).use { s -> s.input.readBytes() }) } else ctx.contentResolver.openInputStream(Uri.parse(lrc))?.use { String(it.readBytes()) } ?: ""; if (txt.isNotBlank()) return@withContext Lyrics.saveLrc(ctx, id, txt) } catch (_: Exception) {} }
                if (!Net.online(ctx)) null else try {
                    Lyrics.fetch(ctx, id, x?.title ?: t.optString("t"), x?.a ?: t.optString("a"), x?.al ?: t.optString("al"), (x?.let { durOf(it) }?.takeIf { it > 0 } ?: durMs) / 1000)
                } catch (_: Exception) { null }
            }
            lyricsBusy.remove(id); if (r != null) lyrics[id] = r
        }
    }
    fun countLyrics() { scope.launch { lyricsSaved = withContext(Dispatchers.IO) { Lyrics.savedCount(ctx) } } }
    var lyricsProg by mutableStateOf<Pair<Int, Int>?>(null)
    fun fetchAllLyrics() {
        scope.launch {
            val list = songs(); lyricsProg = 0 to list.size
            withContext(Dispatchers.IO) {
                list.forEachIndexed { i, x ->
                    if (!Lyrics.has(ctx, x.i)) try { Lyrics.fetch(ctx, x.i, x.title, x.artist, x.album, durOf(x) / 1000) } catch (_: Exception) {}
                    scope.launch { lyricsProg = (i + 1) to list.size }
                    Thread.sleep(60)
                }
            }
            lyricsProg = null; countLyrics(); say("Lyrics downloaded")
        }
    }

    // ---------- online music meta ----------
    var metaProg by mutableStateOf<Triple<Int, Int, Int>?>(null)
    fun lookupMeta(force: Boolean = false) {
        scope.launch {
            val list = songs(); val m = store.musicMeta(); var matched = 0
            metaProg = Triple(0, list.size, 0)
            withContext(Dispatchers.IO) {
                list.forEachIndexed { i, x ->
                    val key = "s:${x.i}"
                    if (!force && m.has(key)) return@forEachIndexed
                    val r = try { MusicMeta.lookup(x.artist, x.title) } catch (_: Exception) { null }
                    if (r != null) { m.put(key, r); matched++ } else m.put(key, JSONObject().put("none", true))
                    if (i % 20 == 0) store.saveMusicMeta(m)
                    val mm = matched; scope.launch { metaProg = Triple(i + 1, list.size, mm) }
                    Thread.sleep(120)
                }
                store.saveMusicMeta(m)
            }
            meta = parseMeta(m); metaProg = null; say("Found info for $matched songs")
        }
    }

    // ---------- TMDB ----------
    fun mkey(x: Item): String? = when (x.k) { "movie" -> "f:${x.i}"; "tv" -> "tv:" + (x.show ?: "").lowercase(); "mv" -> "mv:${x.i}"; else -> null }
    fun match(x: Item): Match? = mkey(x)?.let { tmdb[it] }?.let { Match.of(it) }
    fun posterReq(x: Item): Img? = match(x)?.poster?.takeIf { it.isNotEmpty() }?.let { Img.Poster("w342", it) } ?: artReq(x)
    var tmdbProg by mutableStateOf<Pair<Int, Int>?>(null)
    fun matchAll(force: Boolean = false) {
        if (!store.hasTmdb()) { dialog = Dlg.Message("Add a TMDB key", "Movie and TV matching needs a free TMDB key. Add it in Settings → API keys."); return }
        scope.launch {
            val vids = videos(); tmdbProg = 0 to vids.size
            withContext(Dispatchers.IO) {
                val cache = store.tmdbCache()
                vids.forEachIndexed { i, x ->
                    val key = mkey(x) ?: return@forEachIndexed
                    if (!force && cache.has(key)) return@forEachIndexed
                    val o = JSONObject().put("n", x.n).put("f", x.f).put("i", x.i); Tmdb.parse(o)
                    val m = try { Tmdb.autoMatch(store, o) } catch (_: Exception) { null }
                    cache.put(key, m?.let { Tmdb.slim(it, x.k ?: "movie") } ?: JSONObject().put("none", true))
                    if (i % 10 == 0) store.saveTmdb(cache)
                    scope.launch { tmdbProg = (i + 1) to vids.size }
                    Thread.sleep(120)
                }
                store.saveTmdb(cache)
            }
            loadLibrary(); tmdbProg = null; say("Matched movies & TV")
        }
    }
    suspend fun tmdbSearch(q: String, type: String): List<JSONObject> = withContext(Dispatchers.IO) {
        try {
            when (type) {
                "movie" -> Tmdb.search(store, q, "movie").objsList()
                "tv" -> Tmdb.search(store, q, "tv").objsList()
                "mv" -> MvDb.search(q)
                else -> Tmdb.search(store, q, "movie").objsList() + Tmdb.search(store, q, "tv").objsList()
            }
        } catch (_: Exception) { emptyList() }
    }
    fun fixMatch(x: Item) { mkey(x)?.let { dialog = Dlg.FixMatch(it, x.pt ?: x.title, if (x.k == "tv") "tv" else if (x.k == "mv") "mv" else "movie", x.py) } }
    fun tmdbSetMatch(key: String, m: JSONObject?) {
        scope.launch { withContext(Dispatchers.IO) { store.updateTmdb { it.put(key, m ?: JSONObject().put("none", true)) } }; loadLibrary() }
    }
    fun loadDetails(x: Item) {
        val key = mkey(x) ?: return; val m = tmdb[key] ?: return
        if (m.optBoolean("detailed")) return
        scope.launch { withContext(Dispatchers.IO) { try { val full = if (x.k == "mv") MvDb.complete(m) else Tmdb.details(store, m); store.updateTmdb { it.put(key, full) } } catch (_: Exception) {} }; loadLibrary() }
    }

    // ---------- downloads / offline ----------
    var dl by mutableStateOf(mapOf<Long, Boolean>())
    fun refreshDownloads() { dl = Downloads.index(ctx).let { o -> o.keys().asSequence().associate { it.toLong() to java.io.File(o.optString(it)).exists() } } }
    fun isDownloaded(x: Item) = dl[x.i] == true
    fun download(list: List<Item>) {
        scope.launch { var ok = 0; for (x in list) Downloads.download(ctx, x) { if (it) ok++ }; refreshDownloads(); say("Saved $ok for offline") }
    }
    fun removeDownload(x: Item) { Downloads.remove(ctx, x.i); refreshDownloads() }

    // ---------- search (catalog) ----------
    var catalog by mutableStateOf<List<JSONObject>?>(null)
    fun searchCatalog(q: String) { scope.launch { catalog = withContext(Dispatchers.IO) { MusicMeta.searchCatalog(q) } } }
    fun playPreview(o: JSONObject) {
        val q = JSONArray().put(JSONObject().put("i", o.optLong("id")).put("t", o.optString("t")).put("a", o.optString("a")).put("al", o.optString("al"))
            .put("u", o.optString("preview")).put("cv", o.optString("cover")).put("th", 0).put("d", o.optLong("d")))
        Player.playQueue(ctx, "preview", q, 0); pollPlayer(); sheetOpen = true
    }

    // ---------- skins / visualizer ----------
    var showVis by mutableStateOf(pref("visShow", "0") == "1")
    var visStyle by mutableStateOf(pref("visStyle", "bars"))
    var visFull by mutableStateOf(false)
    fun toggleVis() { showVis = !showVis; if (showVis && showLyrics) toggleLyrics(); setPref("visShow", if (showVis) "1" else "0") }
    fun nextVisStyle() { val l = app.orionmd.omniplay.ui.VIS_STYLES.map { it.first }; visStyle = l[(l.indexOf(visStyle) + 1) % l.size]; setPref("visStyle", visStyle) }
}

private fun JSONArray.objsList(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
