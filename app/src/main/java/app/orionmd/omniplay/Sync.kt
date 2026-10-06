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
                if (cache.optJSONObject(key)?.optBoolean("manual") != true && (force || needsMatch(cache.optJSONObject(key)))) {
                    val o = JSONObject().put("n", x.n).put("f", x.f).put("i", x.i); Tmdb.parse(o)
                    val m = try { Tmdb.autoMatch(store, o) } catch (_: Exception) { null }
                    if (m != null) cache.put(key, if (x.k == "mv") m else Tmdb.slim(m, if (x.k == "tv") "tv" else "movie"))
                    else if (!cache.has(key) && Net.online(ctx)) cache.put(key, noMatch())
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
                    if (cache.optJSONObject(sk)?.optInt("tvid") != m.optInt("id")) try { cache.put(sk, Tmdb.season(store, m.optInt("id"), x.se).put("tvid", m.optInt("id"))) } catch (_: Exception) {}
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

/** Download progress per episode (0..1, -1 = starting / size unknown). */
val podProg = androidx.compose.runtime.mutableStateMapOf<Long, Float>()

fun AppState.downloadEpisode(show: Podcasts.Show, e: Podcasts.Episode) {
    if (podBusy[e.id] == true) return
    podBusy[e.id] = true; podProg[e.id] = -1f
    say("Downloading \"${e.title}\" — see the notification for progress")
    scope.launch {
        val err = withContext(Dispatchers.IO) {
            val ext = e.url.substringBefore('?').substringAfterLast('.', "mp3").takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "mp3"
            val urls = listOf(e.url, e.orig).filter { it.isNotBlank() }.distinct()
            var last: String? = "no download link in the feed"
            for (u in urls) {
                last = try { Downloads.downloadWithManager(ctx, e.id, u, ext, e.title) { p -> scope.launch { podProg[e.id] = p } } } catch (x: Exception) { x.message ?: "unknown error" }
                if (last == null) break
            }
            if (last == null) {
                if (e.image.isNotEmpty()) try { MusicMeta.cover(store, e.image) } catch (_: Exception) {}
                if (show.image.isNotEmpty()) try { MusicMeta.cover(store, show.image) } catch (_: Exception) {}
            }
            last
        }
        podBusy.remove(e.id); podProg.remove(e.id)
        if (err == null) {
            val l = offlineEpisodes().filter { it.optLong("id") != e.id }.toMutableList()
            l.add(0, JSONObject().put("id", e.id).put("title", e.title).put("url", e.url).put("image", e.image).put("date", e.date).put("duration", e.duration)
                .put("show", JSONObject().put("id", show.id).put("title", show.title).put("author", show.author).put("image", show.image)))
            setPref("podDl", JSONArray(l).toString()); refreshDownloads()
            say("Saved for offline: ${e.title}")
        } else dialog = Dlg.Message("Couldn't download the episode", "\"${e.title}\" couldn't be saved: $err.\n\nYou can still stream it, or try again later.")
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

/**
 * Fills in missing info by itself — no button needed: song info + album art (Deezer/iTunes, no key) and movie/TV matches
 * (TMDB, once a key is saved). Runs after the library loads or is rescanned, and right after an API key is saved. Only
 * new / unmatched items are looked up; "Sync all" also saves lyrics, images and subtitles for offline.
 */
private var enriching = false
/** Something asked for a look-up while one was running (e.g. a folder was added): run again when it finishes. */
private var enrichAgain = false
fun AppState.autoEnrich() {
    if (!Net.online(ctx) || pref("autoEnrich", "1") != "1") return
    if (enriching) { enrichAgain = true; return }
    enriching = true; enrichAgain = false
    scope.launch {
        try {
            // videos first (usually far fewer than songs): TMDB matches (needs a key)
            if (store.hasTmdb() && sync.video == null) {
                val vids = videos()
                val cache = withContext(Dispatchers.IO) { store.tmdbCache() }
                val keys = vids.mapNotNull { x -> mkey(x)?.let { it to x } }.distinctBy { it.first }.filter { (k, _) -> needsMatch(cache.optJSONObject(k)) }
                if (keys.isNotEmpty()) {
                    var found = 0
                    withContext(Dispatchers.IO) {
                        keys.forEachIndexed { i, (key, x) ->
                            val o = JSONObject().put("n", x.n).put("f", x.f).put("i", x.i); Tmdb.parse(o)
                            // a failed request (no connection, server busy) isn't "no match": it's simply tried again next time
                            val r = try { Tmdb.autoMatch(store, o) } catch (e: Tmdb.NoKey) { return@withContext } catch (_: Exception) { return@forEachIndexed }
                            cache.put(key, r?.let { if (x.k == "mv") it else Tmdb.slim(it, if (x.k == "tv") "tv" else "movie") } ?: noMatch())
                            if (r != null) found++
                            if (i % 10 == 0) store.saveTmdb(cache)
                            scope.launch { tmdbProg = (i + 1) to keys.size }
                            Thread.sleep(100)
                        }
                        store.saveTmdb(cache)
                    }
                    tmdbProg = null; loadLibrary()
                    if (found > 0) say("Matched $found movie${if (found == 1) "" else "s"} / show${if (found == 1) "" else "s"} on TMDB")
                }
            }
            // songs: info + cover URL
            val songs = songs()
            val m = withContext(Dispatchers.IO) {
                if (pref("metaFix1", "0") != "1") store.updateMusicMeta { mm -> mm.keys().asSequence().toList().forEach { if (mm.optJSONObject(it)?.optBoolean("none") == true) mm.remove(it) } }
                store.musicMeta()
            }
            if (pref("metaFix1", "0") != "1") setPref("metaFix1", "1")
            val todo = songs.filter { !m.has("s:${it.i}") }
            if (todo.isNotEmpty() && sync.music == null) {
                var found = 0
                withContext(Dispatchers.IO) {
                    todo.forEachIndexed { i, x ->
                        val r = try { MusicMeta.lookup(x.artist, x.title) } catch (_: Exception) { null }
                        m.put("s:${x.i}", r ?: JSONObject().put("none", true).put("t", System.currentTimeMillis())); if (r != null) found++
                        if (i % 15 == 0) { store.saveMusicMeta(m); val mm = parseMeta(m); scope.launch { meta = mm; metaProg = Triple(i + 1, todo.size, found) } }
                        if (enrichAgain) { store.saveMusicMeta(m); return@withContext }   // new files were added: match them first, then carry on here
                        Thread.sleep(120)
                    }
                    store.saveMusicMeta(m)
                }
                meta = parseMeta(m); metaProg = null
                if (found > 0) say("Found info & album art for $found song${if (found == 1) "" else "s"}")
            }
        } finally {
            enriching = false
            if (enrichAgain) autoEnrich()
        }
    }
}

/** "No match" answers are tried again after a few days (TMDB adds titles; a title may have been mistyped then). */
internal fun noMatch(): JSONObject = JSONObject().put("none", true).put("t", System.currentTimeMillis())
internal fun needsMatch(m: JSONObject?): Boolean = m == null ||
    (m.optBoolean("none") && !m.optBoolean("manual") && System.currentTimeMillis() - m.optLong("t") > 3L * 24 * 3600 * 1000)

/** A new key was saved: drop earlier "not found" answers so they're tried again, then look everything up. */
fun AppState.onKeySaved(k: String) {
    scope.launch {
        withContext(Dispatchers.IO) {
            if (k.startsWith("tmdb")) store.updateTmdb { c -> c.keys().asSequence().toList().forEach { if (c.optJSONObject(it)?.optBoolean("none") == true) c.remove(it) } }
        }
        if (k.startsWith("tmdb")) { val r = withContext(Dispatchers.IO) { Tmdb.test(store) }; say(r); if (r.contains("works")) autoEnrich() }
    }
}


/** Key typed in Settings / on the Dashboard: verified in the background (or held as pending when offline). */
fun AppState.enterActivationKey(k: String) {
    say("Checking your key…")
    scope.launch {
        val (_, m) = withContext(Dispatchers.IO) { Activation.enterKey(ctx, store, k) }
        prefGen++; dialog = Dlg.Message("Activation", m)
    }
}

/** "Trial - X days left" / "Pending verification - X days left to confirm" / "Activated". */
fun AppState.licenceStatus(): Pair<String, String> { prefGen
    return when (Activation.state(store)) {
        Activation.State.ACTIVATED -> "Activated" to Activation.holder(store).let { if (it.isNotEmpty()) "Full version · $it" else "Full version" }
        Activation.State.PENDING -> "Pending verification" to "${Activation.pendingDaysLeft(store)} day(s) left to confirm your key — connect to the internet"
        Activation.State.TRIAL -> "Free trial" to "${Activation.trialDaysLeft(store)} day(s) left"
        Activation.State.LOCKED -> "Locked" to "Enter a key or purchase one"
    }
}
