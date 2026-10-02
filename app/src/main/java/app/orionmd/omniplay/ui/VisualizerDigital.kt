package app.orionmd.omniplay.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.orionmd.omniplay.Visual
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Digital gauges: LED / LCD / VFD / HUD-style instruments, each showing an equaliser band, as ten single styles and ten
 * full digital dashboards (speed, revs, shift lights, tapes, bar meters, graphs and the waveform together).
 * Every layout adapts to portrait and landscape.
 */

// ================================================================ drawing kit

private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
private val MONO = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
private val LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL)
private val COND = Typeface.create("sans-serif-condensed", Typeface.BOLD)

private fun DrawScope.txt(t: String, x: Float, y: Float, px: Float, col: Color, font: Typeface = COND, align: Paint.Align = Paint.Align.CENTER) {
    if (px < 4f) return
    drawIntoCanvas { c -> tp.color = col.toArgb(); tp.textSize = px; tp.textAlign = align; tp.typeface = font; c.nativeCanvas.drawText(t, x, y, tp) }
}

private val SEGS = mapOf('0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc", '5' to "afgcd", '6' to "afgedc",
    '7' to "abc", '8' to "abcdefg", '9' to "abcdfg", '-' to "g", ' ' to "", 'P' to "abefg", 'N' to "ceg", 'R' to "eg")

/** Seven-segment number centred on [cx], top at [y], digit height [h]; unlit segments glow faintly (like a real LCD). */
private fun DrawScope.seg7(s: String, cx: Float, y: Float, h: Float, col: Color, ghost: Float = .08f) {
    val w = h * .52f; val gap = h * .14f; val t = h * .11f; val half = h / 2
    val total = s.length * w + (s.length - 1) * gap
    var x = cx - total / 2
    for (ch in s) {
        val on = SEGS[ch] ?: ""
        fun seg(k: Char, r: Rect) { val lit = k in on; if (lit || ghost > 0f) drawRoundRect(if (lit) col else col.copy(alpha = ghost), r.topLeft, r.size, CornerRadius(t / 2)) }
        seg('a', Rect(x + t, y, x + w - t, y + t)); seg('d', Rect(x + t, y + h - t, x + w - t, y + h)); seg('g', Rect(x + t, y + half - t / 2, x + w - t, y + half + t / 2))
        seg('f', Rect(x, y + t, x + t, y + half - t / 2)); seg('b', Rect(x + w - t, y + t, x + w, y + half - t / 2))
        seg('e', Rect(x, y + half + t / 2, x + t, y + h - t)); seg('c', Rect(x + w - t, y + half + t / 2, x + w, y + h - t))
        x += w + gap
    }
}

private fun heat(f: Float, a: Color = Color(0xFF19FB9B), b: Color = Color(0xFFFFD60A), c: Color = Color(0xFFFF3B30)) =
    if (f < .6f) lerp(a, b, f / .6f) else lerp(b, c, ((f - .6f) / .4f).coerceIn(0f, 1f))

/** Segmented LED arc. */
private fun DrawScope.ledArc(c: Offset, r: Float, v: Float, segs: Int = 24, start: Float = 135f, sweep: Float = 270f, thick: Float = r * .16f,
                             off: Float = .1f, col: (Float) -> Color = { heat(it) }) {
    val step = sweep / segs
    for (k in 0 until segs) {
        val f = (k + .5f) / segs; val cc = col(f)
        drawArc(if (f <= v) cc else cc.copy(alpha = off), start + k * step, step * .72f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(thick))
    }
}

/** Segmented LED bar (vertical fills upwards). */
private fun DrawScope.ledBar(x: Float, y: Float, w: Float, h: Float, v: Float, segs: Int, vertical: Boolean = true, off: Float = .1f, col: (Float) -> Color = { heat(it) }) {
    for (k in 0 until segs) {
        val f = (k + .5f) / segs; val cc = col(f); val c2 = if (f <= v) cc else cc.copy(alpha = off)
        if (vertical) { val sh = h / segs; drawRect(c2, Offset(x, y + h - (k + 1) * sh + sh * .12f), Size(w, sh * .76f)) }
        else { val sw = w / segs; drawRect(c2, Offset(x + k * sw + sw * .12f, y), Size(sw * .76f, h)) }
    }
}

/** Smooth glowing ring with a value. */
private fun DrawScope.ring(c: Offset, r: Float, v: Float, col: Color, thick: Float = r * .12f, start: Float = 135f, sweep: Float = 270f) {
    drawArc(col.copy(alpha = .1f), start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(thick, cap = StrokeCap.Round))
    drawArc(col.copy(alpha = .3f), start, sweep * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(thick * 2.2f, cap = StrokeCap.Round))
    drawArc(col, start, sweep * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(thick, cap = StrokeCap.Round))
}

/** Shift lights: green → red → blue, lit up to [v]. */
private fun DrawScope.shift(x: Float, y: Float, w: Float, v: Float, n: Int = 15) {
    val d = w / n
    for (k in 0 until n) {
        val f = (k + .5f) / n
        val col = when { f < .4f -> Color(0xFF00E676); f < .73f -> Color(0xFFFF1744); else -> Color(0xFF2979FF) }
        val on = f <= v
        if (on) drawCircle(col.copy(alpha = .3f), d * .55f, Offset(x + d * (k + .5f), y))
        drawCircle(if (on) col else col.copy(alpha = .12f), d * .34f, Offset(x + d * (k + .5f), y))
    }
}

/** Spectrum bars in a box. */
private fun DrawScope.spectrum(r: Rect, s: VisState, top: Color, bottom: Color, n: Int = Visual.BANDS, gap: Float = .25f) {
    val bw = r.width / n; val step = Visual.BANDS.toFloat() / n
    for (k in 0 until n) {
        val v = s.disp[(k * step).toInt().coerceIn(0, Visual.BANDS - 1)].coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(listOf(top, bottom), r.top, r.bottom), Offset(r.left + k * bw + bw * gap / 2, r.bottom - r.height * v), Size(bw * (1 - gap), r.height * v))
    }
}

/** The waveform as a line across a box. */
private fun DrawScope.wave(r: Rect, s: VisState, col: Color, width: Float = 2.5f, glow: Boolean = true) {
    val p = Path(); val mid = r.center.y
    for (k in 0 until Visual.WAVE) { val x = r.left + r.width * k / (Visual.WAVE - 1); val y = mid - s.wdisp[k].coerceIn(-1f, 1f) * r.height * .48f; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
    if (glow) drawPath(p, col.copy(alpha = .25f), style = Stroke(width * 3.5f, cap = StrokeCap.Round))
    drawPath(p, col, style = Stroke(width, cap = StrokeCap.Round))
}

/** A rolling graph of the overall level over the last few seconds (from the spectrogram history). */
private fun DrawScope.history(r: Rect, s: VisState, col: Color, fill: Boolean = true) {
    val n = s.hist.size; val p = Path()
    for (i in 0 until n) {
        val row = s.hist[(s.hi + 1 + i) % n]; var sum = 0f; for (v in row) sum += v
        val v = (sum / row.size * 1.6f).coerceIn(0f, 1f)
        val x = r.left + r.width * i / (n - 1); val y = r.bottom - r.height * v
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    if (fill) { val f = Path().apply { addPath(p); lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close() }; drawPath(f, Brush.verticalGradient(listOf(col.copy(alpha = .35f), Color.Transparent), r.top, r.bottom)) }
    drawPath(p, col, style = Stroke(2.5f))
}

/** Moving tape (like a jet's speed / altitude tape) with a readout box in the middle. */
private fun DrawScope.tape(r: Rect, v: Float, col: Color, label: String, right: Boolean = false) {
    val value = v * 500
    clipRect(r.left, r.top, r.right, r.bottom) {
        val px = r.height / 120   // 120 units visible
        var u = ((value - 60) / 10).toInt() * 10f
        while (u < value + 70) {
            val y = r.center.y - (u - value) * px
            val big = u.toInt() % 50 == 0
            val x0 = if (right) r.left else r.right; val dir = if (right) 1 else -1
            drawLine(col, Offset(x0, y), Offset(x0 + dir * r.width * (if (big) .35f else .18f), y), 2f)
            if (big && u >= 0) txt("${u.toInt()}", x0 + dir * r.width * .65f, y + r.width * .1f, r.width * .26f, col, MONO)
            u += 10f
        }
    }
    val bh = r.width * .45f
    drawRect(Color.Black.copy(alpha = .6f), Offset(r.left, r.center.y - bh / 2), Size(r.width, bh))
    drawRect(col, Offset(r.left, r.center.y - bh / 2), Size(r.width, bh), style = Stroke(2f))
    txt("${value.toInt()}", r.center.x, r.center.y + bh * .28f, bh * .7f, col, MONO)
    txt(label, r.center.x, r.top - 6f, r.width * .22f, col.copy(alpha = .8f))
}

private fun DrawScope.frame(r: Rect, col: Color, rad: Float = 14f, fillCol: Color? = null) {
    fillCol?.let { drawRoundRect(it, r.topLeft, r.size, CornerRadius(rad)) }
    drawRoundRect(col, r.topLeft, r.size, CornerRadius(rad), style = Stroke(2f))
}

private fun pct(v: Float) = "${(v * 100).toInt().coerceIn(0, 99)}".padStart(2, ' ')
private fun DrawScope.portrait() = size.height > size.width * 1.15f

/** Lays [n] cells out in a grid that suits the screen's shape. */
private fun DrawScope.grid(n: Int, area: Rect = Rect(0f, 0f, size.width, size.height)): List<Rect> {
    var best = 1; var bestS = 0f
    for (cols in 1..n) { val rows = (n + cols - 1) / cols; val s = min(area.width / cols, area.height / rows); if (s > bestS) { bestS = s; best = cols } }
    val cols = best; val rows = (n + cols - 1) / cols; val cw = area.width / cols; val ch = area.height / rows
    return (0 until n).map { i -> val r = i / cols; val inRow = if (r == rows - 1) n - r * cols else cols; val off = (cols - inRow) * cw / 2
        Rect(area.left + off + (i % cols) * cw, area.top + r * ch, area.left + off + (i % cols + 1) * cw, area.top + (r + 1) * ch) }
}

// ================================================================ ten digital gauges

/** Amber LED rings: eight segmented arcs with seven-segment readouts. */
internal fun DrawScope.dgAmber(s: VisState) {
    drawRect(Color(0xFF0A0603), size = size)
    val b = s.eq(8)
    grid(8).forEachIndexed { i, cell ->
        val c = cell.center; val r = min(cell.width, cell.height) * .38f; val (v, l) = b[i]
        ledArc(c, r, v, 20, col = { f -> if (f > .8f) Color(0xFFFF3D00) else Color(0xFFFFAB00) }, off = .12f)
        seg7(pct(v), c.x, c.y - r * .22f, r * .45f, Color(0xFFFFAB00), .1f)
        txt(l, c.x, c.y + r * .62f, r * .2f, Color(0xFFFFAB00).copy(alpha = .8f))
    }
}

/** Green LCD: a classic reflective LCD with ghost segments, numbers and bar graphs. */
internal fun DrawScope.dgLcd(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFFB8C7A0), Color(0xFF93A57C))), size = size)
    val ink = Color(0xFF1E2A14)
    val b = s.eq(6)
    grid(6, Rect(w * .04f, h * .04f, w * .96f, h * .96f)).forEachIndexed { i, cell ->
        val (v, l) = b[i]; val pad = min(cell.width, cell.height) * .1f
        frame(Rect(cell.left + pad / 2, cell.top + pad / 2, cell.right - pad / 2, cell.bottom - pad / 2), ink.copy(alpha = .4f), 8f)
        val dh = cell.height * .32f
        seg7(pct(v), cell.center.x, cell.top + cell.height * .14f, dh, ink, .07f)
        ledBar(cell.left + pad * 1.4f, cell.top + cell.height * .58f, cell.width - pad * 2.8f, cell.height * .14f, v, 16, vertical = false, off = .08f) { ink }
        txt(l, cell.center.x, cell.top + cell.height * .9f, cell.height * .09f, ink, MONO)
    }
}

/** 80s stepped tach: rows of rising bars, one row per band, like an '80s digital dash. */
internal fun DrawScope.dgTach80(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF050505), size = size)
    val b = s.eq(10); val rows = b.size; val rh = h * .9f / rows; val x0 = w * .14f; val x1 = w * .86f; val n = 24
    b.forEachIndexed { i, (v, l) ->
        val y = h * .05f + i * rh
        for (k in 0 until n) {
            val f = (k + .5f) / n; val bh = rh * (.25f + .65f * f); val bw = (x1 - x0) / n
            val col = when { f > .85f -> Color(0xFFFF1744); f > .65f -> Color(0xFFFFC400); else -> Color(0xFF00E676) }
            drawRect(if (f <= v) col else col.copy(alpha = .08f), Offset(x0 + k * bw + bw * .15f, y + rh * .9f - bh), Size(bw * .7f, bh))
        }
        txt(l, x0 - w * .02f, y + rh * .8f, min(rh * .45f, w * .04f), Color(0xFF00E676), MONO, Paint.Align.RIGHT)
        txt(pct(v), x1 + w * .02f, y + rh * .8f, min(rh * .5f, w * .045f), Color(0xFFFFC400), MONO, Paint.Align.LEFT)
    }
}

/** Green HUD: phosphor-green head-up gauges with tick rings and digital numbers. */
internal fun DrawScope.dgHud(s: VisState) {
    drawRect(Color(0xFF020804), size = size)
    val g = Color(0xFF39FF88); val b = s.eq(4)
    grid(4).forEachIndexed { i, cell ->
        val c = cell.center; val r = min(cell.width, cell.height) * .4f; val (v, l) = b[i]
        for (k in 0..40) { val a = 135f + 270f * k / 40; val on = k / 40f <= v
            drawLine(if (on) g else g.copy(alpha = .15f), c.at(a, r * (if (k % 5 == 0) .78f else .85f)), c.at(a, r), if (k % 5 == 0) 3f else 1.5f) }
        drawCircle(g.copy(alpha = .5f), r * .62f, c, style = Stroke(1.5f))
        seg7("${(v * 300).toInt()}".padStart(3, ' '), c.x, c.y - r * .2f, r * .38f, g, .06f)
        txt(l, c.x, c.y + r * .45f, r * .16f, g)
        drawLine(g, c.at(135f + 270f * v, r * .66f), c.at(135f + 270f * v, r * 1.08f), 4f)
    }
}

/** Concentric rings: every band is one ring around the centre, bass inside, treble outside. */
internal fun DrawScope.dgRings(s: VisState) {
    drawRect(Color(0xFF05060C), size = size)
    val c = center; val R = min(size.width, size.height) * .46f; val b = s.eq(10)
    b.forEachIndexed { i, (v, _) ->
        val r = R * (.25f + .75f * i / (b.size - 1)); val col = heat(i / 9f, Color(0xFF00E5FF), Color(0xFF7C4DFF), Color(0xFFFF4081))
        ring(c, r, v, col, R * .028f, 270f, 360f)
    }
    seg7(pct(s.level.coerceIn(0f, .99f)), c.x, c.y - R * .1f, R * .2f, Color.White, .06f)
}

/** Shift lights: six rows of racing shift lights with a digital percentage. */
internal fun DrawScope.dgShift(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF15161A), Color(0xFF050506))), size = size)
    val b = s.eq(6); val rh = h / (b.size + .5f)
    b.forEachIndexed { i, (v, l) ->
        val y = rh * (i + .75f)
        txt(l, w * .03f, y + rh * .1f, min(rh * .26f, w * .04f), Color.White.copy(alpha = .7f), align = Paint.Align.LEFT)
        shift(w * .2f, y, w * .62f, v, 15)
        seg7(pct(v), w * .9f, y - rh * .2f, rh * .4f, Color(0xFFFF1744), .08f)
    }
}

/** Dot matrix: gauges made of LED dots. */
internal fun DrawScope.dgDots(s: VisState) {
    drawRect(Color(0xFF000000), size = size)
    val b = s.eq(6)
    grid(6).forEachIndexed { i, cell ->
        val c = cell.center; val r = min(cell.width, cell.height) * .4f; val (v, l) = b[i]
        for (ring in 0 until 4) { val rr = r * (.62f + ring * .12f); val n = 22 + ring * 4
            for (k in 0 until n) { val f = k / (n - 1f); val col = heat(f, Color(0xFF00B0FF), Color(0xFFD500F9), Color(0xFFFF1744))
                drawCircle(if (f <= v) col else col.copy(alpha = .1f), r * .035f, c.at(135f + 270f * f, rr)) } }
        seg7(pct(v), c.x, c.y - r * .2f, r * .36f, Color(0xFFD500F9), .08f)
        txt(l, c.x, c.y + r * .45f, r * .17f, Color.White.copy(alpha = .7f))
    }
}

/** VFD: a vacuum-fluorescent display — ten blue-green segmented columns with numbers above. */
internal fun DrawScope.dgVfd(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF02080A), size = size)
    val vfd = Color(0xFF3CF2E0); val b = s.eq(10); val cw = w / b.size
    b.forEachIndexed { i, (v, l) ->
        val x = i * cw
        seg7(pct(v), x + cw / 2, h * .05f, min(cw * .55f, h * .09f), vfd, .1f)
        ledBar(x + cw * .2f, h * .2f, cw * .6f, h * .68f, v, 22, off = .1f) { vfd }
        txt(l, x + cw / 2, h * .96f, min(cw * .3f, h * .04f), vfd.copy(alpha = .8f), MONO)
    }
}

/** Sci-fi: double arcs with chevrons inside hexagon frames. */
internal fun DrawScope.dgScifi(s: VisState) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF0A1630), Color(0xFF02040A))), size = size)
    val b = s.eq(4); val cy = Color(0xFF00E5FF); val pk = Color(0xFFFF2BD6)
    grid(4).forEachIndexed { i, cell ->
        val c = cell.center; val r = min(cell.width, cell.height) * .4f; val (v, l) = b[i]
        val hex = Path(); for (k in 0..6) { val p = c.at(60f * k + 30f, r * 1.1f); if (k == 0) hex.moveTo(p.x, p.y) else hex.lineTo(p.x, p.y) }
        drawPath(hex, cy.copy(alpha = .35f), style = Stroke(2f))
        ring(c, r * .82f, v, cy, r * .07f, 150f, 240f)
        ring(c, r * .64f, (v * v).coerceIn(0f, 1f), pk, r * .05f, 150f, 240f)
        for (k in 0 until 3) { val a = 150f + 240f * v - k * 6f; val p = c.at(a, r * .98f); drawCircle(cy.copy(alpha = 1f - k * .3f), r * .035f, p) }
        txt("${(v * 100).toInt()}", c.x, c.y + r * .14f, r * .42f, Color.White, LIGHT)
        txt(l, c.x, c.y + r * .42f, r * .14f, cy)
    }
}

/** Minimal: thin white arcs and big light numbers, like a modern EV. */
internal fun DrawScope.dgMinimal(s: VisState) {
    drawRect(Color(0xFF0D0F12), size = size)
    val b = s.eq(4)
    grid(4).forEachIndexed { i, cell ->
        val c = cell.center; val r = min(cell.width, cell.height) * .4f; val (v, l) = b[i]
        drawArc(Color.White.copy(alpha = .12f), 135f, 270f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .03f, cap = StrokeCap.Round))
        drawArc(Color.White, 135f, 270f * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .05f, cap = StrokeCap.Round))
        txt("${(v * 100).toInt()}", c.x, c.y + r * .18f, r * .55f, Color.White, LIGHT)
        txt(l.lowercase(), c.x, c.y + r * .5f, r * .14f, Color.White.copy(alpha = .5f), LIGHT)
    }
}

// ================================================================ ten full digital dashboards

/** Two areas side by side (landscape) or stacked (portrait); [f] = share of the first. */
private fun DrawScope.split(f: Float, area: Rect = Rect(0f, 0f, size.width, size.height), vertical: Boolean = portrait()): Pair<Rect, Rect> =
    if (vertical) Rect(area.left, area.top, area.right, area.top + area.height * f) to Rect(area.left, area.top + area.height * f, area.right, area.bottom)
    else Rect(area.left, area.top, area.left + area.width * f, area.bottom) to Rect(area.left + area.width * f, area.top, area.right, area.bottom)

private fun Rect.inset(d: Float) = Rect(left + d, top + d, right - d, bottom - d)
private fun Rect.r() = min(width, height) / 2

/** Supercar: shift lights, a big rev ring with digital speed, side fuel / temp bars and a spectrum strip. */
internal fun DrawScope.dbSupercar(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF111318), Color(0xFF000000))), size = size)
    val b = s.eq(6)
    shift(w * .1f, h * .05f, w * .8f, b[0].first, 18)
    val (main, low) = split(.72f, Rect(0f, h * .1f, w, h), vertical = true)
    val c = main.center; val r = min(main.width * .3f, main.height * .46f)
    ledArc(c, r, b[1].first, 36, 150f, 240f, r * .14f) { f -> if (f > .8f) Color(0xFFFF1744) else Color(0xFFFF6D00) }
    seg7("${(b[2].first * 320).toInt()}".padStart(3, ' '), c.x, c.y - r * .32f, r * .48f, Color.White, .06f)
    txt("KM/H · MID", c.x, c.y + r * .38f, r * .12f, Color.White.copy(alpha = .6f))
    txt("RPM · ${b[1].second}", c.x, c.y + r * .62f, r * .12f, Color(0xFFFF6D00))
    // side bars
    val bw = w * .05f; val bh = main.height * .7f; val by = main.top + main.height * .15f
    ledBar(w * .07f, by, bw, bh, b[4].first, 14) { f -> lerp(Color(0xFF00B0FF), Color(0xFFFF1744), f) }
    ledBar(w * .88f, by, bw, bh, b[5].first, 14) { f -> lerp(Color(0xFF00E676), Color(0xFFFFC400), f) }
    txt("TEMP", w * .07f + bw / 2, by + bh + h * .035f, h * .025f, Color.White.copy(alpha = .6f))
    txt("FUEL", w * .88f + bw / 2, by + bh + h * .035f, h * .025f, Color.White.copy(alpha = .6f))
    // gear (from the overall level)
    seg7("${1 + (s.level * 6).toInt().coerceIn(0, 6)}", w * (if (portrait()) .5f else .78f), main.top + main.height * (if (portrait()) .02f else .3f), r * .3f, Color(0xFFFF6D00), .06f)
    spectrum(low.inset(h * .02f), s, Color(0xFFFF6D00), Color(0xFF6A1B9A), 40)
}

/** EV: power meter, battery rings, range readout and a "road" made of the spectrum. */
internal fun DrawScope.dbEv(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF07101E), Color(0xFF0E1B33))), size = size)
    val b = s.eq(6); val blue = Color(0xFF3FC8F5); val purple = Color(0xFFB45CFF)
    val (top, road) = split(.6f, vertical = true)
    val p = portrait()
    val cells = if (p) listOf(Rect(0f, top.top, w / 2, top.bottom), Rect(w / 2, top.top, w, top.bottom)) else listOf(Rect(0f, top.top, w * .3f, top.bottom), Rect(w * .7f, top.top, w, top.bottom))
    ring(cells[0].center, cells[0].r() * .7f, b[1].first, blue); txt("${(b[1].first * 250).toInt()} kW", cells[0].center.x, cells[0].center.y + cells[0].r() * .12f, cells[0].r() * .26f, Color.White, LIGHT)
    txt("POWER · ${b[1].second}", cells[0].center.x, cells[0].center.y + cells[0].r() * .45f, cells[0].r() * .1f, blue)
    ring(cells[1].center, cells[1].r() * .7f, b[4].first, purple); txt("${(b[4].first * 100).toInt()}%", cells[1].center.x, cells[1].center.y + cells[1].r() * .12f, cells[1].r() * .26f, Color.White, LIGHT)
    txt("BATTERY · ${b[4].second}", cells[1].center.x, cells[1].center.y + cells[1].r() * .45f, cells[1].r() * .1f, purple)
    if (!p) {   // centre: speed + range
        txt("${(b[2].first * 180).toInt()}", w / 2, top.center.y, top.height * .3f, Color.White, LIGHT)
        txt("km/h", w / 2, top.center.y + top.height * .1f, top.height * .06f, Color.White.copy(alpha = .5f), LIGHT)
        txt("RANGE ${(200 + b[5].first * 300).toInt()} km", w / 2, top.center.y + top.height * .25f, top.height * .05f, blue)
    }
    // the road: spectrum in perspective
    val rd = road.inset(w * .02f); val n = 24
    for (k in 0 until n) {
        val v = s.disp[k * 2].coerceIn(0f, 1f); val y = rd.bottom - rd.height * (k + 1) / n; val half = rd.width * (.5f - .38f * k / n)
        drawLine(lerp(blue, purple, k / (n - 1f)).copy(alpha = .25f + .75f * v), Offset(rd.center.x - half * v, y), Offset(rd.center.x + half * v, y), rd.height / n * .6f)
    }
}

/** 80s: a curved stepped tach, LCD numbers and amber / green bar meters. */
internal fun DrawScope.db80s(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF040404), size = size)
    val b = s.eq(8); val grn = Color(0xFF00E676); val amb = Color(0xFFFFB300)
    val (top, bot) = split(.5f, vertical = true)
    // stepped tach across the top, bars rising along a curve
    val n = 40; val bw = top.width * .9f / n
    for (k in 0 until n) {
        val f = (k + .5f) / n; val bh = top.height * (.12f + .38f * f); val yb = top.bottom - top.height * .06f - top.height * .14f * (1 - (2 * f - 1) * (2 * f - 1))
        val col = if (f > .8f) Color(0xFFFF1744) else grn
        drawRect(if (f <= b[1].first) col else col.copy(alpha = .08f), Offset(top.left + top.width * .05f + k * bw + bw * .15f, yb - bh), Size(bw * .7f, bh))
    }
    val sh = min(top.height * .2f, w * .12f)
    seg7("${(b[2].first * 199).toInt()}".padStart(3, ' '), w * .5f, top.top + top.height * .04f, sh, amb, .08f)
    txt("MPH", w * .5f + sh * 1.2f, top.top + top.height * .04f + sh, sh * .3f, amb)
    // bar meters
    val cells = grid(6, bot.inset(w * .03f))
    listOf("OIL", "VOLT", "TEMP", "FUEL", "BOOST", "AIR").forEachIndexed { i, nm ->
        val cell = cells[i]; val v = b[(i + 2).coerceAtMost(7)].first
        ledBar(cell.left + cell.width * .1f, cell.center.y - cell.height * .12f, cell.width * .8f, cell.height * .22f, v, 12, vertical = false, off = .08f) { f -> if (f > .75f) amb else grn }
        val ts = min(cell.height * .14f, cell.width * .1f)
        txt(nm, cell.left + cell.width * .1f, cell.center.y - cell.height * .2f, ts, grn, MONO, Paint.Align.LEFT)
        txt(pct(v), cell.right - cell.width * .1f, cell.center.y - cell.height * .2f, ts * 1.1f, amb, MONO, Paint.Align.RIGHT)
    }
}

/** Race wheel: shift lights, gear, delta bars and a telemetry trace. */
internal fun DrawScope.dbRace(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0B0B0D), size = size)
    val b = s.eq(6)
    val scr = Rect(w * .05f, h * .12f, w * .95f, h * .96f)
    shift(w * .1f, h * .06f, w * .8f, b[1].first, 15)
    frame(scr, Color(0xFF3A3D45), 18f, Color(0xFF15171C))
    val (l, r) = split(.5f, scr.inset(w * .02f))
    seg7("${1 + (b[0].first * 7).toInt().coerceIn(0, 7)}", l.center.x, l.top + l.height * .08f, l.height * .5f, Color(0xFFFFEA00), .06f)
    txt("GEAR", l.center.x, l.top + l.height * .7f, l.height * .07f, Color.White.copy(alpha = .6f))
    txt("${(b[2].first * 340).toInt()} km/h", l.center.x, l.top + l.height * .86f, l.height * .1f, Color.White, MONO)
    // delta bars (+ green / - red) from mids vs highs
    val bars = r.inset(r.width * .04f)
    val rows = listOf(b[2], b[3], b[4], b[5]); val rh = bars.height * .5f / rows.size
    rows.forEachIndexed { i, (v, nm) ->
        val y = bars.top + i * rh; val d = (v - .4f).coerceIn(-.5f, .5f) * 2
        drawRect(Color.White.copy(alpha = .08f), Offset(bars.left, y + rh * .2f), Size(bars.width, rh * .6f))
        drawRect(if (d >= 0) Color(0xFF00E676) else Color(0xFFFF1744), Offset(bars.center.x + min(0f, d) * bars.width / 2, y + rh * .2f), Size(abs(d) * bars.width / 2, rh * .6f))
        txt(nm, bars.left + 6f, y + rh * .65f, rh * .4f, Color.White, align = Paint.Align.LEFT)
    }
    history(Rect(bars.left, bars.top + bars.height * .58f, bars.right, bars.bottom), s, Color(0xFF00E5FF))
    wave(Rect(bars.left, bars.top + bars.height * .58f, bars.right, bars.bottom), s, Color(0xFFFFEA00), 2f, false)
}

/** Fighter-jet HUD: speed tape (bass), altitude tape (treble), heading, a pitch ladder riding the waveform. */
internal fun DrawScope.dbJet(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1A2E), Color(0xFF1E2E1A))), size = size)
    val g = Color(0xFF39FF88); val b = s.eq(5)
    val tw = w * .14f
    tape(Rect(w * .03f, h * .15f, w * .03f + tw, h * .85f), b[0].first, g, "SPD")
    tape(Rect(w * .97f - tw, h * .15f, w * .97f, h * .85f), b[4].first, g, "ALT", right = true)
    // heading tape
    val hd = (b[2].first * 360); clipRect(w * .25f, 0f, w * .75f, h * .1f) {
        var k = ((hd - 60) / 10).toInt() * 10
        while (k < hd + 60) { val x = w / 2 + (k - hd) * w * .5f / 120; drawLine(g, Offset(x, h * .08f), Offset(x, h * (if (k % 30 == 0) .04f else .06f)), 2f)
            if (k % 30 == 0) txt("${((k % 360) + 360) % 360 / 10}", x, h * .035f, h * .03f, g, MONO); k += 10 } }
    // pitch ladder tilted by the bass / treble balance, moved by the mids
    val roll = (b[1].first - b[3].first) * 40f; val pitch = (b[2].first - .4f) * h * .3f
    val c = Offset(w / 2, h / 2)
    clipRect(w * .2f, h * .12f, w * .8f, h * .88f) {
        rotate(roll, c) {
            for (k in -3..3) { val y = c.y + pitch + k * h * .12f; val hw = w * (if (k == 0) .28f else .14f)
                if (k == 0) drawLine(g, Offset(c.x - hw, y), Offset(c.x + hw, y), 3f)
                else { drawLine(g, Offset(c.x - hw, y), Offset(c.x - hw * .3f, y), 2f); drawLine(g, Offset(c.x + hw * .3f, y), Offset(c.x + hw, y), 2f)
                    txt("${-k * 10}", c.x - hw - 20f, y + 6f, h * .025f, g, MONO) } }
        }
    }
    // flight path marker + waveform along the bottom
    drawCircle(g, w * .02f, c, style = Stroke(2.5f)); drawLine(g, Offset(c.x - w * .05f, c.y), Offset(c.x - w * .02f, c.y), 2.5f); drawLine(g, Offset(c.x + w * .02f, c.y), Offset(c.x + w * .05f, c.y), 2.5f)
    wave(Rect(w * .22f, h * .86f, w * .78f, h * .98f), s, g, 2f)
    txt("G ${"%.1f".format(1 + s.level * 8)}", w * .22f, h * .14f, h * .03f, g, MONO, Paint.Align.LEFT)
}

/** Big rig: two big digital gauges and six small ones. */
internal fun DrawScope.dbTruck(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1C1F24), Color(0xFF0A0B0D))), size = size)
    val b = s.eq(8); val amber = Color(0xFFFFB300)
    val (big, small) = split(.55f, vertical = true)
    val bc = grid(2, big)
    listOf(1 to "RPM", 2 to "MPH").forEachIndexed { i, (bi, nm) ->
        val c = bc[i].center; val r = bc[i].r() * .82f
        ledArc(c, r, b[bi].first, 30, thick = r * .12f) { f -> if (f > .8f) Color(0xFFFF1744) else amber }
        seg7("${(b[bi].first * (if (i == 0) 30 else 99)).toInt()}".padStart(2, ' '), c.x, c.y - r * .25f, r * .42f, Color.White, .06f)
        txt("$nm · ${b[bi].second}", c.x, c.y + r * .5f, r * .13f, amber)
    }
    val names = listOf("OIL", "WATER", "AIR 1", "AIR 2", "FUEL", "VOLTS")
    grid(6, small.inset(w * .02f)).forEachIndexed { i, cell ->
        val c = cell.center; val r = cell.r() * .7f; val v = b[if (i == 0) 0 else (i + 2).coerceAtMost(7)].first
        ledArc(c, r, v, 16, 180f, 180f, r * .2f) { f -> if (f > .8f) Color(0xFFFF1744) else Color(0xFF00E676) }
        txt(pct(v).trim(), c.x, c.y - r * .05f, r * .45f, Color.White, MONO)
        txt(names[i], c.x, c.y + r * .38f, r * .22f, Color.White.copy(alpha = .6f))
    }
}

/** Cyberpunk: neon frames, glitching spectrum, hex readouts. */
internal fun DrawScope.dbCyber(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0A0012), size = size)
    val pk = Color(0xFFFF2A6D); val cy = Color(0xFF05D9E8); val yl = Color(0xFFFCEE0A); val b = s.eq(6)
    var y = 0f; while (y < h) { drawLine(cy.copy(alpha = .05f), Offset(0f, y), Offset(w, y), 1f); y += 6f }   // scanlines
    val (top, bot) = split(.55f, vertical = true)
    grid(3, top.inset(w * .02f)).forEachIndexed { i, cell ->
        val c = cell.center; val r = cell.r() * .78f; val (v, l) = b[i * 2 + 1]
        val sq = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
        drawPath(Path().apply { moveTo(sq.left + r * .3f, sq.top); lineTo(sq.right, sq.top); lineTo(sq.right, sq.bottom - r * .3f); lineTo(sq.right - r * .3f, sq.bottom); lineTo(sq.left, sq.bottom); lineTo(sq.left, sq.top + r * .3f); close() }, pk, style = Stroke(2.5f))
        ledArc(c, r * .7f, v, 18, col = { f -> if (f > .75f) yl else cy })
        txt("0x" + (v * 255).toInt().toString(16).uppercase().padStart(2, '0'), c.x, c.y + r * .12f, r * .32f, yl, MONO)
        txt(l, c.x, c.y + r * .5f, r * .14f, pk, MONO)
    }
    val sp = bot.inset(w * .03f)
    val glitch = if (s.level > .6f) (s.spin * 37 % 1f) * 12f else 0f
    spectrum(Rect(sp.left + glitch, sp.top, sp.right + glitch, sp.bottom), s, pk, cy, 32)
    wave(sp, s, yl, 2f)
    frame(sp, cy.copy(alpha = .6f), 2f)
}

/** Luxury sedan: soft blue rings either side and a centre screen with the waveform and track info style readouts. */
internal fun DrawScope.dbLuxury(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.radialGradient(listOf(Color(0xFF1A2440), Color(0xFF070A12)), center, maxOf(w, h)), size = size)
    val b = s.eq(6); val ice = Color(0xFF9FD8FF)
    val p = portrait()
    val (ra, rb, mid) = if (p) Triple(Rect(0f, 0f, w / 2, h * .4f), Rect(w / 2, 0f, w, h * .4f), Rect(w * .06f, h * .44f, w * .94f, h * .95f))
        else Triple(Rect(0f, 0f, w * .32f, h), Rect(w * .68f, 0f, w, h), Rect(w * .34f, h * .2f, w * .66f, h * .8f))
    for ((rect, bi) in listOf(ra to 1, rb to 3)) {
        val c = rect.center; val r = rect.r() * .78f; val v = b[bi].first
        for (k in 0..60) { val a = 135f + 270f * k / 60; drawLine(if (k / 60f <= v) ice else ice.copy(alpha = .15f), c.at(a, r * .88f), c.at(a, r * (if (k % 10 == 0) .74f else .82f)), if (k % 10 == 0) 2.5f else 1.2f) }
        ring(c, r * .62f, v, ice, r * .025f)
        txt("${(v * (if (bi == 1) 8000 else 260)).toInt()}", c.x, c.y + r * .1f, r * .3f, Color.White, LIGHT)
        txt(if (bi == 1) "rpm · ${b[bi].second.lowercase()}" else "km/h · ${b[bi].second.lowercase()}", c.x, c.y + r * .32f, r * .1f, ice, LIGHT)
    }
    frame(mid, ice.copy(alpha = .35f), 22f, Color(0x33000000))
    val inner = mid.inset(mid.width * .06f)
    history(Rect(inner.left, inner.top, inner.right, inner.top + inner.height * .45f), s, ice)
    wave(Rect(inner.left, inner.top + inner.height * .5f, inner.right, inner.top + inner.height * .8f), s, Color.White, 2f)
    val sb = listOf(b[0], b[2], b[4], b[5]); val sw = inner.width / sb.size
    sb.forEachIndexed { i, (v, l) -> txt("$l ${(v * 100).toInt()}", inner.left + sw * (i + .5f), inner.bottom - 4f, min(inner.height * .07f, sw * .14f), ice, LIGHT) }
}

/** Rally: amber LCD, a g-force dot (bass vs treble, mids), boost gauge and stage bars. */
internal fun DrawScope.dbRally(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0E0A04), size = size)
    val amb = Color(0xFFFFB300); val b = s.eq(6)
    val cells = grid(3)
    // g-force
    val c0 = cells[0].center; val r0 = cells[0].r() * .78f
    for (k in 1..3) drawCircle(amb.copy(alpha = .25f), r0 * k / 3, c0, style = Stroke(1.5f))
    drawLine(amb.copy(alpha = .25f), Offset(c0.x - r0, c0.y), Offset(c0.x + r0, c0.y), 1.5f); drawLine(amb.copy(alpha = .25f), Offset(c0.x, c0.y - r0), Offset(c0.x, c0.y + r0), 1.5f)
    val gx = (b[4].first - b[0].first) * r0; val gy = (b[2].first - .3f) * r0
    drawCircle(amb.copy(alpha = .3f), r0 * .12f, Offset(c0.x + gx, c0.y - gy)); drawCircle(amb, r0 * .06f, Offset(c0.x + gx, c0.y - gy))
    txt("G-FORCE  ${"%.2f".format(sqrt(gx * gx + gy * gy) / r0 * 2)}g", c0.x, c0.y + r0 * 1.15f, r0 * .12f, amb, MONO)
    // boost
    val c1 = cells[1].center; val r1 = cells[1].r() * .75f
    ledArc(c1, r1, b[1].first, 26, col = { f -> if (f > .8f) Color(0xFFFF3D00) else amb })
    seg7("%.1f".format(b[1].first * 2.5f).replace(".", ""), c1.x, c1.y - r1 * .25f, r1 * .4f, amb, .08f)
    txt("BOOST bar · ${b[1].second}", c1.x, c1.y + r1 * .45f, r1 * .12f, amb)
    // stage bars
    val r2 = cells[2].inset(cells[2].width * .1f); val rows = b.size; val rh = r2.height / rows
    b.forEachIndexed { i, (v, l) ->
        txt(l, r2.left, r2.top + rh * (i + .7f), min(rh * .35f, r2.width * .065f), amb, MONO, Paint.Align.LEFT)
        ledBar(r2.left + r2.width * .3f, r2.top + rh * (i + .25f), r2.width * .7f, rh * .5f, v, 14, vertical = false, off = .08f) { f -> if (f > .8f) Color(0xFFFF3D00) else amb }
    }
}

/** Spaceship console: radar sweep with band blips, reactor rings and a spectrum + wave strip. */
internal fun DrawScope.dbSpace(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.radialGradient(listOf(Color(0xFF0B1830), Color(0xFF000004)), center, maxOf(w, h)), size = size)
    val cy = Color(0xFF64FFDA); val b = s.eq(10)
    val (top, bot) = split(.68f, vertical = true)
    val (radar, reactors) = split(.5f, top)
    // radar
    val c = radar.center; val r = radar.r() * .85f
    for (k in 1..4) drawCircle(cy.copy(alpha = .2f), r * k / 4, c, style = Stroke(1.5f))
    val sweep = (s.spin * 120f) % 360f
    drawArc(Brush.sweepGradient(listOf(Color.Transparent, cy.copy(alpha = .35f)), c), sweep - 60f, 60f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
    drawLine(cy, c, c.at(sweep, r), 2f)
    b.forEachIndexed { i, (v, _) -> val p = c.at(i * 36f + 10f, r * (1 - v * .85f)); drawCircle(cy.copy(alpha = .3f + .7f * v), r * (.02f + .04f * v), p) }
    // reactors
    grid(4, reactors).forEachIndexed { i, cell ->
        val cc = cell.center; val rr = cell.r() * .7f; val (v, l) = b[i * 2 + 1]
        val col = listOf(cy, Color(0xFF7C4DFF), Color(0xFFFF4081), Color(0xFFFFD740))[i]
        ring(cc, rr, v, col, rr * .1f, 270f, 360f)
        ring(cc, rr * .7f, (v * 1.3f).coerceAtMost(1f), col.copy(alpha = .6f), rr * .06f, 90f, 360f)
        txt("${(v * 100).toInt()}%", cc.x, cc.y + rr * .12f, rr * .32f, Color.White, LIGHT)
        txt(l, cc.x, cc.y + rr * 1.25f, rr * .18f, col)
    }
    val sp = bot.inset(w * .03f)
    frame(sp, cy.copy(alpha = .4f), 10f)
    spectrum(sp.inset(6f), s, cy, Color(0xFF7C4DFF), 48)
    wave(sp.inset(6f), s, Color.White, 1.8f)
}

// ================================================================ ten more digital gauges

/** Supercar tachs: a mini supercar rev counter per band (segmented sweep, digital value, shift dots). */
internal fun DrawScope.dgSuperTach(s: VisState) {
    drawRect(Color(0xFF07080A), size = size)
    val b = s.eq(6)
    grid(6).forEachIndexed { i, cell ->
        val c = cell.center; val r = cell.r() * .78f; val (v, l) = b[i]
        ledArc(c, r, v, 30, 150f, 240f, r * .16f) { f -> if (f > .8f) Color(0xFFFF1744) else Color(0xFFFFC400) }
        seg7(pct(v), c.x, c.y - r * .28f, r * .42f, Color.White, .06f)
        txt(l, c.x, c.y + r * .38f, r * .16f, Color(0xFFFFC400))
        shift(c.x - r * .6f, c.y + r * .62f, r * 1.2f, v, 7)
    }
}

/** Race deltas: each band as a +/- delta bar against its average, with digital readouts. */
internal fun DrawScope.dgDelta(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0B0C0F), size = size)
    val b = s.eq(8); val rh = h / (b.size + .6f)
    drawLine(Color.White.copy(alpha = .3f), Offset(w * .55f, rh * .3f), Offset(w * .55f, h - rh * .3f), 2f)
    b.forEachIndexed { i, (v, l) ->
        val y = rh * (i + .5f); val d = ((v - .45f) * 2.2f).coerceIn(-1f, 1f); val bw = w * .26f
        drawRect(Color.White.copy(alpha = .06f), Offset(w * .17f, y), Size(w * .76f, rh * .6f))
        drawRect(if (d >= 0) Color(0xFF00E676) else Color(0xFFFF1744), Offset(w * .55f + min(0f, d) * bw, y + rh * .08f), Size(abs(d) * bw, rh * .44f))
        txt(l, w * .15f, y + rh * .48f, min(rh * .4f, w * .04f), Color.White.copy(alpha = .8f), align = Paint.Align.RIGHT)
        txt((if (d >= 0) "+" else "−") + "%.2f".format(abs(d)), w * .97f, y + rh * .48f, min(rh * .4f, w * .04f), if (d >= 0) Color(0xFF00E676) else Color(0xFFFF1744), MONO, Paint.Align.RIGHT)
    }
}

/** Gear indicators: big seven-segment gears (1–8 by level) with shift lights over each. */
internal fun DrawScope.dgGears(s: VisState) {
    drawRect(Color(0xFF050505), size = size)
    val b = s.eq(6)
    grid(6).forEachIndexed { i, cell ->
        val (v, l) = b[i]; val c = cell.center; val hh = min(cell.height * .5f, cell.width * .7f)
        shift(cell.left + cell.width * .1f, cell.top + cell.height * .14f, cell.width * .8f, v, 9)
        seg7("${1 + (v * 7).toInt().coerceIn(0, 7)}", c.x, c.y - hh * .38f, hh, if (v > .8f) Color(0xFFFF1744) else Color(0xFFFFEA00), .06f)
        txt(l, c.x, cell.bottom - cell.height * .08f, min(cell.height * .08f, cell.width * .1f), Color.White.copy(alpha = .7f))
    }
}

/** Peak bargraph: tall segmented columns with peak-hold markers. */
internal fun DrawScope.dgPeak(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF020306), size = size)
    val n = 16; val cw = w / n; val step = Visual.BANDS / n
    for (k in 0 until n) {
        val v = s.disp[k * step].coerceIn(0f, 1f); val pk = s.peak[k * step].coerceIn(0f, 1f)
        ledBar(k * cw + cw * .15f, h * .06f, cw * .7f, h * .86f, v, 28, off = .07f)
        val py = h * .06f + h * .86f * (1 - pk); drawRect(Color.White, Offset(k * cw + cw * .15f, py), Size(cw * .7f, h * .012f))
    }
}

/** Radial sweep: ten band sectors around a centre readout, like a sports-car centre display. */
internal fun DrawScope.dgRadial(s: VisState) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF14161C), Color(0xFF020203)), center, maxOf(size.width, size.height)), size = size)
    val b = s.eq(10); val c = center; val R = min(size.width, size.height) * .46f
    b.forEachIndexed { i, (v, l) ->
        val a0 = -90f + 36f * i
        for (k in 0 until 10) { val f = (k + .5f) / 10; val r = R * (.35f + .6f * f); val col = heat(f, Color(0xFF00E5FF), Color(0xFFFFC400), Color(0xFFFF1744))
            drawArc(if (f <= v) col else col.copy(alpha = .07f), a0 + 2f, 32f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(R * .045f)) }
        val p = c.at(a0 + 18f, R * 1.02f); txt(l, p.x, p.y + R * .02f, R * .06f, Color.White.copy(alpha = .7f))
    }
    seg7(pct(s.level.coerceIn(0f, .99f)), c.x, c.y - R * .12f, R * .24f, Color.White, .06f)
}

/** OLED: thin white bars and numbers on pure black. */
internal fun DrawScope.dgOled(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color.Black, size = size)
    val b = s.eq(10); val rh = h / (b.size + 1)
    b.forEachIndexed { i, (v, l) ->
        val y = rh * (i + 1)
        txt(l, w * .12f, y + rh * .2f, min(rh * .38f, w * .04f), Color.White.copy(alpha = .6f), LIGHT, Paint.Align.RIGHT)
        drawLine(Color.White.copy(alpha = .12f), Offset(w * .15f, y), Offset(w * .85f, y), rh * .12f, StrokeCap.Round)
        drawLine(Color.White, Offset(w * .15f, y), Offset(w * .15f + w * .7f * v.coerceAtLeast(.01f), y), rh * .12f, StrokeCap.Round)
        txt("${(v * 100).toInt()}", w * .95f, y + rh * .2f, min(rh * .45f, w * .045f), Color.White, LIGHT, Paint.Align.RIGHT)
    }
}

/** Nixie tubes: glowing orange digits in glass tubes. */
internal fun DrawScope.dgNixie(s: VisState) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1A120B), Color(0xFF0A0603))), size = size)
    val b = s.eq(6); val glow = Color(0xFFFF8A1F)
    grid(6).forEachIndexed { i, cell ->
        val (v, l) = b[i]; val tw = min(cell.width * .36f, cell.height * .3f); val th = tw * 1.8f; val c = cell.center
        for (d in 0..1) {
            val x = c.x + (d - .5f) * tw * 1.15f
            val tube = Rect(x - tw / 2, c.y - th / 2, x + tw / 2, c.y + th / 2)
            drawRoundRect(Color(0x33FFD9B0), tube.topLeft, tube.size, CornerRadius(tw / 2), style = Stroke(2f))
            drawRoundRect(Color(0x14FF8A1F), tube.topLeft, tube.size, CornerRadius(tw / 2))
            val digit = pct(v).padStart(2, '0')[d].toString()
            txt(digit, x, c.y + th * .22f, th * .62f, glow.copy(alpha = .35f), LIGHT); txt(digit, x, c.y + th * .22f, th * .6f, Color(0xFFFFC27A), LIGHT)
        }
        txt(l, c.x, c.y + th * .5f + cell.height * .1f, min(cell.height * .08f, cell.width * .09f), glow)
    }
}

/** Amber dot-matrix columns. */
internal fun DrawScope.dgDotAmber(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0B0702), size = size)
    val cols = 24; val rows = 18; val cw = w / cols; val rh = h / rows; val step = Visual.BANDS / cols
    for (k in 0 until cols) { val v = s.disp[k * step].coerceIn(0f, 1f)
        for (r in 0 until rows) { val f = (r + .5f) / rows; drawCircle(if (f <= v) Color(0xFFFFB300) else Color(0x14FFB300), min(cw, rh) * .34f, Offset(cw * (k + .5f), h - rh * (r + .5f))) } }
}

/** Hex meters: honeycomb cells lighting up per band. */
internal fun DrawScope.dgHex(s: VisState) {
    drawRect(Color(0xFF04070C), size = size)
    val b = s.eq(6)
    grid(6).forEachIndexed { i, cell ->
        val (v, l) = b[i]; val c = cell.center; val R = cell.r() * .2f
        val ring = listOf(Offset(0f, 0f)) + (0 until 6).map { Offset(0f, 0f).at(60f * it - 90f, R * 1.75f) } + (0 until 12).map { Offset(0f, 0f).at(30f * it - 90f, R * (if (it % 2 == 0) 3.5f else 3.03f)) }
        ring.forEachIndexed { k, o ->
            val f = (k + .5f) / ring.size; val col = heat(f, Color(0xFF00E5FF), Color(0xFF00E676), Color(0xFFFF1744))
            val p = Path(); for (q in 0..6) { val pt = Offset(c.x + o.x, c.y + o.y).at(60f * q, R * .92f); if (q == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }
            drawPath(p, if (f <= v) col else col.copy(alpha = .08f))
        }
        txt(l, c.x, cell.bottom - cell.height * .03f, min(cell.height * .07f, cell.width * .08f), Color.White.copy(alpha = .7f))
    }
}

/** Split rings: mirrored half-circle meters (top = band, bottom = its peak). */
internal fun DrawScope.dgSplit(s: VisState) {
    drawRect(Color(0xFF0A0A10), size = size)
    val b = s.eq(4); val pk = s.eq(4)
    grid(4).forEachIndexed { i, cell ->
        val c = cell.center; val r = cell.r() * .75f; val (v, l) = b[i]
        ledArc(c, r, v, 18, 180f, 180f, r * .18f) { f -> lerp(Color(0xFF00E5FF), Color(0xFFFF2BD6), f) }
        ledArc(c, r, (pk[i].first * .9f + s.level * .1f).coerceIn(0f, 1f), 18, 0f, 180f, r * .18f) { f -> lerp(Color(0xFFFF2BD6), Color(0xFF00E5FF), f) }
        seg7(pct(v), c.x, c.y - r * .18f, r * .36f, Color.White, .05f)
        txt(l, c.x, c.y + r * .45f, r * .14f, Color.White.copy(alpha = .7f))
    }
}

// ================================================================ ten more digital dashboards

/** Hypercar: centre tach ring with huge gear, speed, lap timer, G-meter and shift lights. */
internal fun DrawScope.dbHyper(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF101114), Color(0xFF000000))), size = size)
    val b = s.eq(6); val gold = Color(0xFFFFC400)
    shift(w * .08f, h * .05f, w * .84f, b[0].first, 21)
    val p = portrait()
    val c = Offset(w / 2, if (p) h * .36f else h * .55f); val r = if (p) w * .38f else min(h * .4f, w * .2f)
    ledArc(c, r, b[1].first, 48, 135f, 270f, r * .1f) { f -> if (f > .85f) Color(0xFFFF1744) else gold }
    for (k in 0..9) { val a = 135f + 27f * k; txt("$k", c.at(a, r * .78f).x, c.at(a, r * .78f).y + r * .05f, r * .1f, Color.White.copy(alpha = .7f)) }
    seg7("${1 + (b[1].first * 7).toInt().coerceIn(0, 7)}", c.x, c.y - r * .4f, r * .55f, gold, .05f)
    txt("${(b[2].first * 380).toInt()} km/h", c.x, c.y + r * .4f, r * .16f, Color.White, MONO)
    // side panels
    val (lp, rp) = if (p) Rect(w * .04f, h * .66f, w * .48f, h * .96f) to Rect(w * .52f, h * .66f, w * .96f, h * .96f)
        else Rect(w * .02f, h * .2f, w * .27f, h * .9f) to Rect(w * .73f, h * .2f, w * .98f, h * .9f)
    frame(lp, Color.White.copy(alpha = .15f), 12f)
    val lap = (s.spin * 1000).toLong() % 120_000
    txt("LAP", lp.left + lp.width * .08f, lp.top + lp.height * .15f, lp.height * .08f, gold, align = Paint.Align.LEFT)
    txt("%d:%02d.%03d".format(lap / 60000, lap / 1000 % 60, lap % 1000), lp.center.x, lp.top + lp.height * .38f, min(lp.height * .16f, lp.width * .16f), Color.White, MONO)
    history(Rect(lp.left + lp.width * .06f, lp.top + lp.height * .5f, lp.right - lp.width * .06f, lp.bottom - lp.height * .08f), s, gold)
    frame(rp, Color.White.copy(alpha = .15f), 12f)
    val gc = rp.center; val gr = rp.r() * .7f
    for (k in 1..2) drawCircle(Color.White.copy(alpha = .2f), gr * k / 2, gc, style = Stroke(1.5f))
    val g = Offset(gc.x + (b[4].first - b[0].first) * gr, gc.y - (b[3].first - .3f) * gr); drawCircle(gold, gr * .1f, g)
    txt("G", rp.left + rp.width * .08f, rp.top + rp.height * .12f, rp.height * .08f, gold, align = Paint.Align.LEFT)
}

/** F1 steering wheel: grips, coloured rotaries, rev LEDs and the central screen. */
internal fun DrawScope.dbF1(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0A0A0B), size = size)
    val b = s.eq(6)
    val wheel = Rect(w * .03f, h * .12f, w * .97f, h * .92f)
    val body = Path().apply {
        moveTo(wheel.left + wheel.width * .12f, wheel.top); lineTo(wheel.right - wheel.width * .12f, wheel.top)
        quadraticTo(wheel.right, wheel.top, wheel.right, wheel.top + wheel.height * .35f); lineTo(wheel.right - wheel.width * .04f, wheel.bottom)
        lineTo(wheel.left + wheel.width * .04f, wheel.bottom); lineTo(wheel.left, wheel.top + wheel.height * .35f)
        quadraticTo(wheel.left, wheel.top, wheel.left + wheel.width * .12f, wheel.top); close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color(0xFF26282C), Color(0xFF111214)), wheel.top, wheel.bottom))
    drawRoundRect(Color(0xFF1B1C1F), Offset(wheel.left - w * .01f, wheel.top + wheel.height * .15f), Size(wheel.width * .1f, wheel.height * .7f), CornerRadius(30f))
    drawRoundRect(Color(0xFF1B1C1F), Offset(wheel.right - wheel.width * .09f, wheel.top + wheel.height * .15f), Size(wheel.width * .1f, wheel.height * .7f), CornerRadius(30f))
    shift(wheel.left + wheel.width * .18f, wheel.top + wheel.height * .07f, wheel.width * .64f, b[1].first, 15)
    val scr = Rect(wheel.left + wheel.width * .25f, wheel.top + wheel.height * .14f, wheel.right - wheel.width * .25f, wheel.top + wheel.height * .58f)
    frame(scr, Color(0xFF3A3D45), 10f, Color(0xFF000000))
    seg7("${1 + (b[0].first * 7).toInt().coerceIn(0, 7)}", scr.center.x, scr.top + scr.height * .12f, scr.height * .5f, Color.White, .05f)
    txt("${(b[2].first * 340).toInt()}", scr.left + scr.width * .14f, scr.top + scr.height * .4f, min(scr.height * .2f, scr.width * .1f), Color(0xFF00E676), MONO)
    txt("ERS", scr.left + scr.width * .14f, scr.top + scr.height * .58f, scr.height * .09f, Color.White.copy(alpha = .6f))
    ledBar(scr.right - scr.width * .2f, scr.top + scr.height * .12f, scr.width * .1f, scr.height * .6f, b[3].first, 10) { Color(0xFFFFEA00) }
    ledBar(scr.left + scr.width * .06f, scr.bottom - scr.height * .18f, scr.width * .88f, scr.height * .1f, b[4].first, 24, vertical = false) { f -> lerp(Color(0xFF2979FF), Color(0xFFFF1744), f) }
    // rotaries and buttons
    val knobs = listOf(Color(0xFFFF1744), Color(0xFFFFEA00), Color(0xFF00E676), Color(0xFF2979FF), Color(0xFFFF9100), Color(0xFFE040FB))
    knobs.forEachIndexed { i, col ->
        val c = Offset(wheel.left + wheel.width * (.22f + (i % 3) * .28f), wheel.top + wheel.height * (.7f + (i / 3) * .15f)); val r = min(wheel.width * .04f, wheel.height * .06f)
        drawCircle(Color(0xFF2E3035), r * 1.25f, c); drawCircle(col, r, c)
        drawLine(Color.White, c, c.at(-135f + 270f * b[i].first, r * .9f), r * .18f, StrokeCap.Round)
    }
}

/** GT3 dash: full-width tach bar, gear, delta, tyre temperatures and a speed readout. */
internal fun DrawScope.dbGt3(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF000000), size = size)
    val b = s.eq(8)
    ledBar(w * .03f, h * .04f, w * .94f, h * .1f, b[1].first, 40, vertical = false, off = .1f) { f -> when { f > .9f -> Color(0xFF2979FF); f > .75f -> Color(0xFFFF1744); f > .5f -> Color(0xFFFFEA00); else -> Color(0xFF00E676) } }
    val p = portrait()
    val gearR = if (p) Rect(w * .3f, h * .18f, w * .7f, h * .45f) else Rect(w * .4f, h * .2f, w * .6f, h * .75f)
    frame(gearR, Color.White.copy(alpha = .4f), 8f)
    seg7("${1 + (b[0].first * 6).toInt().coerceIn(0, 6)}", gearR.center.x, gearR.top + gearR.height * .1f, gearR.height * .65f, Color.White, .05f)
    txt("${(b[2].first * 290).toInt()}", gearR.center.x, gearR.bottom - gearR.height * .05f, gearR.height * .14f, Color(0xFF00E676), MONO)
    // delta + tyres
    val left = if (p) Rect(w * .04f, h * .5f, w * .48f, h * .95f) else Rect(w * .03f, h * .2f, w * .37f, h * .95f)
    val right = if (p) Rect(w * .52f, h * .5f, w * .96f, h * .95f) else Rect(w * .63f, h * .2f, w * .97f, h * .95f)
    val d = (b[3].first - b[4].first) * 2
    frame(left, Color.White.copy(alpha = .2f), 8f)
    txt("DELTA", left.left + 10f, left.top + left.height * .12f, left.height * .07f, Color.White.copy(alpha = .6f), align = Paint.Align.LEFT)
    txt((if (d >= 0) "+" else "−") + "%.3f".format(abs(d)), left.center.x, left.top + left.height * .35f, min(left.height * .16f, left.width * .17f), if (d <= 0) Color(0xFF00E676) else Color(0xFFFF1744), MONO)
    history(Rect(left.left + 8f, left.top + left.height * .48f, left.right - 8f, left.bottom - 8f), s, Color(0xFF00E5FF))
    frame(right, Color.White.copy(alpha = .2f), 8f)
    txt("TYRES °C", right.left + 10f, right.top + right.height * .12f, right.height * .07f, Color.White.copy(alpha = .6f), align = Paint.Align.LEFT)
    for (t in 0 until 4) {
        val v = b[4 + t].first; val cell = Rect(right.left + right.width * (.12f + (t % 2) * .44f), right.top + right.height * (.22f + (t / 2) * .38f), right.left + right.width * (.44f + (t % 2) * .44f), right.top + right.height * (.52f + (t / 2) * .38f))
        drawRoundRect(heat(v, Color(0xFF2979FF), Color(0xFF00E676), Color(0xFFFF1744)), cell.topLeft, cell.size, CornerRadius(cell.width * .3f))
        txt("${(60 + v * 60).toInt()}", cell.center.x, cell.center.y + cell.height * .12f, cell.height * .3f, Color.Black, MONO)
    }
}

/** Supercar hexagon: a yellow-on-black hexagonal tach with the speed inside, bars either side. */
internal fun DrawScope.dbHexcar(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF050505), size = size)
    val b = s.eq(6); val yl = Color(0xFFFFD000)
    val c = Offset(w / 2, h * (if (portrait()) .38f else .5f)); val R = min(w * .36f, h * .4f)
    val n = 36
    for (k in 0 until n) {
        val f = (k + .5f) / n; val a = 150f + 240f * f
        val p0 = c.at(a, R * .8f); val p1 = c.at(a, R)
        drawLine(if (f <= b[1].first) (if (f > .8f) Color(0xFFFF1744) else yl) else yl.copy(alpha = .1f), p0, p1, R * .07f)
    }
    val hex = Path(); for (q in 0..6) { val pt = c.at(60f * q + 30f, R * .72f); if (q == 0) hex.moveTo(pt.x, pt.y) else hex.lineTo(pt.x, pt.y) }
    drawPath(hex, yl, style = Stroke(3f))
    seg7("${(b[2].first * 350).toInt()}".padStart(3, ' '), c.x, c.y - R * .25f, R * .34f, Color.White, .05f)
    txt("KM/H", c.x, c.y + R * .25f, R * .1f, yl)
    txt("${1 + (s.level * 6).toInt().coerceIn(0, 6)}", c.x, c.y + R * .5f, R * .2f, yl, MONO)
    val by = if (portrait()) h * .72f else h * .2f; val bh = if (portrait()) h * .24f else h * .6f
    ledBar(w * .04f, by, w * .05f, bh, b[4].first, 16) { yl }; ledBar(w * .91f, by, w * .05f, bh, b[5].first, 16) { f -> if (f > .7f) Color(0xFFFF1744) else yl }
    if (portrait()) spectrum(Rect(w * .14f, h * .74f, w * .86f, h * .96f), s, yl, Color(0xFF6B5300), 32)
}

/** Drift: drift-angle arc (bass vs treble), boost, oil and a rev strip. */
internal fun DrawScope.dbDrift(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF130716), Color(0xFF050208))), size = size)
    val b = s.eq(6); val pk = Color(0xFFFF2BD6); val cy = Color(0xFF00E5FF)
    ledBar(w * .05f, h * .04f, w * .9f, h * .07f, b[1].first, 30, vertical = false) { f -> lerp(cy, pk, f) }
    val p = portrait()
    val c = Offset(w / 2, h * (if (p) .38f else .58f)); val r = if (p) w * .4f else min(w * .25f, h * .42f)
    drawArc(Color.White.copy(alpha = .1f), 200f, 140f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .08f))
    val ang = (b[0].first - b[4].first) * 70f
    drawArc(pk, 270f, ang, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .08f, cap = StrokeCap.Round))
    txt("${abs(ang).toInt()}°", c.x, c.y - r * .25f, r * .35f, Color.White, LIGHT)
    txt("DRIFT ANGLE", c.x, c.y - r * .05f, r * .09f, pk)
    val cells = grid(3, if (p) Rect(0f, h * .66f, w, h) else Rect(0f, h * .72f, w, h))
    listOf("BOOST" to b[2], "OIL" to b[3], "WATER" to b[5]).forEachIndexed { i, (nm, bv) ->
        val cc = cells[i].center; val rr = cells[i].r() * .65f
        ledArc(cc, rr, bv.first, 16, 180f, 180f, rr * .2f) { f -> lerp(cy, pk, f) }
        txt(pct(bv.first), cc.x, cc.y - rr * .05f, rr * .4f, Color.White, MONO); txt(nm, cc.x, cc.y + rr * .35f, rr * .2f, cy)
    }
}

/** Le Mans endurance: hybrid deploy graph (the wave), energy / fuel bars, laps and a tach. */
internal fun DrawScope.dbLeMans(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF06090D), size = size)
    val b = s.eq(6); val teal = Color(0xFF1DE9B6)
    ledBar(w * .03f, h * .04f, w * .94f, h * .06f, b[1].first, 36, vertical = false) { f -> if (f > .85f) Color(0xFFFF1744) else teal }
    val (top, bot) = split(.55f, Rect(0f, h * .12f, w, h), vertical = true)
    val cells = grid(3, top)
    seg7("${1 + (b[0].first * 6).toInt().coerceIn(0, 6)}", cells[0].center.x, cells[0].center.y - cells[0].r() * .45f, cells[0].r() * .9f, Color.White, .05f)
    txt("${(b[2].first * 330).toInt()} km/h", cells[1].center.x, cells[1].center.y, cells[1].r() * .3f, Color.White, MONO)
    txt("LAP ${(s.spin * 2).toInt() % 400 + 1}  ·  P${(1 + (1 - s.level) * 8).toInt()}", cells[1].center.x, cells[1].center.y + cells[1].r() * .4f, cells[1].r() * .16f, teal)
    val bars = cells[2].inset(cells[2].width * .1f)
    listOf("FUEL" to b[3], "ERS" to b[4], "TYRE" to b[5]).forEachIndexed { i, (nm, bv) ->
        val y = bars.top + bars.height * i / 3
        txt(nm, bars.left, y + bars.height * .14f, min(bars.height * .1f, bars.width * .14f), Color.White.copy(alpha = .7f), align = Paint.Align.LEFT)
        ledBar(bars.left + bars.width * .38f, y + bars.height * .04f, bars.width * .62f, bars.height * .14f, bv.first, 12, vertical = false) { teal }
    }
    val g = bot.inset(w * .03f); frame(g, teal.copy(alpha = .4f), 8f)
    history(g.inset(4f), s, teal); wave(g.inset(4f), s, Color(0xFFFFEA00), 2f)
    txt("HYBRID DEPLOY", g.left + 10f, g.top + g.height * .14f, g.height * .08f, teal, align = Paint.Align.LEFT)
}

/** Drag strip: the Christmas tree, reaction time, quarter-mile progress and trap speed. */
internal fun DrawScope.dbDrag(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF0A0A0A), size = size)
    val b = s.eq(7)
    val p = portrait()
    val tree = if (p) Rect(w * .3f, h * .04f, w * .7f, h * .62f) else Rect(w * .05f, h * .06f, w * .3f, h * .94f)
    drawRoundRect(Color(0xFF1C1D20), tree.topLeft, tree.size, CornerRadius(14f))
    val rows = 7; val lr = min(tree.width * .17f, tree.height / rows * .38f)
    for (r in 0 until rows) { val y = tree.top + tree.height * (r + .5f) / rows; val col = when { r < 2 -> Color(0xFFFFFFFF); r < 5 -> Color(0xFFFFB300); r == 5 -> Color(0xFF00E676); else -> Color(0xFFFF1744) }
        for (side in 0..1) { val c = Offset(tree.left + tree.width * (if (side == 0) .3f else .7f), y); val on = b[r].first > .45f
            if (on) drawCircle(col.copy(alpha = .35f), lr * 1.5f, c); drawCircle(if (on) col else col.copy(alpha = .12f), lr, c) } }
    val info = if (p) Rect(w * .05f, h * .66f, w * .95f, h * .97f) else Rect(w * .35f, h * .06f, w * .96f, h * .94f)
    txt("REACTION", info.left, info.top + info.height * .1f, info.height * .06f, Color.White.copy(alpha = .6f), align = Paint.Align.LEFT)
    txt("0.%03d".format((s.level * 999).toInt().coerceIn(0, 999)), info.left, info.top + info.height * .28f, info.height * .16f, Color(0xFF00E676), MONO, Paint.Align.LEFT)
    txt("TRAP", info.left + info.width * .55f, info.top + info.height * .1f, info.height * .06f, Color.White.copy(alpha = .6f), align = Paint.Align.LEFT)
    txt("${(b[2].first * 330).toInt()} mph", info.left + info.width * .55f, info.top + info.height * .28f, info.height * .13f, Color.White, MONO, Paint.Align.LEFT)
    val prog = (s.spin * .07f) % 1f
    drawRect(Color.White.copy(alpha = .08f), Offset(info.left, info.top + info.height * .45f), Size(info.width, info.height * .08f))
    drawRect(Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFFFF1744)), info.left, info.right), Offset(info.left, info.top + info.height * .45f), Size(info.width * prog, info.height * .08f))
    txt("¼ MILE", info.right, info.top + info.height * .43f, info.height * .05f, Color.White.copy(alpha = .6f), align = Paint.Align.RIGHT)
    spectrum(Rect(info.left, info.top + info.height * .62f, info.right, info.bottom), s, Color(0xFFFFB300), Color(0xFFFF1744), 32)
}

/** Synthwave racer: a neon road grid to the sun, with a digital speed and tach. */
internal fun DrawScope.dbSynth(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF12002A), Color(0xFF3A0040), Color(0xFF0A0010))), size = size)
    val hz = h * .5f; val b = s.eq(6)
    val sun = Offset(w / 2, hz); val sr = min(w, h) * .2f * (1 + s.level * .2f)
    drawArc(Brush.verticalGradient(listOf(Color(0xFFFFD319), Color(0xFFFF2975)), sun.y - sr, sun.y), 180f, 180f, true, Offset(sun.x - sr, sun.y - sr), Size(sr * 2, sr * 2))
    for (k in 1..5) drawRect(Color(0xFF3A0040), Offset(sun.x - sr, sun.y - sr * k * .17f), Size(sr * 2, sr * .05f))
    val move = (s.spin * 3f) % 1f
    for (k in 0 until 12) { val t = ((k + move) / 12f); val y = hz + (h - hz) * t * t; drawLine(Color(0xFFFF2BD6).copy(alpha = .3f + .7f * t), Offset(0f, y), Offset(w, y), 2f) }
    for (k in -10..10) drawLine(Color(0xFFFF2BD6).copy(alpha = .6f), Offset(w / 2 + k * w * .02f, hz), Offset(w / 2 + k * w * .25f, h), 2f)
    // mountains from the spectrum
    val mp = Path().apply { moveTo(0f, hz); for (k in 0..24) { val v = s.disp[k * Visual.BANDS / 25].coerceIn(0f, 1f); lineTo(w * k / 24, hz - h * .12f * v) }; lineTo(w, hz); close() }
    drawPath(mp, Color(0xFF00E5FF).copy(alpha = .5f)); drawPath(mp, Color(0xFF00E5FF), style = Stroke(2f))
    seg7("${(b[2].first * 299).toInt()}".padStart(3, ' '), w * .2f, h * .08f, min(h * .1f, w * .08f), Color(0xFF00E5FF), .08f)
    ledBar(w * .55f, h * .1f, w * .4f, h * .05f, b[1].first, 20, vertical = false) { f -> lerp(Color(0xFF00E5FF), Color(0xFFFF2BD6), f) }
    txt("RPM", w * .55f, h * .08f, h * .03f, Color(0xFFFF2BD6), align = Paint.Align.LEFT)
}

/** Superbike TFT: a curved rev bar across the top, lean angle, gear and lap. */
internal fun DrawScope.dbBike(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF000000), size = size)
    val b = s.eq(6)
    val c = Offset(w / 2, h * 1.1f); val r = h * .95f
    val n = 50
    for (k in 0 until n) { val f = (k + .5f) / n; val a = 235f + 70f * f
        val col = if (f > .85f) Color(0xFFFF1744) else lerp(Color(0xFF2979FF), Color(0xFF00E5FF), f)
        drawLine(if (f <= b[1].first) col else col.copy(alpha = .1f), c.at(a, r), c.at(a, r - h * (.06f + .08f * f)), w / n * .6f) }
    seg7("${1 + (b[0].first * 5).toInt().coerceIn(0, 5)}", w / 2, h * .32f, min(h * .3f, w * .2f), Color.White, .05f)
    txt("${(b[2].first * 299).toInt()} km/h", w / 2, h * .78f, min(h * .1f, w * .08f), Color.White, MONO)
    // lean angle
    val lean = (b[3].first - b[4].first) * 120f
    val lc = Offset(w * .15f, h * .55f); val lr = min(w * .12f, h * .22f)
    drawArc(Color.White.copy(alpha = .15f), 200f, 140f, false, Offset(lc.x - lr, lc.y - lr), Size(lr * 2, lr * 2), style = Stroke(lr * .12f))
    drawLine(Color(0xFF00E5FF), lc, lc.at(270f + lean, lr), lr * .08f, StrokeCap.Round)
    txt("${abs(lean).toInt()}° ${if (lean < 0) "L" else "R"}", lc.x, lc.y + lr * .4f, lr * .25f, Color.White, MONO)
    val tc = Offset(w * .85f, h * .55f)
    txt("TC ${1 + (b[5].first * 7).toInt()}", tc.x, tc.y - lr * .1f, lr * .3f, Color(0xFF00E676), MONO)
    txt("ABS ${1 + (b[4].first * 3).toInt()}", tc.x, tc.y + lr * .35f, lr * .3f, Color(0xFFFFEA00), MONO)
    wave(Rect(w * .05f, h * .86f, w * .95f, h * .98f), s, Color(0xFF00E5FF), 2f)
}

/** Rally raid: compass heading, trip meters, waypoint distance bars. */
internal fun DrawScope.dbRaid(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Color(0xFF14110A), size = size)
    val amb = Color(0xFFFFC107); val b = s.eq(6)
    val (l, r) = split(.5f)
    val c = l.center; val R = l.r() * .78f
    val hd = b[2].first * 360f
    drawCircle(Color(0xFF241E10), R, c); drawCircle(amb.copy(alpha = .5f), R, c, style = Stroke(3f))
    for (k in 0 until 36) { val a = k * 10f - hd - 90f; drawLine(amb, c.at(a, R * (if (k % 9 == 0) .8f else .9f)), c.at(a, R), 2f)
        if (k % 9 == 0) txt(listOf("N", "E", "S", "W")[k / 9], c.at(a, R * .66f).x, c.at(a, R * .66f).y + R * .06f, R * .16f, if (k == 0) Color(0xFFFF5252) else amb) }
    drawLine(Color.White, Offset(c.x, c.y - R * 1.05f), Offset(c.x, c.y - R * .7f), 4f)
    txt("${hd.toInt()}°", c.x, c.y + R * .1f, R * .3f, Color.White, MONO)
    val rr = r.inset(r.width * .06f)
    txt("TRIP", rr.left, rr.top + rr.height * .1f, rr.height * .06f, amb, align = Paint.Align.LEFT)
    txt("%05.1f".format((s.spin * 3) % 1000), rr.left, rr.top + rr.height * .26f, min(rr.height * .14f, rr.width * .2f), Color.White, MONO, Paint.Align.LEFT)
    txt("CAP ${(hd).toInt()}", rr.left + rr.width * .55f, rr.top + rr.height * .26f, min(rr.height * .09f, rr.width * .12f), amb, MONO, Paint.Align.LEFT)
    listOf("WPT 1", "WPT 2", "WPT 3", "DSS").forEachIndexed { i, nm ->
        val y = rr.top + rr.height * (.38f + i * .15f)
        txt(nm, rr.left, y + rr.height * .06f, rr.height * .05f, amb, MONO, Paint.Align.LEFT)
        ledBar(rr.left + rr.width * .3f, y, rr.width * .7f, rr.height * .07f, b[i + 1].first, 16, vertical = false) { f -> if (f > .8f) Color(0xFFFF5252) else amb }
    }
}

/** Formula E: pastel-neon energy rings, attack-mode bar and the wave as regen. */
internal fun DrawScope.dbFormulaE(s: VisState) {
    val w = size.width; val h = size.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF051226), Color(0xFF020611))), size = size)
    val b = s.eq(6); val blue = Color(0xFF00B0FF); val lime = Color(0xFFC6FF00)
    shift(w * .1f, h * .05f, w * .8f, b[1].first, 17)
    val cells = grid(3, Rect(0f, h * .1f, w, h * .66f))
    listOf(Triple("POWER kW", b[1], blue), Triple("SPEED", b[2], Color.White), Triple("ENERGY %", b[4], lime)).forEachIndexed { i, (nm, bv, col) ->
        val c = cells[i].center; val r = cells[i].r() * .75f
        ring(c, r, bv.first, col, r * .1f)
        txt(when (i) { 0 -> "${(bv.first * 350).toInt()}"; 1 -> "${(bv.first * 280).toInt()}"; else -> "${(bv.first * 100).toInt()}" }, c.x, c.y + r * .14f, r * .42f, Color.White, LIGHT)
        txt(nm, c.x, c.y + r * .45f, r * .13f, col)
    }
    val am = Rect(w * .06f, h * .7f, w * .94f, h * .76f)
    txt("ATTACK MODE", am.left, am.top - 6f, am.height * .7f, Color(0xFFE040FB), align = Paint.Align.LEFT)
    ledBar(am.left, am.top, am.width, am.height, b[0].first, 30, vertical = false) { Color(0xFFE040FB) }
    val g = Rect(w * .06f, h * .8f, w * .94f, h * .97f); frame(g, blue.copy(alpha = .4f), 8f); wave(g.inset(4f), s, lime, 2f)
}
