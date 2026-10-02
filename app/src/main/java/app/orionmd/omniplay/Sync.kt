package app.orionmd.omniplay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Progress of a "Sync all" job, shown on the Dashboard. */
data class SyncProg(val done: Int, val total: Int, val step: String)

class SyncState {
    var music by mutableStateOf<SyncProg?>(null)
    var video by mutableStateOf<SyncProg?>(null)
    var musicAt by mutableLongStateOf(0L)
    var videoAt by mutableLongStateOf(0L)
    @Volatile var cancel = false
}

/*
 * "Sync all" — fetches everything the app shows from the internet and saves it on the device (encrypted), so the
 * library looks complete with no connection:
 *   Music  → Deezer/iTunes info (genre, year, length), album-art images, lyrics (LRCLIB, time-synced when available).
 *   Video  → TMDB match + full details (cast, crew, genres, runtime…), poster + backdrop images, cast/trailer/media
 *            extras, TV season & episode info, and subtitles (Wyzie Subs, when a key is set).
 * Already-saved items are skipped, so running it again only picks up new files. Needs internet while it runs.
 */

fun AppState.syncMusic(force: Boolean = false) {
    if (sync.music != null) return
    if (!Net.online(ctx)) { say("Connect to the internet to sync"); return }
    sync.cancel = false
    scope.launch {
        val list = songs()
        var done = 0
        sync.music = SyncProg(0, list.size, "Song info & album art")
        withContext(Dispatchers.IO) {
            val m = store.musicMeta()
            for (x in list) {
                if (sync.cancel) break
                val key = "s:${x.i}"
                // 1) Deezer / iTunes info
                if (force || !m.has(key)) {
                    val r = try { MusicMeta.lookup(x.artist, x.title) } catch (_: Exception) { null }
                    m.put(key, r ?: JSONObject().put("none", true))
                    Thread.sleep(120)
                }
                // 2) album art image, saved encrypted for offline
                m.optJSONObject(key)?.optString("cv")?.takeIf { it.isNotEmpty() }?.let { try { MusicMeta.cover(store, it) } catch (_: Exception) {} }
                // 3) embedded cover from the file itself (device / folder songs)
                if (x.th && !x.isCloud) ImageLoader.file(Img.Art(x.i, x.u, 3))
                // 4) lyrics
                if (!Lyrics.has(ctx, x.i)) try { Lyrics.fetch(ctx, x.i, x.title, x.artist, x.album, durOf(x) / 1000) } catch (_: Exception) {}
                done++
                if (done % 20 == 0) store.saveMusicMeta(m)
                val d = done; scope.launch { sync.music = SyncProg(d, list.size, "Info, album art & lyrics") }
            }
            store.saveMusicMeta(m)
            // album pages: tracklists & covers
            val albums = list.filter { it.al != null }.groupBy { it.albKey }.values.map { it.first() }
            val info = store.albumInfo()
            albums.forEachIndexed { i, a ->
                if (sync.cancel) return@forEachIndexed
                val k = a.albKey
                if (force || !info.has(k)) {
                    val r = try { MusicMeta.album(a.artist, a.album) } catch (_: Exception) { null }
                    store.updateAlbumInfo { it.put(k, r ?: JSONObject().put("none", true)) }
                    r?.optString("cover")?.takeIf { it.isNotEmpty() }?.let { try { MusicMeta.cover(store, it) } catch (_: Exception) {} }
                }
                scope.launch { sync.music = SyncProg(i + 1, albums.size, "Album details") }
            }
        }
        meta = parseMeta(store.musicMeta())
        sync.music = null; sync.musicAt = System.currentTimeMillis(); setPref("musicSyncAt", sync.musicAt.toString())
        countLyrics(); ImageLoader.bump()
        say(if (sync.cancel) "Music sync stopped" else "Music synced — info, album art & lyrics saved for offline")
    }
}

fun AppState.syncVideos(force: Boolean = false) {
    if (sync.video != null) return
    if (!Net.online(ctx)) { say("Connect to the internet to sync"); return }
    if (!store.hasTmdb()) { dialog = Dlg.Message("Add a TMDB key", "Video sync gets posters, cast and details from TMDB. Add your free key in Settings → API keys."); return }
    sync.cancel = false
    scope.launch {
        val vids = videos()
        val subKey = store.secret("wyzieKey").trim()
        val lang = pref("subLang", "en")
        sync.video = SyncProg(0, vids.size, "Matching with TMDB")
        withContext(Dispatchers.IO) {
            val cache = store.tmdbCache()
            val detailed = HashSet<String>()
            vids.forEachIndexed { i, x ->
                if (sync.cancel) return@forEachIndexed
                val key = mkey(x) ?: return@forEachIndexed
                // 1) match
                if (force || !cache.has(key)) {
                    val o = JSONObject().put("n", x.n).put("f", x.f).put("i", x.i); Tmdb.parse(o)
                    val m = try { Tmdb.autoMatch(store, o) } catch (_: Exception) { null }
                    cache.put(key, m?.let { if (x.k == "mv") it else Tmdb.slim(it, if (x.k == "tv") "tv" else "movie") } ?: JSONObject().put("none", true))
                    Thread.sleep(100)
                }
                val m = cache.optJSONObject(key)?.takeIf { !it.optBoolean("none") }
                if (m != null && key !in detailed) {
                    detailed.add(key)
                    // 2) full details
                    if (!m.optBoolean("full")) try { cache.put(key, if (x.k == "mv") MvDb.complete(m) else Tmdb.details(store, m)) } catch (_: Exception) {}
                    val mm = cache.optJSONObject(key)!!
                    // 3) images
                    mm.optString("poster").takeIf { it.isNotEmpty() }?.let { Tmdb.image(store, "w342", it) }
                    mm.optString("backdrop").takeIf { it.isNotEmpty() }?.let { Tmdb.image(store, "w780", it) }
                    // 4) cast, trailers, media (cached a week) + cast photos
                    if (x.k != "mv") try {
                        Tmdb.extras(store, mm.optString("type").ifEmpty { "movie" }, mm.optInt("id"))?.optJSONArray("cast")?.let { c ->
                            for (k in 0 until minOf(c.length(), 9)) c.optJSONObject(k)?.optString("p")?.takeIf { it.isNotEmpty() }?.let { p -> Tmdb.image(store, "w185", p) }
                        }
                    } catch (_: Exception) {}
                }
                // 5) TV: season & episode info
                if (m != null && x.k == "tv" && x.se > 0) {
                    val sk = "$key#s${x.se}"
                    if (!cache.has(sk)) try { cache.put(sk, Tmdb.season(store, m.optInt("id"), x.se)) } catch (_: Exception) {}
                }
                // 6) subtitles
                if (m != null && subKey.isNotEmpty() && x.k != "mv" && x.subs == null && !Subs.saved(store, x.i, lang)) try {
                    Subs.fetch(store, subKey, x.i, m.optInt("id"), if (x.k == "tv") x.se else 0, if (x.k == "tv") x.ep else 0, lang, false)
                } catch (_: Exception) {}
                if (i % 10 == 0) store.saveTmdb(cache)
                scope.launch { sync.video = SyncProg(i + 1, vids.size, if (subKey.isEmpty()) "Details & posters" else "Details, posters & subtitles") }
            }
            store.saveTmdb(cache)
        }
        loadLibrary()
        sync.video = null; sync.videoAt = System.currentTimeMillis(); setPref("videoSyncAt", sync.videoAt.toString())
        ImageLoader.bump()
        say(if (sync.cancel) "Video sync stopped" else "Videos synced — details, posters" + (if (subKey.isNotEmpty()) " & subtitles" else "") + " saved for offline")
    }
}

fun AppState.syncAll() { syncMusic(); scope.launch { while (sync.music != null) kotlinx.coroutines.delay(500); syncVideos() } }

// ---------- podcasts: offline episodes ----------

private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(true).build() }

/** Episodes saved for offline, newest first: [{id, title, url, image, date, duration, show:{id,title,author,image}}]. */
fun AppState.offlineEpisodes(): List<JSONObject> { prefGen; return try { JSONArray(pref("podDl", "[]")).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } } catch (_: Exception) { emptyList() } }
fun AppState.isEpisodeSaved(id: Long) = offlineEpisodes().any { it.optLong("id") == id } && Downloads.isDownloaded(ctx, id)
val podBusy = androidx.compose.runtime.mutableStateMapOf<Long, Boolean>()

fun AppState.downloadEpisode(show: Podcasts.Show, e: Podcasts.Episode) {
    if (podBusy[e.id] == true) return
    podBusy[e.id] = true
    say("Downloading \"${e.title}\"…")
    scope.launch {
        val ok = withContext(Dispatchers.IO) {
            try {
                val ext = e.url.substringBefore('?').substringAfterLast('.', "mp3").takeIf { it.length in 2..4 } ?: "mp3"
                val ok = Downloads.saveUrl(ctx, e.id, e.url, ext)
                if (ok && e.image.isNotEmpty()) try { MusicMeta.cover(store, e.image) } catch (_: Exception) {}
                if (ok && show.image.isNotEmpty()) try { MusicMeta.cover(store, show.image) } catch (_: Exception) {}
                ok
            } catch (_: Exception) { false }
        }
        podBusy.remove(e.id)
        if (ok) {
            val l = offlineEpisodes().filter { it.optLong("id") != e.id }.toMutableList()
            l.add(0, JSONObject().put("id", e.id).put("title", e.title).put("url", e.url).put("image", e.image).put("date", e.date).put("duration", e.duration)
                .put("show", JSONObject().put("id", show.id).put("title", show.title).put("author", show.author).put("image", show.image)))
            setPref("podDl", JSONArray(l).toString()); refreshDownloads()
            say("Saved for offline: ${e.title}")
        } else say("Couldn't download that episode")
    }
}

fun AppState.removeEpisode(id: Long) {
    Downloads.remove(ctx, id)
    setPref("podDl", JSONArray(offlineEpisodes().filter { it.optLong("id") != id }).toString()); refreshDownloads()
}

/** Plays saved episodes (works offline — the player uses the downloaded copy). */
fun AppState.playOfflineEpisodes(list: List<JSONObject>, start: Int) {
    val q = JSONArray()
    list.forEach { o -> val s = o.optJSONObject("show") ?: JSONObject()
        q.put(JSONObject().put("i", o.optLong("id")).put("t", o.optString("title")).put("n", o.optString("title")).put("a", s.optString("title")).put("al", "Podcast")
            .put("u", o.optString("url")).put("th", 0).apply { o.optString("image").ifEmpty { s.optString("image") }.takeIf { it.isNotEmpty() }?.let { put("cv", it) }; if (o.optLong("duration") > 0) put("d", o.optLong("duration")) }) }
    Player.playQueue(ctx, "podcasts", q, start.coerceIn(0, maxOf(0, list.size - 1))); pollPlayer(); sheetOpen = true
}

/** Plain download of a URL (follows redirects across http/https, which podcast hosts use a lot). */
internal fun httpDownload(url: String, dest: File): Boolean {
    val req = Request.Builder().url(url).header("User-Agent", Net.UA).build()
    http.newCall(req).execute().use { r ->
        if (!r.isSuccessful) return false
        val body = r.body ?: return false
        val tmp = File(dest.path + ".part")
        body.byteStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
        return dest.exists() && dest.length() > 0
    }
}

/** Videos stopped part-way (from the player's resume positions), newest first, with how far in (0..1). */
fun AppState.continueWatching(): List<Pair<Item, Float>> {
    prefGen; resumeTick
    val o = try { JSONObject(pref("vpos", "{}")) } catch (_: Exception) { return emptyList() }
    val byId = videos().associateBy { it.i }
    return o.keys().asSequence().mapNotNull { k -> val v = o.optJSONObject(k) ?: return@mapNotNull null; val x = byId[k.toLongOrNull() ?: 0] ?: return@mapNotNull null
        Triple(x, if (v.optLong("d") > 0) (v.optLong("p").toFloat() / v.optLong("d")).coerceIn(0f, 1f) else 0f, v.optLong("t")) }
        .sortedByDescending { it.third }.take(15).map { it.first to it.second }.toList()
}
