package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.*
import kotlinx.coroutines.Job
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import sh.calvin.reorderable.ReorderableItem
import kotlinx.coroutines.launch

private val sliderColors @Composable get() = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac)

/** Movies & Reels: a storyboard of clips (trim, reorder, caption), format, transitions, title and music → MP4. */
@Composable
internal fun ReelTool(st: AppState, picked: List<GItem>, saved: MutableState<Reel?>, onRepick: () -> Unit) {
    fun newClip(it: GItem) = if (it.video) Clip(it, 0, minOf(it.dur, 15_000).coerceAtLeast(500)) else Clip(it)
    var reel by remember(picked) {
        val prev = saved.value
        mutableStateOf(if (prev == null) Reel(clips = picked.take(60).map(::newClip), format = if (picked.count { it.w > it.h } > picked.size / 2) "16:9" else "9:16")
            // back from adding media: keep the edits and the order, drop what was unticked, add the new picks at the end
            else prev.copy(clips = prev.clips.filter { c -> picked.any { it.key == c.item.key } } + picked.filter { p -> prev.clips.none { it.item.key == p.key } }.map(::newClip)))
    }
    LaunchedEffect(reel) { saved.value = reel }
    var sel by remember(picked) { mutableIntStateOf(0) }
    var pickMusic by remember { mutableStateOf(false) }
    val preview = rememberMusicPreview()
    var titleEdit by remember { mutableStateOf(false) }
    var captionEdit by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var done by remember { mutableStateOf<android.net.Uri?>(null) }
    val scope = rememberCoroutineScope()
    fun setClip(i: Int, c: Clip) { reel = reel.copy(clips = reel.clips.toMutableList().also { it[i] = c }) }
    fun move(i: Int, d: Int) { val j = i + d; if (j !in reel.clips.indices) return
        reel = reel.copy(clips = reel.clips.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }); sel = j }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Ic.back, { st.back() })
            Column(Modifier.weight(1f)) {
                Text("Movie & Reel", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Muted("${reel.clips.size} clips · ${mmss(reel.totalMs)}", size = 12.sp)
            }
            Btn("Export", {
                if (reel.clips.isEmpty()) { st.say("Add some photos or videos first"); return@Btn }
                preview.stop()
                prog = 0f
                job = scope.launch {
                    try { done = MovieMaker.export(st.ctx, st, reel) { prog = it }; st.loadGallery() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { st.say(e.message ?: "Export failed") }
                    finally { prog = null }
                }
            }, small = true)
        }

        // the selected clip, big
        val cur = reel.clips.getOrNull(sel)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            val (w, h) = reel.size()
            BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(w.toFloat() / h, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                if (cur != null) {
                    val look = reel.look(cur)
                    Thumb(Img.Thumb(cur.item.uri, cur.item.video), "", Modifier.fillMaxSize()
                        .graphicsLayer { if (reel.mirror) scaleX = -1f }.let { if (reel.soft && android.os.Build.VERSION.SDK_INT >= 31) it.blur(2.dp) else it }, px = 720, corner = 0.dp,
                        contentScale = if (reel.fill) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                        colorFilter = if (MovieFx.isIdentity(look)) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(MovieFx.compose(look))))
                    if (reel.vignette) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color(0xA0000000)))))
                }
                // title and caption: drag them anywhere on the picture
                val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
                if (sel == 0 && reel.title.isNotBlank()) Text(reel.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(reel.titleX, reel.titleY)).background(Color(0x88000000)).padding(horizontal = 8.dp)
                        .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); val r = reel
                            reel = r.copy(titleX = (r.titleX + d.x * 2 / boxW).coerceIn(-1f, 1f), titleY = (r.titleY + d.y * 2 / boxH).coerceIn(-1f, 1f)) } })
                if (cur != null && cur.caption.isNotBlank()) Text(cur.caption, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(androidx.compose.ui.BiasAlignment(cur.capX, cur.capY)).background(Color(0x88000000)).padding(horizontal = 6.dp)
                        .pointerInput(sel) { detectDragGestures { ch, d -> ch.consume(); val c = reel.clips.getOrNull(sel) ?: return@detectDragGestures
                            setClip(sel, c.copy(capX = (c.capX + d.x * 2 / boxW).coerceIn(-1f, 1f), capY = (c.capY + d.y * 2 / boxH).coerceIn(-1f, 1f))) } })
                if (cur?.item?.video == true) Icon(Ic.play, null, Modifier.align(Alignment.Center).size(44.dp).clickable { st.playGalleryVideo(cur.item) }, tint = Color.White)
            }
        }

        // storyboard
        // storyboard: tap a clip to edit it, hold and drag to move it
        val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
        val dragState = sh.calvin.reorderable.rememberReorderableLazyListState(rowState) { from, to ->
            val a = reel.clips.indexOfFirst { it.id == from.key }; val b = reel.clips.indexOfFirst { it.id == to.key }
            if (a >= 0 && b >= 0) { val selId = reel.clips.getOrNull(sel)?.id
                reel = reel.copy(clips = reel.clips.toMutableList().apply { add(b, removeAt(a)) }); sel = reel.clips.indexOfFirst { it.id == selId }.coerceAtLeast(0) }
        }
        Muted("Hold a clip and drag to change the order", Modifier.padding(start = 14.dp, top = 6.dp), 11.sp)
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), state = rowState, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(reel.clips, key = { _, c -> c.id }) { i, c ->
                ReorderableItem(dragState, key = c.id) { dragging ->
                    Box(Modifier.size(if (dragging) 72.dp else 64.dp).longPressDraggableHandle().clip(RoundedCornerShape(8.dp))
                        .border(2.dp, if (i == sel || dragging) C.ac else Color.Transparent, RoundedCornerShape(8.dp)).clickable { sel = i }) {
                        Thumb(Img.Thumb(c.item.uri, c.item.video), "", Modifier.fillMaxSize(), px = 160, corner = 8.dp)
                        Text("${i + 1}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000)).padding(horizontal = 3.dp))
                        Text(mmss(c.lenMs), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 3.dp))
                    }
                }
            }
            item {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.s2).clickable(onClick = onRepick), contentAlignment = Alignment.Center) { Icon(Ic.add, "Change media", tint = C.mu) }
            }
        }

        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).background(C.s1).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ---- selected clip
            if (cur != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Clip ${sel + 1}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconBtn(Ic.back, { move(sel, -1) }, size = 34.dp, iconSize = 20.dp)
                    IconBtn(Ic.forward, { move(sel, 1) }, size = 34.dp, iconSize = 20.dp)
                    IconBtn(Ic.trash, { reel = reel.copy(clips = reel.clips.filterIndexed { j, _ -> j != sel }); sel = sel.coerceAtMost(reel.clips.lastIndex).coerceAtLeast(0) }, size = 34.dp, iconSize = 20.dp)
                }
                if (cur.item.video) {
                    val d = maxOf(cur.item.dur, cur.endMs, 1).toFloat()
                    Muted("Trim ${mmss(cur.startMs)} – ${mmss(cur.endMs)} of ${mmss(cur.item.dur)}", size = 12.sp)
                    RangeSlider(cur.startMs / d..cur.endMs / d, { r ->
                        val s = (r.start * d).toLong(); val e = (r.endInclusive * d).toLong()
                        if (e - s >= 300) setClip(sel, cur.copy(startMs = s, endMs = e))
                    }, colors = sliderColors)
                    // split in two, or cut a piece out of the middle
                    var cut by remember(cur.id, cur.startMs, cur.endMs) { val l = cur.endMs - cur.startMs; mutableStateOf(cur.startMs + l / 3 to cur.startMs + l * 2 / 3) }
                    val span = (cur.endMs - cur.startMs).toFloat().coerceAtLeast(1f)
                    Muted("Split at ${mmss(cut.first)} · or cut out ${mmss(cut.first)} – ${mmss(cut.second)}", size = 12.sp)
                    RangeSlider((cut.first - cur.startMs) / span..(cut.second - cur.startMs) / span, { r ->
                        cut = (cur.startMs + (r.start * span).toLong()) to (cur.startMs + (r.endInclusive * span).toLong()) }, colors = sliderColors)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Btn("Split at ${mmss(cut.first)}", {
                            if (cut.first - cur.startMs < 300 || cur.endMs - cut.first < 300) { st.say("Too close to the end of the clip"); return@Btn }
                            reel = reel.copy(clips = reel.clips.toMutableList().apply { set(sel, cur.copy(endMs = cut.first)); add(sel + 1, cur.copy(startMs = cut.first, id = nextClipId())) })
                            st.say("Split into two clips")
                        }, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                        Btn("Cut out middle", {
                            val keepA = cut.first - cur.startMs >= 300; val keepB = cur.endMs - cut.second >= 300
                            reel = reel.copy(clips = reel.clips.toMutableList().apply {
                                removeAt(sel); var k = sel
                                if (keepA) add(k++, cur.copy(endMs = cut.first))
                                if (keepB) add(k, cur.copy(startMs = cut.second, id = nextClipId()))
                            })
                            sel = sel.coerceAtMost(reel.clips.lastIndex).coerceAtLeast(0); st.say("Cut out ${mmss(cut.second - cut.first)}")
                        }, small = true, kind = BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                        Btn("Duplicate", { reel = reel.copy(clips = reel.clips.toMutableList().apply { add(sel + 1, cur.copy(id = nextClipId())) }); sel += 1 },
                            small = true, kind = BtnKind.Ghost)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted("Show for ${"%.1f".format(cur.photoMs / 1000f)} s", Modifier.width(110.dp), 12.sp)
                        Slider(cur.photoMs / 1000f, { setClip(sel, cur.copy(photoMs = (it * 10).toLong() * 100)) }, valueRange = 1f..10f, modifier = Modifier.weight(1f), colors = sliderColors)
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Muted("Filter", Modifier.width(56.dp), 12.sp)
                    Btn("Same as movie", { setClip(sel, cur.copy(filter = null)) }, small = true, kind = if (cur.filter == null) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                    MovieFx.FILTERS.forEach { (k, l) -> Btn(l, { setClip(sel, cur.copy(filter = k)) }, small = true, kind = if (cur.filter == k) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp)) }
                }
                Btn(if (cur.caption.isBlank()) "Add caption" else "Caption: ${cur.caption}", { captionEdit = true }, small = true, kind = BtnKind.Ghost)
            }

            // ---- whole movie
            SectionHeader("Movie")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("9:16" to "Reel 9:16", "16:9" to "Movie 16:9", "1:1" to "Square", "4:5" to "Portrait 4:5").forEach { (k, l) ->
                    Btn(l, { reel = reel.copy(format = k) }, small = true, kind = if (reel.format == k) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                }
            }
            Row {
                Btn("Fill frame", { reel = reel.copy(fill = true) }, small = true, kind = if (reel.fill) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                Btn("Fit (bars)", { reel = reel.copy(fill = false) }, small = true, kind = if (!reel.fill) BtnKind.Primary else BtnKind.Ghost)
            }
            Row {
                Btn("Fade transitions", { reel = reel.copy(transition = if (reel.transition == "fade") "none" else "fade") }, small = true,
                    kind = if (reel.transition == "fade") BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                Btn("Zoom on photos", { reel = reel.copy(kenBurns = !reel.kenBurns) }, small = true, kind = if (reel.kenBurns) BtnKind.Primary else BtnKind.Ghost)
            }
            Btn(if (reel.title.isBlank()) "Add title" else "Title: ${reel.title}", { titleEdit = true }, small = true, kind = BtnKind.Ghost)

            SectionHeader("Filters & effects")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                MovieFx.FILTERS.forEach { (k, l) ->
                    // each filter shown on the current clip
                    val m = MovieFx.matrix(k, 0f, 0f, 0f)
                    Column(Modifier.padding(end = 8.dp).width(64.dp).clickable { reel = reel.copy(filter = k) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (reel.filter == k) C.ac else Color.Transparent, RoundedCornerShape(8.dp))) {
                            cur?.let { Thumb(Img.Thumb(it.item.uri, it.item.video), "", Modifier.fillMaxSize(), px = 160, corner = 8.dp,
                                colorFilter = if (MovieFx.isIdentity(m)) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(MovieFx.compose(m)))) }
                        }
                        Text(l, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (reel.filter == k) C.ac else C.tx)
                    }
                }
            }
            listOf(Triple("Brightness", reel.brightness) { v: Float -> reel = reel.copy(brightness = v) },
                Triple("Contrast", reel.contrast) { v: Float -> reel = reel.copy(contrast = v) },
                Triple("Saturation", reel.saturation) { v: Float -> reel = reel.copy(saturation = v) }).forEach { (l, v, set) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("$l ${if (v >= 0) "+" else ""}${(v * 100).toInt()}", Modifier.width(110.dp), 12.sp)
                    Slider(v, set, valueRange = -1f..1f, modifier = Modifier.weight(1f), colors = sliderColors)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Btn("Vignette", { reel = reel.copy(vignette = !reel.vignette) }, small = true, kind = if (reel.vignette) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                Btn("Soft focus", { reel = reel.copy(soft = !reel.soft) }, small = true, kind = if (reel.soft) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                Btn("Mirror", { reel = reel.copy(mirror = !reel.mirror) }, small = true, kind = if (reel.mirror) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp))
                Btn("Reset look", { reel = reel.copy(filter = "none", brightness = 0f, contrast = 0f, saturation = 0f, vignette = false, soft = false, mirror = false) }, small = true, kind = BtnKind.Ghost)
            }

            SectionHeader("Quality & size")
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Muted("Size", Modifier.width(56.dp), 12.sp)
                listOf(480 to "480p", 720 to "720p", 1080 to "1080p HD", 2160 to "4K").forEach { (k, l) ->
                    Btn(l, { reel = reel.copy(res = k) }, small = true, kind = if (reel.res == k) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Muted("Frames", Modifier.width(56.dp), 12.sp)
                listOf(24 to "24 fps (film)", 30 to "30 fps", 60 to "60 fps (smooth)").forEach { (k, l) ->
                    Btn(l, { reel = reel.copy(fps = k) }, small = true, kind = if (reel.fps == k) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Muted("Quality", Modifier.width(56.dp), 12.sp)
                listOf("low" to "Low (smallest)", "medium" to "Medium", "high" to "High (biggest)").forEach { (k, l) ->
                    Btn(l, { reel = reel.copy(quality = k) }, small = true, kind = if (reel.quality == k) BtnKind.Primary else BtnKind.Ghost, modifier = Modifier.padding(end = 6.dp)) }
            }
            val (ow, oh) = reel.size()
            Muted("${ow}×$oh · ${reel.fps} fps · ${"%.1f".format(reel.bitrate() / 1e6)} Mbit/s → about ${bytes(reel.estimateBytes())}" +
                (if (reel.fps == 60) " · 60 fps only where the clips themselves are 60 fps" else ""), size = 12.sp)

            SectionHeader("Sound")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Ic.music, null, tint = C.mu); Spacer(Modifier.width(8.dp))
                Text(reel.music?.let { "${it.title} — ${it.artist}" } ?: "No music", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                reel.music?.let { m ->
                    val on = preview.playing == m.u
                    IconBtn(if (on) Ic.stop else Ic.play, { if (on) preview.stop() else preview.play(m.u, reel.musicStartMs, reel.musicVol) }, size = 34.dp, iconSize = 22.dp, tint = C.ac)
                    IconBtn(Ic.close, { preview.stop(); reel = reel.copy(music = null) }, size = 34.dp, iconSize = 20.dp)
                }
                Btn(if (reel.music == null) "Add music" else "Change", { pickMusic = true }, small = true, kind = BtnKind.Ghost)
            }
            reel.music?.let { m ->
                if (m.d > 3_000) {
                    val end = if (reel.musicEndMs > 0) reel.musicEndMs else m.d
                    Muted("Song from ${mmss(reel.musicStartMs)} to ${mmss(end)} (${mmss(end - reel.musicStartMs)})" +
                        if (end - reel.musicStartMs < reel.totalMs) " · repeats to fill the movie" else "", size = 12.sp)
                    var moved by remember { mutableIntStateOf(0) }
                    RangeSlider(reel.musicStartMs.toFloat()..end.toFloat(), { r ->
                        val a = r.start.toLong(); val b = r.endInclusive.toLong()
                        if (b - a >= 2000) { moved = if (a != reel.musicStartMs) 0 else 1; reel = reel.copy(musicStartMs = a, musicEndMs = if (b >= m.d - 500) 0 else b) }
                    }, valueRange = 0f..m.d.toFloat(), colors = sliderColors,
                        // hear the new start, or the last few seconds before the new stop
                        onValueChangeFinished = { preview.play(m.u, if (moved == 0) reel.musicStartMs else maxOf(reel.musicStartMs, (if (reel.musicEndMs > 0) reel.musicEndMs else m.d) - 4000), reel.musicVol) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Music ${(reel.musicVol * 100).toInt()}%", Modifier.width(110.dp), 12.sp)
                    Slider(reel.musicVol, { reel = reel.copy(musicVol = it); preview.volume(it) }, modifier = Modifier.weight(1f), colors = sliderColors)
                }
            }
            if (reel.clips.any { it.item.video }) Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Clip sound ${(reel.clipVol * 100).toInt()}%", Modifier.width(110.dp), 12.sp)
                Slider(reel.clipVol, { reel = reel.copy(clipVol = it) }, modifier = Modifier.weight(1f), colors = sliderColors)
            }
        }
    }

    if (titleEdit) ReelTextDialog("Title", "Shown over the first clip — drag it on the picture to move it", reel.title, { titleEdit = false }) { reel = reel.copy(title = it) }
    if (captionEdit) reel.clips.getOrNull(sel)?.let { c -> ReelTextDialog("Caption", "Shown on this clip — drag it on the picture to move it", c.caption, { captionEdit = false }) { setClip(sel, c.copy(caption = it)) } }
    if (pickMusic) MusicPickDialog(st, preview, { preview.stop(); pickMusic = false }) { preview.stop(); reel = reel.copy(music = it, musicStartMs = 0, musicEndMs = 0); pickMusic = false }

    prog?.let { p ->
        Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(18.dp).widthIn(min = 280.dp)) {
                Text("Making your movie…", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Muted("${(p * 100).toInt()}% · keep Media Hub open", Modifier.padding(vertical = 8.dp), 12.sp)
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = C.ac, trackColor = C.s3)
                Btn("Cancel", { job?.cancel(); prog = null }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = BtnKind.Ghost)
            }
        }
    }
    done?.let { u ->
        Dialog({ done = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(18.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to Movies/Media Hub", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                val g = GItem(u.toString(), u.toString(), "Movie", "Media Hub", "", System.currentTimeMillis(), 0, 0, true, reel.totalMs, 0, "video/mp4")
                Btn("Play", { st.playGalleryVideo(g) }, Modifier.fillMaxWidth(), icon = Ic.play)
                Btn("Share", { st.shareItems(listOf(g)) }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost, icon = Ic.send)
                Btn("Done", { done = null; st.back() }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            }
        }
    }
}

@Composable
private fun ReelTextDialog(title: String, hint: String, initial: String, onDone: () -> Unit, ok: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 280.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp); Muted(hint, size = 12.sp)
            Field(v, { v = it.take(80) }, title, Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Row {
                Btn("Remove", { ok(""); onDone() }, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp))
                Btn("OK", { ok(v.trim()); onDone() }, Modifier.weight(1f))
            }
        }
    }
}

/** Songs on this phone (cloud songs need streaming, so they're left out — download them first). */
@Composable
private fun MusicPickDialog(st: AppState, preview: MusicPreview, onDone: () -> Unit, onPick: (Item) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { st.songs().filter { !it.isCloud }.sortedBy { it.title.lowercase() } }
    val list = remember(q) { if (q.isBlank()) all else all.filter { (it.title + " " + it.artist).contains(q, true) } }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.8f).clip(RoundedCornerShape(16.dp)).background(C.s1).padding(14.dp)) {
            Text("Background music", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Field(q, { q = it }, "Search your songs", Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (all.isEmpty()) Muted("No songs on this phone yet — add a music folder in Music, or download songs from your cloud.", Modifier.padding(8.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list.take(500), key = { it.i }) { s ->
                    val on = preview.playing == s.u
                    Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        // listen before choosing (starts a little way in, where songs usually get going)
                        IconBtn(if (on) Ic.stop else Ic.play, { if (on) preview.stop() else preview.play(s.u, if (s.d > 90_000) 30_000 else 0, 1f) }, size = 40.dp, iconSize = 24.dp, tint = if (on) C.ac else C.tx)
                        Column(Modifier.weight(1f).padding(start = 6.dp)) {
                            Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (on) C.ac else C.tx)
                            Muted("${s.artist}${if (s.d > 0) " · " + mmss(s.d) else ""}", size = 12.sp)
                        }
                        Btn("Use", { onPick(s) }, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
            Btn("Cancel", onDone, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
        }
    }
}

/** A small player for trying out background music while making a reel (one song at a time). */
internal class MusicPreview(private val ctx: android.content.Context) {
    var playing by mutableStateOf<String?>(null); private set
    private var p: androidx.media3.exoplayer.ExoPlayer? = null
    private fun player() = p ?: androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true)
        .build().also { pl ->
            pl.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) { if (state == androidx.media3.common.Player.STATE_ENDED) playing = null }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playing = null }
            })
            p = pl
        }
    fun play(uri: String, fromMs: Long, vol: Float) {
        val pl = player()
        if (playing != uri) { pl.setMediaItem(androidx.media3.common.MediaItem.fromUri(uri)); pl.prepare() }
        pl.seekTo(fromMs); pl.volume = vol; pl.play(); playing = uri
    }
    fun volume(v: Float) { p?.volume = v }
    fun stop() { p?.stop(); playing = null }
    fun release() { p?.release(); p = null; playing = null }
}

@Composable
internal fun rememberMusicPreview(): MusicPreview {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val mp = remember { MusicPreview(ctx.applicationContext) }
    DisposableEffect(mp) { onDispose { mp.release() } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) mp.stop() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return mp
}
