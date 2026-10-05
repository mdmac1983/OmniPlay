package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.*
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.launch
import java.util.Locale
import org.json.JSONObject

@Composable
private fun DlgBox(st: AppState, title: String, content: @Composable ColumnScope.() -> Unit) {
    Dialog({ st.dialog = null }, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.s1)
            .heightIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(18.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp)); content()
        }
    }
}
@Composable private fun Buttons(cancel: String = "Cancel", ok: String, okKind: BtnKind = BtnKind.Primary, onCancel: () -> Unit, onOk: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn(cancel, onCancel, Modifier.weight(1f), kind = BtnKind.Ghost); Btn(ok, onOk, Modifier.weight(1f), kind = okKind)
    }
}

@Composable
fun Dialogs(st: AppState) {
    when (val d = st.dialog) {
        null -> {}
        is Dlg.Message -> DlgBox(st, d.title) { Muted(d.msg, size = 13.sp); Btn("OK", { st.dialog = null }, Modifier.fillMaxWidth().padding(top = 14.dp)) }
        is Dlg.Confirm -> DlgBox(st, d.title) { Muted(d.msg, size = 13.sp); Buttons(ok = "Confirm", okKind = if (d.danger) BtnKind.Danger else BtnKind.Primary, onCancel = { st.dialog = null }) { st.dialog = null; d.ok() } }
        is Dlg.TextInput -> { var v by remember(d) { mutableStateOf(d.initial) }; DlgBox(st, d.title) { Field(v, { v = it }, d.label, password = d.password); Buttons(ok = "OK", onCancel = { st.dialog = null }) { if (v.isNotBlank()) { st.dialog = null; d.ok(v.trim()) } } } }
        is Dlg.Choice -> DlgBox(st, d.title) { if (d.msg.isNotEmpty()) Muted(d.msg, Modifier.padding(bottom = 10.dp), 13.sp); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { d.options.forEach { (l, r) -> Btn(l, { st.dialog = null; r() }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost) }; Btn("Cancel", { st.dialog = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost) } }

        Dlg.Sleep -> DlgBox(st, "Sleep timer") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30, 45, 60, 90, 120).chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { m -> Btn(if (m < 60) "$m min" else "${m / 60}h", { Player.setSleep(m); st.dialog = null; st.say("Sleep timer: $m min") }, Modifier.weight(1f), kind = BtnKind.Ghost, small = true) } } }
                Btn("End of this song", { Player.sleepAtEndOfSong(); st.dialog = null; st.say("Stopping after this song") }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                if (Player.sleepAt > 0 || Player.sleepEndOfSong) Btn("Turn off timer", { Player.setSleep(0); st.dialog = null }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
                Btn("Close", { st.dialog = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            }
        }
        Dlg.VisPick -> DlgBox(st, "Visualizer style") {
            Muted("${VIS_STYLES.size} styles in ${VIS_GROUPS.size} groups — tap a group to open it.", size = 13.sp)
            VisGroupList(st)
            Btn("Close", { st.dialog = null }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = BtnKind.Ghost)
        }
        Dlg.SkinPick -> DlgBox(st, "Player skin") {
            st.prefGen; val cur = st.pref("playerSkin", "midnight")
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SKINS.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { sk -> SkinSwatch(sk, sk.id == cur, Modifier.weight(1f).clickable { st.setPref("playerSkin", sk.id); Profiles.saveCurrent(st) }) }; repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } } }
            }
            SkinLogoEditor(st, cur)
            Btn("Done", { st.dialog = null }, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
        is Dlg.AddToPlaylist -> DlgBox(st, if (d.queue) "Save queue to a playlist" else "Add to playlist") {
            Muted(if (d.ids.size == 1) "1 song" else "${fmt(d.ids.size)} songs", size = 13.sp)
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("New playlist…", { st.dialog = Dlg.TextInput("New playlist", "Name", "") { n -> st.createPlaylist(n, d.ids); st.say("Saved to \"$n\"") } }, Modifier.fillMaxWidth(), icon = Ic.playlistAdd)
                st.playlists().sortedByDescending { it.created }.forEach { p -> Btn("${p.name}  (${p.ids.size})", { val n = st.addToPlaylist(p.id, d.ids); st.dialog = null; st.say(if (n == 0) "Already in \"${p.name}\"" else "Added $n to \"${p.name}\"") }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost, icon = Ic.playlist) }
                Btn("Cancel", { st.dialog = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            }
        }

        is Dlg.FixMatch -> FixMatchDialog(st, d)
        is Dlg.EpisodeEdit -> EpisodeEditDialog(st, d.item)
        is Dlg.CloudPassword -> { val p = Cloud.provider(d.provider)!!; var u by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
            DlgBox(st, "Connect ${p.name}") { Muted(p.help, size = 12.sp); Field(u, { u = it }, "Email", Modifier.fillMaxWidth().padding(top = 8.dp)); Field(pw, { pw = it }, "Password", Modifier.fillMaxWidth().padding(top = 8.dp), password = true)
                Buttons(ok = "Connect", onCancel = { st.dialog = null }) { st.dialog = null; st.connectWithFields(d.provider, mapOf("user" to u.trim(), "pass" to pw)) } } }
        is Dlg.ConnectWebDav -> { val p = Cloud.provider(d.provider)!!; var url by remember { mutableStateOf("") }; var u by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }
            DlgBox(st, "Connect ${p.name}") { Muted(p.help, size = 12.sp); if (p.signupUrl.isNotEmpty()) Text(p.signupUrl, color = C.ac, fontSize = 12.sp, modifier = Modifier.clickable { st.openUrl(p.signupUrl) }.padding(vertical = 4.dp))
                Field(url, { url = it }, "WebDAV URL", Modifier.fillMaxWidth().padding(top = 8.dp)); Field(u, { u = it }, "Username", Modifier.fillMaxWidth().padding(top = 8.dp)); Field(pw, { pw = it }, "Password", Modifier.fillMaxWidth().padding(top = 8.dp), password = true)
                Buttons(ok = "Connect", onCancel = { st.dialog = null }) { st.dialog = null; st.connectWithFields(d.provider, mapOf("url" to url.trim(), "user" to u.trim(), "pass" to pw)) } } }
        Dlg.AddSource -> { st.dialog = null }
    }
}

@Composable
private fun FixMatchDialog(st: AppState, d: Dlg.FixMatch) {
    var q by remember { mutableStateOf(Tmdb.clean(d.query)) }
    var type by remember { mutableStateOf(if (d.type == "tv" || d.type == "mv") d.type else "movie") }
    var results by remember { mutableStateOf<List<JSONObject>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun go() { if (!Net.online(st.ctx)) { st.say("Connect to the internet to search"); return }; busy = true; scope.launch { results = st.tmdbSearch(q.trim(), type); busy = false } }
    LaunchedEffect(Unit) { go() }
    DlgBox(st, "Find a match") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(q, { q = it; results = null }, "Title", Modifier.weight(1f))
            Select(type, listOf("all" to "All", "movie" to "Movie", "tv" to "TV", "mv" to "Music video"), { type = it; results = null; go() }, Modifier.padding(start = 8.dp).width(130.dp))
        }
        Btn("Search", { go() }, Modifier.fillMaxWidth().padding(top = 10.dp))
        if (busy) Muted("Searching…", Modifier.padding(top = 10.dp))
        if (results?.isEmpty() == true) Muted("No results.", Modifier.padding(top = 10.dp))
        results?.forEach { m ->
            val mv = m.optString("type") == "mv"
            ListRow(if (mv) m.optString("art").takeIf { it.isNotEmpty() }?.let { Img.Cover(it) } else m.optString("poster").takeIf { it.isNotEmpty() }?.let { Img.Poster("w92", it) },
                m.optString("title"), if (mv) m.optString("artist") + " – " + m.optString("title") else m.optString("title"),
                listOf(when (m.optString("type")) { "mv" -> "Music video"; "tv" -> "TV"; else -> "Movie" }, m.optString("year"), if (m.optDouble("rating") > 0) "★ %.1f".format(Locale.US, m.optDouble("rating")) else "").filter { it.isNotEmpty() }.joinToString(" · "),
                { st.tmdbSetMatch(d.key, m); st.dialog = null; st.say("Matched: " + m.optString("title")) }, thumbW = if (mv) 64.dp else 40.dp, thumbRatio = if (mv) 16f / 9f else 2f / 3f)
        }
        Buttons(cancel = "Close", ok = "No match", okKind = BtnKind.Danger, onCancel = { st.dialog = null }) { st.tmdbSetMatch(d.key, null); st.dialog = null }
    }
}

/** Add / change / remove your own logo on the chosen skin. */
@Composable
private fun SkinLogoEditor(st: AppState, skinId: String) {
    val sk = skinOf(skinId)
    val has = remember(skinId, SkinLogos.gen) { SkinLogos.has(st.ctx, skinId) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) st.say(if (SkinLogos.save(st.ctx, skinId, uri)) "Logo added to ${sk.name}" else "Couldn't read that picture")
    }
    Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(C.s2).padding(12.dp)) {
        Text("Logo for \"${sk.name}\"", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Muted("Add your own logo to this skin — any picture on your phone (a transparent PNG looks best). It shows above the song title, in the video player and on the skin's swatch, and stays on this device.", size = 12.sp)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SkinLogo(skinId, Modifier.size(44.dp).padding(end = 8.dp))
            Btn(if (has) "Change logo" else "Add logo…", { pick.launch("image/*") }, small = true)
            if (has) { Spacer(Modifier.width(8.dp)); Btn("Remove", { SkinLogos.remove(st.ctx, skinId) }, small = true, kind = BtnKind.Ghost) }
        }
        Row(Modifier.padding(top = 6.dp).clickable { st.setPref("skinLogoWm", if (st.pref("skinLogoWm", "0") == "1") "0" else "1") }, verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(st.pref("skinLogoWm", "0") == "1", { st.setPref("skinLogoWm", if (it) "1" else "0") })
            Text("Also show it large and faint in the background", fontSize = 13.sp)
        }
    }
}

/** The visualizer picker's groups, each collapsible (tap the header); the one holding the current style starts open. */
@Composable
internal fun VisGroupList(st: AppState) {
    val curGroup = VIS_GROUPS.firstOrNull { (_, l) -> l.any { it.first == st.visStyle } }?.first
    for ((group, styles) in VIS_GROUPS) {
        val key = "visOpen_" + group
        var open by androidx.compose.runtime.remember(group) { androidx.compose.runtime.mutableStateOf(st.pref(key, if (group == curGroup) "1" else "0") == "1") }
        val sel = styles.any { it.first == st.visStyle }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(if (open) C.s3 else C.s2).clickable { open = !open; st.setPref(key, if (open) "1" else "0") }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(group, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (sel) C.ac else C.tx, modifier = Modifier.weight(1f))
            Text("${styles.size}", fontSize = 12.sp, color = C.mu, modifier = Modifier.padding(end = 8.dp))
            Text(if (open) "▾" else "▸", fontSize = 16.sp, color = C.mu)
        }
        if (open) BtnRow { styles.forEach { (id, name) -> Btn(name, { st.visStyle = id; st.setPref("visStyle", id); st.dialog = null }, kind = if (st.visStyle == id) BtnKind.Primary else BtnKind.Ghost, small = true) } }
    }
}

/** One TV episode: play it, put it under the right season / episode (pick from TMDB's list or type the numbers), subtitles. */
@Composable
private fun EpisodeEditDialog(st: AppState, x0: Item) {
    val x = st.allItems.firstOrNull { it.i == x0.i } ?: x0
    val key = st.mkey(x) ?: return
    val m = st.match(x)
    var se by remember(x.i) { mutableStateOf(maxOf(1, x.se)) }
    var seTxt by remember(x.i) { mutableStateOf(if (x.se > 0) x.se.toString() else "") }
    var epTxt by remember(x.i) { mutableStateOf(if (x.ep > 0) x.ep.toString() else "") }
    LaunchedEffect(key, se) { st.loadSeasons(key, listOf(se)) }
    val season = st.seasonOf(key, se)
    val scope = rememberCoroutineScope()
    DlgBox(st, (st.vtitle(x) + " " + epLabel(x)).trim()) {
        Muted(x.n, size = 12.sp)
        st.epInfo(x)?.optString("name")?.takeIf { it.isNotBlank() }?.let { Muted("TMDB: $it", Modifier.padding(top = 2.dp), 13.sp) }
        Btn("Play", { st.dialog = null; st.openVideo(x, listOf(x)) }, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Ic.play)

        Text("Wrong episode? Pick the right one", color = C.mu, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        val count = m?.seasons ?: 0
        if (count > 0) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..count).forEach { n ->
                val on = n == se
                Text("Season $n", fontSize = 13.sp, color = if (on) androidx.compose.ui.graphics.Color.Black else C.tx,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.ac else C.s2).clickable { se = n }.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        when {
            m == null -> Muted("Match the show first (Fix match below).", size = 13.sp)
            season == null -> Muted(if (Net.online(st.ctx)) "Loading season $se…" else "Connect to the internet to see TMDB's episode list.", Modifier.padding(top = 6.dp), 13.sp)
            else -> season.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted().forEach { n ->
                val e = season.optJSONObject(n.toString()) ?: return@forEach
                val cur = x.se == se && x.ep == n
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (cur) C.ac.copy(alpha = .18f) else C.s2)
                    .clickable { st.setEpisode(x, se, n); st.dialog = null }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(epLabel(se, n) + " · " + e.optString("name"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    e.optString("air").takeIf { it.isNotBlank() }?.let { Muted(it + if (cur) " · this file" else "", size = 12.sp) }
                }
            }
        }

        Text("Or type the numbers", color = C.mu, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(seTxt, { seTxt = it.filter(Char::isDigit).take(3) }, "Season", Modifier.weight(1f))
            Field(epTxt, { epTxt = it.filter(Char::isDigit).take(4) }, "Episode", Modifier.weight(1f))
            Btn("Set", {
                val a = seTxt.toIntOrNull(); val b = epTxt.toIntOrNull()
                if (a == null || b == null || a < 1 || b < 1) st.say("Enter a season and an episode number") else { st.setEpisode(x, a, b); st.dialog = null }
            }, small = true)
        }
        if (st.episodeFixed(x)) Btn("Use the number from the file name", { st.resetEpisode(x); st.dialog = null }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost, small = true)

        Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (st.wyzieOn()) Btn(if (st.hasOnlineSub(x)) "Get subtitles again" else "Get subtitles for this episode", {
                scope.launch { if (st.hasOnlineSub(x)) st.removeOnlineSub(x); st.say(st.fetchSub(x)) }
            }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost, icon = Ic.cc)
            Btn("Wrong show? Fix match", { st.fixMatch(x) }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            Btn("Close", { st.dialog = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
        }
    }
}
