package app.orionmd.omniplay.ui

import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Player
import app.orionmd.omniplay.Visual
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** All visualizer styles (id to name), in the order a tap cycles through them. Defined in VisualizerMore.kt. */
val VIS_STYLES: List<Pair<String, String>> get() = VIS_REG.map { it.id to it.name }
/** Style groups for the picker: group name to its (id, name) styles. */
val VIS_GROUPS: List<Pair<String, List<Pair<String, String>>>> get() = VIS_REG.groupBy { it.group }.map { (g, l) -> g to l.map { it.id to it.name } }
/** Styles that show the album art in the middle. */
fun visHasArt(style: String) = style.startsWith("circle")
internal val VIS = listOf(Color(0xFF7C5CFF), Color(0xFFB45CFF), Color(0xFFE0457B), Color(0xFFFF8A5C), Color(0xFFFFC857))

/** Live band / wave values for one visualizer, smoothed so bars rise fast and fall gently. */
internal class VisState {
    val bands = FloatArray(Visual.BANDS); val wave = FloatArray(Visual.WAVE)
    val disp = FloatArray(Visual.BANDS); val peak = FloatArray(Visual.BANDS); val wdisp = FloatArray(Visual.WAVE)
    var level = 0f
    // starfield: x, y in -1..1, z depth 0..1
    val sx = FloatArray(140) { kotlin.random.Random.nextFloat() * 2 - 1 }; val sy = FloatArray(140) { kotlin.random.Random.nextFloat() * 2 - 1 }
    val sz = FloatArray(140) { kotlin.random.Random.nextFloat() }
    var spin = 0f
    // recent spectra for the spectrogram / ridgeline styles (one row every 50 ms)
    val hist = Array(48) { FloatArray(Visual.BANDS) }; var hi = 0; private var hacc = 0f
    fun step(dt: Float) {
        val lv = Visual.frameAt(Player.positionMs(), bands, wave)
        val have = lv >= 0f && Player.isPlaying()
        val fall = 1.6f * dt; val pfall = .45f * dt
        for (k in 0 until Visual.BANDS) {
            val t = if (have) bands[k] else 0f
            disp[k] = if (t > disp[k]) disp[k] + (t - disp[k]) * .65f else maxOf(t, disp[k] - fall)
            peak[k] = if (disp[k] > peak[k]) disp[k] else maxOf(0f, peak[k] - pfall)
        }
        for (k in 0 until Visual.WAVE) wdisp[k] = if (have) wdisp[k] * .35f + wave[k] * .65f else wdisp[k] * .85f
        level = if (have) level * .6f + lv * .4f else level * .9f
        val bass = (disp[0] + disp[1] + disp[2] + disp[3]) / 4f
        for (k in sz.indices) { sz[k] -= dt * (.12f + bass * 1.6f); if (sz[k] <= .02f) { sz[k] = 1f; sx[k] = kotlin.random.Random.nextFloat() * 2 - 1; sy[k] = kotlin.random.Random.nextFloat() * 2 - 1 } }
        spin += dt * (.2f + level * 2f)
        hacc += dt
        if (hacc >= .05f) { hacc = 0f; hi = (hi + 1) % hist.size; disp.copyInto(hist[hi]) }
    }
}

/** A live visualizer. [center] is drawn underneath (e.g. the album art inside the Circle style). */
@Composable
fun Visualizer(style: String, modifier: Modifier, center: (@Composable () -> Unit)? = null) {
    val s = remember { VisState() }
    var tick by remember { mutableLongStateOf(0L) }
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t ->
            val dt = if (last == 0L) .016f else ((t - last) / 1e9f).coerceIn(.001f, .1f); last = t
            s.step(dt); pulse = s.level; tick = t
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (center != null) Box(Modifier.fillMaxSize(if (visHasArt(style)) .56f + (pulse * .12f).coerceAtMost(.08f) else 1f).clip(if (visHasArt(style)) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(0.dp)), contentAlignment = Alignment.Center) { center() }
        Canvas(Modifier.fillMaxSize()) {
            tick   // redraw every frame
            val def = VIS_REG.firstOrNull { it.id == style } ?: VIS_REG[0]
            PAL = def.pal ?: VIS
            clipRect { def.draw(this, s) }
        }
    }
}

/** The palette the current style draws with (set per style before drawing). */
internal var PAL: List<Color> = VIS

internal fun DrawScope.grad() = Brush.horizontalGradient(PAL, 0f, size.width)

internal fun DrawScope.bars(s: VisState, alpha: Float = 1f) {
    val n = Visual.BANDS; val gap = size.width / n * .22f; val w = size.width / n - gap
    val g = grad(); val h = size.height * .92f
    for (k in 0 until n) {
        val x = k * (w + gap) + gap / 2
        val bh = maxOf(3f, s.disp[k] * h)
        drawRoundRect(g, Offset(x, size.height - bh), Size(w, bh), CornerRadius(w / 2.5f), alpha = alpha)
        val py = size.height - s.peak[k] * h - 6f
        drawRoundRect(Color.White.copy(alpha = .85f * alpha), Offset(x, py), Size(w, 4f), CornerRadius(2f))
    }
}

internal fun DrawScope.mirror(s: VisState) {
    val n = Visual.BANDS; val half = size.width / 2; val w = half / n * .72f; val step = half / n
    val g = Brush.verticalGradient(listOf(PAL[4], PAL[2], PAL[0], PAL[2], PAL[4]))
    val mid = size.height / 2
    for (k in 0 until n) {
        val bh = maxOf(2f, s.disp[k] * size.height * .46f)
        for (side in listOf(-1, 1)) {
            val x = half + side * (k * step + step / 2) - w / 2
            drawRoundRect(g, Offset(x, mid - bh), Size(w, bh * 2), CornerRadius(w / 2))
        }
    }
}

internal fun DrawScope.wave(s: VisState) {
    val n = Visual.WAVE; val mid = size.height / 2; val amp = size.height * .42f
    val p = Path()
    for (k in 0 until n) {
        val x = size.width * k / (n - 1); val y = mid - s.wdisp[k].coerceIn(-1f, 1f) * amp * 1.6f
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    val g = grad()
    drawPath(p, g, alpha = .25f, style = Stroke(width = 18f, cap = StrokeCap.Round))
    drawPath(p, g, alpha = .5f, style = Stroke(width = 8f, cap = StrokeCap.Round))
    drawPath(p, Color.White.copy(alpha = .9f), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
    // a soft spectrum floor under the line
    val n2 = Visual.BANDS; val w = size.width / n2
    for (k in 0 until n2) drawRect(PAL[k * PAL.size / n2].copy(alpha = .22f), Offset(k * w, size.height - s.disp[k] * size.height * .35f), Size(w * .8f, s.disp[k] * size.height * .35f))
}

internal fun DrawScope.circle(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val r0 = min(size.width, size.height) * .3f
    val n = Visual.BANDS * 2; val maxLen = min(size.width, size.height) * .19f
    for (k in 0 until n) {
        val b = if (k < Visual.BANDS) k else n - 1 - k   // mirrored all the way round
        val a = (k.toFloat() / n) * 2 * PI.toFloat() - PI.toFloat() / 2
        val len = 4f + s.disp[b] * maxLen
        val dx = cos(a); val dy = sin(a)
        drawLine(PAL[(b * PAL.size / Visual.BANDS).coerceAtMost(PAL.size - 1)], Offset(c.x + dx * r0, c.y + dy * r0), Offset(c.x + dx * (r0 + len), c.y + dy * (r0 + len)),
            strokeWidth = (2 * PI.toFloat() * r0 / n) * .62f, cap = StrokeCap.Round)
    }
    drawCircle(PAL[1].copy(alpha = .25f + s.level * .6f), r0, c, style = Stroke(width = 3f + s.level * 10f))
}

internal fun DrawScope.mountain(s: VisState) {
    // a smooth filled spectrum with its reflection
    val n = Visual.BANDS; val base = size.height * .72f; val h = size.height * .66f
    val p = Path(); val r = Path()
    p.moveTo(0f, base); r.moveTo(0f, base)
    for (k in 0 until n) {
        val x = size.width * k / (n - 1)
        val v = s.disp[k]
        p.lineTo(x, base - v * h); r.lineTo(x, base + v * h * .35f)
    }
    p.lineTo(size.width, base); p.close(); r.lineTo(size.width, base); r.close()
    drawPath(p, Brush.verticalGradient(listOf(PAL[4], PAL[2], PAL[0].copy(alpha = .6f)), base - h, base))
    drawPath(r, Brush.verticalGradient(listOf(PAL[0].copy(alpha = .35f), Color.Transparent), base, base + h * .35f))
    drawLine(Color.White.copy(alpha = .5f), Offset(0f, base), Offset(size.width, base), strokeWidth = 1.5f)
}

internal fun DrawScope.rings(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = min(size.width, size.height) * .48f
    val groups = 8; val per = Visual.BANDS / groups
    for (g in 0 until groups) {
        val v = (0 until per).sumOf { s.disp[g * per + it].toDouble() }.toFloat() / per
        val r = maxR * (g + 1) / groups * (.75f + v * .3f)
        drawCircle(PAL[g % PAL.size].copy(alpha = .25f + v * .7f), r, c, style = Stroke(width = 3f + v * 14f))
    }
}

internal fun DrawScope.stars(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val w = size.width / 2; val h = size.height / 2
    for (k in s.sz.indices) {
        val z = s.sz[k]; val x = c.x + s.sx[k] / z * w * .5f; val y = c.y + s.sy[k] / z * h * .5f
        if (x < 0 || x > size.width || y < 0 || y > size.height) continue
        val br = (1f - z).coerceIn(0f, 1f)
        drawCircle(PAL[k % PAL.size].copy(alpha = .3f + br * .7f), 1.5f + br * (3f + s.level * 6f), Offset(x, y))
    }
    drawCircle(Brush.radialGradient(listOf(PAL[1].copy(alpha = .35f + s.level * .5f), Color.Transparent), c, min(w, h) * .6f), min(w, h) * .6f, c)
}

internal fun DrawScope.led(s: VisState) {
    // LED-style columns of dots
    val cols = 24; val rows = 14; val per = Visual.BANDS / cols
    val cw = size.width / cols; val rh = size.height / rows; val dot = min(cw, rh) * .34f
    for (col in 0 until cols) {
        val v = (0 until per).maxOf { s.disp[col * per + it] }
        val lit = (v * rows).toInt()
        for (row in 0 until rows) {
            val on = rows - 1 - row < lit
            val colr = when { row < rows / 5 -> Color(0xFFFF4D4D); row < rows / 2 -> Color(0xFFFFC857); else -> Color(0xFF4DFF88) }
            drawCircle(if (on) colr else colr.copy(alpha = .08f), dot, Offset(col * cw + cw / 2, row * rh + rh / 2))
        }
    }
}

internal fun DrawScope.spiral(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = min(size.width, size.height) * .46f
    val n = Visual.BANDS * 2
    for (k in 0 until n) {
        val b = k % Visual.BANDS
        val t = k.toFloat() / n
        val a = s.spin + t * 6f * PI.toFloat()
        val r = maxR * (.12f + t * .88f)
        val p = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        drawCircle(PAL[(b * PAL.size / Visual.BANDS).coerceAtMost(PAL.size - 1)].copy(alpha = .35f + s.disp[b] * .65f), 2f + s.disp[b] * 14f, p)
    }
}

internal fun DrawScope.glow(s: VisState) {
    // three soft blobs for bass / mids / highs that swell with the music
    val groups = listOf(0 until 10, 10 until 26, 26 until Visual.BANDS)
    val vals = groups.map { g -> g.sumOf { s.disp[it].toDouble() }.toFloat() / g.count() }
    val base = min(size.width, size.height)
    vals.forEachIndexed { i, v ->
        val cx = size.width * (.28f + .22f * i); val cy = size.height * (if (i == 1) .42f else .58f)
        val r = base * (.12f + v * .32f)
        drawCircle(Brush.radialGradient(listOf(PAL[i * 2].copy(alpha = .85f), PAL[i * 2].copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    bars(s, .35f)   // a faint spectrum on top
}

/** Small animated bars for the mini player while music plays. */
@Composable
fun MiniBars(modifier: Modifier) {
    val s = remember { VisState() }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { t -> val dt = if (last == 0L) .016f else ((t - last) / 1e9f).coerceIn(.001f, .1f); last = t; s.step(dt); tick = t }
    }
    Canvas(modifier) {
        tick
        val groups = listOf(1 until 6, 6 until 14, 14 until 24, 24 until 34, 34 until Visual.BANDS)
        val w = size.width / groups.size
        groups.forEachIndexed { i, g ->
            val v = g.maxOf { s.disp[it] }
            val h = maxOf(size.height * .12f, v * size.height)
            drawRoundRect(VIS[i], Offset(i * w + w * .15f, size.height - h), Size(w * .7f, h), CornerRadius(w * .3f))
        }
    }
}

/** The visualizer box on the Now Playing screen: tap to change style, ⛶ for full screen. */
@Composable
fun NowPlayingVisual(st: AppState, t: org.json.JSONObject, modifier: Modifier) {
    val style = st.visStyle
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B0A14)).pointerInput(style) {
        detectTapGestures(onTap = { st.nextVisStyle() }, onLongPress = { st.visFull = true })
    }) {
        if (!visHasArt(style)) Thumb(st.playerArt(t), "", Modifier.fillMaxSize().graphicsLayer { alpha = .22f }, px = 400, corner = 0.dp, fallback = st.playerAlt(t))
        Visualizer(style, Modifier.fillMaxSize().padding(if (visHasArt(style)) 0.dp else 14.dp),
            center = if (visHasArt(style)) ({ Thumb(st.playerArt(t), "", Modifier.fillMaxSize(), px = 400, corner = 0.dp, fallback = st.playerAlt(t)) }) else null)
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((VIS_STYLES.firstOrNull { it.first == style }?.second ?: "") + "  ▾", color = Color.White.copy(alpha = .85f), fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x66000000)).clickable { st.dialog = app.orionmd.omniplay.Dlg.VisPick }.padding(horizontal = 8.dp, vertical = 4.dp))
        }
        IconBtn(Ic.expand, { st.visFull = true }, Modifier.align(Alignment.TopEnd).padding(4.dp), tint = Color.White.copy(alpha = .85f), size = 36.dp, iconSize = 20.dp)
    }
}

/** Full-screen visualizer: tap to change style, the ✕ or Back to leave. Keeps the screen on. */
@Composable
fun VisualizerFull(st: AppState) {
    val t = st.player.track ?: run { st.visFull = false; return }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val style = st.visStyle
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(style) { detectTapGestures(onTap = { st.nextVisStyle() }) }) {
        Visualizer(style, Modifier.fillMaxSize().padding(if (visHasArt(style)) 40.dp else 24.dp),
            center = if (visHasArt(style)) ({ Thumb(st.playerArt(t), "", Modifier.fillMaxSize(), px = 800, corner = 0.dp, fallback = st.playerAlt(t)) }) else null)
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Text((VIS_STYLES.firstOrNull { it.first == style }?.second ?: "") + "  ▾", color = Color.White.copy(alpha = .7f), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { st.visFull = false; st.dialog = app.orionmd.omniplay.Dlg.VisPick }.padding(horizontal = 10.dp, vertical = 6.dp)) }
            IconBtn(if (st.player.playing) Ic.pause else Ic.play, { Player.toggle() }, tint = Color.White)
            IconBtn(Ic.next, { Player.next() }, tint = Color.White)
            IconBtn(Ic.close, { st.visFull = false }, tint = Color.White)
        }
        // lyrics over the visualizer (Settings → Playback → Music player)
        st.prefGen
        if (st.pref("visLyrics", "0") == "1") {
            val id = t.optLong("i")
            androidx.compose.runtime.LaunchedEffect(id) { st.lyricsFor(t, st.player.dur) }
            val l = st.lyrics[id]
            val line = l?.synced?.let { s -> s.indexOfLast { it.first <= st.player.pos + 300 }.takeIf { it >= 0 }?.let { s[it].second } }
            if (!line.isNullOrBlank()) Text(line, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x66000000)).padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(20.dp)) {
            Text(t.optString("t").ifEmpty { t.optString("n") }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(t.optString("a"), t.optString("al")).filter { it.isNotEmpty() }.joinToString(" — "), color = Color.White.copy(alpha = .7f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
