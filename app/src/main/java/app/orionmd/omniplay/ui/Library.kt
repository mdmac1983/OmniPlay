package app.orionmd.omniplay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.*

@Composable
fun MusicLibrary(st: AppState, pad: PaddingValues) {
    var sub by remember { mutableStateOf("songs") }
    var q by remember { mutableStateOf("") }
    val songs = remember(st.allItems, q) { val s = st.songs(); if (q.isBlank()) s else s.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) } }
    Column(Modifier.fillMaxSize()) {
        TopBar(st, "Media Hub", actions = {
            IconBtn(Ic.search, { st.open(Page.Discover) })
            IconBtn(Ic.podcast, { st.open(Page.Podcasts) })
            IconBtn(Ic.download, { st.dialog = Dlg.Confirm("Sync all music for offline?", "Downloads album art, song info (genre, year, length), album details and lyrics for every song and saves them on the device. Already-saved items are skipped.") { st.syncMusic() } })
            IconBtn(Ic.sync, { st.sync() })
        })
        st.sync.music?.let { p -> Column(Modifier.padding(horizontal = 16.dp)) { Progress(if (p.total > 0) p.done.toFloat() / p.total else null); Muted("${p.step} · ${p.done} / ${p.total}", size = 12.sp) } }
        st.scanProg?.let { (name, n) -> Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Progress(null); Spacer(Modifier.width(10.dp)); Muted("Scanning $name — $n items", size = 12.sp) } }
        Field(q, { q = it }, "Search songs, artists, albums…", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth())
        Seg(listOf("songs" to "Songs", "albums" to "Albums", "artists" to "Artists", "genres" to "Genres", "playlists" to "Playlists", "folders" to "Folders"), sub) { sub = it }
        if (st.allItems.isEmpty()) { EmptyLibrary(st); return@Column }
        when (sub) {
            "songs" -> SongList(st, songs, pad)
            "albums" -> AlbumGrid(st, songs, pad)
            "artists" -> ArtistList(st, songs, pad)
            "genres" -> GenreList(st, songs, pad)
            "playlists" -> PlaylistList(st, pad)
            "folders" -> FolderList(st, songs, pad)
        }
    }
}

@Composable private fun EmptyLibrary(st: AppState) {
    Empty("No music or video yet.\nAdd this device, a folder, or a cloud account in Settings.", "Open Settings") { st.tab = "set"; st.openSettingsCat("sources") }
}

@Composable
private fun SongList(st: AppState, songs: List<Item>, pad: PaddingValues) {
    if (songs.isEmpty()) { Empty("No songs."); return }
    val ls = rememberLazyListState()
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = ls, contentPadding = contentPad(pad)) {
            item { Row(Modifier.padding(vertical = 6.dp)) {
                Btn("Play all", { st.playList(songs, 0) }, icon = Ic.play, small = true)
                Spacer(Modifier.width(8.dp)); Btn("Shuffle", { st.playList(songs.shuffled(), 0) }, icon = Ic.shuffle, small = true, kind = BtnKind.Ghost)
            } }
            items(songs, key = { it.i }) { x -> SongRow(st, x, songs) }
        }
    }
}

@Composable
internal fun SongRow(st: AppState, x: Item, list: List<Item>) {
    Row(Modifier.fillMaxWidth().clickable { st.playItem(x, list) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(st.artReq(x), x.title, Modifier.size(46.dp), px = 160, fallback = st.artAlt(x))
        Column(Modifier.padding(horizontal = 12.dp).weight(1f)) {
            Text(x.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (st.player.track?.optLong("i") == x.i) C.ac else C.tx)
            Muted(listOf(x.artist, st.genreOf(x) ?: "").filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp)
        }
        if (st.isDownloaded(x)) Icon(Ic.offline, "Offline", tint = C.ac2, modifier = Modifier.size(16.dp).padding(end = 4.dp))
        IconBtn(Ic.more, { st.dialog = Dlg.AddToPlaylist(listOf(x.i)) }, size = 34.dp, iconSize = 20.dp, tint = C.mu)
    }
}

@Composable
private fun AlbumGrid(st: AppState, songs: List<Item>, pad: PaddingValues) {
    val albums = remember(songs) {
        val g = LinkedHashMap<String, AlbumGroup>()
        songs.forEach { x -> val k = x.albKey; val grp = g.getOrPut(k) { AlbumGroup(k, x.album) }; grp.items.add(x); if (grp.art == null && x.th) grp.art = x; if (x.y > 0) grp.year = x.y; grp.artist = x.artist }
        g.values.sortedBy { it.name.lowercase() }
    }
    if (albums.isEmpty()) { Empty("No albums."); return }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(albums, key = { it.key }) { a ->
            Column(Modifier.clickable { st.open(Page.Album(a.key, a.name)) }) {
                Thumb(st.artReq(a.art), a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 300, fallback = st.artAlt(a.art))
                Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Muted(a.artist, size = 11.sp)
            }
        }
    }
}

@Composable
private fun ArtistList(st: AppState, songs: List<Item>, pad: PaddingValues) {
    val artists = remember(songs) { songs.groupBy { it.akey }.map { (_, l) -> l.first().artist to l.size }.sortedBy { it.first.lowercase() } }
    if (artists.isEmpty()) { Empty("No artists."); return }
    LazyColumn(contentPadding = contentPad(pad)) {
        items(artists, key = { it.first }) { (name, n) ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.Artist(name)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(21.dp)).let { it }, contentAlignment = Alignment.Center) { Icon(Ic.artist, null, tint = C.mu) }
                Column(Modifier.padding(start = 12.dp)) { Text(name, fontWeight = FontWeight.SemiBold); Muted("$n song${if (n == 1) "" else "s"}", size = 12.sp) }
            }
        }
    }
}

@Composable
private fun GenreList(st: AppState, songs: List<Item>, pad: PaddingValues) {
    val genres = remember(songs, st.meta) { songs.mapNotNull { st.genreOf(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
    if (genres.isEmpty()) { Empty("No genres yet — run “Look up song info” in Settings."); return }
    LazyColumn(contentPadding = contentPad(pad)) {
        items(genres, key = { it.key }) { (g, n) ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.GenrePage(g)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.genre, null, tint = C.ac); Column(Modifier.padding(start = 12.dp)) { Text(g, fontWeight = FontWeight.SemiBold); Muted("$n songs", size = 12.sp) }
            }
        }
    }
}

@Composable
private fun FolderList(st: AppState, songs: List<Item>, pad: PaddingValues) {
    val folders = remember(songs) { songs.groupBy { it.folder }.map { it.key to it.value.size }.sortedBy { it.first.lowercase() } }
    LazyColumn(contentPadding = contentPad(pad)) {
        items(folders, key = { it.first }) { (f, n) ->
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.FolderPage(f)) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.folder, null, tint = C.ac); Column(Modifier.padding(start = 12.dp)) { Text(f.substringAfterLast('/'), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Muted("$n songs", size = 12.sp) }
            }
        }
    }
}

@Composable
private fun PlaylistList(st: AppState, pad: PaddingValues) {
    val smart = st.smartPlaylists(); val mine = st.playlists()
    LazyColumn(contentPadding = contentPad(pad)) {
        item { Btn("New playlist", { st.dialog = Dlg.TextInput("New playlist", "Name", "") { n -> st.createPlaylist(n); st.say("Created \"$n\"") } }, icon = Ic.playlistAdd, small = true, modifier = Modifier.padding(vertical = 6.dp)) }
        if (smart.isNotEmpty()) item { SectionHeader("Automatic") }
        items(smart, key = { it.id }) { p -> PlRow(st, p) }
        if (mine.isNotEmpty()) item { SectionHeader("Your playlists") }
        items(mine, key = { it.id }) { p -> PlRow(st, p) }
        if (mine.isEmpty() && smart.isEmpty()) item { Empty("No playlists yet.") }
    }
}
@Composable private fun PlRow(st: AppState, p: Playlist) {
    Row(Modifier.fillMaxWidth().clickable { st.open(Page.PlaylistPage(p.id)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (p.smart) Ic.star else Ic.playlist, null, tint = C.ac)
        Column(Modifier.padding(start = 12.dp).weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Muted("${p.ids.size} songs", size = 12.sp) }
        IconBtn(Ic.play, { st.playPlaylist(p).let { st.say(it) } })
    }
}

// ---------------- video ----------------
@Composable
fun VideoLibrary(st: AppState, pad: PaddingValues) {
    var sub by remember { mutableStateOf("all") }
    var q by remember { mutableStateOf("") }
    val vids = remember(st.allItems, q, sub) {
        val v = st.videos().filter { when (sub) { "movies" -> it.k == "movie"; "tv" -> it.k == "tv"; "mv" -> it.k == "mv"; else -> true } }
        if (q.isBlank()) v else v.filter { st.vtitle(it).contains(q, true) || it.n.contains(q, true) }
    }
    Column(Modifier.fillMaxSize()) {
        var stream by remember { mutableStateOf(false) }
        if (stream) StreamDialog(st) { stream = false }
        TopBar(st, "Video", actions = {
            IconBtn(Ic.link, { stream = true })
            IconBtn(Ic.download, { st.dialog = Dlg.Confirm("Sync all videos for offline?", "Downloads TMDB details, posters, backdrops, cast, episode info and subtitles (with a Wyzie key) for every video and saves them on the device. Already-saved items are skipped.") { st.syncVideos() } })
            IconBtn(Ic.sync, { st.sync() })
        })
        st.sync.video?.let { p -> Column(Modifier.padding(horizontal = 16.dp)) { Progress(if (p.total > 0) p.done.toFloat() / p.total else null); Muted("${p.step} · ${p.done} / ${p.total}", size = 12.sp) } }
        Field(q, { q = it }, "Search videos…", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth())
        Seg(listOf("all" to "All", "movies" to "Movies", "tv" to "TV", "mv" to "Music video"), sub) { sub = it }
        if (st.videos().isEmpty()) { EmptyLibrary(st); return@Column }
        if (sub == "tv") TvShowGrid(st, pad) else VideoGrid(st, vids, pad)
    }
}

@Composable
private fun VideoGrid(st: AppState, vids: List<Item>, pad: PaddingValues) {
    if (vids.isEmpty()) { Empty("No videos."); return }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(vids, key = { it.i }) { x ->
            Column(Modifier.clickable { if (x.k == "tv") st.open(Page.Show(st.mkey(x) ?: "")) else st.open(Page.Movie(x)) }) {
                Thumb(st.posterReq(x), st.vtitle(x), Modifier.fillMaxWidth().aspectRatio(2f / 3f), px = 300)
                Text(st.vtitle(x), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
                st.match(x)?.year?.takeIf { it.isNotEmpty() }?.let { Muted(it, size = 11.sp) }
            }
        }
    }
}

@Composable
private fun TvShowGrid(st: AppState, pad: PaddingValues) {
    val shows = remember(st.allItems) {
        st.videos().filter { it.k == "tv" }.groupBy { it.show?.lowercase() ?: "" }.mapNotNull { (_, eps) -> eps.firstOrNull()?.let { it to eps.size } }.sortedBy { st.vtitle(it.first).lowercase() }
    }
    if (shows.isEmpty()) { Empty("No TV shows."); return }
    LazyVerticalGrid(GridCells.Adaptive(110.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shows, key = { it.first.i }) { (x, n) ->
            Column(Modifier.clickable { st.open(Page.Show(st.mkey(x) ?: "")) }) {
                Thumb(st.posterReq(x), st.vtitle(x), Modifier.fillMaxWidth().aspectRatio(2f / 3f), px = 300)
                Text(st.vtitle(x), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Muted("$n episode${if (n == 1) "" else "s"}", size = 11.sp)
            }
        }
    }
}

@Composable
internal fun TopBar(st: AppState, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
        actions()
    }
}

/** "Open network stream" (like VLC's): a video URL — MP4/WebM/MKV over http(s), HLS (.m3u8) or DASH (.mpd). */
@Composable
private fun StreamDialog(st: AppState, onDone: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val recent = remember { try { org.json.JSONArray(st.pref("vstreams", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() } }
    fun play(u: String) {
        val v = u.trim(); if (!v.startsWith("http://") && !v.startsWith("https://") && !v.startsWith("rtsp://")) { st.say("Enter a link starting with http:// or https://"); return }
        st.setPref("vstreams", org.json.JSONArray((listOf(v) + recent.filter { it != v }).take(8)).toString())
        val q = org.json.JSONArray().put(org.json.JSONObject().put("i", app.orionmd.omniplay.idFor(v)).put("t", android.net.Uri.parse(v).lastPathSegment ?: v).put("u", v))
        try { st.host.launch(android.content.Intent(st.ctx, app.orionmd.omniplay.VideoActivity::class.java).putExtra("queue", q.toString()).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        onDone()
    }
    androidx.compose.ui.window.Dialog(onDone) {
        Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 280.dp)) {
            Text("Open network stream", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Muted("A video link: MP4, WebM or MKV over http(s), live HLS (.m3u8) or DASH (.mpd).", Modifier.padding(top = 4.dp), 12.sp)
            Field(url, { url = it }, "https://…", Modifier.fillMaxWidth().padding(vertical = 10.dp))
            if (recent.isNotEmpty()) {
                Muted("Recent", size = 12.sp)
                recent.forEach { r -> Text(r, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, fontSize = 13.sp, color = C.ac2,
                    modifier = Modifier.fillMaxWidth().clickable { play(r) }.padding(vertical = 6.dp)) }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Btn("Cancel", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                Btn("Play", { play(url) }, Modifier.weight(1f))
            }
        }
    }
}
