package app.orionmd.omniplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun AlbumPage(st: AppState, p: Page.Album, pad: PaddingValues) {
    val list = remember(st.allItems, p.key) { st.songs().filter { it.albKey == p.key }.sortedWith(compareBy({ it.tr.takeIf { t -> t > 0 } ?: 9999 }, { it.title.lowercase() })) }
    val art = list.firstOrNull { it.th } ?: list.firstOrNull()
    var info by remember(p.key) { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(p.key, st.online) {
        // saved by "Sync all" (works offline); otherwise look it up and save it
        info = withContext(Dispatchers.IO) {
            st.store.albumInfo().optJSONObject(p.key)?.takeIf { !it.optBoolean("none") } ?: if (!st.online) null else try {
                MusicMeta.album(list.firstOrNull()?.artist ?: "", p.name)?.also { r -> st.store.updateAlbumInfo { it.put(p.key, r) } }
            } catch (_: Exception) { null }
        }
    }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row {
                Thumb(st.artReq(art) ?: info?.optString("cover")?.let { Img.Cover(it) }, p.name, Modifier.size(120.dp), px = 400, fallback = st.artAlt(art))
                Column(Modifier.padding(start = 14.dp)) {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Muted(list.firstOrNull()?.artist ?: "", size = 13.sp)
                    info?.let { Muted(listOf(it.optString("year"), it.optString("genre"), it.optString("label")).filter { s -> s.isNotEmpty() }.joinToString(" · "), size = 12.sp) }
                    Row(Modifier.padding(top = 8.dp)) {
                        Btn("Play", { st.playList(list, 0) }, icon = Ic.play, small = true)
                        Spacer(Modifier.width(8.dp)); Btn("Shuffle", { st.playList(list.shuffled(), 0) }, icon = Ic.shuffle, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
        }
        item { Row(Modifier.padding(top = 10.dp)) {
            Btn("Download", { st.download(list) }, icon = Ic.download, small = true, kind = BtnKind.Ghost)
            Spacer(Modifier.width(8.dp)); Btn("Add to playlist", { st.dialog = Dlg.AddToPlaylist(list.map { it.i }) }, icon = Ic.playlistAdd, small = true, kind = BtnKind.Ghost)
        } }
        item { SectionHeader("${list.size} songs") }
        items(list, key = { it.i }) { x -> SongRow(st, x, list) }
        // the full album from Deezer / iTunes, with what you have marked
        info?.optJSONArray("tracks")?.takeIf { it.length() > 0 }?.let { t ->
            val mine = list.map { MusicMeta.norm(it.title) }.toSet()
            item { SectionHeader("Full album · ${info?.optString("src")?.replaceFirstChar { it.uppercase() }}") }
            items(t.length()) { k -> val o = t.getJSONObject(k); val have = MusicMeta.norm(o.optString("t")) in mine
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${o.optInt("n")}", color = C.mu, fontSize = 12.sp, modifier = Modifier.width(26.dp))
                    Text(o.optString("t"), color = if (have) C.tx else C.mu, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (have) Icon(Ic.done, "In your library", tint = C.ac2, modifier = Modifier.size(16.dp))
                    else if (o.optString("preview").startsWith("https") && st.online) IconBtn(Ic.play, { st.playPreview(JSONObject().put("id", -(k + 1L)).put("t", o.optString("t")).put("a", o.optString("a")).put("al", p.name).put("preview", o.optString("preview")).put("cover", info?.optString("cover") ?: "")) }, size = 30.dp, iconSize = 18.dp, tint = C.mu)
                    Text(if (o.optLong("d") > 0) mmss(o.optLong("d")) else "", color = C.mu, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
fun ArtistPage(st: AppState, p: Page.Artist, pad: PaddingValues) {
    val songs = remember(st.allItems, p.name) { st.songs().filter { it.artist.equals(p.name, true) } }
    val albums = remember(songs) { songs.groupBy { it.albKey }.map { (k, l) -> AlbumGroup(k, l.first().album).apply { items.addAll(l); art = l.firstOrNull { it.th } } }.sortedBy { it.name.lowercase() } }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item { Text(p.name, fontSize = 24.sp, fontWeight = FontWeight.Bold); Muted("${songs.size} songs · ${albums.size} albums", size = 13.sp) }
        item { Row(Modifier.padding(vertical = 10.dp)) { Btn("Play all", { st.playList(songs, 0) }, icon = Ic.play, small = true); Spacer(Modifier.width(8.dp)); Btn("Shuffle", { st.playList(songs.shuffled(), 0) }, icon = Ic.shuffle, small = true, kind = BtnKind.Ghost) } }
        item { SectionHeader("Albums") }
        items(albums, key = { it.key }) { a ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.Album(a.key, a.name)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(st.artReq(a.art), a.name, Modifier.size(48.dp), px = 160, fallback = st.artAlt(a.art))
                Column(Modifier.padding(start = 12.dp)) { Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Muted("${a.items.size} songs", size = 12.sp) }
            }
        }
    }
}

@Composable fun FolderPage(st: AppState, p: Page.FolderPage, pad: PaddingValues) = SimpleList(st, p.name.substringAfterLast('/'), st.songs().filter { it.folder == p.name }, pad)
@Composable fun GenrePage(st: AppState, p: Page.GenrePage, pad: PaddingValues) = SimpleList(st, p.name, st.songs().filter { st.genreOf(it) == p.name }, pad)

@Composable
private fun SimpleList(st: AppState, title: String, list: List<Item>, pad: PaddingValues) {
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item { Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold); Muted("${list.size} songs", size = 13.sp)
            Row(Modifier.padding(vertical = 8.dp)) { Btn("Play", { st.playList(list, 0) }, icon = Ic.play, small = true); Spacer(Modifier.width(8.dp)); Btn("Shuffle", { st.playList(list.shuffled(), 0) }, icon = Ic.shuffle, small = true, kind = BtnKind.Ghost) } }
        items(list, key = { it.i }) { x -> SongRow(st, x, list) }
    }
}

@Composable
fun PlaylistPage(st: AppState, p: Page.PlaylistPage, pad: PaddingValues) {
    val pl = st.playlist(p.id) ?: run { BackBar(st); Empty("Playlist not found."); return }
    val list = st.playlistSongs(pl)
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pl.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (!pl.smart) {
                    IconBtn(Ic.rotate, { st.dialog = Dlg.TextInput("Rename playlist", "Name", pl.name) { n -> st.renamePlaylist(pl.id, n) } }, tint = C.mu)
                    IconBtn(Ic.trash, { st.dialog = Dlg.Confirm("Delete \"${pl.name}\"?", "The songs stay in your library.", true) { st.deletePlaylist(pl.id); st.back() } }, tint = C.mu)
                }
            }
            Muted("${list.size} songs", size = 13.sp)
            Row(Modifier.padding(vertical = 8.dp)) { Btn("Play", { st.playPlaylist(pl) }, icon = Ic.play, small = true); Spacer(Modifier.width(8.dp)); Btn("Shuffle", { st.playPlaylist(pl, true) }, icon = Ic.shuffle, small = true, kind = BtnKind.Ghost) }
        }
        items(list, key = { it.i }) { x -> SongRow(st, x, list) }
    }
}

@Composable
fun MoviePage(st: AppState, p: Page.Movie, pad: PaddingValues) {
    val x = p.item; val m = st.match(x)
    LaunchedEffect(x.i) { st.loadDetails(x) }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row {
                Thumb(st.posterReq(x), st.vtitle(x), Modifier.width(120.dp).aspectRatio(2f / 3f), px = 400)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(st.vtitle(x), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    m?.let { Muted(listOf(it.year, it.genres, if (it.runtime > 0) hms(it.runtime * 60000L) else "").filter { s -> s.isNotEmpty() }.joinToString(" · "), size = 12.sp) }
                    if (m != null && m.rating > 0) Text("★ %.1f".format(m.rating), color = C.ac, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 10.dp)) { Btn("Play", { st.openVideo(x) }, icon = Ic.play, small = true) }
                    Row(Modifier.padding(top = 6.dp)) {
                        DownloadButton(st, x)
                        Spacer(Modifier.width(6.dp)); Btn("Fix match", { st.fixMatch(x) }, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
        }
        item { TmdbSections(st, m) }
        item { Muted(x.n, Modifier.padding(top = 16.dp), 11.sp) }
    }
}

@Composable
fun ShowPage(st: AppState, p: Page.Show, pad: PaddingValues) {
    val eps = remember(st.allItems, p.key) { st.videos().filter { it.k == "tv" && st.mkey(it) == p.key }.sortedWith(compareBy({ it.se }, { it.ep })) }
    val first = eps.firstOrNull() ?: run { BackBar(st); Empty("No episodes."); return }
    val m = st.match(first)
    LaunchedEffect(p.key) { st.loadDetails(first) }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st) }
        item {
            Row {
                Thumb(st.posterReq(first), st.vtitle(first), Modifier.width(120.dp).aspectRatio(2f / 3f), px = 400)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(st.vtitle(first), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    m?.let { Muted(listOf(it.year, it.genres).filter { s -> s.isNotEmpty() }.joinToString(" · "), size = 12.sp) }
                    Muted("${eps.size} episodes", size = 12.sp)
                    Row(Modifier.padding(top = 10.dp)) { Btn("Play", { st.openVideo(first, eps) }, icon = Ic.play, small = true); Spacer(Modifier.width(6.dp)); Btn("Fix match", { st.fixMatch(first) }, small = true, kind = BtnKind.Ghost) }
                }
            }
        }
        item { TmdbSections(st, m) }
        item { SectionHeader("Episodes") }
        items(eps, key = { it.i }) { e ->
            Row(Modifier.fillMaxWidth().clickable { st.openVideo(e, eps) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Icon(Ic.play, null, tint = C.mu) }
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(listOf(epLabel(e), e.pt ?: e.title).filter { it.isNotEmpty() }.joinToString("  "), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (st.isDownloaded(e)) Icon(Ic.offline, "Offline", tint = C.ac2, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun DownloadsPage(st: AppState, pad: PaddingValues) {
    val offline = remember(st.allItems, st.dl) { st.allItems.filter { st.isDownloaded(it) } }
    val active = app.orionmd.omniplay.DlQueue.entries.values.sortedBy { it.title.lowercase() }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { BackBar(st); Text("Offline", fontSize = 22.sp, fontWeight = FontWeight.Bold); Muted("${offline.size} items · ${bytes(Downloads.bytesUsed(st.ctx))}", size = 13.sp) }
        if (active.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading (${active.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val allPaused = active.all { it.state == app.orionmd.omniplay.DlQueue.State.PAUSED || it.state == app.orionmd.omniplay.DlQueue.State.FAILED }
                    Btn(if (allPaused) "Resume all" else "Pause all", { if (allPaused) app.orionmd.omniplay.DlQueue.resumeAll() else app.orionmd.omniplay.DlQueue.pauseAll() }, small = true, kind = BtnKind.Ghost)
                }
            }
            items(active, key = { "dl" + it.id }) { e -> DownloadRow(e) }
            item { Muted("Paused and unfinished downloads carry on from where they stopped — even after closing Media Hub.", Modifier.padding(vertical = 6.dp), 12.sp) }
        }
        if (offline.isEmpty()) item { Empty("Nothing saved for offline yet.\nUse Download on a song, album or video.") }
        items(offline, key = { it.i }) { x ->
            Row(Modifier.fillMaxWidth().clickable { if (x.c == 2) st.openVideo(x, offline.filter { it.c == 2 }) else st.playItem(x, offline.filter { it.c == 3 }) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (x.c == 2) Ic.movie else Ic.music, null, tint = C.ac)
                Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(if (x.c == 2) st.vtitle(x) else x.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Muted(x.folder, size = 12.sp) }
                IconBtn(Ic.trash, { st.removeDownload(x) }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
            }
        }
    }
}

/** "Download" button that turns into live progress: 45 % (tap to pause), Paused 45 % (tap to resume), Saved. */
@Composable
internal fun DownloadButton(st: AppState, x: app.orionmd.omniplay.Item) {
    val e = app.orionmd.omniplay.DlQueue.entries[x.i]
    when {
        st.isDownloaded(x) -> Btn("Saved", { st.open(Page.Downloads) }, icon = Ic.offline, small = true, kind = BtnKind.Ghost)
        e == null -> Btn("Download", { st.download(listOf(x)) }, icon = Ic.download, small = true, kind = BtnKind.Ghost)
        e.state == app.orionmd.omniplay.DlQueue.State.PAUSED || e.state == app.orionmd.omniplay.DlQueue.State.FAILED ->
            Btn("Resume" + if (e.pct >= 0) " ${e.pct}%" else "", { app.orionmd.omniplay.DlQueue.resume(x.i) }, icon = Ic.play, small = true, kind = BtnKind.Ghost)
        else -> Btn(if (e.pct >= 0) "${e.pct}%" else "Starting…", { app.orionmd.omniplay.DlQueue.pause(x.i) }, icon = Ic.pause, small = true, kind = BtnKind.Ghost)
    }
}

@Composable
private fun DownloadRow(e: app.orionmd.omniplay.DlQueue.Entry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val size = if (e.total > 0) "${bytes(e.done)} of ${bytes(e.total)}" else if (e.done > 0) bytes(e.done) else ""
                val status = when (e.state) {
                    app.orionmd.omniplay.DlQueue.State.RUNNING -> listOf(if (e.pct >= 0) "${e.pct}%" else "Starting…", size, if (e.speed > 0) "${bytes(e.speed)}/s" else "", e.error).filter { it.isNotEmpty() }.joinToString(" · ")
                    app.orionmd.omniplay.DlQueue.State.QUEUED -> listOf("Waiting", size).filter { it.isNotEmpty() }.joinToString(" · ")
                    app.orionmd.omniplay.DlQueue.State.PAUSED -> listOf("Paused", if (e.pct >= 0) "${e.pct}%" else "", size).filter { it.isNotEmpty() }.joinToString(" · ")
                    app.orionmd.omniplay.DlQueue.State.FAILED -> "Stopped: ${e.error}" + if (e.pct > 0) " · ${e.pct}% saved" else ""
                }
                Muted(status, size = 12.sp)
            }
            if (e.state == app.orionmd.omniplay.DlQueue.State.RUNNING || e.state == app.orionmd.omniplay.DlQueue.State.QUEUED) IconBtn(Ic.pause, { app.orionmd.omniplay.DlQueue.pause(e.id) }, size = 36.dp, iconSize = 22.dp)
            else IconBtn(Ic.play, { app.orionmd.omniplay.DlQueue.resume(e.id) }, tint = C.ac, size = 36.dp, iconSize = 22.dp)
            IconBtn(Ic.close, { app.orionmd.omniplay.DlQueue.cancel(e.id) }, tint = C.mu, size = 36.dp, iconSize = 20.dp)
        }
        androidx.compose.material3.LinearProgressIndicator(progress = { if (e.pct >= 0) e.pct / 100f else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            color = if (e.state == app.orionmd.omniplay.DlQueue.State.FAILED) C.warn else C.ac, trackColor = C.s3)
    }
}
