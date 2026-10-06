package app.orionmd.omniplay.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// ============================================================ Gallery tab

@Composable
fun GalleryScreen(st: AppState, pad: PaddingValues) {
    val g = st.gallery
    LaunchedEffect(Unit) { if (g.items == null) st.loadGallery() }
    var sub by remember { mutableStateOf(st.pref("gTab", "timeline")) }
    var newAlbum by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (g.sel.isNotEmpty()) SelectionBar(st) else TopBar(st, "Gallery", actions = {
            PrivateToggle(st)
            IconBtn(Ic.folder, { folders = true })
            IconBtn(Ic.trash, { st.open(Page.GTrash) })
            IconBtn(Ic.sync, { st.loadGallery() })
        })
        Seg(listOf("timeline" to "Timeline", "albums" to "Albums", "groups" to "Groups", "create" to "Create"), sub) { sub = it; st.setPref("gTab", it) }
        val items = g.items
        when {
            items == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Progress(null) }
            items.isEmpty() && sub != "groups" && !g.all.isNullOrEmpty() -> Empty("Every folder is hidden.\nChoose which folders the Gallery shows.", "Choose folders") { folders = true }
            items.isEmpty() && sub != "groups" -> Empty("No photos or videos found.\nAllow Media Hub to access photos and videos when Android asks, then tap refresh.", "Refresh") { st.loadGallery() }
            sub == "timeline" -> Timeline(st, items, pad)
            sub == "albums" -> AlbumsGrid(st, items, pad) { newAlbum = true }
            sub == "groups" -> GroupsList(st, items, pad) { newGroup = true }
            else -> CreateHub(st, pad)
        }
    }
    if (newAlbum) NameDialog("New album", "Album name", { newAlbum = false }) { n -> Gallery.createAlbum(st, n, g.sel.toList().mapNotNull { k -> g.items?.firstOrNull { it.key == k }?.uri }); g.sel.clear(); st.say("Album \"$n\" created") }
    if (newGroup) GroupEditor(st, null) { newGroup = false }
    if (folders) FoldersDialog(st) { folders = false }
}

/** Choose which device folders appear in the Gallery (Timeline, Albums, pickers). Nothing is moved or deleted. */
@Composable
private fun FoldersDialog(st: AppState, onDone: () -> Unit) {
    val all = st.gallery.all.orEmpty()
    val folders = remember(all) { Gallery.deviceAlbums(all).sortedBy { it.name.lowercase() } }
    val hidden = remember { mutableStateListOf<String>().apply { addAll(Gallery.hidden(st)) } }
    fun id(a: GAlbum) = a.key.removePrefix("b:")
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.85f).clip(RoundedCornerShape(16.dp)).background(C.s1).padding(14.dp)) {
            Text("Folders in Gallery", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Muted("Ticked folders are shown. Unticked ones are hidden from the Gallery only — nothing is moved or deleted, and photos you added to your own albums stay in them.", Modifier.padding(vertical = 6.dp), 12.sp)
            Row(Modifier.padding(bottom = 6.dp)) {
                Btn("Show all", { hidden.clear() }, small = true, kind = BtnKind.Ghost); Spacer(Modifier.width(6.dp))
                Btn("Hide all", { hidden.clear(); hidden.addAll(folders.map { id(it) }) }, small = true, kind = BtnKind.Ghost)
                Spacer(Modifier.weight(1f))
                Muted("${folders.count { id(it) !in hidden }} of ${folders.size}", Modifier.align(Alignment.CenterVertically), 12.sp)
            }
            if (folders.isEmpty()) Muted("No folders found yet.", Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(folders, key = { it.key }) { a ->
                    val on = id(a) !in hidden
                    Row(Modifier.fillMaxWidth().clickable { if (on) hidden.add(id(a)) else hidden.remove(id(a)) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Thumb(a.items.firstOrNull()?.let { Img.Thumb(it.uri, it.video) }, a.name, Modifier.size(44.dp), px = 120, corner = 6.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) C.tx else C.mu)
                            Muted("${a.items.count { !it.video }} photos · ${a.items.count { it.video }} videos", size = 11.sp)
                        }
                        androidx.compose.material3.Checkbox(on, { v -> if (v) hidden.remove(id(a)) else hidden.add(id(a)) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.ac, uncheckedColor = C.mu))
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp)) {
                Btn("Cancel", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                Btn("Save", { Gallery.setHidden(st, hidden.toSet()); onDone() }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SelectionBar(st: AppState) {
    val g = st.gallery
    val chosen = g.items.orEmpty().filter { it.key in g.sel }
    var addTo by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().statusBarsPadding().background(C.s2).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.close, { g.sel.clear() })
            Text("${g.sel.size} selected", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconBtn(Ic.playlistAdd, { addTo = true })
            IconBtn(Ic.send, { st.shareItems(chosen) })
            IconBtn(Ic.trash, { st.trashItems(chosen) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val photos = chosen.filter { !it.video }
            Btn("Collage", { st.open(Page.GCreate("collage", photos.take(9))) }, small = true, kind = BtnKind.Pill, modifier = Modifier.padding(end = 6.dp))
            Btn("GIF", { st.open(Page.GCreate("gif", chosen.take(60))) }, small = true, kind = BtnKind.Pill, modifier = Modifier.padding(end = 6.dp))
            Btn("Movie", { st.open(Page.GCreate("movie", chosen.take(60))) }, small = true, kind = BtnKind.Pill, modifier = Modifier.padding(end = 6.dp))
            Btn("Long exposure", { st.open(Page.GCreate("exposure", chosen.take(60))) }, small = true, kind = BtnKind.Pill, modifier = Modifier.padding(end = 6.dp))
        }
    }
    if (addTo) AddToAlbumDialog(st, chosen) { addTo = false }
}


@Composable
private fun Cell(st: AppState, x: GItem, list: List<GItem>, albumKey: String = "") {
    val g = st.gallery
    val selected = x.key in g.sel
    val rot = remember(g.tick, st.prefGen) { Gallery.rotation(st, x.uri) }
    Box(Modifier.aspectRatio(1f).padding(1.dp).combinedClickable(
        onClick = { if (g.sel.isNotEmpty()) toggle(g, x.key) else { g.viewList = list; st.open(Page.GViewer(list.indexOf(x).coerceAtLeast(0), albumKey)) } },
        onLongClick = { toggle(g, x.key) })) {
        Thumb(Img.Thumb(x.uri, x.video), "", Modifier.fillMaxSize().graphicsLayer { rotationZ = rot.toFloat() }, px = 300, corner = 2.dp)
        if (x.video) Text(mmss(x.dur), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
        if (g.sel.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(if (selected) C.ac else Color(0x66000000)).border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center) { if (selected) Icon(Ic.done, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
    }
}
private fun toggle(g: GalleryState, k: String) { if (k in g.sel) g.sel.remove(k) else g.sel.add(k) }

// ------------------------------------------------------------ timeline
@Composable
private fun Timeline(st: AppState, items: List<GItem>, pad: PaddingValues) {
    var zoom by remember { mutableStateOf(st.pref("gZoom", "day")) }
    val fmt = when (zoom) { "year" -> "yyyy"; "month" -> "MMMM yyyy"; else -> "EEE, d MMM yyyy" }
    val groups = remember(items, zoom) { val f = SimpleDateFormat(fmt, Locale.getDefault()); items.groupBy { f.format(Date(it.date)) }.toList() }
    val cols = when (zoom) { "year" -> 6; "month" -> 5; else -> 4 }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Muted("${fmt(items.size)} items · group by", size = 12.sp)
        listOf("day" to "Day", "month" to "Month", "year" to "Year").forEach { (k, l) ->
            Text(l, color = if (zoom == k) C.ac else C.mu, fontWeight = if (zoom == k) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp,
                modifier = Modifier.padding(start = 10.dp).clickable { zoom = k; st.setPref("gZoom", k) })
        }
    }
    LazyVerticalGrid(GridCells.Fixed(cols), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = pad.calculateBottomPadding() + 8.dp)) {
        groups.forEach { (label, l) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h$label") {
                Row(Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Muted("${l.size}", Modifier.padding(end = 8.dp), 12.sp)
                }
            }
            items(l, key = { it.key }) { x -> Cell(st, x, items) }
        }
    }
}

// ------------------------------------------------------------ albums
@Composable
private fun AlbumsGrid(st: AppState, items: List<GItem>, pad: PaddingValues, onNew: () -> Unit) {
    val albums = remember(items, st.prefGen) { Gallery.allAlbums(st, items) }
    var menu by remember { mutableStateOf<GAlbum?>(null) }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Row { Btn("New album", onNew, icon = Ic.add, small = true); Spacer(Modifier.width(8.dp)); Muted("Long-press an album for cover, rename and more.", Modifier.align(Alignment.CenterVertically), 12.sp) } }
        items(albums, key = { it.key }) { a -> AlbumCard(st, a, { st.open(Page.GAlbumPage(a.key)) }, { menu = a }) }
    }
    menu?.let { a -> AlbumMenu(st, a) { menu = null } }
}

@Composable
private fun AlbumCard(st: AppState, a: GAlbum, onClick: () -> Unit, onLong: () -> Unit) {
    val c = Gallery.cover(st, a)
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Thumb(c?.let { Img.Thumb(it.uri, it.video) }, a.name, Modifier.fillMaxWidth().aspectRatio(1f), px = 400, corner = 12.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            if (a.custom) Icon(Ic.star, null, tint = C.ac, modifier = Modifier.size(14.dp).padding(end = 2.dp))
            Text(a.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Muted("${a.items.size}", size = 11.sp)
    }
}

@Composable
private fun AlbumMenu(st: AppState, a: GAlbum, onDone: () -> Unit) {
    var pickCover by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var toGroup by remember { mutableStateOf(false) }
    when {
        pickCover -> PickItemDialog("Choose a cover for \"${a.name}\"", a.items, { onDone() }) { x -> Gallery.setCover(st, a.key, x.uri); st.say("Cover updated"); onDone() }
        rename -> NameDialog("Rename album", "Name", { onDone() }, a.name) { n -> Gallery.renameAlbum(st, a.key, n); onDone() }
        toGroup -> PickGroupDialog(st, { onDone() }) { gr -> Gallery.saveGroup(st, gr.copy(albums = (gr.albums + a.key).distinct())); st.say("Added to \"${gr.name}\""); onDone() }
        else -> Dialog(onDone) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(a.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Btn("Set cover image…", { pickCover = true }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                Btn("Add to a group…", { toGroup = true }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                Btn("Share all", { st.shareItems(a.items.take(100)); onDone() }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                if (!a.custom) Btn(if (Lock.isLocked(st.store, a.key.replace("b:", "bucket:"))) "Unlock this folder" else "Lock with PIN", {
                    st.toggleLock(a.key.replace("b:", "bucket:"), a.name); onDone()
                    if (st.pages.lastOrNull() is Page.GAlbumPage) st.back()
                }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                if (!a.custom) Btn("Hide this folder from Gallery", {
                    Gallery.setHidden(st, Gallery.hidden(st) + a.key.removePrefix("b:")); st.say("\"${a.name}\" hidden — bring it back with the folder button"); onDone()
                    if (st.pages.lastOrNull() is Page.GAlbumPage) st.back()
                }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                if (a.custom) {
                    Btn("Rename", { rename = true }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                    Btn("Delete album (photos are kept)", { Gallery.deleteAlbum(st, a.key); onDone() }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
                }
                Btn("Close", onDone, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            }
        }
    }
}

@Composable
fun GAlbumScreen(st: AppState, p: Page.GAlbumPage, pad: PaddingValues) {
    val items = st.gallery.items.orEmpty()
    val a = remember(items, st.prefGen, p.key) { Gallery.allAlbums(st, items).firstOrNull { it.key == p.key } }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        if (st.gallery.sel.isNotEmpty()) SelectionBar(st) else Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { st.back() })
            Column(Modifier.weight(1f)) { Text(a?.name ?: "Album", fontSize = 20.sp, fontWeight = FontWeight.Bold); Muted("${a?.items?.size ?: 0} items", size = 12.sp) }
            if (a?.custom == true && st.gallery.sel.isEmpty()) IconBtn(Ic.more, { menu = true }) else if (a != null) IconBtn(Ic.more, { menu = true })
        }
        if (a == null) { Empty("Album not found."); return@Column }
        LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, bottom = pad.calculateBottomPadding() + 8.dp)) {
            items(a.items, key = { it.key }) { x -> Cell(st, x, a.items, a.key) }
        }
        if (menu) AlbumMenu(st, a) { menu = false }
    }
}

// ------------------------------------------------------------ groups
@Composable
private fun GroupsList(st: AppState, items: List<GItem>, pad: PaddingValues, onNew: () -> Unit) {
    val groups = remember(st.prefGen) { Gallery.groups(st) }
    val albums = remember(items, st.prefGen) { Gallery.allAlbums(st, items).associateBy { it.key } }
    LazyColumn(contentPadding = contentPad(pad)) {
        item { Btn("New group", onNew, icon = Ic.add, small = true, modifier = Modifier.padding(vertical = 6.dp)); Muted("A group combines several albums — e.g. \"Holidays\" = Beach + Camera + WhatsApp Images.", size = 12.sp) }
        if (groups.isEmpty()) item { Empty("No groups yet.") }
        items(groups, key = { it.id }) { gr ->
            val members = gr.albums.mapNotNull { albums[it] }
            val cover = members.firstOrNull()?.let { Gallery.cover(st, it) }
            Row(Modifier.fillMaxWidth().clickable { st.open(Page.GGroupPage(gr.id)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(cover?.let { Img.Thumb(it.uri, it.video) }, gr.name, Modifier.size(64.dp), px = 160, corner = 10.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(gr.name, fontWeight = FontWeight.SemiBold)
                    Muted("${members.size} albums · ${members.sumOf { it.items.size }} items", size = 12.sp)
                }
            }
        }
    }
}

@Composable
fun GGroupScreen(st: AppState, p: Page.GGroupPage, pad: PaddingValues) {
    val items = st.gallery.items.orEmpty()
    val gr = remember(st.prefGen, p.id) { Gallery.groups(st).firstOrNull { it.id == p.id } }
    val albums = remember(items, st.prefGen) { Gallery.allAlbums(st, items).associateBy { it.key } }
    var edit by remember { mutableStateOf(false) }
    if (gr == null) { BackBar(st); Empty("Group not found."); return }
    val members = gr.albums.mapNotNull { albums[it] }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = contentPad(pad), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                BackBar(st)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(gr.name, fontSize = 22.sp, fontWeight = FontWeight.Bold); Muted("${members.size} albums", size = 12.sp) }
                    Btn("Edit", { edit = true }, small = true, kind = BtnKind.Ghost)
                    Spacer(Modifier.width(6.dp))
                    Btn("Delete", { Gallery.deleteGroup(st, gr.id); st.back() }, small = true, kind = BtnKind.Danger)
                }
            }
        }
        items(members, key = { it.key }) { a -> AlbumCard(st, a, { st.open(Page.GAlbumPage(a.key)) }, {}) }
    }
    if (edit) GroupEditor(st, gr) { edit = false }
}

@Composable
private fun GroupEditor(st: AppState, gr: GGroup?, onDone: () -> Unit) {
    val albums = remember(st.gallery.items, st.prefGen) { Gallery.allAlbums(st, st.gallery.items.orEmpty()) }
    var name by remember { mutableStateOf(gr?.name ?: "") }
    val chosen = remember { mutableStateListOf<String>().apply { addAll(gr?.albums ?: emptyList()) } }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp)) {
            Text(if (gr == null) "New group" else "Edit group", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Field(name, { name = it }, "Group name", Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Muted("Albums in this group", size = 12.sp)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(albums, key = { it.key }) { a -> Check(a.key in chosen, "${a.name} (${a.items.size})") { on -> if (on) chosen.add(a.key) else chosen.remove(a.key) } }
            }
            Row(Modifier.padding(top = 10.dp)) {
                Btn("Cancel", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                Btn("Save", { if (name.isNotBlank()) { Gallery.saveGroup(st, GGroup(gr?.id ?: System.currentTimeMillis().toString(36), name.trim(), chosen.toList())); onDone() } }, Modifier.weight(1f))
            }
        }
    }
}

// ------------------------------------------------------------ creation hub
@Composable
private fun CreateHub(st: AppState, pad: PaddingValues) {
    LazyColumn(contentPadding = contentPad(pad)) {
        item {
            Muted("Select photos or videos in Timeline or an album (long-press), then pick a tool from the bar at the top — or start here and choose.", Modifier.padding(vertical = 8.dp), 13.sp)
        }
        val tools = listOf(
            Triple("movie", "Movie & Reel", "Join photos and video clips into a video — trim, reorder, fades, slow zoom on photos, title, captions and background music. Reel (9:16), Movie (16:9), Square or Portrait."),
            Triple("collage", "Collage", "Arrange 2–9 photos in a grid layout — spacing, rounded corners, background, shape."),
            Triple("gif", "Animated GIF", "Turn photos, or a stretch of a video, into a looping GIF — choose speed, frames and size."),
            Triple("exposure", "Long exposure", "Blend a video (or a burst of photos) into one shot — water turns silky, traffic becomes light trails."),
        )
        items(tools) { (k, t, d) ->
            Card(Modifier.clickable { st.open(Page.GCreate(k, emptyList())) }) {
                Text(t, fontWeight = FontWeight.Bold); Muted(d, size = 12.sp)
            }
        }
        item {
            Card {
                Text("Stickers", fontWeight = FontWeight.Bold)
                Muted("Open a photo and press and hold on it — the main subject is cut out (on your phone, with Google's ML Kit) and saved as a sticker.", size = 12.sp)
            }
            Card {
                Text("Edit & eraser", fontWeight = FontWeight.Bold)
                Muted("Open a photo → Edit: crop, rotate, flip, light & colour, filters, and AI Eraser — paint over people or objects and the background is rebuilt (on the phone, after a one-time 28 MB download).", size = 12.sp)
            }
        }
    }
}

// ============================================================ viewer

@Composable
fun GViewerScreen(st: AppState, p: Page.GViewer) {
    val list = st.gallery.viewList
    if (list.isEmpty()) { LaunchedEffect(Unit) { st.back() }; return }
    val pager = rememberPagerState(initialPage = p.start.coerceIn(0, list.size - 1)) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var wall by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    var sticker by remember { mutableStateOf<Bitmap?>(null) }
    var cutting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val cur = list.getOrNull(pager.currentPage) ?: list[0]
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { list[it].key }) { i ->
            val x = list[i]
            if (!(x.video && i == pager.settledPage)) ZoomImage(st, x, onTap = { chrome = !chrome }, onLong = {
                if (x.video || cutting) return@ZoomImage
                cutting = true; st.say("Cutting out the subject…")
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) { PhotoOps.load(st.ctx, x, 1600)?.let { PhotoOps.rotate(it, Gallery.rotation(st, x.uri)) } }
                    val cut = bmp?.let { PhotoOps.cutOut(it) }
                    cutting = false
                    if (cut == null) st.say("Couldn't find a subject to cut out (needs Google Play services; the first time it downloads a small model).") else sticker = cut
                }
            })
            // videos play right here once their page is on screen (one player at a time)
            if (x.video && i == pager.settledPage) GalleryVideo(x, onChrome = { chrome = it })
            else if (x.video) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xAA000000)), contentAlignment = Alignment.Center) { Icon(Ic.play, "Play", tint = Color.White, modifier = Modifier.size(44.dp)) }
            }
        }
        if (cutting) Box(Modifier.align(Alignment.Center)) { androidx.compose.material3.CircularProgressIndicator(color = C.ac) }
        if (chrome) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(Color(0x88000000)).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ic.back, { st.back() }, tint = Color.White)
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(cur.date)), color = Color.White, fontSize = 14.sp)
                    Text(cur.bucket, color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
                }
                IconBtn(Ic.info, { info = true }, tint = Color.White)
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().background(Color(0x88000000)).padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (cur.video) Act(Ic.expand, "Full player") { st.playGalleryVideo(cur) }
                if (!cur.video) Act(Ic.tune, "Edit") { st.open(Page.GEditor(cur)) }
                if (!cur.video) Act(Ic.rotate, "Rotate") { Gallery.rotate(st, cur.uri, 90) }
                Act(Ic.send, "Share") { st.shareItems(listOf(cur)) }
                if (!cur.video) Act(Ic.skin, "Wallpaper") { wall = true }
                Act(Ic.playlistAdd, "Album") { addTo = true }
                if (p.albumKey.isNotEmpty()) Act(Ic.star, "Cover") { Gallery.setCover(st, p.albumKey, cur.uri); st.say("Set as album cover") }
                Act(Ic.trash, "Trash") { st.trashItems(listOf(cur)) }
            }
        }
    }
    if (wall) WallpaperDialog(st, cur) { wall = false }
    if (addTo) AddToAlbumDialog(st, listOf(cur)) { addTo = false }
    if (info) Dialog({ info = false }) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp)) {
            Text(cur.name, fontWeight = FontWeight.Bold)
            listOf("Album" to cur.bucket, "Date" to SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date(cur.date)),
                "Size" to bytes(cur.size), "Dimensions" to if (cur.w > 0) "${cur.w} × ${cur.h}" else "—", "Type" to cur.mime,
            ).plus(if (cur.video) listOf("Length" to mmss(cur.dur)) else emptyList()).forEach { (k, v) -> Row(Modifier.padding(top = 6.dp)) { Muted(k, Modifier.width(96.dp)); Text(v, fontSize = 13.sp) } }
            Btn("Close", { info = false }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = BtnKind.Ghost)
        }
    }
    sticker?.let { s -> StickerDialog(st, s) { sticker = null } }
}

@Composable private fun Act(ic: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(ic, label, tint = Color.White, modifier = Modifier.size(22.dp)); Text(label, color = Color.White, fontSize = 10.sp)
    }
}

/** Full-size photo with pinch-zoom / pan / double-tap zoom. */
/** Plays a gallery video inline (media3 player with its own seek bar / play / pause controls). */
@Composable
private fun GalleryVideo(x: GItem, onChrome: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val player = remember(x.uri) {
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx, OmniRenderers(ctx, video = true))
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true).build().apply {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(x.uri)); prepare(); playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    // pause when the app goes to the background
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c ->
            androidx.media3.ui.PlayerView(c).apply {
                this.player = player
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setControllerVisibilityListener(androidx.media3.ui.PlayerView.ControllerVisibilityListener { v -> onChrome(v == android.view.View.VISIBLE) })
                setBackgroundColor(android.graphics.Color.BLACK)
                // the player skin's accent on the seek bar and play button (like the full video player)
                val sk = skinOf(app.orionmd.omniplay.Store(c).getPref("playerSkin", "midnight"))
                val acc = sk.accent.let { (0xFF shl 24) or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt() }
                findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply { setPlayedColor(acc); setScrubberColor(acc); setBufferedColor((acc and 0x00FFFFFF) or 0x66000000) }
                (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause) as? android.widget.ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(acc)
            }
        },
        update = { it.player = player },
        // keep the seek bar clear of the viewer's top and bottom bars
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 52.dp, bottom = 60.dp),
    )
}

@Composable
private fun ZoomImage(st: AppState, x: GItem, onTap: () -> Unit, onLong: () -> Unit) {
    var bmp by remember(x.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(x.uri) { bmp = withContext(Dispatchers.IO) { PhotoOps.load(st.ctx, x, 2048) } }
    val rot = remember(st.prefGen, x.uri) { Gallery.rotation(st, x.uri) }
    var scale by remember(x.uri) { mutableFloatStateOf(1f) }
    var off by remember(x.uri) { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize()
        .pointerInput(x.uri) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f }, onLongPress = { onLong() }) }
        // pinch to zoom, drag to move a zoomed photo — a one-finger swipe on an unzoomed photo is left alone, so it
        // reaches the pager and goes to the next / previous photo (detectTransformGestures took every swipe)
        .pointerInput(x.uri) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    val fingers = ev.changes.count { it.pressed }
                    if (fingers >= 2 || scale > 1f) {
                        val z = ev.calculateZoom(); val pan = ev.calculatePan()
                        scale = (scale * z).coerceIn(1f, 6f)
                        off = if (scale <= 1f) Offset.Zero else off + pan
                        ev.changes.forEach { if (it.pressed) it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        },
        contentAlignment = Alignment.Center) {
        val b = bmp
        if (b == null) Thumb(Img.Thumb(x.uri, x.video), "", Modifier.fillMaxSize(), px = 400, corner = 0.dp, contentScale = ContentScale.Fit, placeholder = false)
        else Image(b.asImageBitmap(), x.name, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y; rotationZ = rot.toFloat() }, contentScale = ContentScale.Fit)
    }
}

// ------------------------------------------------------------ small dialogs
@Composable
private fun WallpaperDialog(st: AppState, x: GItem, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun set(which: Int) {
        onDone(); st.say("Setting wallpaper…")
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { val b = PhotoOps.load(st.ctx, x, 2560) ?: return@withContext false; PhotoOps.setWallpaper(st.ctx, PhotoOps.rotate(b, Gallery.rotation(st, x.uri)), which); true } catch (_: Exception) { false } }
            st.say(if (ok) "Wallpaper set" else "Couldn't set the wallpaper")
        }
    }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set as wallpaper", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Btn("Home screen", { set(1) }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            Btn("Lock screen", { set(2) }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            Btn("Both", { set(3) }, Modifier.fillMaxWidth())
            Btn("Cancel", onDone, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
        }
    }
}

@Composable
private fun AddToAlbumDialog(st: AppState, items: List<GItem>, onDone: () -> Unit) {
    val custom = remember(st.prefGen) { Gallery.customAlbums(st, st.gallery.items.orEmpty()) }
    var naming by remember { mutableStateOf(false) }
    if (naming) { NameDialog("New album", "Album name", onDone) { n -> Gallery.createAlbum(st, n, items.map { it.uri }); st.gallery.sel.clear(); st.say("Added to new album \"$n\"") }; return }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 260.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add ${items.size} to album", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Btn("New album…", { naming = true }, Modifier.fillMaxWidth(), icon = Ic.add)
            custom.forEach { a -> Btn("${a.name} (${a.items.size})", { Gallery.addToAlbum(st, a.key, items.map { it.uri }); st.gallery.sel.clear(); st.say("Added to \"${a.name}\""); onDone() }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost) }
            Btn("Cancel", onDone, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, onDone: () -> Unit, initial: String = "", ok: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 280.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Field(v, { v = it }, label, Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Row { Btn("Cancel", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp)); Btn("OK", { if (v.isNotBlank()) { ok(v.trim()); onDone() } }, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun PickItemDialog(title: String, items: List<GItem>, onDone: () -> Unit, onPick: (GItem) -> Unit) {
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(C.s1).padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f, fill = false)) {
                items(items, key = { it.key }) { x -> Thumb(Img.Thumb(x.uri, x.video), "", Modifier.aspectRatio(1f).padding(1.dp).clickable { onPick(x) }, px = 200, corner = 2.dp) }
            }
            Btn("Cancel", onDone, Modifier.fillMaxWidth().padding(top = 8.dp), kind = BtnKind.Ghost)
        }
    }
}

@Composable
private fun PickGroupDialog(st: AppState, onDone: () -> Unit, onPick: (GGroup) -> Unit) {
    val groups = remember(st.prefGen) { Gallery.groups(st) }
    var naming by remember { mutableStateOf(groups.isEmpty()) }
    if (naming) { NameDialog("New group", "Group name", onDone) { n -> onPick(GGroup(System.currentTimeMillis().toString(36), n, emptyList())) }; return }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add to group", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Btn("New group…", { naming = true }, Modifier.fillMaxWidth(), icon = Ic.add)
            groups.forEach { g -> Btn(g.name, { onPick(g) }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost) }
            Btn("Cancel", onDone, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
        }
    }
}

@Composable
private fun StickerDialog(st: AppState, s: Bitmap, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sticker", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            // checkerboard shows the transparent background
            Box(Modifier.padding(vertical = 12.dp).size(260.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3F55)), contentAlignment = Alignment.Center) {
                Image(s.asImageBitmap(), "Sticker", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            }
            Row {
                Btn("Discard", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                Btn("Save sticker", {
                    scope.launch { val u = withContext(Dispatchers.IO) { PhotoOps.savePng(st.ctx, s, "Sticker", "Stickers") }
                        st.say(if (u != null) "Saved to Pictures/Media Hub/Stickers" else "Couldn't save"); st.loadGallery(); onDone() }
                }, Modifier.weight(1f))
            }
        }
    }
}

// ============================================================ editor

@Composable
fun GEditorScreen(st: AppState, p: Page.GEditor) {
    val scope = rememberCoroutineScope()
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var adj by remember { mutableStateOf(Adjust()) }
    var mode by remember { mutableStateOf("adjust") }
    var crop by remember { mutableStateOf(RectF(0f, 0f, 1f, 1f)) }
    var cropAspect by remember { mutableStateOf(0f) }
    val strokes = remember { mutableStateListOf<Offset>() }   // eraser brush points (0..1 of the image)
    var brush by remember { mutableFloatStateOf(.04f) }
    var busy by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Bitmap>() }
    LaunchedEffect(p.item.uri) { base = withContext(Dispatchers.IO) { PhotoOps.load(st.ctx, p.item, 2560)?.let { PhotoOps.rotate(it, Gallery.rotation(st, p.item.uri)) } } }
    fun push(b: Bitmap) { base?.let { history.add(it); if (history.size > 8) history.removeAt(0) }; base = b }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.close, { st.back() }, tint = Color.White)
            Text("Edit", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) Text("Undo", color = Color.White, modifier = Modifier.padding(horizontal = 10.dp).clickable { base = history.removeAt(history.size - 1) })
            Btn("Save copy", {
                val b = base ?: return@Btn
                busy = true
                scope.launch {
                    val u = withContext(Dispatchers.IO) { PhotoOps.saveJpeg(st.ctx, PhotoOps.applyAdjust(b, adj), "Edit") }
                    busy = false; st.say(if (u != null) "Saved a copy to Pictures/Media Hub" else "Couldn't save"); st.loadGallery(); st.back()
                }
            }, small = true)
        }
        // preview
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val b = base
            if (b == null || busy) androidx.compose.material3.CircularProgressIndicator(color = C.ac)
            else {
                val cm = remember(adj) { ColorMatrix(PhotoOps.matrix(adj).array) }
                var box by remember { mutableStateOf(IntSize.Zero) }
                Box(Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).onSizeChanged { box = it }) {
                    Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, colorFilter = ColorFilter.colorMatrix(cm))
                    if (mode == "crop") CropOverlay(crop, cropAspect * b.height / b.width) { crop = it }
                    if (mode == "erase") {
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                            detectDragGestures(onDragStart = { o -> strokes.add(Offset(o.x / size.width, o.y / size.height)) }) { ch, _ -> strokes.add(Offset(ch.position.x / size.width, ch.position.y / size.height)) }
                        }.pointerInput(Unit) { detectTapGestures { o -> strokes.add(Offset(o.x / size.width, o.y / size.height)) } }) {
                            strokes.forEach { s -> drawCircle(Color(0x88FF4081), radius = brush * size.width, center = Offset(s.x * size.width, s.y * size.height)) }
                        }
                    }
                }
            }
        }
        // tools
        Column(Modifier.fillMaxWidth().background(C.s1).navigationBarsPadding().padding(12.dp)) {
            when (mode) {
                "adjust" -> {
                    EditSlider("Brightness", adj.bright) { adj = adj.copy(bright = it) }
                    EditSlider("Contrast", adj.contrast) { adj = adj.copy(contrast = it) }
                    EditSlider("Saturation", adj.sat) { adj = adj.copy(sat = it) }
                    EditSlider("Warmth", adj.warmth) { adj = adj.copy(warmth = it) }
                }
                "filters" -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PhotoOps.FILTERS) { (k, l) -> Btn(l, { adj = adj.copy(filter = k) }, small = true, kind = if (adj.filter == k) BtnKind.Primary else BtnKind.Ghost) }
                }
                "crop" -> Column {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(0f to "Free", 1f to "1:1", 4f / 5f to "4:5", 3f / 4f to "3:4", 9f / 16f to "9:16", 16f / 9f to "16:9")) { (a, l) ->
                            Btn(l, { cropAspect = a; if (a > 0f) crop = fitAspect(a * (base?.height ?: 1) / (base?.width ?: 1)) }, small = true, kind = if (cropAspect == a) BtnKind.Primary else BtnKind.Ghost)
                        }
                    }
                    Row(Modifier.padding(top = 8.dp)) {
                        Btn("Rotate", { base?.let { push(PhotoOps.rotate(it, 90)) } }, small = true, kind = BtnKind.Ghost, icon = Ic.rotate); Spacer(Modifier.width(6.dp))
                        Btn("Flip", { base?.let { push(PhotoOps.flip(it)) } }, small = true, kind = BtnKind.Ghost); Spacer(Modifier.width(6.dp))
                        Btn("Apply crop", { base?.let { push(PhotoOps.crop(it, crop)); crop = RectF(0f, 0f, 1f, 1f) } }, small = true)
                    }
                }
                "erase" -> Column {
                    val lastRun = remember { AiEraser.checkLastRun(st.ctx) }   // before the support check below
                    LaunchedEffect(Unit) { lastRun?.let { st.say(it) } }
                    var aiReady by remember { mutableStateOf(AiEraser.isReady(st.ctx) && AiEraser.supported(st.ctx)) }
                    val aiOk = remember { AiEraser.supported(st.ctx) }
                    var dl by remember { mutableStateOf<Float?>(null) }
                    if (!aiOk) {
                        Muted(AiEraser.unsupportedReason(st.ctx) ?: "", size = 12.sp)
                        // free the space if the model was downloaded before this phone was found too small
                        if (AiEraser.isReady(st.ctx)) Btn("Remove AI model (${AiEraser.SIZE_MB} MB)", { AiEraser.remove(st.ctx); st.say("AI model removed") }, small = true, kind = BtnKind.Ghost)
                    }
                    else Muted(if (aiReady) "AI Eraser: paint over what to remove, then tap Erase — the background is regenerated."
                        else "Paint over what to remove. The AI Eraser regenerates the background realistically (one-time ${AiEraser.SIZE_MB} MB download, then works offline); Basic fills from the surroundings.", size = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted("Brush", Modifier.width(56.dp)); Slider(brush, { brush = it }, valueRange = .01f..0.12f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac))
                    }
                    dl?.let { Progress(it); Muted("Downloading AI Eraser… ${(it * 100).toInt()}%", size = 12.sp) }
                    fun run(ai: Boolean) {
                        val b = base ?: return; if (strokes.isEmpty()) { st.say("Paint over the object first"); return }
                        if (ai) AiEraser.memoryWarning(st.ctx)?.let { st.say(it); return }
                        busy = true; val pts = strokes.toList(); val r = brush
                        scope.launch {
                            val out = withContext(Dispatchers.Default) {
                                val work = if (ai) b else PhotoOps.fit(b, 1280)
                                val w = work.width; val h = work.height; val mask = BooleanArray(w * h)
                                val rad = (r * w).toInt().coerceAtLeast(2)
                                for (s in pts) { val cx = (s.x * w).toInt(); val cy = (s.y * h).toInt()
                                    for (y in max(0, cy - rad)..min(h - 1, cy + rad)) for (x in max(0, cx - rad)..min(w - 1, cx + rad)) if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= rad * rad) mask[y * w + x] = true }
                                try { if (ai) AiEraser.erase(st.ctx, work, mask) else PhotoOps.erase(work, mask) } catch (e: Throwable) { null }
                            }
                            busy = false
                            if (out != null) { push(out); strokes.clear() } else st.say("Couldn't erase — try a smaller area")
                        }
                    }
                    Row {
                        Btn("Clear", { strokes.clear() }, small = true, kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                        if (aiReady) Btn("Erase", { run(true) }, small = true, icon = Ic.claude)
                        else if (dl == null && aiOk) Btn("Get AI Eraser", {
                            dl = 0f
                            scope.launch {
                                val err = withContext(Dispatchers.IO) { AiEraser.download(st.ctx) { p -> scope.launch { dl = p } } }
                                dl = null; aiReady = AiEraser.isReady(st.ctx) && AiEraser.supported(st.ctx)
                                st.say(err?.let { "AI Eraser download failed: $it" } ?: "AI Eraser ready")
                            }
                        }, small = true, icon = Ic.download)
                        Spacer(Modifier.width(8.dp)); Btn("Basic", { run(false) }, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("adjust" to "Light & colour", "filters" to "Filters", "crop" to "Crop & rotate", "erase" to "Eraser").forEach { (k, l) ->
                    Text(l, color = if (mode == k) C.ac else C.mu, fontWeight = if (mode == k) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp, modifier = Modifier.clickable { mode = k }.padding(6.dp))
                }
            }
        }
    }
}

@Composable private fun EditSlider(label: String, v: Float, on: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Muted(label, Modifier.width(88.dp), 12.sp)
        Slider(v, on, valueRange = -1f..1f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac))
        Text(((v * 100).toInt()).toString(), color = C.mu, fontSize = 11.sp, modifier = Modifier.width(34.dp).clickable { on(0f) })
    }
}

/** Largest centred crop rect with box aspect [a] (width / height in 0..1 units of the image). */
private fun fitAspect(a: Float): RectF = if (a >= 1f) { val h = 1f / a; RectF(0f, (1 - h) / 2, 1f, (1 + h) / 2) } else { val w = a; RectF((1 - w) / 2, 0f, (1 + w) / 2, 1f) }

@Composable
private fun CropOverlay(r: RectF, aspect: Float, onChange: (RectF) -> Unit) {
    var rect by remember(r) { mutableStateOf(r) }
    Canvas(Modifier.fillMaxSize().pointerInput(aspect) {
        var corner = -1
        detectDragGestures(onDragStart = { o ->
            val x = o.x / size.width; val y = o.y / size.height
            val cs = listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)
            corner = cs.indexOfFirst { (cx, cy) -> kotlin.math.abs(cx - x) < .08f && kotlin.math.abs(cy - y) < .08f }.let { if (it < 0) 4 else it }
        }, onDragEnd = { onChange(rect) }) { ch, d ->
            val dx = d.x / size.width; val dy = d.y / size.height
            val n = RectF(rect)
            when (corner) {
                0 -> { n.left += dx; n.top += dy }; 1 -> { n.right += dx; n.top += dy }
                2 -> { n.left += dx; n.bottom += dy }; 3 -> { n.right += dx; n.bottom += dy }
                else -> { val w = n.width(); val h = n.height(); n.left = (n.left + dx).coerceIn(0f, 1f - w); n.top = (n.top + dy).coerceIn(0f, 1f - h); n.right = n.left + w; n.bottom = n.top + h }
            }
            if (aspect > 0f && corner in 0..3) { val w = n.width(); val h = w / aspect; if (corner < 2) n.top = n.bottom - h else n.bottom = n.top + h }
            n.left = n.left.coerceIn(0f, 1f); n.top = n.top.coerceIn(0f, 1f); n.right = n.right.coerceIn(0f, 1f); n.bottom = n.bottom.coerceIn(0f, 1f)
            if (n.width() > .08f && n.height() > .08f) rect = n
            ch.consume()
        }
    }) {
        val l = rect.left * size.width; val t = rect.top * size.height; val rr = rect.right * size.width; val b = rect.bottom * size.height
        val dim = Color(0x99000000)
        drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, t))
        drawRect(dim, Offset(0f, b), androidx.compose.ui.geometry.Size(size.width, size.height - b))
        drawRect(dim, Offset(0f, t), androidx.compose.ui.geometry.Size(l, b - t))
        drawRect(dim, Offset(rr, t), androidx.compose.ui.geometry.Size(size.width - rr, b - t))
        drawRect(Color.White, Offset(l, t), androidx.compose.ui.geometry.Size(rr - l, b - t), style = Stroke(2.dp.toPx()))
        for (i in 1..2) { drawLine(Color(0x66FFFFFF), Offset(l + (rr - l) * i / 3, t), Offset(l + (rr - l) * i / 3, b)); drawLine(Color(0x66FFFFFF), Offset(l, t + (b - t) * i / 3), Offset(rr, t + (b - t) * i / 3)) }
        listOf(Offset(l, t), Offset(rr, t), Offset(l, b), Offset(rr, b)).forEach { drawCircle(Color.White, 7.dp.toPx(), it) }
    }
}

// ============================================================ creation tools

@Composable
fun GCreateScreen(st: AppState, p: Page.GCreate, pad: PaddingValues) {
    var picked by remember { mutableStateOf(p.items) }
    var picking by remember { mutableStateOf(p.items.isEmpty()) }
    val title = when (p.kind) { "collage" -> "Collage"; "gif" -> "Animated GIF"; "movie" -> "Movie & Reel"; else -> "Long exposure" }
    if (picking) {
        MultiPick(st, title, when (p.kind) { "collage" -> "Choose 2–9 photos"; "movie" -> "Choose photos and videos, in order"; else -> "Choose one video, or several photos" }, videos = p.kind != "collage", onCancel = { if (picked.isEmpty()) st.back() else picking = false }) { picked = it; picking = false }
        return
    }
    when (p.kind) {
        "collage" -> CollageTool(st, picked.filter { !it.video }) { picking = true }
        "gif" -> FramesTool(st, picked, gif = true) { picking = true }
        "movie" -> ReelTool(st, picked) { picking = true }
        else -> FramesTool(st, picked, gif = false) { picking = true }
    }
}

@Composable
private fun MultiPick(st: AppState, title: String, hint: String, videos: Boolean, onCancel: () -> Unit, onDone: (List<GItem>) -> Unit) {
    LaunchedEffect(Unit) { if (st.gallery.items == null) st.loadGallery() }
    val all = st.gallery.items.orEmpty().filter { videos || !it.video }
    val chosen = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.close, onCancel)
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp); Muted("$hint · ${chosen.size} chosen", size = 12.sp) }
            Btn("Next", { onDone(all.filter { it.key in chosen }) }, small = true)
        }
        LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(4.dp)) {
            items(all, key = { it.key }) { x ->
                val on = x.key in chosen
                Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { if (on) chosen.remove(x.key) else chosen.add(x.key) }) {
                    Thumb(Img.Thumb(x.uri, x.video), "", Modifier.fillMaxSize(), px = 200, corner = 2.dp)
                    if (x.video) Text(mmss(x.dur), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color(0x99000000)))
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(if (on) C.ac else Color(0x66000000)).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) Text("${chosen.indexOf(x.key) + 1}", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageTool(st: AppState, items: List<GItem>, onRepick: () -> Unit) {
    val scope = rememberCoroutineScope()
    var photos by remember(items) { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(items) { photos = withContext(Dispatchers.IO) { items.take(9).mapNotNull { x -> PhotoOps.load(st.ctx, x, 1400)?.let { PhotoOps.rotate(it, Gallery.rotation(st, x.uri)) } } } }
    val ph = photos
    val layouts = remember(ph?.size) { PhotoOps.layouts((ph?.size ?: 1).coerceIn(1, 9)) }
    var li by remember(layouts) { mutableIntStateOf(0) }
    var aspect by remember { mutableFloatStateOf(1f) }
    var gap by remember { mutableFloatStateOf(8f) }
    var round by remember { mutableStateOf(true) }
    var bg by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(ph, li, aspect, gap, round, bg) {
        if (ph != null && ph.isNotEmpty()) preview = withContext(Dispatchers.Default) { PhotoOps.renderCollage(ph, layouts.getOrNull(li)?.second ?: layouts[0].second, 900, aspect, gap.toInt(), bg, if (round) 22f else 0f) }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { st.back() }); Text("Collage", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Btn("Save", {
                val src = ph ?: return@Btn
                scope.launch {
                    val u = withContext(Dispatchers.IO) { PhotoOps.saveJpeg(st.ctx, PhotoOps.renderCollage(src, layouts.getOrNull(li)?.second ?: layouts[0].second, 2400, aspect, (gap * 2.6f).toInt(), bg, if (round) 58f else 0f), "Collage") }
                    st.say(if (u != null) "Collage saved to Pictures/Media Hub" else "Couldn't save"); st.loadGallery(); st.back()
                }
            }, small = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            preview?.let { Image(it.asImageBitmap(), "Collage", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: androidx.compose.material3.CircularProgressIndicator(color = C.ac)
        }
        Column(Modifier.fillMaxWidth().background(C.s1).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if ((ph?.size ?: 0) < 2) Muted("Pick at least 2 photos.", size = 12.sp)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(layouts.size) { i -> Btn(layouts[i].first, { li = i }, small = true, kind = if (li == i) BtnKind.Primary else BtnKind.Ghost) } }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf(1f to "1:1", 4f / 5f to "4:5", 9f / 16f to "9:16 Story", 16f / 9f to "16:9")) { (a, l) -> Btn(l, { aspect = a }, small = true, kind = if (aspect == a) BtnKind.Primary else BtnKind.Ghost) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Muted("Spacing", Modifier.width(70.dp), 12.sp); Slider(gap, { gap = it }, valueRange = 0f..30f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Check(round, "Rounded") { round = it }
                Spacer(Modifier.width(12.dp))
                listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF1F2230.toInt(), 0xFF7C5CFF.toInt(), 0xFFFFD6E0.toInt(), 0xFFFFF1C1.toInt()).forEach { c ->
                    Box(Modifier.padding(3.dp).size(26.dp).clip(CircleShape).background(Color(c)).border(if (bg == c) 2.dp else 1.dp, if (bg == c) C.ac else C.line, CircleShape).clickable { bg = c })
                }
            }
            Btn("Change photos", onRepick, small = true, kind = BtnKind.Ghost)
        }
    }
}

/** GIF maker and long exposure: both work on a list of frames (photos, or frames from one video). */
@Composable
private fun FramesTool(st: AppState, items: List<GItem>, gif: Boolean, onRepick: () -> Unit) {
    val scope = rememberCoroutineScope()
    val video = items.firstOrNull { it.video }
    var count by remember { mutableFloatStateOf(if (gif) 16f else 40f) }
    var range by remember { mutableStateOf(0f..1f) }
    var delay by remember { mutableFloatStateOf(120f) }
    var size by remember { mutableIntStateOf(480) }
    var frames by remember { mutableStateOf<List<Bitmap>?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(0) }
    fun build() {
        busy = true; result = null
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                if (video != null) {
                    val s = (video.dur * range.start).toLong(); val e = (video.dur * range.endInclusive).toLong().coerceAtLeast(s + 200)
                    PhotoOps.frames(st.ctx, video.uri, s, e, count.toInt(), if (gif) size else 1600)
                } else items.filter { !it.video }.mapNotNull { x -> PhotoOps.load(st.ctx, x, if (gif) size else 1600)?.let { PhotoOps.rotate(it, Gallery.rotation(st, x.uri)) } }
            }
            frames = f
            if (!gif) result = withContext(Dispatchers.Default) { PhotoOps.longExposure(f) }
            busy = false
        }
    }
    LaunchedEffect(items) { build() }
    // animate the GIF preview
    LaunchedEffect(frames, delay) { while (gif && (frames?.size ?: 0) > 1) { kotlinx.coroutines.delay(delay.toLong()); shown = (shown + 1) % (frames?.size ?: 1) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { st.back() }); Text(if (gif) "Animated GIF" else "Long exposure", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Btn("Save", {
                scope.launch {
                    val u = withContext(Dispatchers.IO) {
                        if (gif) frames?.takeIf { it.isNotEmpty() }?.let { f -> val fs = f.map { PhotoOps.fit(it, size) }
                            PhotoOps.save(st.ctx, "GIF_${PhotoOps.stamp()}.gif", "image/gif") { o -> PhotoOps.writeGif(fs, delay.toInt(), o) } }
                        else result?.let { PhotoOps.saveJpeg(st.ctx, it, "LongExposure") }
                    }
                    st.say(if (u != null) "Saved to Pictures/Media Hub" else "Nothing to save yet"); st.loadGallery(); if (u != null) st.back()
                }
            }, small = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            val img = if (gif) frames?.getOrNull(shown) else result
            if (busy || img == null) { if (busy) androidx.compose.material3.CircularProgressIndicator(color = C.ac) else Muted(if (items.isEmpty()) "Pick a video or photos." else "Nothing to show.") }
            else Image(img.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Column(Modifier.fillMaxWidth().background(C.s1).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (video != null) {
                Muted("From \"${video.name}\" · ${mmss((video.dur * range.start).toLong())} – ${mmss((video.dur * range.endInclusive).toLong())}", size = 12.sp)
                androidx.compose.material3.RangeSlider(range, { range = it }, colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac))
                Row(verticalAlignment = Alignment.CenterVertically) { Muted("Frames ${count.toInt()}", Modifier.width(90.dp), 12.sp)
                    Slider(count, { count = it }, valueRange = 4f..(if (gif) 60f else 120f), modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac)) }
            } else Muted("${items.size} photos" + if (!gif) " — shots from a tripod/burst work best" else "", size = 12.sp)
            if (gif) {
                Row(verticalAlignment = Alignment.CenterVertically) { Muted("Speed ${delay.toInt()} ms", Modifier.width(90.dp), 12.sp)
                    Slider(delay, { delay = it }, valueRange = 30f..1000f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac)) }
                Row { listOf(320, 480, 640).forEach { s -> Btn("${s}px", { size = s }, small = true, kind = if (size == s) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp)) } }
            }
            Row { Btn("Update preview", { build() }, small = true); Spacer(Modifier.width(8.dp)); Btn("Change media", onRepick, small = true, kind = BtnKind.Ghost) }
        }
    }
}

// ============================================================ trash

@Composable
fun GTrashScreen(st: AppState, pad: PaddingValues) {
    LaunchedEffect(Unit) { st.loadGallery() }
    val items = st.gallery.trash
    val chosen = remember { mutableStateListOf<String>() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { st.back() })
            Column(Modifier.weight(1f)) { Text("Trash", fontWeight = FontWeight.Bold, fontSize = 20.sp); Muted("Items are deleted for good after 30 days", size = 12.sp) }
        }
        if (!Gallery.hasTrash) { Empty("The trash bin needs Android 11 or newer. On this phone, deleting asks first and removes items straight away."); return@Column }
        if (items.isEmpty()) { Empty("Trash is empty."); return@Column }
        val sel = if (chosen.isEmpty()) items else items.filter { it.key in chosen }
        Row(Modifier.padding(horizontal = 12.dp)) {
            Btn(if (chosen.isEmpty()) "Restore all" else "Restore ${chosen.size}", { st.restoreItems(sel); chosen.clear() }, small = true); Spacer(Modifier.width(8.dp))
            Btn(if (chosen.isEmpty()) "Empty trash" else "Delete ${chosen.size}", { st.deleteForever(sel); chosen.clear() }, small = true, kind = BtnKind.Danger)
        }
        LazyVerticalGrid(GridCells.Fixed(4), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 8.dp, bottom = pad.calculateBottomPadding() + 8.dp)) {
            items(items, key = { it.key }) { x ->
                val on = x.key in chosen
                val days = if (x.trashedUntil > 0) ((x.trashedUntil - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) else 30
                Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { if (on) chosen.remove(x.key) else chosen.add(x.key) }) {
                    Thumb(Img.Thumb(x.uri, x.video), "", Modifier.fillMaxSize(), px = 200, corner = 2.dp)
                    Text("${days}d", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomStart).padding(3.dp).background(Color(0x99000000)).padding(horizontal = 3.dp))
                    if (on) Box(Modifier.fillMaxSize().background(Color(0x667C5CFF)))
                }
            }
        }
    }
}
