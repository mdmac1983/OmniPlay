package app.orionmd.omniplay.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun cover(u: String): Img? = u.takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }

@Composable
fun PodcastsPage(st: AppState, pad: PaddingValues) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("shows") }
    var results by remember { mutableStateOf<List<Podcasts.Show>?>(null) }
    var trending by remember { mutableStateOf<List<Podcasts.Show>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val subs = remember(st.resumeTick, st.dialog) { Podcasts.subscriptions(st) }
    val keys = Podcasts.hasKeys(st)
    LaunchedEffect(keys) { if (keys && st.online) try { trending = withContext(Dispatchers.IO) { Podcasts.trending(st) } } catch (e: Exception) { err = e.message } }
    fun search() {
        if (q.isBlank() || busy) return
        busy = true; err = null
        scope.launch { try { results = withContext(Dispatchers.IO) { Podcasts.search(st, q.trim(), kind == "music") } } catch (e: Exception) { err = e.message ?: "Search failed" }; busy = false }
    }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Text("Podcasts", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (!keys) {
                Muted("Add your free Podcast Index API key and secret in Settings → API keys (podcastindex.org → sign up → API Keys).", Modifier.padding(vertical = 10.dp), 13.sp)
                Btn("Open Settings", { st.pages.clear(); st.tab = "set"; st.openSettingsCat("keys") }, small = true)
                return@item
            }
            if (!st.online) Muted("You're offline — podcasts stream from the internet.", Modifier.padding(vertical = 6.dp), 13.sp)
            Seg(listOf("shows" to "Podcasts", "music" to "Music podcasts"), kind) { kind = it; if (q.isNotBlank()) search() }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                Field(q, { q = it }, if (kind == "music") "Search music podcasts…" else "Search podcasts…", Modifier.weight(1f))
                IconBtn(Ic.search, { search() }, Modifier.padding(start = 4.dp))
            }
            if (busy) Progress(null); err?.let { Muted(it, Modifier.padding(vertical = 6.dp), 13.sp) }
        }
        if (!keys) return@LazyColumn
        results?.let { r -> item { SectionHeader("Results (${r.size})") }; if (r.isEmpty()) item { Muted("Nothing found.") }; items(r, key = { "r" + it.id }) { s -> ShowRow(st, s) } }
        if (subs.isNotEmpty()) { item { SectionHeader("Subscribed") }; items(subs, key = { "s" + it.id }) { s -> ShowRow(st, s) } }
        if (trending.isNotEmpty() && results == null) { item { SectionHeader("Trending") }; items(trending, key = { "t" + it.id }) { s -> ShowRow(st, s) } }
    }
}

@Composable
private fun ShowRow(st: AppState, s: Podcasts.Show) {
    ListRow(cover(s.image), s.title, s.title, listOf(s.author, if (s.episodes > 0) "${s.episodes} episodes" else "").filter { it.isNotEmpty() }.joinToString(" · "),
        { st.open(Page.PodcastShow(s)) }, badge = if (Podcasts.isSubscribed(st, s.id)) "★" else null)
}

@Composable
fun PodcastShowPage(st: AppState, p: Page.PodcastShow, pad: PaddingValues) {
    val s = p.show
    var eps by remember(s.id) { mutableStateOf<List<Podcasts.Episode>?>(null) }
    var err by remember(s.id) { mutableStateOf<String?>(null) }
    var subTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(s.id) { try { eps = withContext(Dispatchers.IO) { Podcasts.episodes(st, s.id, s.image) } } catch (e: Exception) { err = e.message ?: "Couldn't load episodes" } }
    val playingId = st.player.track?.optLong("i")
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(cover(s.image), s.title, Modifier.size(96.dp), px = 300, corner = 14.dp)
                Column(Modifier.padding(start = 14.dp).weight(1f)) { Text(s.title, fontSize = 20.sp, fontWeight = FontWeight.Bold); if (s.author.isNotEmpty()) Muted(s.author, size = 13.sp) }
            }
            if (s.description.isNotEmpty()) Muted(s.description.take(400), Modifier.padding(top = 10.dp), 13.sp)
            val subbed = remember(subTick) { Podcasts.isSubscribed(st, s.id) }
            BtnRow(Modifier.padding(vertical = 10.dp)) {
                eps?.takeIf { it.isNotEmpty() }?.let { l -> Btn("Play latest", { Podcasts.play(st, s, l, 0) }, small = true, icon = Ic.play) }
                Btn(if (subbed) "★ Subscribed" else "☆ Subscribe", { Podcasts.toggle(st, s); subTick++ }, kind = BtnKind.Ghost, small = true)
            }
            if (eps == null && err == null) Progress(null); err?.let { Muted(it, Modifier.padding(vertical = 8.dp)) }
            eps?.let { SectionHeader("Episodes (${it.size})") }
        }
        val list = eps ?: emptyList()
        items(list.size, key = { "e" + list[it].id }) { i ->
            val e = list[i]
            ListRow(cover(e.image), e.title, e.title,
                listOf(if (e.date > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(e.date)) else "", if (e.duration > 0) mmss(e.duration) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                { Podcasts.play(st, s, list, i) }, highlight = playingId == e.id)
        }
    }
}
