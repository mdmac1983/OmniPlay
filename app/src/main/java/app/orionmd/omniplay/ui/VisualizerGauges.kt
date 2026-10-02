package app.orionmd.omniplay.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.orionmd.omniplay.Visual
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Instrument-cluster visualizers: dials like a car's dashboard, each one showing an equaliser band (sub-bass on the
 * left … treble on the right). Five styles, plus a cockpit that puts VU meters, gauges, bars and a wave together.
 */

/** Equaliser bands for [n] dials, spaced like an equaliser (more resolution in the lows): value 0…1 and a label. */
internal fun VisState.eq(n: Int): List<Pair<Float, String>> {
    val labels = when (n) {
        2 -> listOf("BASS", "TREBLE")
        3 -> listOf("BASS", "MID", "HIGH")
        4 -> listOf("BASS", "LOW MID", "HIGH MID", "TREBLE")
        5 -> listOf("SUB", "BASS", "MID", "HIGH", "AIR")
        6 -> listOf("SUB", "BASS", "LOW MID", "MID", "HIGH", "AIR")
        else -> listOf("31", "63", "125", "250", "500", "1K", "2K", "4K", "8K", "16K").let { l -> List(n) { l.getOrElse(it) { "${it + 1}" } } }
    }
    val b = Visual.BANDS
    return (0 until n).map { i ->
        val a = (b * (i.toFloat() / n).pow(1.35f)).toInt().coerceIn(0, b - 1)
        val z = (b * ((i + 1f) / n).pow(1.35f)).toInt().coerceIn(a + 1, b)
        val v = (a until z).sumOf { disp[it].toDouble() }.toFloat() / (z - a)
        (v * 1.25f).coerceIn(0f, 1f) to labels[i]
    }
}

private fun DrawScope.text(t: String, x: Float, y: Float, sizePx: Float, color: Color, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER, font: Typeface? = null) {
    drawIntoCanvas { c ->
        c.nativeCanvas.drawText(t, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = sizePx; textAlign = align
            typeface = font ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        })
    }
}

internal fun rad(deg: Float) = deg / 180f * PI.toFloat()
internal fun Offset.at(deg: Float, r: Float) = Offset(x + cos(rad(deg)) * r, y + sin(rad(deg)) * r)

/** Everything a dial can look like. */
private class DialLook(
    val face: Brush?, val rim: Color, val tick: Color, val num: Color, val needle: Color, val redline: Color?,
    val start: Float = 135f, val sweep: Float = 270f, val ticks: Int = 10, val numbers: Boolean = true, val glowArc: Color? = null,
    val font: Typeface? = null, val hub: Color = Color(0xFF222222),
)

/** One round dial at [c] with radius [r] showing [v] (0…1). */
private fun DrawScope.dial(c: Offset, r: Float, v: Float, label: String, L: DialLook, maxLabel: Int = 10) {
    L.face?.let { drawCircle(it, r, c) }
    drawCircle(L.rim, r, c, style = Stroke(width = r * .06f))
    // live arc showing the level (glowing on modern dials)
    L.glowArc?.let { g ->
        drawArc(g.copy(alpha = .18f), L.start, L.sweep, false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(width = r * .1f, cap = StrokeCap.Round))
        drawArc(Brush.sweepGradient(listOf(g, L.redline ?: g, g), c), L.start, L.sweep * v, false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(width = r * .1f, cap = StrokeCap.Round))
    }
    // red zone
    L.redline?.let { rl -> drawArc(rl, L.start + L.sweep * .8f, L.sweep * .2f, false, Offset(c.x - r * .86f, c.y - r * .86f), Size(r * 1.72f, r * 1.72f), style = Stroke(width = r * .07f)) }
    // ticks and numbers
    val major = L.ticks
    for (k in 0..major * 5) {
        val a = L.start + L.sweep * k / (major * 5)
        val big = k % 5 == 0
        drawLine(if (L.redline != null && k >= major * 4) L.redline else L.tick, c.at(a, r * (if (big) .72f else .8f)), c.at(a, r * .88f), strokeWidth = if (big) r * .035f else r * .015f)
        if (big && L.numbers && r > 30) {
            val p = c.at(a, r * .56f)
            text("${(k / 5) * maxLabel / major}", p.x, p.y + r * .06f, r * .14f, L.num, font = L.font)
        }
    }
    text(label, c.x, c.y + r * .62f, r * .13f, L.num.copy(alpha = .85f), font = L.font)
    // needle
    val a = L.start + L.sweep * v
    drawLine(L.needle.copy(alpha = .35f), c.at(a + 180f, r * .15f), c.at(a, r * .86f), strokeWidth = r * .09f, cap = StrokeCap.Round)
    drawLine(L.needle, c.at(a + 180f, r * .15f), c.at(a, r * .86f), strokeWidth = r * .04f, cap = StrokeCap.Round)
    drawCircle(L.hub, r * .09f, c); drawCircle(L.needle, r * .04f, c)
}

// ---------------------------------------------------------------- the five clusters

/** Sport: two big dials (bass as the rev counter, mids as the speedo) and three small ones between / below. */
internal fun DrawScope.clusterSport(s: VisState) {
    val look = { ticks: Int -> DialLook(Brush.radialGradient(listOf(Color(0xFF1A1D26), Color(0xFF07080C))), Color(0xFF9AA3B5), Color.White, Color.White, Color(0xFFFF6A1A), Color(0xFFE53935), ticks = ticks, glowArc = Color(0xFFFF6A1A).copy(alpha = .9f)) }
    val sl = DialLook(Brush.radialGradient(listOf(Color(0xFF1A1D26), Color(0xFF07080C))), Color(0xFF9AA3B5), Color.White, Color.White, Color(0xFFFF6A1A), null, start = 180f, sweep = 180f, ticks = 4, numbers = false)
    val b = s.eq(5)
    val w = size.width; val h = size.height
    if (h > w * 1.15f) {   // portrait: the two big dials side by side, three small ones in a row below
        val big = w * .23f
        dial(Offset(w * .26f, h * .38f), big, b[1].first, "RPM · ${b[1].second}", look(8), 8)
        dial(Offset(w * .74f, h * .38f), big, b[2].first, "KM/H · ${b[2].second}", look(8), 240)
        val sm = w * .12f
        listOf(0, 3, 4).forEachIndexed { k, idx -> dial(Offset(w * (.2f + k * .3f), h * .38f + big + sm * 1.6f), sm, b[idx].first, b[idx].second, sl) }
    } else {               // landscape: big dials left and right, small ones stacked in the middle
        val big = min(w * .22f, h * .44f)
        dial(Offset(w * .25f, h * .5f), big, b[1].first, "RPM · ${b[1].second}", look(8), 8)
        dial(Offset(w * .75f, h * .5f), big, b[2].first, "KM/H · ${b[2].second}", look(8), 240)
        val sm = min(w * .075f, h * .14f)
        listOf(0, 3, 4).forEachIndexed { k, idx -> dial(Offset(w * .5f, h * (.2f + k * .3f)), sm, b[idx].first, b[idx].second, sl) }
    }
}

/** Classic chrome: five cream-faced analogue dials in a row with black numerals, like an old sports car. */
internal fun DrawScope.clusterClassic(s: VisState) {
    val look = DialLook(Brush.radialGradient(listOf(Color(0xFFF7F0DC), Color(0xFFDCCFA8))), Color(0xFFC9CED6), Color(0xFF1B1B1B), Color(0xFF1B1B1B), Color(0xFFC62828), Color(0xFFC62828),
        font = Typeface.create(Typeface.SERIF, Typeface.BOLD), hub = Color(0xFF444444))
    val b = s.eq(5)
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF3B2A1A), Color(0xFF1E140C))), size = size)   // wooden dash
    val portrait = h > w * 1.15f
    val r = if (portrait) w / 7.5f else min(w / 11f, h * .38f)
    for (i in 0 until 5) {
        val big = i == 1 || i == 3
        val rr = if (big) r * 1.2f else r * .95f
        // portrait: three on top, two below; landscape: one row
        val c = if (portrait) (if (i < 3) Offset(w * (i + .5f) / 3, h * .36f) else Offset(w * (i - 3 + 1f) / 3, h * .66f)) else Offset(w * (i + .5f) / 5, h * .52f)
        drawCircle(Color(0xFF8E959E), rr * 1.08f, c)   // chrome bezel
        dial(c, rr, b[i].first, b[i].second, look)
    }
}

/** Digital: segmented LED arcs with big digital readouts — six of them, neon on black. */
internal fun DrawScope.clusterDigital(s: VisState) {
    val b = s.eq(6)
    val w = size.width; val h = size.height
    val cols = 3; val rows = 2
    val r = min(w / cols, h / rows) * .38f
    val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    b.forEachIndexed { i, (v, l) ->
        val c = Offset(w * (i % cols + .5f) / cols, h * (i / cols + .5f) / rows)
        val segs = 24
        for (k in 0 until segs) {
            val a = 150f + 240f * k / segs
            val on = k < v * segs
            val col = when { k > segs * .83f -> Color(0xFFFF1744); k > segs * .6f -> Color(0xFFFFEA00); else -> Color(0xFF00E5FF) }
            drawArc(if (on) col else col.copy(alpha = .1f), a, 240f / segs * .7f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = r * .2f))
        }
        text("${(v * 100).toInt()}".padStart(3, '0'), c.x, c.y + r * .15f, r * .45f, Color(0xFF00E5FF), font = mono)
        text(l, c.x, c.y + r * .55f, r * .18f, Color.White.copy(alpha = .7f), font = mono)
    }
}

/** Aviation cockpit: eight small black round instruments in two rows, with green / yellow / red bands. */
internal fun DrawScope.clusterCockpit(s: VisState) {
    val b = s.eq(8)
    val w = size.width; val h = size.height
    drawRect(Color(0xFF2B2F36), size = size)   // panel
    val r = min(w / 4f, h / 2f) * .4f
    b.forEachIndexed { i, (v, l) ->
        val c = Offset(w * (i % 4 + .5f) / 4, h * (i / 4 + .5f) / 2)
        drawCircle(Color(0xFF15171B), r * 1.12f, c)
        // green / yellow / red operating ranges
        for ((from, to, col) in listOf(Triple(.1f, .55f, Color(0xFF43A047)), Triple(.55f, .8f, Color(0xFFFDD835)), Triple(.8f, 1f, Color(0xFFE53935))))
            drawArc(col, 135f + 270f * from, 270f * (to - from), false, Offset(c.x - r * .9f, c.y - r * .9f), Size(r * 1.8f, r * 1.8f), style = Stroke(width = r * .1f))
        dial(c, r, v, l, DialLook(Brush.radialGradient(listOf(Color(0xFF111316), Color(0xFF050607))), Color(0xFF6B717C), Color.White, Color.White, Color.White, null, ticks = 5), maxLabel = 100)
        // screws in the corners
        for (d in listOf(-45f, 45f, 135f, 225f)) drawCircle(Color(0xFF6B717C), r * .05f, c.at(d, r * 1.2f))
    }
}

/** Modern EV: two big glowing ring gauges with a band column in the middle. */
internal fun DrawScope.clusterEv(s: VisState) {
    val w = size.width; val h = size.height
    val b = s.eq(6)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF05070D), Color(0xFF0B1222))), size = size)
    val portrait = h > w * 1.15f
    val r = if (portrait) w * .2f else min(w * .2f, h * .42f)
    val ring = { c: Offset, v: Float, l: String, col: Color ->
        drawCircle(col.copy(alpha = .08f), r, c)
        drawArc(Color.White.copy(alpha = .08f), 120f, 300f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = r * .12f, cap = StrokeCap.Round))
        drawArc(col.copy(alpha = .35f), 120f, 300f * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = r * .24f, cap = StrokeCap.Round))
        drawArc(col, 120f, 300f * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = r * .12f, cap = StrokeCap.Round))
        text("${(v * 100).toInt()}", c.x, c.y + r * .15f, r * .5f, Color.White, font = Typeface.create("sans-serif-light", Typeface.NORMAL))
        text(l, c.x, c.y + r * .45f, r * .14f, col)
    }
    val ry = if (portrait) h * .3f else h * .5f
    ring(Offset(w * (if (portrait) .27f else .2f), ry), b[1].first, b[1].second, Color(0xFF3FC8F5))
    ring(Offset(w * (if (portrait) .73f else .8f), ry), b[4].first, b[4].second, Color(0xFFB45CFF))
    // band meters: in the middle (landscape) or below the rings (portrait)
    val x0 = if (portrait) w * .25f else w * .4f; val x1 = if (portrait) w * .85f else w * .6f
    val y0 = if (portrait) ry + r * 1.35f else h * .22f
    val step = if (portrait) (h - y0) / 6.5f else h * .11f
    b.forEachIndexed { i, (v, l) ->
        val y = y0 + i * step
        drawRoundRect(Color.White.copy(alpha = .08f), Offset(x0, y), Size(x1 - x0, h * .05f), androidx.compose.ui.geometry.CornerRadius(h * .025f))
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF3FC8F5), Color(0xFFB45CFF)), x0, x1), Offset(x0, y), Size((x1 - x0) * v.coerceAtLeast(.02f), h * .05f), androidx.compose.ui.geometry.CornerRadius(h * .025f))
        text(l, x0 - 6f, y + h * .04f, h * .04f, Color.White.copy(alpha = .6f), align = Paint.Align.RIGHT)
    }
}

/** The full cockpit: two VU meters, a row of gauges, and a spectrum with the waveform running over it. */
internal fun DrawScope.cockpitCombo(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0D14), Color(0xFF151A28))), size = size)
    // --- VU meters (top), left = lows, right = highs
    val vuH = h * .32f
    val lr = listOf(s.eq(2)[0].first, s.eq(2)[1].first)
    lr.forEachIndexed { i, v ->
        val box = Offset(w * (.03f + i * .5f), h * .03f); val bw = w * .44f
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF3E7C2), Color(0xFFD9C38B)), box.y, box.y + vuH), box, Size(bw, vuH), androidx.compose.ui.geometry.CornerRadius(12f))
        val c = Offset(box.x + bw / 2, box.y + vuH * 1.05f); val r = min(bw * .55f, vuH * .95f)
        drawArc(Color(0xFF1B1B1B), 225f, 90f * .75f, false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(width = 2.5f))
        drawArc(Color(0xFFC62828), 225f + 90f * .75f, 90f * .25f, false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(width = 5f))
        for (k in 0..10) { val a = 225f + 90f * k / 10; drawLine(if (k > 7) Color(0xFFC62828) else Color(0xFF1B1B1B), c.at(a, r * .8f), c.at(a, r * (if (k % 5 == 0) .92f else .87f)), strokeWidth = 2f) }
        text("VU", c.x - bw * .3f, box.y + vuH * .9f, vuH * .13f, Color(0xFF1B1B1B), font = Typeface.SERIF)
        text(if (i == 0) "L · LOWS" else "R · HIGHS", c.x + bw * .22f, box.y + vuH * .9f, vuH * .08f, Color(0xFF1B1B1B).copy(alpha = .7f))
        val a = 225f + 90f * v
        clipRect(box.x, box.y, box.x + bw, box.y + vuH) {
            drawLine(Color(0xFF111111), c, c.at(a, r * .95f), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
    // --- gauges (middle row)
    val g = s.eq(4)
    val gr = min(w / 8f, h * .13f) * .9f
    val gl = DialLook(Brush.radialGradient(listOf(Color(0xFF1A1D26), Color(0xFF07080C))), Color(0xFF9AA3B5), Color.White, Color.White, Color(0xFFFF6A1A), Color(0xFFE53935), ticks = 5, numbers = false, glowArc = Color(0xFF3FC8F5))
    g.forEachIndexed { i, (v, l) -> dial(Offset(w * (i + .5f) / 4, h * .52f), gr, v, l, gl) }
    // --- bars with the waveform over them (bottom)
    val top = h * .68f; val bh = h * .3f
    val n = Visual.BANDS; val bw = w / n
    for (k in 0 until n) {
        val v = s.disp[k].coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(listOf(Color(0xFFFF4D8D), Color(0xFFFFC857), Color(0xFF19FB9B)), top, top + bh), Offset(k * bw + bw * .15f, top + bh * (1 - v)), Size(bw * .7f, bh * v))
    }
    val p = Path(); val mid = top + bh * .5f
    for (k in 0 until Visual.WAVE) { val x = w * k / (Visual.WAVE - 1); val y = mid - s.wdisp[k].coerceIn(-1f, 1f) * bh * .8f; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
    drawPath(p, Color.White.copy(alpha = .35f), style = Stroke(width = 8f, cap = StrokeCap.Round))
    drawPath(p, Color.White, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
}

// ---------------------------------------------------------------- ten more clusters

private fun DrawScope.carbon() {
    drawRect(Color(0xFF0D0E10), size = size)
    val st = 14f; var y = 0f; var row = 0
    while (y < size.height) { var x = if (row % 2 == 0) 0f else st; while (x < size.width) { drawRect(Color(0xFF17191C), Offset(x, y), Size(st, st / 2)); x += st * 2 }; y += st / 2; row++ }
}

/** Supercar: one huge carbon-fibre rev counter (bass) in the middle, speed and boost dials either side. */
internal fun DrawScope.clusterSupercar(s: VisState) {
    carbon(); val b = s.eq(5); val w = size.width; val h = size.height
    val big = DialLook(Brush.radialGradient(listOf(Color(0xFF1C1E22), Color(0xFF050505))), Color(0xFFFFC400), Color.White, Color.White, Color(0xFFFFC400), Color(0xFFFF1744), ticks = 9, glowArc = Color(0xFFFFC400))
    val side = DialLook(Brush.radialGradient(listOf(Color(0xFF16181C), Color(0xFF050505))), Color(0xFF8A9099), Color.White, Color.White, Color(0xFFFFC400), Color(0xFFFF1744), ticks = 6)
    if (h > w * 1.15f) {
        dial(Offset(w / 2, h * .36f), w * .4f, b[1].first, "RPM ×1000 · ${b[1].second}", big, 9)
        dial(Offset(w * .26f, h * .78f), w * .2f, b[2].first, "KM/H · ${b[2].second}", side, 360)
        dial(Offset(w * .74f, h * .78f), w * .2f, b[3].first, "BOOST · ${b[3].second}", side, 3)
    } else {
        val r = min(h * .46f, w * .24f)
        dial(Offset(w / 2, h / 2), r, b[1].first, "RPM ×1000 · ${b[1].second}", big, 9)
        dial(Offset(w * .17f, h * .58f), r * .62f, b[2].first, "KM/H · ${b[2].second}", side, 360)
        dial(Offset(w * .83f, h * .58f), r * .62f, b[3].first, "BOOST · ${b[3].second}", side, 3)
    }
    text("${1 + (s.level * 6).toInt().coerceIn(0, 6)}", w / 2, if (h > w * 1.15f) h * .36f + w * .1f else h / 2 + min(h * .46f, w * .24f) * .32f, min(w, h) * .08f, Color(0xFFFFC400))
}

/** Muscle car: six black dials with white needles and chrome bezels along an arc. */
internal fun DrawScope.clusterMuscle(s: VisState) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF26201A), Color(0xFF0E0B08))), size = size)
    val L = DialLook(Brush.radialGradient(listOf(Color(0xFF151515), Color(0xFF000000))), Color(0xFFD7DCE2), Color.White, Color.White, Color.White, Color(0xFFFF5252), ticks = 8, hub = Color(0xFF8E959E))
    val b = s.eq(6); val w = size.width; val h = size.height; val portrait = h > w * 1.15f
    b.forEachIndexed { i, (v, l) ->
        val c = if (portrait) Offset(w * (i % 2 * .5f + .25f), h * (i / 2 * .3f + .2f)) else Offset(w * (i + .5f) / 6, h * (.62f - .14f * kotlin.math.sin(PI.toFloat() * (i + .5f) / 6)))
        val r = if (portrait) min(w * .2f, h * .13f) else min(w / 13.5f, h * .3f)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFF2F4F7), Color(0xFF6E757E), Color(0xFFE3E6EA)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r * 1.12f, c)
        dial(c, r, v, l, L)
    }
}

/** Luxury chronograph: deep-blue watch faces with silver serif numerals and blue-steel needles. */
internal fun DrawScope.clusterChrono(s: VisState) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF1B2433), Color(0xFF080B12)), center, maxOf(size.width, size.height)), size = size)
    val L = DialLook(Brush.radialGradient(listOf(Color(0xFF1E3A6E), Color(0xFF0B1A38))), Color(0xFFC0C6CF), Color(0xFFE6E9EE), Color(0xFFE6E9EE), Color(0xFF8FB8FF), null, ticks = 12, font = Typeface.create(Typeface.SERIF, Typeface.BOLD), hub = Color(0xFFC0C6CF))
    val b = s.eq(4); val w = size.width; val h = size.height
    val cells = if (h > w * 1.15f) listOf(Offset(w * .28f, h * .3f), Offset(w * .72f, h * .3f), Offset(w * .28f, h * .68f), Offset(w * .72f, h * .68f)) else List(4) { Offset(w * (it + .5f) / 4, h / 2) }
    val r = if (h > w * 1.15f) w * .2f else min(w / 9f, h * .4f)
    b.forEachIndexed { i, (v, l) -> drawCircle(Color(0xFFC0C6CF), r * 1.07f, cells[i]); dial(cells[i], r, v, l, L, 60) }
}

/** Rally: white faces, red needles, bold black numbers — four big dials. */
internal fun DrawScope.clusterRally(s: VisState) {
    drawRect(Color(0xFF1A1A1A), size = size)
    val L = DialLook(Brush.radialGradient(listOf(Color.White, Color(0xFFE3E3E3))), Color(0xFF111111), Color(0xFF111111), Color(0xFF111111), Color(0xFFE10600), Color(0xFFE10600), ticks = 8, hub = Color(0xFF111111))
    val b = s.eq(4); val w = size.width; val h = size.height
    val cells = if (h > w * 1.15f) listOf(Offset(w * .28f, h * .3f), Offset(w * .72f, h * .3f), Offset(w * .28f, h * .68f), Offset(w * .72f, h * .68f)) else List(4) { Offset(w * (it + .5f) / 4, h / 2) }
    val r = if (h > w * 1.15f) w * .21f else min(w / 8.6f, h * .42f)
    b.forEachIndexed { i, (v, l) -> dial(cells[i], r, v, l, L, listOf(8, 200, 120, 100)[i]) }
}

/** Motorbike: one big rev counter with two half-moon gauges under it. */
internal fun DrawScope.clusterMoto(s: VisState) {
    drawRect(Color(0xFF060708), size = size)
    val b = s.eq(3); val w = size.width; val h = size.height
    val big = DialLook(Brush.radialGradient(listOf(Color(0xFF141619), Color(0xFF000000))), Color(0xFF2F80ED), Color.White, Color.White, Color(0xFFFF3D00), Color(0xFFFF1744), ticks = 14, glowArc = Color(0xFF2F80ED))
    val half = DialLook(null, Color(0xFF2F80ED), Color.White, Color.White, Color(0xFFFF3D00), null, start = 180f, sweep = 180f, ticks = 4, numbers = false)
    if (h > w * 1.15f) {
        val r = w * .36f
        dial(Offset(w / 2, h * .4f), r, b[0].first, "RPM · ${b[0].second}", big, 14)
        dial(Offset(w * .3f, h * .4f + r * 1.55f), r * .45f, b[1].first, b[1].second, half)
        dial(Offset(w * .7f, h * .4f + r * 1.55f), r * .45f, b[2].first, b[2].second, half)
    } else {   // landscape: the half-moons sit either side of the big dial
        val r = min(w * .22f, h * .44f)
        dial(Offset(w / 2, h / 2), r, b[0].first, "RPM · ${b[0].second}", big, 14)
        dial(Offset(w * .17f, h * .6f), r * .55f, b[1].first, b[1].second, half)
        dial(Offset(w * .83f, h * .6f), r * .55f, b[2].first, b[2].second, half)
    }
}

/** Vintage '50s: ivory ribbon speedometer across the top, three art-deco dials below. */
internal fun DrawScope.clusterVintage(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF5B1F1F), Color(0xFF2B0E0E))), size = size)
    val b = s.eq(4)
    // ribbon speedo
    val rb = androidx.compose.ui.geometry.Rect(w * .06f, h * .1f, w * .94f, h * .3f)
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFF6EBCF), Color(0xFFD9C79A)), rb.top, rb.bottom), rb.topLeft, rb.size, androidx.compose.ui.geometry.CornerRadius(rb.height / 2))
    for (k in 0..12) { val x = rb.left + rb.width * (.06f + .88f * k / 12); drawLine(Color(0xFF3A2A12), Offset(x, rb.top + rb.height * .15f), Offset(x, rb.top + rb.height * (if (k % 2 == 0) .45f else .3f)), 2f)
        if (k % 2 == 0) text("${k * 10}", x, rb.top + rb.height * .78f, min(rb.height * .22f, rb.width * .035f), Color(0xFF3A2A12), font = Typeface.SERIF) }
    drawRect(Color(0xFFD32F2F), Offset(rb.left + rb.width * .06f, rb.top + rb.height * .48f), Size(rb.width * .88f * b[1].first, rb.height * .1f))
    val L = DialLook(Brush.radialGradient(listOf(Color(0xFFF6EBCF), Color(0xFFCDB98A))), Color(0xFFB0A070), Color(0xFF3A2A12), Color(0xFF3A2A12), Color(0xFF8B0000), null, ticks = 4, font = Typeface.SERIF, hub = Color(0xFF3A2A12))
    val r = min(w * .13f, h * .2f)
    listOf(0, 2, 3).forEachIndexed { k, i -> val c = Offset(w * (.2f + k * .3f), h * .62f); drawCircle(Color(0xFFB0A070), r * 1.1f, c); dial(c, r, b[i].first, b[i].second, L) }
}

/** Racing five: five overlapping dials in a row, the middle one biggest (rev counter). */
internal fun DrawScope.clusterFive(s: VisState) {
    drawRect(Color(0xFF0B0B0C), size = size)
    val b = s.eq(5); val w = size.width; val h = size.height
    val L = { red: Boolean -> DialLook(Brush.radialGradient(listOf(Color(0xFF1A1A1C), Color(0xFF020202))), Color(0xFF6E737B), Color.White, Color.White, Color(0xFFFF6D00), if (red) Color(0xFFFF1744) else null, ticks = if (red) 8 else 5) }
    val portrait = h > w * 1.15f
    val base = if (portrait) w * .17f else min(w / 9f, h * .38f)
    val order = listOf(0, 1, 2, 3, 4); val scale = listOf(.62f, .8f, 1.2f, .8f, .62f)
    val xs = if (portrait) listOf(.2f, .35f, .5f, .65f, .8f) else listOf(.14f, .3f, .5f, .7f, .86f)
    for (i in listOf(0, 4, 1, 3, 2)) dial(Offset(w * xs[i], h * (if (portrait) .45f else .52f)), base * scale[i] * (if (portrait) 1.3f else 1f), b[order[i]].first, b[order[i]].second, L(i == 2), if (i == 2) 8 else 10)
}

/** Truck: eight small amber-lit gauges plus two big ones. */
internal fun DrawScope.clusterTruck(s: VisState) {
    drawRect(Color(0xFF151618), size = size)
    val amber = Color(0xFFFFB300); val b = s.eq(10); val w = size.width; val h = size.height
    val big = DialLook(Brush.radialGradient(listOf(Color(0xFF101112), Color(0xFF000000))), Color(0xFF5A5F66), amber, amber, Color.White, Color(0xFFFF3D00), ticks = 8, glowArc = amber)
    val sm = DialLook(Brush.radialGradient(listOf(Color(0xFF101112), Color(0xFF000000))), Color(0xFF5A5F66), amber, amber, Color.White, null, ticks = 4, numbers = false)
    val portrait = h > w * 1.15f
    val R = if (portrait) w * .22f else min(w * .13f, h * .3f)
    dial(Offset(w * .27f, h * (if (portrait) .22f else .3f)), R, b[0].first, "RPM", big, 30); dial(Offset(w * .73f, h * (if (portrait) .22f else .3f)), R, b[1].first, "MPH", big, 80)
    val cols = 4; val rr = if (portrait) w * .09f else min(w * .045f, h * .1f)
    for (i in 0 until 8) { val c = Offset(w * (i % cols + .5f) / cols, h * (if (portrait) .55f + i / cols * .2f else .7f + i / cols * .2f)); dial(c, rr, b[i + 2].first, b[i + 2].second, sm) }
}

/** Neon night: dials with glowing cyan / pink rims on black. */
internal fun DrawScope.clusterNeon(s: VisState) {
    drawRect(Color(0xFF05020A), size = size)
    val b = s.eq(6); val w = size.width; val h = size.height
    val cols = if (h > w * 1.15f) 2 else 3; val rows = 6 / cols
    val r = min(w / cols, h / rows) * .36f
    b.forEachIndexed { i, (v, l) ->
        val col = if (i % 2 == 0) Color(0xFF00E5FF) else Color(0xFFFF2BD6)
        val c = Offset(w * (i % cols + .5f) / cols, h * (i / cols + .5f) / rows)
        drawCircle(col.copy(alpha = .15f), r * 1.18f, c, style = Stroke(r * .14f)); drawCircle(col, r * 1.1f, c, style = Stroke(r * .03f))
        dial(c, r, v, l, DialLook(null, col.copy(alpha = .0f), col, Color.White, col, null, ticks = 6, numbers = r > 40, glowArc = col))
    }
}

/** Steampunk: brass dials with turning gears behind them. */
internal fun DrawScope.clusterSteam(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.radialGradient(listOf(Color(0xFF3B2A18), Color(0xFF140D06)), center, maxOf(w, h)), size = size)
    val brass = Color(0xFFC9A04C)
    fun gear(c: Offset, r: Float, teeth: Int, rot: Float) {
        val p = Path()
        for (k in 0 until teeth * 2) { val a = rot + 360f * k / (teeth * 2); val rr = if (k % 2 == 0) r else r * .82f
            val q1 = c.at(a - 180f / teeth / 2, rr); val q2 = c.at(a + 180f / teeth / 2, rr); if (k == 0) p.moveTo(q1.x, q1.y) else p.lineTo(q1.x, q1.y); p.lineTo(q2.x, q2.y) }
        p.close(); drawPath(p, brass.copy(alpha = .35f)); drawCircle(Color(0xFF140D06), r * .3f, c)
    }
    gear(Offset(w * .15f, h * .2f), min(w, h) * .2f, 12, s.spin * 40f); gear(Offset(w * .85f, h * .8f), min(w, h) * .25f, 16, -s.spin * 30f); gear(Offset(w * .5f, h * .95f), min(w, h) * .15f, 10, s.spin * 50f)
    val L = DialLook(Brush.radialGradient(listOf(Color(0xFFF3E3C0), Color(0xFFCDB27A))), Color(0xFF8A6A2C), Color(0xFF3A2410), Color(0xFF3A2410), Color(0xFF3A2410), Color(0xFF8B1A1A), ticks = 6, font = Typeface.SERIF, hub = brass)
    val b = s.eq(3); val portrait = h > w * 1.15f
    val r = if (portrait) w * .22f else min(w * .15f, h * .34f)
    b.forEachIndexed { i, (v, l) ->
        val c = if (portrait) Offset(w * (if (i == 1) .5f else .28f + i * .22f), h * (.28f + i * .24f)) else Offset(w * (i + .5f) / 3, h / 2)
        drawCircle(Brush.linearGradient(listOf(Color(0xFFE8C77A), Color(0xFF7A5A20)), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r)), r * 1.14f, c)
        for (k in 0 until 8) drawCircle(Color(0xFF5A3F14), r * .035f, c.at(k * 45f + 22f, r * 1.07f))
        dial(c, r, v, l, L, listOf(10, 300, 50)[i])
    }
}
