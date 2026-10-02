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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val sliderColors @Composable get() = SliderDefaults.colors(thumbColor = C.ac, activeTrackColor = C.ac)

/** Movies & Reels: a storyboard of clips (trim, reorder, caption), format, transitions, title and music → MP4. */
@Composable
internal fun ReelTool(st: AppState, picked: List<GItem>, onRepick: () -> Unit) {
    var reel by remember(picked) {
        mutableStateOf(Reel(clips = picked.take(60).map { if (it.video) Clip(it, 0, minOf(it.dur, 15_000).coerceAtLeast(500)) else Clip(it) },
            format = if (picked.count { it.w > it.h } > picked.size / 2) "16:9" else "9:16"))
    }
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
            Box(Modifier.fillMaxHeight().aspectRatio(w.toFloat() / h, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                if (cur != null) Thumb(Img.Thumb(cur.item.uri, cur.item.video), "", Modifier.fillMaxSize(), px = 720, corner = 0.dp)
                if (sel == 0 && reel.title.isNotBlank()) Text(reel.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x88000000)).padding(horizontal = 8.dp))
                if (cur != null && cur.caption.isNotBlank()) Text(cur.caption, color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).background(Color(0x88000000)).padding(horizontal = 6.dp))
                if (cur?.item?.video == true) Icon(Ic.play, null, Modifier.align(Alignment.Center).size(44.dp).clickable { st.playGalleryVideo(cur.item) }, tint = Color.White)
            }
        }

        // storyboard
        LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(reel.clips) { i, c ->
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (i == sel) C.ac else Color.Transparent, RoundedCornerShape(8.dp)).clickable { sel = i }) {
                    Thumb(Img.Thumb(c.item.uri, c.item.video), "", Modifier.fillMaxSize(), px = 160, corner = 8.dp)
                    Text(mmss(c.lenMs), color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(horizontal = 3.dp))
                }
            }
            item {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(C.s2).clickable(onClick = onRepick), contentAlignment = Alignment.Center) { Icon(Ic.add, "Change media", tint = C.mu) }
            }
        }

        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).background(C.s1).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted("Show for ${"%.1f".format(cur.photoMs / 1000f)} s", Modifier.width(110.dp), 12.sp)
                        Slider(cur.photoMs / 1000f, { setClip(sel, cur.copy(photoMs = (it * 10).toLong() * 100)) }, valueRange = 1f..10f, modifier = Modifier.weight(1f), colors = sliderColors)
                    }
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
                if (m.d > 10_000) Row(verticalAlignment = Alignment.CenterVertically) {
                    Muted("Start at ${mmss(reel.musicStartMs)}", Modifier.width(110.dp), 12.sp)
                    Slider(reel.musicStartMs.toFloat(), { reel = reel.copy(musicStartMs = it.toLong()) }, valueRange = 0f..(m.d - 5_000).toFloat(), modifier = Modifier.weight(1f), colors = sliderColors,
                        // hear the new starting point straight away
                        onValueChangeFinished = { preview.play(m.u, reel.musicStartMs, reel.musicVol) })
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

    if (titleEdit) ReelTextDialog("Title", "Shown over the first clip", reel.title, { titleEdit = false }) { reel = reel.copy(title = it) }
    if (captionEdit) reel.clips.getOrNull(sel)?.let { c -> ReelTextDialog("Caption", "Shown along the bottom of this clip", c.caption, { captionEdit = false }) { setClip(sel, c.copy(caption = it)) } }
    if (pickMusic) MusicPickDialog(st, preview, { preview.stop(); pickMusic = false }) { preview.stop(); reel = reel.copy(music = it, musicStartMs = 0); pickMusic = false }

    prog?.let { p ->
        Dialog({}, DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(18.dp).widthIn(min = 280.dp)) {
                Text("Making your movie…", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Muted("${(p * 100).toInt()}% · keep OmniPlay open", Modifier.padding(vertical = 8.dp), 12.sp)
                LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = C.ac, trackColor = C.s3)
                Btn("Cancel", { job?.cancel(); prog = null }, Modifier.fillMaxWidth().padding(top = 12.dp), kind = BtnKind.Ghost)
            }
        }
    }
    done?.let { u ->
        Dialog({ done = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(18.dp).widthIn(min = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Saved to Movies/OmniPlay", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                val g = GItem(u.toString(), u.toString(), "Movie", "OmniPlay", "", System.currentTimeMillis(), 0, 0, true, reel.totalMs, 0, "video/mp4")
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
