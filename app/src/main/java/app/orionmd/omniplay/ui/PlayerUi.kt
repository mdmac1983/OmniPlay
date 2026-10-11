package app.orionmd.omniplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Img
import app.orionmd.omniplay.Player
import app.orionmd.omniplay.fmt
import app.orionmd.omniplay.mmss

/** Swipe left for the next song, right for the previous one; the content follows the finger a little. */
@Composable
private fun Modifier.songSwipe(follow: Float = .5f): Modifier {
    var dx by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    return pointerInput(Unit) {
        val limit = 80.dp.toPx()
        detectHorizontalDragGestures(
            onDragEnd = { if (dx < -limit) Player.next() else if (dx > limit) Player.back(); dx = 0f },
            onDragCancel = { dx = 0f },
        ) { change, d -> change.consume(); dx += d }
    }.graphicsLayer { translationX = dx * follow; alpha = 1f - (kotlin.math.abs(dx) / 1000f).coerceAtMost(.4f) }
}

private fun label(t: org.json.JSONObject) = t.optString("t").ifEmpty { t.optString("n") }

@Composable
fun MiniPlayer(st: AppState) {
    val p = st.player; val t = p.track ?: return
    Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(C.s2).border(1.dp, C.line, RoundedCornerShape(14.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).songSwipe(.25f).clickable { st.sheetOpen = true }, verticalAlignment = Alignment.CenterVertically) {
                Thumb(st.playerArt(t), label(t), Modifier.size(44.dp), fallback = st.playerAlt(t))
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text(label(t), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (p.error.isNotEmpty()) p.error else if (p.preparing) "Loading…" else t.optString("a"), color = C.mu, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (p.playing && st.pref("visMini", "1") == "1") MiniBars(Modifier.padding(horizontal = 6.dp).size(22.dp, 18.dp))
            }
            IconBtn(Ic.prev, { Player.prev() })
            IconBtn(if (p.playing) Ic.pause else Ic.play, { Player.toggle() })
            IconBtn(Ic.next, { Player.next() })
            IconBtn(Ic.close, { st.stopPlayer() }, tint = C.mu, size = 34.dp, iconSize = 20.dp)
        }
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth().height(2.dp).background(C.s3)) {
            Box(Modifier.fillMaxWidth(if (p.dur > 0) (p.pos.toFloat() / p.dur).coerceIn(0f, 1f) else 0f).height(2.dp).background(C.ac))
        }
    }
}

@Composable
fun NowPlaying(st: AppState) {
    val p = st.player; val t = p.track
    if (t == null) { androidx.compose.runtime.LaunchedEffect(Unit) { st.sheetOpen = false }; return }
    var seek by remember { mutableStateOf<Float?>(null) }
    var optionsOpen by remember { mutableStateOf(false) }
    val queue = remember(p.index, p.size, t.optLong("i")) { Player.queue.let { q -> (maxOf(0, p.index) until minOf(q.length(), p.index + 30)).map { it to q.getJSONObject(it) } } }
    st.prefGen
    val sk = skinOf(st.pref("playerSkin", "midnight"))
    // swipe down (from the top of the list) to minimise to the mini player
    var drag by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val dens = androidx.compose.ui.platform.LocalDensity.current
    val conn = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (drag > 0f && available.y < 0f) { val c = maxOf(available.y, -drag); drag += c; return androidx.compose.ui.geometry.Offset(0f, c) }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) { drag += available.y; return androidx.compose.ui.geometry.Offset(0f, available.y) }
                return androidx.compose.ui.geometry.Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (drag <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                if (drag > with(dens) { 120.dp.toPx() } || available.y > 1800f) {
                    androidx.compose.animation.core.animate(drag, with(dens) { 900.dp.toPx() }, animationSpec = androidx.compose.animation.core.tween(160)) { v, _ -> drag = v }
                    st.sheetOpen = false
                } else androidx.compose.animation.core.animate(drag, 0f) { v, _ -> drag = v }
                return available
            }
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { translationY = drag; alpha = 1f - (drag / 2500f).coerceIn(0f, .5f) }.nestedScroll(conn).clickable(enabled = false) {}) {
        SkinBackground(st, t, sk)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides sk.text) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Box(Modifier.padding(top = 6.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(sk.muted.copy(alpha = .5f))) }   // "swipe down" handle
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.down, { st.sheetOpen = false }, tint = sk.text)
                    IconBtn(Ic.skin, { st.dialog = app.orionmd.omniplay.Dlg.SkinPick }, tint = sk.text)
                    Spacer(Modifier.weight(1f))
                    if (p.size > 0) Text("${p.index + 1} of ${fmt(p.size)}", color = sk.muted, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
                    Btn(if (st.showVis) "Artwork" else "Visualizer", { st.toggleVis() }, kind = BtnKind.Pill, icon = Ic.eq, small = true, modifier = Modifier.padding(end = 6.dp))
                    Btn(if (st.showLyrics) "Artwork" else "Lyrics", { st.toggleLyrics() }, kind = BtnKind.Pill, icon = Ic.lyrics, small = true, modifier = Modifier.padding(end = 6.dp))
                }
            }
            item {
                Box(Modifier.songSwipe()) {
                    if (st.showLyrics) LyricsBox(st, t, p.pos, p.dur, Modifier.padding(top = 10.dp).widthIn(max = 420.dp).fillMaxWidth().aspectRatio(.9f))
                    else if (st.showVis) NowPlayingVisual(st, t, Modifier.padding(top = 10.dp).widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f))
                    else SkinArt(st, t, sk, p.playing, Modifier.padding(top = 10.dp).widthIn(max = 420.dp).fillMaxWidth()) { st.toggleLyrics() }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(40.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        SkinLogo(sk.id, Modifier.padding(bottom = 6.dp).height(40.dp).widthIn(max = 160.dp))
                        Text(label(t), fontSize = 22.sp, fontWeight = if (sk.heavy) FontWeight.Black else FontWeight.Bold, textAlign = TextAlign.Center, fontFamily = sk.font,
                            fontStyle = if (sk.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                            color = if (sk.boxTitle) Color.White else sk.text,
                            modifier = if (sk.boxTitle) Modifier.background(sk.accent).padding(horizontal = 12.dp, vertical = 2.dp) else Modifier)
                        Text(listOf(t.optString("a"), t.optString("al")).filter { it.isNotEmpty() }.joinToString(" — "), color = sk.muted, fontSize = 15.sp, textAlign = TextAlign.Center, fontFamily = sk.font, modifier = Modifier.padding(top = 4.dp))
                    }
                    // add this song to a playlist
                    IconBtn(Ic.playlistAdd, { st.dialog = app.orionmd.omniplay.Dlg.AddToPlaylist(listOf(t.optLong("i"))) }, tint = sk.text)
                }
            }
            item {
                val frac = seek ?: if (p.dur > 0) p.pos.toFloat() / p.dur else 0f
                Slider(frac.coerceIn(0f, 1f), { seek = it }, onValueChangeFinished = { seek?.let { Player.seek((it * p.dur).toInt()) }; seek = null },
                    modifier = Modifier.padding(top = 10.dp), colors = SliderDefaults.colors(thumbColor = sk.text, activeTrackColor = sk.accent, inactiveTrackColor = sk.muted.copy(alpha = .3f)))
                Row(Modifier.fillMaxWidth()) { Text(mmss(((seek ?: -1f).takeIf { it >= 0 }?.let { (it * p.dur).toLong() }) ?: p.pos), color = sk.muted, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(mmss(p.dur), color = sk.muted, fontSize = 12.sp) }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.shuffle, { st.shuffleQueue() }, tint = sk.text)
                    IconBtn(Ic.prev, { Player.prev() }, iconSize = 32.dp, size = 52.dp, tint = sk.text)
                    Box(Modifier.size(68.dp).clip(CircleShape).let { if (sk.glow) it.border(3.dp, sk.accent.copy(alpha = .5f), CircleShape) else it }
                        .background(if (sk.outlinePlay) Color.Transparent else sk.playBg).border(if (sk.outlinePlay) 2.dp else 0.dp, if (sk.outlinePlay) sk.playBg else Color.Transparent, CircleShape)
                        .clickable { Player.toggle() }, contentAlignment = Alignment.Center) {
                        Icon(if (p.playing) Ic.pause else Ic.play, null, tint = if (sk.outlinePlay) sk.playBg else sk.playFg, modifier = Modifier.size(36.dp))
                    }
                    IconBtn(Ic.next, { Player.next() }, iconSize = 32.dp, size = 52.dp, tint = sk.text)
                    IconBtn(if (p.repeat == "one") Ic.repeat1 else Ic.repeat, { st.cycleRepeat() }, tint = if (p.repeat != "off") sk.accent else sk.text)
                }
                // sleep timer · sound (equaliser, volume boost) · visualizer full screen
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    val sleeping = Player.sleepAt > 0 || Player.sleepEndOfSong
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBtn(Ic.sleep, { st.dialog = app.orionmd.omniplay.Dlg.Sleep }, tint = if (sleeping) sk.accent else sk.text)
                        if (Player.sleepAt > 0) Muted("${((Player.sleepAt - System.currentTimeMillis()) / 60000).coerceAtLeast(0) + 1}m", size = 12.sp) else if (Player.sleepEndOfSong) Muted("end", size = 12.sp)
                    }
                    IconBtn(Ic.tune, { st.soundOpen = true }, tint = if (app.orionmd.omniplay.Dsp.cfg.on || (st.pref("boost", "0").toIntOrNull() ?: 0) > 0) sk.accent else sk.text)
                    IconBtn(Ic.playlistAdd, { st.dialog = app.orionmd.omniplay.Dlg.AddToPlaylist(Player.queue.let { q -> (0 until q.length()).map { q.getJSONObject(it).optLong("i") } }, queue = true) }, tint = sk.text)
                    IconBtn(Ic.expand, { st.visFull = true }, tint = sk.text)
                    IconBtn(Ic.more, { optionsOpen = true }, tint = sk.text)
                }
                val msg = p.error.ifEmpty { if (p.preparing) "Loading…" else "" }
                if (msg.isNotEmpty()) Text(msg, color = C.warn, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                Btn("Stop & close player", { st.stopPlayer() }, kind = BtnKind.Ghost, small = true, icon = Ic.stop)
            }
            item { Box(Modifier.fillMaxWidth()) { SectionHeader("Up next") } }
            // light skins: the list sits on a dark card so it stays readable
            items(queue, key = { it.first }) { (j, x) ->
                Box(Modifier.let { if (sk.light) it.background(C.s1.copy(alpha = .92f)).padding(horizontal = 8.dp) else it }) {
                    ListRow(st.playerArt(x), label(x), label(x), x.optString("a"), { Player.jump(j) }, highlight = j == p.index)
                }
            }
            if (Player.queue.length() > p.index + 30) item { Muted("+${fmt(Player.queue.length() - p.index - 30)} more", Modifier.padding(16.dp)) }
        }
        }
    }
    if (optionsOpen) MusicOptions(st) { optionsOpen = false }
}

/** Lyrics for the playing song: time-synced ones follow the music (tap a line to jump there), plain ones scroll. */
@Composable
private fun LyricsBox(st: AppState, t: org.json.JSONObject, pos: Long, dur: Long, modifier: Modifier) {
    val id = t.optLong("i")
    androidx.compose.runtime.LaunchedEffect(id) { st.lyricsFor(t, dur) }
    val l = st.lyrics[id]
    val visBehind = st.pref("lyricsVis", "1") == "1"
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(if (visBehind) 0xFF0B0A14 else 0x66000000)).clickable { st.toggleLyrics() }, contentAlignment = Alignment.Center) {
        // the live visualizer playing behind the lyrics (switch it on the chip below, or in Settings → Playback)
        if (visBehind) {
            Visualizer(st.visStyle, Modifier.fillMaxSize().padding(10.dp).alpha(.55f))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x33000000), Color(0x99000000)))))
        }
        Text(if (visBehind) "Visualizer: on" else "Visualizer: off", color = Color.White.copy(alpha = .75f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x55000000))
                .clickable { st.setPref("lyricsVis", if (visBehind) "0" else "1") }.padding(horizontal = 8.dp, vertical = 3.dp))
        when {
            l == null -> Muted(if (app.orionmd.omniplay.Net.online(st.ctx)) "Looking for lyrics…" else "Lyrics need internet the first time", Modifier.padding(24.dp), 15.sp)
            l.instrumental -> Muted("♪  Instrumental  ♪", Modifier.padding(24.dp), 16.sp)
            l.isEmpty -> Muted("No lyrics found for this song.", Modifier.padding(24.dp), 15.sp)
            l.synced.isNotEmpty() -> {
                val cur = l.synced.indexOfLast { it.first <= pos + 300 }
                val ls = androidx.compose.foundation.lazy.rememberLazyListState()
                androidx.compose.runtime.LaunchedEffect(cur) { if (cur >= 0) ls.animateScrollToItem(maxOf(0, cur - 3)) }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp), state = ls, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)) {
                    items(l.synced.size) { k ->
                        val (ms, line) = l.synced[k]
                        Text(line.ifEmpty { "♪" }, fontSize = if (k == cur) 21.sp else 18.sp, fontWeight = if (k == cur) FontWeight.Bold else FontWeight.SemiBold,
                            color = when { k == cur -> C.tx; k < cur -> C.mu.copy(alpha = .6f); else -> C.mu },
                            modifier = Modifier.fillMaxWidth().clickable { Player.seek(ms.toInt()) }.padding(vertical = 6.dp))
                    }
                }
            }
            else -> Text(l.plain, fontSize = 17.sp, lineHeight = 26.sp, color = C.tx,
                modifier = Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(20.dp))
        }
    }
}
