package app.orionmd.omniplay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * TV episodes and per-title subtitles: episode details from TMDB (name, summary, still), putting a file under the right
 * season / episode when its name was read wrong, and subtitles for one movie or one show instead of the whole library.
 */

// ---------- your season / episode fixes ----------
/** Season and episode you set for a file: {"<item id>": "season:episode"}. */
private fun AppState.epFixes(): JSONObject = try { JSONObject(pref("epFix", "{}")) } catch (_: Exception) { JSONObject() }

/** The item with your season / episode fix applied (unchanged when there's none). */
internal fun applyEpFix(x: Item, fixes: JSONObject): Item {
    if (x.k != "tv") return x
    val p = fixes.optString(x.i.toString()).split(':').takeIf { it.size == 2 } ?: return x
    val se = p[0].toIntOrNull() ?: return x; val ep = p[1].toIntOrNull() ?: return x
    return x.copy(se = se, ep = ep)
}
internal fun AppState.epFixesForLoad(): JSONObject = epFixes()

fun AppState.episodeFixed(x: Item) = epFixes().has(x.i.toString())
fun AppState.setEpisode(x: Item, se: Int, ep: Int) {
    setPref("epFix", epFixes().put(x.i.toString(), "$se:$ep").toString())
    afterEpisodeChange(x, se, "Now " + epLabel(se, ep))
}
fun AppState.resetEpisode(x: Item) {
    setPref("epFix", epFixes().apply { remove(x.i.toString()) }.toString())
    afterEpisodeChange(x, 0, "Back to the number in the file name")
}
private fun AppState.afterEpisodeChange(x: Item, se: Int, msg: String) {
    scope.launch(Dispatchers.IO) { Subs.remove(store, x.i) }   // its subtitles were for the old episode
    loadLibrary()
    mkey(x)?.let { k -> if (se > 0) loadSeasons(k, listOf(se)) }
    say(msg)
}

// ---------- episode details ----------
/** A loaded season of the show's current match (one saved for an earlier, different match is ignored and loaded again). */
fun AppState.seasonOf(showKey: String, se: Int): JSONObject? {
    val id = tmdb[showKey]?.takeIf { !it.optBoolean("none") }?.optInt("id") ?: return null
    return tmdb["$showKey#s$se"]?.takeIf { it.optInt("tvid") == id }
}
/** TMDB details of one episode (name, overview, still, air date, runtime), once its season is loaded. */
fun AppState.epInfo(x: Item): JSONObject? = if (x.k != "tv" || x.se <= 0) null else mkey(x)?.let { seasonOf(it, x.se) }?.optJSONObject(x.ep.toString())

private val seasonLoading = java.util.Collections.synchronizedSet(HashSet<String>())
/** Loads episode names, summaries and stills for these seasons of a show (once; saved with the other TMDB data). */
fun AppState.loadSeasons(showKey: String, seasons: Collection<Int>) {
    val id = tmdb[showKey]?.takeIf { !it.optBoolean("none") }?.optInt("id") ?: return
    if (id <= 0 || !Net.online(ctx)) return
    val todo = seasons.filter { it > 0 && seasonOf(showKey, it) == null && seasonLoading.add("$showKey#s$it#$id") }
    if (todo.isEmpty()) return
    scope.launch {
        val got = withContext(Dispatchers.IO) {
            val got = HashMap<String, JSONObject>()
            for (se in todo) {
                val k = "$showKey#s$se"
                try { Tmdb.season(store, id, se).takeIf { it.length() > 0 }?.let { got[k] = it.put("tvid", id) } } catch (_: Exception) {}
                if (k !in got) seasonLoading.remove("$k#$id")   // try again next time
            }
            if (got.isNotEmpty()) store.updateTmdb { c -> got.forEach { (k, v) -> c.put(k, v) } }
            got
        }
        if (got.isNotEmpty()) loadLibrary()
    }
}

/** After a show is matched to a different TMDB show: its saved seasons and episode subtitles belonged to the old one. */
internal fun AppState.forgetShow(key: String, eps: List<Item>) {
    synchronized(seasonLoading) { seasonLoading.removeAll { it.startsWith("$key#s") } }
    store.updateTmdb { c -> c.keys().asSequence().filter { it.startsWith("$key#s") }.toList().forEach { c.remove(it) } }
    eps.forEach { Subs.remove(store, it.i) }
}

// ---------- subtitles for one title ----------
fun AppState.wyzieOn() = store.secret("wyzieKey").isNotBlank()
fun AppState.hasOnlineSub(x: Item) = Subs.saved(store, x.i, pref("subLang", "en").ifBlank { "en" })
fun AppState.removeOnlineSub(x: Item) = Subs.remove(store, x.i)

/** Downloads subtitles for one video (returns a message). */
suspend fun AppState.fetchSub(x: Item): String {
    if (!wyzieOn()) return "Add your Wyzie Subs key in Settings → API keys"
    val m = match(x) ?: return "Match this video on TMDB first (Fix match)"
    val id = m.json.optInt("id"); if (id <= 0 || x.k == "mv") return "Match this video on TMDB first (Fix match)"
    if (x.k == "tv" && (x.se <= 0 || x.ep <= 0)) return "Set this episode's season and number first"
    val lang = pref("subLang", "en").ifBlank { "en" }
    return try {
        if (withContext(Dispatchers.IO) { Subs.fetch(store, store.secret("wyzieKey"), x.i, id, if (x.k == "tv") x.se else 0, if (x.k == "tv") x.ep else 0, lang, false) }) "Subtitles saved" else "No subtitles found"
    } catch (e: Exception) { e.message ?: "Subtitle search failed" }
}

/** Progress of a "Get subtitles" for one show: done / total (null when idle). */
var subProg by mutableStateOf<Pair<Int, Int>?>(null)
/** Subtitles for these videos (one show's episodes) that don't have any yet. */
fun AppState.fetchSubs(list0: List<Item>) {
    if (subProg != null) return
    if (!wyzieOn()) { say("Add your Wyzie Subs key in Settings → API keys"); return }
    val list = list0.filter { it.subs == null && !hasOnlineSub(it) && match(it) != null && (it.k != "tv" || it.se > 0 && it.ep > 0) }
    if (list.isEmpty()) { say("These already have subtitles (or aren't matched yet)"); return }
    subProg = 0 to list.size
    scope.launch {
        var ok = 0
        for ((n, x) in list.withIndex()) {
            if (subProg == null) break
            if (fetchSub(x) == "Subtitles saved") ok++
            subProg = n + 1 to list.size
        }
        subProg = null; say("Subtitles saved for $ok of ${list.size}")
    }
}
