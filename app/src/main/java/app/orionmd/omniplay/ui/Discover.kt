package app.orionmd.omniplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Discover's two halves: Deezer (search, previews, get the full song) and free downloads. */
private var discoverMode by mutableStateOf("deezer")
private var deezerKind by mutableStateOf("songs")
private var albumRes by mutableStateOf<List<JSONObject>?>(null)
private var freeRes by mutableStateOf<Pair<List<JSONObject>, List<JSONObject>>?>(null)   // Jamendo tracks, Internet Archive releases
private var freeMsg by mutableStateOf<String?>(null)
private var busy by mutableStateOf(false)

/** Discover: search Deezer (songs and albums, 30-second previews, links to get the full song) or free downloads. */
@Composable
fun DiscoverScreen(st: AppState, pad: PaddingValues) {
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val go: () -> Unit = go@{
        val t = q.trim(); if (t.isEmpty()) return@go
        if (!Net.online(st.ctx)) { st.say("Connect to the internet to search"); return@go }
        when {
            discoverMode == "free" -> { busy = true; freeMsg = null; scope.launch {
                val jamId = st.store.secret("jamendoId")
                val r = withContext(Dispatchers.IO) {
                    val jam = try { FreeMusic.jamendo(jamId, t) } catch (e: Exception) { freeMsg = "Jamendo: " + (e.message ?: "search failed"); emptyList() }
                    val ia = try { FreeMusic.archiveSearch(t) } catch (_: Exception) { emptyList() }
                    jam to ia
                }
                freeRes = r; busy = false } }
            deezerKind == "albums" -> { busy = true; scope.launch { albumRes = withContext(Dispatchers.IO) { try { FreeMusic.searchAlbums(t) } catch (_: Exception) { emptyList() } }; busy = false } }
            else -> st.searchCatalog(t)
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(st, "Discover")
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Deezer", discoverMode == "deezer") { discoverMode = "deezer" }
            Chip("Free downloads", discoverMode == "free") { discoverMode = "free" }
            if (discoverMode == "deezer") {
                Spacer(Modifier.width(10.dp))
                Chip("Songs", deezerKind == "songs") { deezerKind = "songs"; if (q.isNotBlank() && st.catalog == null) go() }
                Chip("Albums", deezerKind == "albums") { deezerKind = "albums"; if (q.isNotBlank() && albumRes == null) go() }
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(q, { q = it }, if (discoverMode == "free") "Search free music…" else "Search songs, artists, albums…", Modifier.weight(1f))
            IconBtn(Ic.search, go, Modifier.padding(start = 4.dp))
        }
        if (discoverMode == "free") FreeResults(st, pad) else DeezerResults(st, pad)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) =
    Text(label, fontSize = 13.sp, color = if (on) androidx.compose.ui.graphics.Color.Black else C.tx,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.ac else C.s2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))

/** "Get the full song" (or album): open it in Deezer, or buy it as a DRM-free download. */
internal fun getFull(st: AppState, o: JSONObject, album: Boolean) {
    val what = if (album) "\"${o.optString("al").ifEmpty { o.optString("t") }}\"" else "\"${o.optString("t")}\""
    st.dialog = Dlg.Choice("Get $what", "Listen in Deezer with your subscription, or buy it as a download you keep. Save a bought file on this phone " +
        "and Media Hub adds it to your library when you come back (Music → This device).",
        FreeMusic.storeLinks(o, album).map { (name, url) -> name to { st.openStore(url) } })
}

@Composable
private fun ColumnScope.DeezerResults(st: AppState, pad: PaddingValues) {
    Muted("Previews are 30-second clips. Tap ⋯ to get the full song.", Modifier.padding(horizontal = 16.dp), 12.sp)
    LazyColumn(contentPadding = contentPad(pad), modifier = Modifier.weight(1f)) {
        if (deezerKind == "albums") {
            val res = albumRes
            when {
                busy -> item { Empty("Searching…") }
                res == null -> item { Empty("Search for an album or artist.") }
                res.isEmpty() -> item { Empty("Nothing found.") }
                else -> items(res) { o ->
                    Row(Modifier.fillMaxWidth().clickable { st.open(Page.TrackList("deezer", o.optLong("alid").toString(), o.optString("t"), o.optString("a"), o.optString("cover"), o)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(o.optString("cover").takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, o.optString("t"), Modifier.size(52.dp), px = 160)
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(o.optString("t"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted(listOf(o.optString("a"), if (o.optInt("n") > 0) "${o.optInt("n")} songs" else "").filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
                        }
                        IconBtn(Ic.more, { getFull(st, o, true) }, size = 36.dp, iconSize = 22.dp, tint = C.mu)
                    }
                }
            }
        } else {
            val res = st.catalog
            if (res == null) item { Empty("Search for a song, artist or album to preview.") }
            else if (res.isEmpty()) item { Empty("Nothing found.") }
            else items(res) { o -> DeezerSongRow(st, o) { st.playPreview(o) } }
        }
    }
}

@Composable
private fun DeezerSongRow(st: AppState, o: JSONObject, play: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = play).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(o.optString("cover").takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, o.optString("t"), Modifier.size(46.dp), px = 160)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(o.optString("t"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(listOf(o.optString("a"), o.optString("al")).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
        }
        IconBtn(Ic.play, play, size = 36.dp, iconSize = 22.dp)
        IconBtn(Ic.more, { getFull(st, o, false) }, size = 36.dp, iconSize = 22.dp, tint = C.mu)
    }
}

@Composable
private fun ColumnScope.FreeResults(st: AppState, pad: PaddingValues) {
    val hasJam = st.store.secret("jamendoId").isNotBlank()
    Muted("Full songs that artists share for free (Creative Commons, live recordings they allow). Downloads go to Music → Media Hub → Free.", Modifier.padding(horizontal = 16.dp), 12.sp)
    val r = freeRes
    LazyColumn(contentPadding = contentPad(pad), modifier = Modifier.weight(1f)) {
        if (!hasJam) item { Muted("Add a free Jamendo client id in Settings → API keys for thousands more songs.", Modifier.padding(top = 8.dp), 12.sp) }
        freeMsg?.let { m -> item { Muted(m, Modifier.padding(top = 6.dp), 12.sp) } }
        when {
            busy -> item { Empty("Searching…") }
            r == null -> item { Empty("Search for an artist, a song or a genre.") }
            r.first.isEmpty() && r.second.isEmpty() -> item { Empty("Nothing found.") }
            else -> {
                if (r.first.isNotEmpty()) {
                    item { SectionHeader("Songs · Jamendo") }
                    itemsIndexed(r.first, key = { _, o -> o.optString("key") }) { i, o -> FreeTrackRow(st, o) { st.playLinks(r.first, i) } }
                }
                if (r.second.isNotEmpty()) {
                    item { SectionHeader("Releases & live recordings · Internet Archive") }
                    items(r.second, key = { it.optString("ia") }) { o ->
                        Row(Modifier.fillMaxWidth().clickable { st.open(Page.TrackList("archive", o.optString("ia"), o.optString("t"), o.optString("a"), o.optString("cover"), o)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Thumb(Img.Cover(o.optString("cover")), o.optString("t"), Modifier.size(52.dp), px = 160)
                            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(o.optString("t"), fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Muted(listOf(o.optString("a"), o.optString("kind"), o.optString("date")).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A free song: play the full song, or download it (with progress). */
@Composable
private fun FreeTrackRow(st: AppState, o: JSONObject, play: () -> Unit) {
    val item = remember(o) { FreeMusic.item(o) }
    Row(Modifier.fillMaxWidth().clickable(onClick = play).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(o.optString("cover").takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, o.optString("t"), Modifier.size(46.dp), px = 160)
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(o.optString("t"), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(listOf(o.optString("a"), o.optString("al"), if (o.optLong("d") > 0) mmss(o.optLong("d")) else "").filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
        }
        IconBtn(Ic.play, play, size = 36.dp, iconSize = 22.dp)
        DownloadButton(st, item)
    }
}

/** A Deezer album's songs (previews + get the full album) or an Internet Archive release (play and download). */
@Composable
fun TrackListPage(st: AppState, p: Page.TrackList, pad: PaddingValues) {
    var tracks by remember(p.id) { mutableStateOf<List<JSONObject>?>(null) }
    var err by remember(p.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(p.id) {
        tracks = withContext(Dispatchers.IO) {
            try { if (p.src == "deezer") FreeMusic.albumTracks(p.id.toLong()) else FreeMusic.archiveTracks(p.id) } catch (e: Exception) { err = e.message ?: "Couldn't load"; emptyList() }
        }
    }
    val list = tracks
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row {
                Thumb(p.cover.takeIf { it.isNotEmpty() }?.let { Img.Cover(it) }, p.title, Modifier.size(120.dp), px = 400)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(p.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Muted(p.sub, size = 13.sp)
                    if (list != null) Muted("${list.size} songs" + if (p.src == "deezer") " · 30-second previews" else " · free download", size = 12.sp)
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (!list.isNullOrEmpty()) Btn("Play", { st.playLinks(list, 0) }, icon = Ic.play, small = true)
                        if (p.src == "deezer") Btn("Get the album", { getFull(st, p.head, true) }, small = true, kind = BtnKind.Ghost)
                        else if (!list.isNullOrEmpty()) Btn("Download all", { st.download(list.map { FreeMusic.item(it) }) }, icon = Ic.download, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
        }
        if (p.src == "archive") item {
            val lic = list?.firstOrNull()?.optString("lic").orEmpty()
            Muted(if (lic.isNotEmpty()) "Licence: $lic" else "Shared on the Internet Archive with the artist's permission for free download.", Modifier.padding(top = 10.dp), 11.sp)
        }
        when {
            list == null -> item { Empty("Loading…") }
            list.isEmpty() -> item { Empty(err ?: "No songs here.") }
            p.src == "deezer" -> itemsIndexed(list) { i, o -> DeezerSongRow(st, o) { st.playLinks(list, i) } }
            else -> itemsIndexed(list, key = { _, o -> o.optString("key") }) { i, o -> FreeTrackRow(st, o) { st.playLinks(list, i) } }
        }
    }
}

/** Browse a cloud account's folders and pick one to add to the library. */
@Composable
fun CloudBrowsePage(st: AppState, p: Page.CloudBrowse, pad: PaddingValues) {
    val acct = remember(p.acctId) { app.orionmd.omniplay.cloud.Cloud.account(p.acctId) }
    var files by remember(p.folderId) { mutableStateOf<List<app.orionmd.omniplay.cloud.CloudFile>?>(null) }
    var err by remember(p.folderId) { mutableStateOf<String?>(null) }
    var syncOn by remember { mutableStateOf(true) }
    LaunchedEffect(p.folderId) {
        if (acct == null) { err = "Account missing"; return@LaunchedEffect }
        try { files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.orionmd.omniplay.cloud.Cloud.list(acct, p.folderId) }.filter { it.isDir } }
        catch (e: Exception) { err = e.message ?: "Couldn't list this folder" }
    }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st); Text(if (p.path.isEmpty()) (acct?.label ?: "Cloud") else p.path.substringAfterLast('/'), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Muted("Pick a folder to add, or open one to go deeper.", Modifier.padding(vertical = 6.dp), 12.sp)
            Check(syncOn, "Keep in sync (check for new files in the background)") { syncOn = it }
            Btn("Add this folder", { st.addCloudFolder(p.acctId, p.folderId, p.path, syncOn); st.pages.clear(); st.tab = "home"; st.say("Added — scanning…") }, icon = Ic.add, modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth())
            SectionHeader("Subfolders")
        }
        err?.let { item { Muted(it, Modifier.padding(8.dp)) } }
        if (files == null && err == null) item { Progress(null) }
        items(files ?: emptyList(), key = { it.id }) { f ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.CloudBrowse(p.provider, p.acctId, f.id, if (p.path.isEmpty()) f.name else "${p.path}/${f.name}")) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.folder, null, tint = C.ac); Spacer(Modifier.width(12.dp)); Text(f.name, fontWeight = FontWeight.SemiBold)
            }
        }
        if (files?.isEmpty() == true) item { Muted("No subfolders here — add this folder above.", Modifier.padding(8.dp)) }
    }
}

@Composable private fun Icon(ic: androidx.compose.ui.graphics.vector.ImageVector, cd: String?, tint: androidx.compose.ui.graphics.Color) = androidx.compose.material3.Icon(ic, cd, tint = tint)
