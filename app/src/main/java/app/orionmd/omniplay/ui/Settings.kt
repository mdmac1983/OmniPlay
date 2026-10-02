package app.orionmd.omniplay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import app.orionmd.omniplay.cloud.Cloud
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(st: AppState, pad: PaddingValues) {
    st.prefGen
    Column(Modifier.fillMaxSize()) {
        TopBar(st, "Settings")
        LazyColumn(contentPadding = contentPad(pad)) {
            item { Cat(st, "sources", "Your library sources", Ic.folder) { Sources(st) } }
            item { Cat(st, "clouds", "Cloud accounts", Ic.cloud) { Clouds(st) } }
            item { Cat(st, "keys", "API keys", Ic.key) { Keys(st) } }
            item { Cat(st, "library", "Library tools", Ic.lib) { LibraryTools(st) } }
            item { Cat(st, "sync", "Sync", Ic.sync) { SyncSettings(st) } }
            item { Cat(st, "playback", "Playback & subtitles", Ic.movie) { Playback(st) } }
            item { Cat(st, "appearance", "Appearance", Ic.skin) { Appearance(st) } }
            item { Cat(st, "security", "Activation & security", Ic.shield) { Security(st) } }
            item { Muted("OmniPlay · OrionMD · built for offline-first music & video", Modifier.padding(vertical = 20.dp), 11.sp) }
        }
    }
}

@Composable
private fun Cat(st: AppState, key: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    val open = st.settingsOpen[key] == true
    Column(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.card)) {
        Row(Modifier.fillMaxWidth().clickable { st.toggleSettingsCat(key) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.ac); Spacer(Modifier.width(12.dp))
            Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Ic.down else Ic.arrowDrop, null, tint = C.mu)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) { content() } }
    }
}

@Composable private fun Toggle(st: AppState, k: String, title: String, sub: String = "", default: String = "0") {
    val on = st.pref(k, default) == "1"
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); if (sub.isNotEmpty()) Muted(sub, size = 12.sp) }
        Switch(on, { st.setPref(k, if (it) "1" else "0") }, colors = SwitchDefaults.colors(checkedThumbColor = C.ac, checkedTrackColor = C.ac.copy(alpha = .5f)))
    }
}
@Composable private fun Pick(st: AppState, k: String, title: String, options: List<Pair<String, String>>, default: String) {
    Column(Modifier.padding(vertical = 6.dp)) { Text(title, fontSize = 13.sp); Select(st.pref(k, default), options, { st.setPref(k, it) }, Modifier.fillMaxWidth().padding(top = 4.dp)) }
}

// ---------------- sources ----------------
@Composable private fun Sources(st: AppState) {
    st.sources.forEach { s ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(when (s.kind) { "device" -> Ic.device; "folder" -> Ic.folder; else -> Ic.cloud }, null, tint = C.ac)
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(s.name, fontWeight = FontWeight.SemiBold); Muted("${fmt(s.count)} items · ${if (s.lastScan > 0) "scanned ${ago(s.lastScan)}" else "not scanned"}" + if (s.sync) " · sync on" else "", size = 12.sp) }
            IconBtn(Ic.sync, { st.sync(s.id) }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
            IconBtn(Ic.trash, { st.dialog = Dlg.Confirm("Remove \"${s.name}\"?", "Its library index is removed from OmniPlay. Your actual files aren't touched.", true) { st.removeSource(s.id) } }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
        }
    }
    Spacer(Modifier.height(6.dp))
    BtnRow {
        if (st.sources.none { it.kind == "device" }) Btn("This device", { st.addDevice() }, icon = Ic.device, small = true, kind = BtnKind.Ghost)
        Btn("Add folder", { st.addFolder() }, icon = Ic.folder, small = true, kind = BtnKind.Ghost)
        Btn("Add cloud", { st.toggleSettingsCat("clouds"); st.openSettingsCat("clouds") }, icon = Ic.cloud, small = true, kind = BtnKind.Ghost)
    }
    Muted("“This device” lists music and videos already on your phone. “Add folder” scans any folder (including an SD card or a cloud app's sync folder). Cloud accounts are below.", Modifier.padding(top = 6.dp), 12.sp)
}

// ---------------- clouds ----------------
@Composable private fun Clouds(st: AppState) {
    val accts = remember(st.prefGen) { Cloud.accounts() }
    if (accts.isNotEmpty()) { SectionHeader("Connected"); accts.forEach { a ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Ic.cloud, null, tint = C.ac2)
            Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(Cloud.provider(a.provider)?.name ?: a.provider, fontWeight = FontWeight.SemiBold); Muted(a.label, size = 12.sp) }
            Btn("Add folder", { st.open(Page.CloudBrowse(a.provider, a.id, Cloud.provider(a.provider)?.rootId ?: "", "")) }, small = true, kind = BtnKind.Ghost)
            IconBtn(Ic.trash, { st.dialog = Dlg.Confirm("Disconnect ${a.label}?", "Cloud folders from this account stop updating.", true) { Cloud.remove(a.id); st.prefGen++ } }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
        }
    } }
    SectionHeader("Connect a cloud")
    Muted("OmniPlay streams from the cloud and keeps playing offline anything you download. Your OAuth keys and sign-ins are stored encrypted on the device.", Modifier.padding(bottom = 6.dp), 12.sp)
    Cloud.PROVIDERS.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Btn("Connect", {
                    when (p.auth) {
                        "oauth" -> st.connectOAuth(p.id)
                        "password" -> st.dialog = Dlg.CloudPassword(p.id)
                        else -> st.dialog = Dlg.ConnectWebDav(p.id)
                    }
                }, small = true)
            }
            Muted(p.help, size = 11.sp)
            if (p.auth == "oauth") Muted("Redirect URI to register: ${st.oauthRedirect()}", Modifier.padding(top = 2.dp), 11.sp)
        }
    }
}

// ---------------- API keys ----------------
@Composable private fun Keys(st: AppState) {
    Muted("All keys are optional and free. They're stored encrypted on your device.", Modifier.padding(bottom = 8.dp), 12.sp)
    SecretField(st, "tmdbToken", "TMDB API Read Access Token", "themoviedb.org → Settings → API → create (v4 token). Powers movie/TV posters, cast, trailers.")
    SecretField(st, "tmdbKey", "TMDB API key (v3)", "Optional alternative to the token above.")
    Btn("Test TMDB key", { st.scope.launch { val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Tmdb.test(st.store) }; st.say(r) } }, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(bottom = 10.dp))
    SecretField(st, "podKey", "Podcast Index API key", "podcastindex.org → sign up → API Keys. For the Podcasts tab.")
    SecretField(st, "podSecret", "Podcast Index API secret", "The secret shown next to the key above.")
    SecretField(st, "wyzieKey", "Wyzie Subs key (online subtitles)", "sub.wyzie.io — free key for downloadable subtitles by TMDB match.")
    SectionHeader("Cloud OAuth keys")
    Muted("Only needed for the clouds you connect with sign-in (pCloud, Dropbox, Google Drive). Create your own free app and paste its keys; the redirect URI to register is ${st.oauthRedirect()}", Modifier.padding(bottom = 8.dp), 12.sp)
    listOf("pcloud" to "pCloud", "dropbox" to "Dropbox", "gdrive" to "Google Drive").forEach { (id, name) ->
        val p = Cloud.provider(id)!!
        CloudKeyField(st, id, name, p.needsClientSecret)
    }
}
@Composable private fun SecretField(st: AppState, k: String, label: String, help: String) {
    var v by remember(st.prefGen) { mutableStateOf(st.store.secret(k)) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (help.isNotEmpty()) Muted(help, size = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(v, { v = it }, "paste here", Modifier.weight(1f).padding(top = 4.dp), password = true)
            Btn("Save", { st.store.setSecret(k, v.trim()); st.prefGen++; st.say("Saved"); st.onKeySaved(k) }, small = true, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
@Composable private fun CloudKeyField(st: AppState, provider: String, name: String, secret: Boolean) {
    var id by remember(st.prefGen) { mutableStateOf(Cloud.clientId(provider)) }
    var sec by remember(st.prefGen) { mutableStateOf(Cloud.clientSecret(provider)) }
    Column(Modifier.padding(vertical = 6.dp)) {
        Text("$name Client ID", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Field(id, { id = it }, "Client ID", Modifier.fillMaxWidth().padding(top = 4.dp), password = true)
        if (secret) { Text("$name Client secret", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp)); Field(sec, { sec = it }, "Client secret", Modifier.fillMaxWidth().padding(top = 4.dp), password = true) }
        Btn("Save", { Cloud.setClientId(provider, id); if (secret) Cloud.setClientSecret(provider, sec); st.prefGen++; st.say("Saved $name keys") }, small = true, modifier = Modifier.padding(top = 4.dp))
    }
}

// ---------------- library tools ----------------
@Composable private fun LibraryTools(st: AppState) {
    Muted("Enrich your library using free online sources. “Sync all” saves everything on the device so it shows offline.", Modifier.padding(bottom = 6.dp), 12.sp)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Music — sync all for offline"); Muted(st.sync.music?.let { "${it.step} · ${it.done} / ${it.total}" } ?: "Album art, info, album details, lyrics · last ${ago(st.sync.musicAt)}", size = 12.sp) }
        Btn("Sync", { st.syncMusic() }, small = true)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Videos — sync all for offline"); Muted(st.sync.video?.let { "${it.step} · ${it.done} / ${it.total}" } ?: "TMDB details, posters, cast, episodes, subtitles · last ${ago(st.sync.videoAt)}", size = 12.sp) }
        Btn("Sync", { st.syncVideos() }, small = true)
    }
    Toggle(st, "autoEnrich", "Look up info automatically", "New songs get info & album art, and videos get TMDB matches, as soon as they're added or a key is saved", "1")
    Pick(st, "subLang", "Subtitle language to download", listOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese", "tl" to "Filipino", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "ar" to "Arabic", "hi" to "Hindi", "ru" to "Russian"), "en")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Look up song info (covers, genre, year)"); st.metaProg?.let { (d, t, m) -> Muted("$d / $t · $m matched", size = 12.sp) } ?: Muted("Deezer + iTunes", size = 12.sp) }
        Btn("Run", { st.lookupMeta() }, small = true)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Match movies & TV (TMDB)"); st.tmdbProg?.let { (d, t) -> Muted("$d / $t", size = 12.sp) } ?: Muted(if (st.store.hasTmdb()) "Posters, cast, ratings" else "Add a TMDB key first", size = 12.sp) }
        Btn("Run", { st.matchAll() }, small = true)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Download lyrics for all songs"); st.lyricsProg?.let { (d, t) -> Muted("$d / $t", size = 12.sp) } ?: Muted("${if (st.lyricsSaved >= 0) st.lyricsSaved else 0} saved · LRCLIB", size = 12.sp) }
        Btn("Run", { st.fetchAllLyrics() }, small = true)
    }
    Btn("Offline downloads", { st.open(Page.Downloads) }, icon = Ic.offline, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(top = 8.dp))
}

// ---------------- sync ----------------
@Composable private fun SyncSettings(st: AppState) {
    Toggle(st, "sync", "Background sync for cloud folders", "Checks synced cloud folders for new files on a schedule", "0")
    Pick(st, "syncHours", "How often", listOf("6" to "Every 6 hours", "12" to "Every 12 hours", "24" to "Daily", "72" to "Every 3 days", "168" to "Weekly"), "24")
    Toggle(st, "syncWifiOnly", "Wi-Fi only", "Don't use mobile data for syncing", "1")
    Btn("Apply schedule", {
        if (st.pref("sync", "0") == "1") app.orionmd.omniplay.cloud.SyncWorker.schedule(st.ctx, st.pref("syncHours", "24").toLongOrNull() ?: 24, st.pref("syncWifiOnly", "1") == "1")
        else app.orionmd.omniplay.cloud.SyncWorker.cancel(st.ctx)
        st.say("Sync schedule updated")
    }, small = true, modifier = Modifier.padding(top = 6.dp))
    Muted("Each cloud folder you add can be marked “keep in sync”. Sync only refreshes the file list; use Download to keep playable copies offline.", Modifier.padding(top = 6.dp), 12.sp)
}

// ---------------- playback ----------------
@Composable private fun Playback(st: AppState) {
    SectionHeader("Video")
    Toggle(st, "vresume", "Resume where you left off", "", "1")
    Toggle(st, "vnext", "Autoplay next episode/video", "", "1")
    Toggle(st, "vgest", "Gestures (swipe brightness/volume, double-tap skip)", "", "1")
    Toggle(st, "vpip", "Picture-in-picture when you leave", "", "1")
    Pick(st, "vseek", "Skip amount", listOf("5" to "5 s", "10" to "10 s", "15" to "15 s", "30" to "30 s"), "10")
    SectionHeader("Subtitles")
    Toggle(st, "subauto", "Turn on subtitles automatically", "", "1")
    Pick(st, "subsize", "Subtitle size", listOf("tiny" to "Tiny", "small" to "Small", "normal" to "Normal", "large" to "Large", "xlarge" to "Extra large", "huge" to "Huge"), "normal")
    Pick(st, "subcolor", "Subtitle colour", listOf("white" to "White", "yellow" to "Yellow", "cyan" to "Cyan", "green" to "Green", "orange" to "Orange"), "white")
    Pick(st, "subbg", "Subtitle background", listOf("shadow" to "Shadow", "outline" to "Outline", "box" to "Box", "window" to "Window"), "shadow")
    SectionHeader("Sound")
    Btn("Open sound & equaliser", { st.soundOpen = true }, icon = Ic.tune, small = true)
}

// ---------------- appearance ----------------
@Composable private fun Appearance(st: AppState) {
    Toggle(st, "splash", "Splash screen when the app opens", "The OrionMD OmniPlay artwork, tap to skip", "1")
    Pick(st, "watermark", "Background watermark", listOf("off" to "Off", "light" to "Light", "medium" to "Medium", "strong" to "Strong"), "medium")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Player skin"); Muted(skinOf(st.pref("playerSkin", "midnight")).name, size = 12.sp) }
        Btn("Choose", { st.dialog = Dlg.SkinPick }, small = true, kind = BtnKind.Ghost)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text("Visualizer style"); Muted(VIS_STYLES.firstOrNull { it.first == st.visStyle }?.second ?: st.visStyle, size = 12.sp) }
        Btn("Choose", { st.dialog = Dlg.VisPick }, small = true, kind = BtnKind.Ghost)
    }
}

// ---------------- security ----------------
@Composable private fun Security(st: AppState) {
    val holder = remember(st.prefGen) { Activation.holder(st.store) }
    val licensed = remember(st.prefGen) { Activation.isLicensed(st.store) }
    val days = remember(st.prefGen) { Activation.trialDaysLeft(st.store) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Ic.shield, null, tint = if (licensed) C.ac2 else C.warn); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (licensed) "Activated" else "Free trial", fontWeight = FontWeight.SemiBold)
            Muted(if (licensed) (if (holder.isNotEmpty()) "for $holder" else "full version") else "$days day${if (days == 1) "" else "s"} left", size = 12.sp)
        }
        if (!licensed) Btn("Buy key", { st.openUrl(Activation.BUY_URL) }, small = true, icon = Ic.key)
    }
    val tampered = remember { Guard.tampered(st.ctx) }
    if (tampered) Muted("⚠ This copy isn't signed with the OrionMD key.", Modifier.padding(vertical = 6.dp), 12.sp)
    Muted("OmniPlay is proprietary. It only runs when activated, and it verifies it hasn't been re-signed or modified; a tampered copy is locked behind the owner password and erases its own data after 3 wrong tries.", Modifier.padding(vertical = 8.dp), 12.sp)
    Btn(if (licensed) "Enter a different activation key" else "Enter activation key", { st.dialog = Dlg.TextInput("Activation key", "Key", "") { k -> val (ok, m) = Activation.activate(st.store, k); st.say(m); st.prefGen++ } }, small = true, kind = BtnKind.Ghost)
}
