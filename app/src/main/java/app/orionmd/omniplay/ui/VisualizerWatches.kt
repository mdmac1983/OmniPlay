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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.orionmd.omniplay.Visual
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Watches: luxury chronographs in the style of Rolex (Daytona, Submariner), TAG Heuer (Monaco, Carrera), Omega
 * (Seamaster Planet Ocean), Richard Mille, Patek Philippe (5170, perpetual, Nautilus, Aquanaut), Audemars Piguet
 * (Royal Oak, Offshore) and IWC (Portugieser, Pilot's), several colourways each. No logos: the dials say OrionMD.
 * The hands tell the real time and date; the music drives the chronograph hand (faster when it's loud), the counters
 * (bass, mids) and the halo of light around the case.
 */

// ---------------------------------------------------------------- music and time

private fun VisState.wb(k: Int) = disp[k.coerceIn(0, Visual.BANDS - 1)].coerceIn(0f, 1f)
private fun VisState.wavg(a: Int, z: Int) = (a until z).sumOf { wb(it).toDouble() }.toFloat() / (z - a)
private fun VisState.wLo() = (wavg(0, 8) * 1.25f).coerceIn(0f, 1f)
private fun VisState.wMid() = (wavg(8, 26) * 1.5f).coerceIn(0f, 1f)
private fun VisState.wHi() = (wavg(26, Visual.BANDS) * 2f).coerceIn(0f, 1f)
/** The chronograph seconds hand: a gentle sweep when it's quiet, racing round when it's loud. */
private fun VisState.chronoDeg() = (spin * 38f) % 360f

private class WTime(val h: Float, val m: Float, val s: Float, val day: Int, val dow: Int, val mon: Int, val moon: Float)
private fun wNow(): WTime {
    val t = LocalDateTime.now(); val s = t.second + t.nano / 1e9f; val m = t.minute + s / 60f
    // moon age as a fraction of a lunation (0 = new, .5 = full), from a known new moon
    val days = (java.time.Duration.between(LocalDateTime.of(2000, 1, 6, 18, 14), t).toMinutes() / 1440.0)
    val moon = ((days / 29.530588) % 1.0).toFloat()
    return WTime((t.hour % 12) + m / 60f, m, s, t.dayOfMonth, t.dayOfWeek.value, t.monthValue, moon)
}
private val DOW = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

// ---------------------------------------------------------------- materials

internal class Metal(val hi: Color, val mid: Color, val lo: Color)
private val STEEL = Metal(Color(0xFFF4F6F8), Color(0xFFB4BAC1), Color(0xFF575D64))
private val GOLD = Metal(Color(0xFFFFF0B3), Color(0xFFD9AE4F), Color(0xFF7A561A))
private val ROSE = Metal(Color(0xFFFFE3D6), Color(0xFFDDA083), Color(0xFF85493A))
private val PLAT = Metal(Color(0xFFFFFFFF), Color(0xFFCDD3DA), Color(0xFF737C86))
private val TITAN = Metal(Color(0xFFD2D5D8), Color(0xFF878D93), Color(0xFF3F4449))
private val CERAMIC = Metal(Color(0xFF686C72), Color(0xFF222427), Color(0xFF070708))
private val BRONZE = Metal(Color(0xFFF0CC98), Color(0xFFAF7B45), Color(0xFF5A3A1C))
private val BLUED = Metal(Color(0xFF7C9BE0), Color(0xFF2C4C9C), Color(0xFF0F1F4A))

private fun Metal.sweep(c: Offset) = Brush.sweepGradient(listOf(mid, hi, mid, lo, mid, hi, mid, lo, mid), c)
private fun Metal.diag(c: Offset, r: Float) = Brush.linearGradient(listOf(hi, mid, lo, mid, hi, mid, lo), Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r))
private fun Metal.hz(l: Float, r: Float) = Brush.horizontalGradient(listOf(lo, mid, hi, mid, lo), l, r)
private fun Metal.vt(t: Float, b: Float) = Brush.verticalGradient(listOf(hi, mid, lo, mid), t, b)
private fun ceramic(col: Color, c: Offset) = Brush.sweepGradient(listOf(col, lerp(col, Color.White, .2f), col, lerp(col, Color.Black, .35f), col,
    lerp(col, Color.White, .16f), col, lerp(col, Color.Black, .35f), col), c)

internal enum class SK { OYSTER, JUBILEE, INTEGRATED, RUBBER, LEATHER }
/** A strap or bracelet: [m] the links' metal, [centre] a different metal for the middle links (two-tone). */
internal class Strap(val k: SK, val m: Metal = STEEL, val col: Color = Color(0xFF111111), val stitch: Color? = null, val centre: Metal? = null)

// ---------------------------------------------------------------- drawing kit

private val HF = HifiFonts
private val SERIF: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
private val SERIF_B: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD) }
private val LIGHT: Typeface by lazy { Typeface.create("sans-serif-light", Typeface.NORMAL) }
private val COND: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
private val WP = Paint(Paint.ANTI_ALIAS_FLAG)

private fun DrawScope.wt(s: String, x: Float, y: Float, px: Float, c: Color, font: Typeface? = null, spacing: Float = 0f, glow: Color? = null) {
    if (px < 2.5f || c.alpha <= 0f) return
    val p = WP; p.reset(); p.isAntiAlias = true; p.color = c.toArgb(); p.textSize = px; p.textAlign = Paint.Align.CENTER
    p.typeface = font ?: Typeface.DEFAULT_BOLD; p.letterSpacing = spacing
    if (glow != null) p.setShadowLayer(px * .5f, 0f, 0f, glow.toArgb())
    drawIntoCanvas { it.nativeCanvas.drawText(s, x, y, p) }
}
/** Text centred on ([x], [y]). */
private fun DrawScope.wtc(s: String, x: Float, y: Float, px: Float, c: Color, font: Typeface? = null, spacing: Float = 0f, glow: Color? = null) = wt(s, x, y + px * .36f, px, c, font, spacing, glow)
/** Applied (raised metal) lettering: a shadow, the metal, and a highlight. */
private fun DrawScope.wtm(s: String, x: Float, y: Float, px: Float, m: Metal, font: Typeface? = null) {
    wtc(s, x + px * .04f, y + px * .06f, px, Color.Black.copy(alpha = .45f), font)
    wtc(s, x, y, px, m.lo, font); wtc(s, x - px * .02f, y - px * .025f, px, m.mid, font); wtc(s, x - px * .03f, y - px * .04f, px, m.hi.copy(alpha = .35f), font)
}
/** Point at watch angle [deg] (0 = 12 o'clock, clockwise) and radius [r]. */
private fun at(c: Offset, deg: Float, r: Float): Offset { val a = Math.toRadians(deg - 90.0); return Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()) }
/** Text standing on a circle, its foot towards the centre; [flip] turns it to read upright at the bottom. */
private fun DrawScope.wtr(s: String, c: Offset, deg: Float, r: Float, px: Float, col: Color, font: Typeface? = null, flip: Boolean = false) {
    rotate(if (flip) deg + 180f else deg, c) { wtc(s, c.x, if (flip) c.y + r else c.y - r, px, col, font) }
}
private fun DrawScope.arcText(s: String, c: Offset, deg: Float, r: Float, px: Float, col: Color, font: Typeface? = null, flip: Boolean = false) {
    val st = Math.toDegrees((px * .78f / r).toDouble()).toFloat() * (if (flip) -1 else 1)
    val start = deg - st * (s.length - 1) / 2
    s.forEachIndexed { i, ch -> if (ch != ' ') wtr(ch.toString(), c, start + i * st, r, px, col, font, flip) }
}

private fun DrawScope.wc() = Offset(size.width / 2, size.height / 2)
private fun DrawScope.wr() = min(size.width * .34f, size.height * .31f)

/** A halo of spectrum bars around the case: bass at the sides, treble towards the strap. */
private fun DrawScope.aura(s: VisState, c: Offset, r: Float, col: Color) {
    val gr = r * 1.9f
    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = .1f + s.level * .3f), Color.Transparent), c, gr), gr, c)
    val n = 96; val r0 = r * 1.2f; val bw = (2 * PI.toFloat() * r0 / n) * .5f
    for (k in 0 until n) {
        val deg = k * 360f / n
        val u = abs(((deg + 90f) % 180f) - 90f) / 90f   // 1 at 3 and 9, 0 at 12 and 6
        val v = s.wb((((1f - u) / .78f).coerceAtMost(1f) * (Visual.BANDS - 1)).toInt())
        drawLine(col.copy(alpha = .3f + v * .7f), at(c, deg, r0), at(c, deg, r0 + r * .03f + v * r * .42f), bw, StrokeCap.Round)
    }
}

private fun DrawScope.piece(l: Float, t: Float, w: Float, h: Float, m: Metal, polished: Boolean = false) {
    if (w <= 0f || h <= 0f) return
    val br = if (polished) Brush.horizontalGradient(listOf(m.mid, m.hi, m.lo, m.hi, m.mid), l, l + w) else Brush.horizontalGradient(listOf(m.lo, m.mid, m.hi, m.mid, m.lo), l, l + w)
    drawRoundRect(br, Offset(l, t), Size(w, h), CornerRadius(min(w, h) * .18f))
    drawRect(Color.White.copy(alpha = .12f), Offset(l, t), Size(w, h * .08f))
}

/** The strap or bracelet, from [from] above and below the centre to the edges of the canvas. */
private fun DrawScope.strap(c: Offset, w0: Float, from: Float, st: Strap) {
    for (dir in intArrayOf(-1, 1)) {
        val y0 = c.y + dir * from; val y1 = if (dir < 0) 0f else size.height
        val len = abs(y1 - y0); if (len <= 1f) continue
        fun wAt(d: Float) = w0 * (1f - .12f * (d / (w0 * 2.5f)).coerceAtMost(1f))
        val wEnd = wAt(len)
        val body = Path().apply { moveTo(c.x - w0 / 2, y0); lineTo(c.x - wEnd / 2, y1); lineTo(c.x + wEnd / 2, y1); lineTo(c.x + w0 / 2, y0); close() }
        when (st.k) {
            SK.RUBBER, SK.LEATHER -> {
                drawPath(body, Brush.horizontalGradient(listOf(lerp(st.col, Color.Black, .45f), st.col, lerp(st.col, Color.White, .12f), st.col, lerp(st.col, Color.Black, .45f)), c.x - w0 / 2, c.x + w0 / 2))
                clipPath(body) {
                    if (st.k == SK.RUBBER) {
                        var d = w0 * .1f
                        while (d < len) { val y = y0 + dir * d
                            drawLine(Color.Black.copy(alpha = .35f), Offset(c.x - w0, y), Offset(c.x + w0, y), w0 * .025f)
                            drawLine(Color.White.copy(alpha = .07f), Offset(c.x - w0, y + w0 * .02f), Offset(c.x + w0, y + w0 * .02f), w0 * .015f)
                            d += w0 * .14f }
                    } else {   // alligator scales: rows of tiles, bigger down the middle
                        var d = 0f; var row = 0; val rh = w0 * .13f
                        while (d < len) { val y = if (dir < 0) y0 - d - rh else y0 + d
                            val off = if (row % 2 == 0) 0f else .5f
                            for (i in -1..5) { val f = (i + off) / 5f; val x = c.x - w0 / 2 + w0 * f; val ww = w0 / 5 * (if (abs(f - .4f) < .3f) 1f else .75f)
                                drawRoundRect(Color.Black.copy(alpha = .3f), Offset(x + ww * .06f, y + rh * .08f), Size(ww * .88f, rh * .84f), CornerRadius(rh * .3f), style = Stroke(w0 * .012f)) }
                            d += rh; row++ }
                    }
                }
                st.stitch?.let { sc -> val e = w0 * .08f
                    for (side in intArrayOf(-1, 1)) drawLine(sc, Offset(c.x + side * (w0 / 2 - e), y0), Offset(c.x + side * (wEnd / 2 - e), y1), w0 * .014f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w0 * .04f, w0 * .03f))) }
            }
            else -> {
                drawPath(body, lerp(st.m.lo, Color.Black, .55f))   // the gaps between links
                val L = when (st.k) { SK.JUBILEE -> w0 * .2f; SK.INTEGRATED -> w0 * .32f; else -> w0 * .3f }
                var d = 0f; var i = 0
                while (d < len) {
                    val top = if (dir < 0) y0 - d - L else y0 + d
                    val w = wAt(d + L / 2); val l = c.x - w / 2; val g = w * .025f; val h = L - g; val cm = st.centre ?: st.m
                    when (st.k) {
                        SK.OYSTER -> { piece(l, top, w * .33f - g, h, st.m); piece(l + w * .33f, top, w * .34f, h, cm, st.centre != null); piece(l + w * .67f + g, top, w * .33f - g, h, st.m) }
                        SK.JUBILEE -> { piece(l, top, w * .26f, h, st.m); piece(l + w * .74f, top, w * .26f, h, st.m)
                            piece(l + w * .28f, top, w * .13f, h, cm, true); piece(l + w * .59f, top, w * .13f, h, cm, true)
                            piece(l + w * .435f, top + (if (i % 2 == 0) 0f else h * .5f) - h * .25f, w * .13f, h * .75f, cm, true) }
                        else -> { piece(l, top, w, h * .64f, st.m); piece(l + w * .26f, top + h * .66f, w * .48f, h * .34f - g, cm, true) }
                    }
                    d += L; i++
                }
            }
        }
    }
}

/** Lug horns either side of the strap. */
private fun DrawScope.lugs(c: Offset, r: Float, w0: Float, m: Metal, reach: Float = 1.2f, thick: Float = .14f) {
    for (dir in intArrayOf(-1, 1)) for (side in intArrayOf(-1, 1)) {
        val xi = c.x + side * w0 / 2; val xo = xi + side * r * thick
        val p = Path().apply { moveTo(xi, c.y + dir * r * .5f); lineTo(xi, c.y + dir * r * reach); lineTo(xo - side * r * .02f, c.y + dir * r * reach)
            quadraticBezierTo(xo + side * r * .02f, c.y + dir * r * .8f, xo + side * r * .06f, c.y + dir * r * .5f); close() }
        drawPath(p, m.hz(min(xi, xo), max(xi, xo)))
    }
}

/** Crown at watch angle [deg]. */
private fun DrawScope.crown(c: Offset, r: Float, m: Metal, deg: Float = 90f, w: Float = .26f, len: Float = .15f, cap: Color? = null) {
    rotate(deg - 90f, c) {
        val l = c.x + r * .9f; val h = r * w; val t = c.y - h / 2; val ll = r * (len + .1f)
        drawRoundRect(m.vt(t, t + h), Offset(l, t), Size(ll, h), CornerRadius(h * .2f))
        for (i in 1 until 9) drawLine(m.lo.copy(alpha = .6f), Offset(l + r * .1f, t + h * i / 9), Offset(l + ll, t + h * i / 9), r * .006f)
        cap?.let { drawRoundRect(it, Offset(l + ll - r * .04f, t), Size(r * .04f, h), CornerRadius(h * .2f)) }
    }
}
private fun DrawScope.pusher(c: Offset, r: Float, m: Metal, deg: Float, w: Float = .13f, len: Float = .13f, collar: Boolean = false, cap: Color? = null) {
    rotate(deg - 90f, c) {
        val h = r * w; val l = c.x + r * .9f
        if (collar) drawRoundRect(m.vt(c.y - h * .75f, c.y + h * .75f), Offset(l, c.y - h * .75f), Size(r * (len * .55f + .1f), h * 1.5f), CornerRadius(h * .2f))
        drawRoundRect(cap?.let { SolidColor(it) } ?: m.vt(c.y - h / 2, c.y + h / 2), Offset(l, c.y - h / 2), Size(r * (len + .1f), h), CornerRadius(h * .3f))
    }
}
private fun DrawScope.guards(c: Offset, r: Float, m: Metal) { pusher(c, r, m, 72f, .09f, .03f); pusher(c, r, m, 108f, .09f, .03f) }
/** A push-piece sticking straight out of a flat case side at ([x], [y]). */
private fun DrawScope.sidePusher(x: Float, y: Float, len: Float, h: Float, m: Metal, cap: Color? = null, knurl: Boolean = false) {
    val l = if (len < 0) x + len else x; val w = abs(len)
    drawRoundRect(m.vt(y - h / 2, y + h / 2), Offset(l, y - h / 2), Size(w, h), CornerRadius(h * .25f))
    if (knurl) for (i in 1 until 8) drawLine(m.lo.copy(alpha = .6f), Offset(l, y - h / 2 + h * i / 8), Offset(l + w, y - h / 2 + h * i / 8), h * .03f)
    cap?.let { val cw = w * .3f; drawRoundRect(it, Offset(if (len < 0) l else l + w - cw, y - h / 2), Size(cw, h), CornerRadius(h * .25f)) }
}

private fun DrawScope.caseRound(c: Offset, r: Float, m: Metal) {
    drawCircle(Color.Black.copy(alpha = .5f), r * 1.01f, c + Offset(r * .02f, r * .05f))
    drawCircle(m.diag(c, r), r, c)
    drawCircle(Color.Black.copy(alpha = .35f), r, c, style = Stroke(r * .012f))
}
private fun DrawScope.ring(c: Offset, r1: Float, r2: Float, br: Brush) { val w = r2 - r1; drawCircle(br, r1 + w / 2, c, style = Stroke(w)) }

private fun DrawScope.dialFace(c: Offset, r: Float, base: Color, sun: Float) {
    if (sun > 0f) { val lt = lerp(base, Color.White, sun); val dk = lerp(base, Color.Black, sun)
        drawCircle(Brush.sweepGradient(listOf(base, lt, base, dk, base, lt, base, dk, base), c), r, c) } else drawCircle(base, r, c)
    drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = .28f)), c, r), r, c)
}
/** A soft reflection across the crystal. */
private fun DrawScope.glare(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .13f), Color.White.copy(alpha = .03f), Color.Transparent), Offset(c.x - r * .45f, c.y - r * .55f), r * 1.1f), r, c)
}
/** Tick marks around a circle ending at [rOut]; every [every]th one is bigger. */
private fun DrawScope.track(c: Offset, rOut: Float, n: Int, len: Float, w: Float, col: Color, every: Int = 0, bigLen: Float = len, bigW: Float = w, skip: (Int) -> Boolean = { false }) {
    for (k in 0 until n) { if (skip(k)) continue; val big = every > 0 && k % every == 0
        val d = k * 360f / n; drawLine(col, at(c, d, rOut - if (big) bigLen else len), at(c, d, rOut), if (big) bigW else w) }
}
/** An applied hour marker with an optional lume strip that glows with the bass. */
private fun DrawScope.applied(c: Offset, deg: Float, r1: Float, r2: Float, w: Float, m: Metal, lume: Color? = null, glow: Float = 0f) {
    rotate(deg, c) {
        val tl = Offset(c.x - w / 2, c.y - r2); val sz = Size(w, r2 - r1)
        drawRect(Color.Black.copy(alpha = .4f), tl + Offset(w * .15f, w * .3f), sz)
        drawRect(Brush.horizontalGradient(listOf(m.hi, m.mid, m.lo), tl.x, tl.x + w), tl, sz)
        lume?.let { val lt = Offset(c.x - w * .27f, c.y - r2 + w * .25f); val ls = Size(w * .54f, r2 - r1 - w * .5f)
            if (glow > .05f) drawRect(it.copy(alpha = glow * .3f), lt - Offset(ls.width * .5f, ls.width * .5f), Size(ls.width * 2f, ls.height + ls.width))
            drawRect(lerp(it, Color.White, glow * .4f), lt, ls) }
    }
}
private fun DrawScope.dateWin(x: Float, y: Float, h: Float, txt: String, bg: Color = Color.White, fg: Color = Color.Black, frame: Metal? = STEEL, w: Float = h * 1.15f, font: Typeface? = null) {
    frame?.let { drawRect(it.hz(x - w / 2 - h * .1f, x + w / 2 + h * .1f), Offset(x - w / 2 - h * .1f, y - h / 2 - h * .1f), Size(w + h * .2f, h * 1.2f)) }
    drawRect(bg, Offset(x - w / 2, y - h / 2), Size(w, h))
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent), y - h / 2, y - h * .2f), Offset(x - w / 2, y - h / 2), Size(w, h * .3f))
    wtc(txt, x, y, h * .74f, fg, font)
}
/** The magnifying bubble over the date. */
private fun DrawScope.cyclops(x: Float, y: Float, h: Float, txt: String) {
    val w = h * 1.3f; val tl = Offset(x - w / 2, y - h / 2); val sz = Size(w, h)
    drawRoundRect(Color.White, tl, sz, CornerRadius(h * .14f)); wtc(txt, x, y, h * .78f, Color.Black)
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .45f), Color.Transparent, Color.White.copy(alpha = .15f)), tl, tl + Offset(w, h)), tl, sz, CornerRadius(h * .14f))
    drawRoundRect(Color(0xFFB8C7D6).copy(alpha = .7f), tl, sz, CornerRadius(h * .14f), style = Stroke(h * .04f))
}

// ---------------------------------------------------------------- hands

internal enum class HS { BATON, SWORD, MERC, DAUPH, LEAF, ARROW, SKEL }
private fun handPath(c: Offset, hs: HS, len: Float, w: Float, tail: Float): Path = Path().apply {
    val x = c.x; val y = c.y
    when (hs) {
        HS.BATON, HS.SKEL -> { moveTo(x - w / 2, y + tail); lineTo(x - w / 2, y - len + w * .5f); lineTo(x, y - len); lineTo(x + w / 2, y - len + w * .5f); lineTo(x + w / 2, y + tail); close() }
        HS.SWORD -> { moveTo(x - w * .28f, y + tail); lineTo(x - w / 2, y - len * .72f); lineTo(x, y - len); lineTo(x + w / 2, y - len * .72f); lineTo(x + w * .28f, y + tail); close() }
        HS.MERC -> { moveTo(x - w * .3f, y + tail); lineTo(x - w * .3f, y - len * .6f); lineTo(x + w * .3f, y - len * .6f); lineTo(x + w * .3f, y + tail); close()
            moveTo(x - w * .62f, y - len * .78f); lineTo(x, y - len); lineTo(x + w * .62f, y - len * .78f); close() }
        HS.DAUPH -> { moveTo(x, y + tail); lineTo(x - w / 2, y - len * .1f); lineTo(x, y - len); lineTo(x + w / 2, y - len * .1f); close() }
        HS.LEAF -> { moveTo(x - w * .12f, y + tail); quadraticBezierTo(x - w * .85f, y - len * .5f, x, y - len); quadraticBezierTo(x + w * .85f, y - len * .5f, x + w * .12f, y + tail); close() }
        HS.ARROW -> { moveTo(x - w * .3f, y + tail); lineTo(x - w * .3f, y - len * .68f); lineTo(x - w, y - len * .68f); lineTo(x, y - len); lineTo(x + w, y - len * .68f)
            lineTo(x + w * .3f, y - len * .68f); lineTo(x + w * .3f, y + tail); close() }
    }
}
private fun DrawScope.hand(c: Offset, deg: Float, hs: HS, len: Float, w: Float, m: Metal, lume: Color? = null, glow: Float = 0f, solid: Color? = null, tail: Float = w * 1.5f) {
    val p = handPath(c, hs, len, w, tail)
    translate(w * .3f, w * .6f) { rotate(deg, c) { drawPath(p, Color.Black.copy(alpha = .35f)) } }
    rotate(deg, c) {
        val br: Brush = solid?.let { SolidColor(it) } ?: Brush.horizontalGradient(listOf(m.hi, m.mid, m.lo), c.x - w / 2, c.x + w / 2)
        if (hs == HS.SKEL) drawPath(p, br, style = Stroke(w * .22f)) else drawPath(p, br)
        if (hs == HS.MERC) drawCircle(br, w * .62f, Offset(c.x, c.y - len * .69f))
        if (hs == HS.DAUPH) drawPath(Path().apply { moveTo(c.x, c.y + tail); lineTo(c.x + w / 2, c.y - len * .1f); lineTo(c.x, c.y - len); close() }, Color.Black.copy(alpha = .18f))
        if (lume != null) {
            val lc = SolidColor(lerp(lume, Color.White, glow * .35f))
            when (hs) {
                HS.MERC -> { drawRect(lc, Offset(c.x - w * .16f, c.y - len * .58f), Size(w * .32f, len * .38f)); drawCircle(lc, w * .42f, Offset(c.x, c.y - len * .69f))
                    drawPath(Path().apply { moveTo(c.x - w * .36f, c.y - len * .8f); lineTo(c.x, c.y - len * .93f); lineTo(c.x + w * .36f, c.y - len * .8f); close() }, lc) }
                HS.DAUPH -> {}
                HS.SKEL -> drawPath(handPath(c, HS.BATON, len - w * .25f, w * .62f, -len * .62f), lc)
                else -> drawPath(handPath(c, hs, len - w * .35f, w * .5f, -len * .25f), lc)
            }
        }
    }
}
/** The chronograph seconds hand. */
private fun DrawScope.chrono(c: Offset, deg: Float, len: Float, w: Float, col: Color, tip: Color? = null, lume: Color? = null, arrow: Boolean = false, big: Boolean = false) {
    translate(w, w * 1.6f) { rotate(deg, c) { drawLine(Color.Black.copy(alpha = .3f), Offset(c.x, c.y + len * .24f), Offset(c.x, c.y - len), w) } }
    rotate(deg, c) {
        drawLine(col, Offset(c.x, c.y + len * .24f), Offset(c.x, c.y - len), w)
        tip?.let { drawLine(it, Offset(c.x, c.y - len * .8f), Offset(c.x, c.y - len), w * 1.15f) }
        if (big) drawRoundRect(col, Offset(c.x - w * 2.2f, c.y + len * .08f), Size(w * 4.4f, len * .18f), CornerRadius(w * 2f)) else drawCircle(col, w * 2.6f, Offset(c.x, c.y + len * .17f))
        lume?.let { drawCircle(col, w * 2.6f, Offset(c.x, c.y - len * .78f)); drawCircle(it, w * 1.7f, Offset(c.x, c.y - len * .78f)) }
        if (arrow) drawPath(Path().apply { moveTo(c.x - w * 2.4f, c.y - len * .86f); lineTo(c.x, c.y - len * 1.01f); lineTo(c.x + w * 2.4f, c.y - len * .86f); close() }, tip ?: col)
    }
    drawCircle(col, w * 2.4f, c); drawCircle(Color.Black.copy(alpha = .45f), w * .8f, c)
}

/** A chronograph counter. [v] 0..1 is the hand; [v2] (if not negative) a shorter second hand, for co-axial hour counters. */
private fun DrawScope.sub(c: Offset, r: Float, v: Float, face: Color?, ink: Color, hand: Color, labels: List<String> = emptyList(), ring: Brush? = null, ringW: Float = .12f,
                          ticks: Int = 60, every: Int = 5, font: Typeface? = null, snail: Boolean = true, tip: Color? = null, v2: Float = -1f, square: Boolean = false) {
    if (face != null) {
        if (square) drawRoundRect(face, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), CornerRadius(r * .3f)) else drawCircle(face, r, c)
        if (snail) for (i in 1..9) drawCircle(Color.White.copy(alpha = .05f), r * i / 10f, c, style = Stroke(r * .03f))
        if (!square) drawCircle(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent), c.y - r, c.y - r * .5f), r, c)
    }
    ring?.let { drawCircle(it, r * (1 - ringW / 2), c, style = Stroke(r * ringW)) }
    val rOut = if (ring != null) r * (1 - ringW) * .97f else r * .94f
    for (k in 0 until ticks) { val big = k % every == 0; val d = k * 360f / ticks
        drawLine(ink, at(c, d, rOut - if (big) r * .17f else r * .08f), at(c, d, rOut), if (big) r * .035f else r * .018f) }
    labels.forEachIndexed { i, s -> val p = at(c, 360f * (i + 1) / labels.size, rOut - r * .37f); wtc(s, p.x, p.y, r * .22f, ink, font) }
    if (v2 >= 0f) { val a = v2.coerceIn(0f, 1f) * 360f; drawLine(hand.copy(alpha = .9f), at(c, a + 180f, r * .1f), at(c, a, r * .5f), r * .1f, StrokeCap.Round) }
    val a = v.coerceIn(0f, 1f) * 360f
    drawLine(Color.Black.copy(alpha = .3f), at(c, a + 180f, r * .2f) + Offset(r * .03f, r * .05f), at(c, a, rOut * .95f) + Offset(r * .03f, r * .05f), r * .06f, StrokeCap.Round)
    drawLine(hand, at(c, a + 180f, r * .2f), at(c, a, rOut * .95f), r * .06f, StrokeCap.Round)
    tip?.let { drawLine(it, at(c, a, rOut * .6f), at(c, a, rOut * .95f), r * .065f, StrokeCap.Round) }
    drawCircle(hand, r * .1f, c)
}

private val TACHY = intArrayOf(400, 300, 240, 200, 180, 150, 130, 110, 100, 90, 85, 80, 75, 70, 65, 60)
private fun tachDeg(v: Int) = (3600f / v * 6f) % 360f
/** A tachymeter scale (numbers standing round the circle at radius [r]). */
private fun DrawScope.tachy(c: Offset, r: Float, px: Float, col: Color, font: Typeface?, label: Boolean = true, tick: Float = 0f) {
    for (v in TACHY) { val d = tachDeg(v); wtr("$v", c, d, r, px, col, font)
        if (tick > 0f) drawLine(col, at(c, d, r + px * .75f), at(c, d, r + px * .75f + tick), px * .1f) }
    if (label) arcText("TACHYMETRE", c, 25f, r, px * .68f, col, font)
}

// ---------------------------------------------------------------- Rolex Daytona style

internal class Dayt(val case: Metal, val bezelCol: Color?, val bezelInk: Color, val dial: Color, val sun: Float, val sub: Color, val subRing: Metal?, val ink: Color, val subInk: Color,
                    val idx: Metal, val hands: Metal, val lume: Color, val red: Color, val strap: Strap, val aura: Color, val newman: Boolean = false)

internal fun DrawScope.wDaytona(s: VisState, d: Dayt) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R, R * .8f, d.strap); lugs(c, R, R, d.case)
    pusher(c, R, d.case, 58f, collar = true); pusher(c, R, d.case, 122f, collar = true); guards(c, R, d.case); crown(c, R, d.case)
    caseRound(c, R, d.case)
    ring(c, R * .76f, R * .96f, d.bezelCol?.let { ceramic(it, c) } ?: d.case.sweep(c))
    drawCircle(Color.Black.copy(alpha = .3f), R * .96f, c, style = Stroke(R * .01f))
    tachy(c, R * .855f, R * .075f, d.bezelInk, HF.semi, tick = R * .03f)
    // the flange, then the dial
    drawCircle(lerp(d.dial, Color.Black, .3f), R * .76f, c)
    dialFace(c, R * .73f, d.dial, d.sun)
    track(c, R * .725f, 300, R * .016f, R * .004f, if (d.newman) d.red else d.ink, every = 5, bigLen = R * .04f, bigW = R * .008f)
    if (d.newman) { drawCircle(d.red, R * .685f, c, style = Stroke(R * .006f))
        for (k in 1..12) { val p = at(c, k * 30f, R * .655f); wtc("${k * 5}", p.x, p.y, R * .034f, d.red, HF.semi) } }
    for (h in 1..12) when (h) {
        3, 6, 9 -> {}
        12 -> { applied(c, -2.6f, R * .5f, R * .63f, R * .04f, d.idx, d.lume, glow); applied(c, 2.6f, R * .5f, R * .63f, R * .04f, d.idx, d.lume, glow) }
        else -> applied(c, h * 30f, if (d.newman) R * .54f else R * .5f, R * .63f, R * .045f, d.idx, d.lume, glow)
    }
    wtc("ORIONMD", c.x, c.y - R * .36f, R * .062f, d.ink, HF.semi, .08f)
    wtc("AUTOMATIC", c.x, c.y - R * .295f, R * .03f, d.ink, spacing = .2f)
    wtc("CHRONOGRAPH", c.x, c.y + R * .105f, R * .034f, d.red, HF.semi, .12f)
    val sr = R * .19f; val sd = R * .33f
    sub(Offset(c.x + sd, c.y), sr, s.wLo(), d.sub, d.subInk, d.hands.mid, listOf("10", "20", "30"), d.subRing?.sweep(Offset(c.x + sd, c.y)), font = HF.semi)
    sub(Offset(c.x, c.y + sd), sr, s.wMid(), d.sub, d.subInk, d.hands.mid, listOf("3", "6", "9", "12"), d.subRing?.sweep(Offset(c.x, c.y + sd)), font = HF.semi)
    sub(Offset(c.x - sd, c.y), sr, tm.s / 60f, d.sub, d.subInk, d.hands.mid, listOf("20", "40", "60"), d.subRing?.sweep(Offset(c.x - sd, c.y)), font = HF.semi)
    hand(c, tm.h * 30f, HS.BATON, R * .43f, R * .05f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.BATON, R * .66f, R * .042f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .7f, R * .009f, d.hands.mid, lume = d.lume)
    glare(c, R * .96f)
}

// ---------------------------------------------------------------- Rolex Submariner style (with a chronograph hand)

internal class SubW(val case: Metal, val bezel: Color, val bezelInk: Color, val dial: Color, val sun: Float, val ink: Color, val idx: Metal, val hands: Metal, val lume: Color,
                    val accent: Color, val strap: Strap, val aura: Color, val bezelRing: Metal? = null)

internal fun DrawScope.wSub(s: VisState, d: SubW) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * .98f, R * .8f, d.strap); lugs(c, R, R * .98f, d.case)
    guards(c, R, d.case); crown(c, R, d.case, w = .28f)
    caseRound(c, R, d.case)
    val rm = d.bezelRing ?: d.case
    ring(c, R * .93f, R * .99f, rm.sweep(c))
    for (k in 0 until 120) drawLine(rm.lo.copy(alpha = .7f), at(c, k * 3f, R * .945f), at(c, k * 3f, R * .99f), R * .008f)   // coin edge
    ring(c, R * .78f, R * .935f, ceramic(d.bezel, c))
    for (k in 0 until 60) { val deg = k * 6f
        when {
            k == 0 -> { drawPath(Path().apply { val a = at(c, -5f, R * .92f); val b = at(c, 5f, R * .92f); val p = at(c, 0f, R * .8f); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(p.x, p.y); close() }, d.bezelInk)
                val pip = at(c, 0f, R * .885f); drawCircle(rm.mid, R * .03f, pip); drawCircle(lerp(d.lume, Color.White, glow * .4f), R * .022f, pip) }
            k % 10 == 0 -> wtr("$k", c, deg, R * .857f, R * .1f, d.bezelInk, HF.semi)
            k % 5 == 0 -> drawLine(d.bezelInk, at(c, deg, R * .82f), at(c, deg, R * .9f), R * .028f)
            k < 15 -> drawLine(d.bezelInk, at(c, deg, R * .865f), at(c, deg, R * .91f), R * .008f)
        } }
    drawCircle(lerp(d.dial, Color.Black, .35f), R * .78f, c)
    dialFace(c, R * .765f, d.dial, d.sun)
    track(c, R * .76f, 60, R * .025f, R * .005f, d.ink, every = 5, bigLen = R * .04f, bigW = R * .01f)
    val lc = lerp(d.lume, Color.White, glow * .4f)
    for (h in 1..12) { val deg = h * 30f
        when (h) {
            3 -> {}
            12 -> { val tri = Path().apply { val a = at(c, -8f, R * .7f); val b = at(c, 8f, R * .7f); val p = at(c, 0f, R * .54f); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(p.x, p.y); close() }
                if (glow > .05f) drawPath(tri, d.lume.copy(alpha = glow * .3f), style = Stroke(R * .04f))
                drawPath(tri, lc); drawPath(tri, Brush.linearGradient(listOf(d.idx.hi, d.idx.lo), at(c, -8f, R * .7f), at(c, 0f, R * .54f)), style = Stroke(R * .014f)) }
            6, 9 -> applied(c, deg, R * .53f, R * .7f, R * .075f, d.idx, d.lume, glow)
            else -> { val p = at(c, deg, R * .63f)
                if (glow > .05f) drawCircle(d.lume.copy(alpha = glow * .3f), R * .085f, p)
                drawCircle(Color.Black.copy(alpha = .4f), R * .062f, p + Offset(R * .008f, R * .012f)); drawCircle(d.idx.sweep(p), R * .062f, p); drawCircle(lc, R * .048f, p) }
        } }
    wtc("ORIONMD", c.x, c.y - R * .36f, R * .062f, d.ink, HF.semi, .08f)
    wtc("AUTOMATIC CHRONOGRAPH", c.x, c.y - R * .295f, R * .028f, d.ink, spacing = .1f)
    wtc("DIVER'S CHRONOGRAPH", c.x, c.y + R * .3f, R * .034f, d.ink, HF.semi, .06f)
    wtc("1000ft = 300m", c.x, c.y + R * .36f, R * .03f, d.ink, spacing = .05f)
    dateWin(c.x + R * .6f, c.y, R * .1f, "${tm.day}", frame = d.idx)
    hand(c, tm.h * 30f, HS.MERC, R * .42f, R * .065f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.SWORD, R * .66f, R * .07f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .72f, R * .01f, d.accent, lume = d.lume)
    cyclops(c.x + R * .6f, c.y, R * .15f, "${tm.day}")
    glare(c, R * .95f)
}

// ---------------------------------------------------------------- TAG Heuer Monaco style (square)

internal class Mono(val case: Metal, val dial: Color, val sun: Float, val sub: Color, val subInk: Color, val ink: Color, val accent: Color, val idx: Metal, val hands: Metal,
                    val lume: Color, val strap: Strap, val aura: Color, val gulf: Boolean = false)

internal fun DrawScope.wMonaco(s: VisState, d: Mono) {
    val c = wc(); val R = wr(); val tm = wNow(); val hw = R * .92f; val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R, R * .85f, d.strap)
    // crown on the left (the classic layout), pushers on the right at 2 and 4
    sidePusher(c.x - hw + R * .02f, c.y, -R * .15f, R * .22f, d.case, knurl = true)
    sidePusher(c.x + hw - R * .02f, c.y - R * .4f, R * .12f, R * .1f, d.case); sidePusher(c.x + hw - R * .02f, c.y + R * .4f, R * .12f, R * .1f, d.case)
    drawRoundRect(Color.Black.copy(alpha = .5f), Offset(c.x - hw + R * .02f, c.y - hw + R * .05f), Size(2 * hw, 2 * hw), CornerRadius(R * .32f))
    drawRoundRect(d.case.diag(c, hw), Offset(c.x - hw, c.y - hw), Size(2 * hw, 2 * hw), CornerRadius(R * .32f))
    drawRoundRect(d.case.sweep(c), Offset(c.x - hw * .93f, c.y - hw * .93f), Size(2 * hw * .93f, 2 * hw * .93f), CornerRadius(R * .28f))
    val dh = hw * .84f; val dtl = Offset(c.x - dh, c.y - dh); val dsz = Size(2 * dh, 2 * dh); val dcr = CornerRadius(R * .2f)
    val lt = lerp(d.dial, Color.White, d.sun); val dk = lerp(d.dial, Color.Black, d.sun)
    drawRoundRect(Brush.sweepGradient(listOf(d.dial, lt, d.dial, dk, d.dial, lt, d.dial, dk, d.dial), c), dtl, dsz, dcr)
    drawRoundRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .3f)), c, dh * 1.4f), dtl, dsz, dcr)
    if (d.gulf) clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(dtl, dsz), dcr)) }) {
        drawRect(Color(0xFF8FC6E8), Offset(c.x - R * .17f, c.y - dh), Size(R * .08f, dh * 2)); drawRect(Color(0xFFF37021), Offset(c.x - R * .09f, c.y - dh), Size(R * .05f, dh * 2)) }
    // minute track and markers follow the square
    fun edge(deg: Float, k: Float): Float { val a = Math.toRadians(deg.toDouble()); val m = max(abs(sin(a)), abs(cos(a))).toFloat()
        return min(dh * k / m, dh * k * 1.22f) }
    for (k in 0 until 60) { val deg = k * 6f; val e = edge(deg, .97f); val big = k % 5 == 0
        drawLine(d.ink, at(c, deg, e - if (big) R * .05f else R * .025f), at(c, deg, e), if (big) R * .01f else R * .005f) }
    for (h in 1..12) { if (h == 3 || h == 9) continue; val deg = h * 30f; val e = edge(deg, .86f)
        if (h == 12) { applied(c, -3f, e - R * .15f, e, R * .045f, d.idx, d.lume, glow); applied(c, 3f, e - R * .15f, e, R * .045f, d.idx, d.lume, glow) }
        else applied(c, deg, e - R * .14f, e, R * .055f, d.idx, d.lume, glow) }
    wtc("ORIONMD", c.x, c.y - R * .42f, R * .055f, d.ink, HF.wide, .05f)
    wtc("AUTOMATIC CHRONOGRAPH", c.x, c.y - R * .34f, R * .026f, d.ink, spacing = .1f)
    sub(Offset(c.x + R * .36f, c.y), R * .17f, s.wLo(), d.sub, d.subInk, d.accent, listOf("10", "20", "30"), square = true, snail = false, font = HF.semi)
    sub(Offset(c.x - R * .36f, c.y), R * .17f, s.wMid(), d.sub, d.subInk, d.accent, listOf("3", "6", "9", "12"), square = true, snail = false, font = HF.semi)
    dateWin(c.x, c.y + R * .56f, R * .07f, "${tm.day}", frame = d.idx)
    hand(c, tm.h * 30f, HS.BATON, R * .42f, R * .055f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.BATON, R * .64f, R * .045f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .7f, R * .011f, d.accent)
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .14f), Color.Transparent), dtl, c), dtl, dsz, dcr)
}

// ---------------------------------------------------------------- TAG Heuer Carrera style

internal class Carr(val case: Metal, val dial: Color, val sun: Float, val flange: Color, val flangeInk: Color, val sub: Color, val subRing: Metal?, val subInk: Color, val ink: Color,
                    val accent: Color, val idx: Metal, val hands: Metal, val lume: Color, val strap: Strap, val aura: Color)

internal fun DrawScope.wCarrera(s: VisState, d: Carr) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * .95f, R * .8f, d.strap); lugs(c, R, R * .95f, d.case, thick = .13f)
    pusher(c, R, d.case, 58f, .12f, .14f); pusher(c, R, d.case, 122f, .12f, .14f); crown(c, R, d.case, w = .24f, len = .12f)
    caseRound(c, R, d.case)
    ring(c, R * .87f, R * .97f, d.case.sweep(c))
    drawCircle(Brush.radialGradient(listOf(lerp(d.flange, Color.White, .1f), d.flange, lerp(d.flange, Color.Black, .4f)), c, R * .87f), R * .87f, c)
    track(c, R * .87f, 60, R * .02f, R * .004f, d.flangeInk, every = 5, bigLen = R * .03f, bigW = R * .008f)
    tachy(c, R * .805f, R * .045f, d.flangeInk, HF.semi, label = false)
    dialFace(c, R * .75f, d.dial, d.sun)
    track(c, R * .745f, 240, R * .014f, R * .003f, d.ink, every = 4, bigLen = R * .03f, bigW = R * .007f)
    for (h in 1..12) when (h) {
        3, 6, 9 -> {}
        12 -> { applied(c, -2.6f, R * .52f, R * .69f, R * .04f, d.idx, d.lume, glow); applied(c, 2.6f, R * .52f, R * .69f, R * .04f, d.idx, d.lume, glow) }
        else -> applied(c, h * 30f, R * .53f, R * .69f, R * .045f, d.idx, d.lume, glow)
    }
    wtc("ORIONMD", c.x, c.y - R * .38f, R * .055f, d.ink, HF.wide, .04f)
    wtc("CHRONOGRAPH", c.x, c.y - R * .31f, R * .028f, d.accent, HF.semi, .2f)
    val sr = R * .17f; val sd = R * .33f
    sub(Offset(c.x + sd, c.y), sr, s.wLo(), d.sub, d.subInk, d.accent, listOf("10", "20", "30"), d.subRing?.sweep(Offset(c.x + sd, c.y)), font = HF.semi)
    sub(Offset(c.x, c.y + sd), sr, tm.s / 60f, d.sub, d.subInk, d.subInk, listOf("20", "40", "60"), d.subRing?.sweep(Offset(c.x, c.y + sd)), font = HF.semi)
    sub(Offset(c.x - sd, c.y), sr, s.wMid(), d.sub, d.subInk, d.accent, listOf("3", "6", "9", "12"), d.subRing?.sweep(Offset(c.x - sd, c.y)), font = HF.semi)
    hand(c, tm.h * 30f, HS.BATON, R * .43f, R * .05f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.BATON, R * .66f, R * .042f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .72f, R * .01f, d.accent)
    glare(c, R * .87f)
}

// ---------------------------------------------------------------- Omega Seamaster Planet Ocean style

internal class Po(val case: Metal, val bezel: Color, val bezelInk: Color, val quarter: Color?, val dial: Color, val sun: Float, val ink: Color, val accent: Color, val idx: Metal,
                  val hands: Metal, val lume: Color, val sub: Color, val strap: Strap, val aura: Color, val dateBg: Color = Color.White, val dateInk: Color = Color.Black)

internal fun DrawScope.wPlanet(s: VisState, d: Po) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R, R * .8f, d.strap); lugs(c, R, R, d.case, thick = .16f)
    crown(c, R, d.case, 300f, w = .2f, len = .09f)   // helium valve
    pusher(c, R, d.case, 58f, .14f, .12f); pusher(c, R, d.case, 122f, .14f, .12f); guards(c, R, d.case); crown(c, R, d.case, w = .27f, len = .14f)
    caseRound(c, R, d.case)
    ring(c, R * .91f, R * .99f, d.case.sweep(c))
    for (k in 0 until 72) drawCircle(d.case.lo, R * .012f, at(c, k * 5f, R * .995f))   // scalloped edge
    ring(c, R * .76f, R * .915f, ceramic(d.bezel, c))
    for (k in 0 until 60) { val deg = k * 6f; val col = if (d.quarter != null && k in 1..15) d.quarter else d.bezelInk
        when {
            k == 0 -> { drawPath(Path().apply { val a = at(c, -5f, R * .9f); val b = at(c, 5f, R * .9f); val p = at(c, 0f, R * .79f); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(p.x, p.y); close() }, d.bezelInk)
                drawCircle(lerp(d.lume, Color.White, glow * .4f), R * .02f, at(c, 0f, R * .87f)) }
            k % 10 == 0 -> wtr("$k", c, deg, R * .838f, R * .095f, col, HF.semi)
            k % 5 == 0 -> drawCircle(col, R * .018f, at(c, deg, R * .85f))
            k <= 15 -> drawLine(col, at(c, deg, R * .84f), at(c, deg, R * .89f), R * .008f)
        } }
    drawCircle(Color.Black.copy(alpha = .4f), R * .76f, c)
    dialFace(c, R * .74f, d.dial, d.sun)
    track(c, R * .735f, 60, R * .025f, R * .006f, d.ink, every = 5, bigLen = R * .04f, bigW = R * .012f)
    for (h in 1..11) if (h != 3 && h != 6 && h != 9) applied(c, h * 30f, R * .5f, R * .68f, R * .06f, d.idx, d.lume, glow)
    wtc("12", c.x + R * .006f, c.y - R * .59f + R * .01f, R * .15f, Color.Black.copy(alpha = .45f), HF.semi)
    wtc("12", c.x, c.y - R * .59f, R * .15f, lerp(d.lume, Color.White, glow * .4f), HF.semi, glow = if (glow > .1f) d.lume else null)
    wtc("ORIONMD", c.x, c.y - R * .37f, R * .055f, d.ink, HF.wide, .04f)
    wtc("AUTOMATIC CHRONOGRAPH", c.x, c.y - R * .3f, R * .026f, d.ink, spacing = .08f)
    wtc("CHRONOMETER", c.x, c.y + R * .3f, R * .03f, d.ink, spacing = .15f)
    wtc("600m / 2000ft", c.x, c.y + R * .37f, R * .034f, d.accent, HF.semi)
    dateWin(c.x, c.y + R * .56f, R * .085f, "${tm.day}", d.dateBg, d.dateInk, d.idx)
    sub(Offset(c.x + R * .36f, c.y), R * .18f, s.wLo(), d.sub, d.ink, d.accent, listOf("15", "30", "45", "60"), font = HF.semi, v2 = s.wHi())
    sub(Offset(c.x - R * .36f, c.y), R * .18f, tm.s / 60f, d.sub, d.ink, d.ink, listOf("15", "30", "45", "60"), font = HF.semi)
    hand(c, tm.h * 30f, HS.ARROW, R * .42f, R * .07f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.SWORD, R * .65f, R * .06f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .72f, R * .011f, d.accent, d.accent, d.lume, arrow = true)
    glare(c, R * .91f)
}

// ---------------------------------------------------------------- Richard Mille style (tonneau, skeleton)

internal class Rm(val case: Color, val layers: Color?, val screws: Metal, val flange: Color, val ink: Color, val accent: Color, val accent2: Color, val bridge: Metal, val wheels: Metal,
                  val strap: Strap, val aura: Color)

private fun tonneau(c: Offset, hw: Float, hh: Float) = Path().apply {
    moveTo(c.x - hw * .72f, c.y - hh)
    quadraticBezierTo(c.x, c.y - hh * 1.035f, c.x + hw * .72f, c.y - hh)
    quadraticBezierTo(c.x + hw * .93f, c.y - hh * .99f, c.x + hw * .95f, c.y - hh * .8f)
    cubicTo(c.x + hw * 1.04f, c.y - hh * .3f, c.x + hw * 1.04f, c.y + hh * .3f, c.x + hw * .95f, c.y + hh * .8f)
    quadraticBezierTo(c.x + hw * .93f, c.y + hh * .99f, c.x + hw * .72f, c.y + hh)
    quadraticBezierTo(c.x, c.y + hh * 1.035f, c.x - hw * .72f, c.y + hh)
    quadraticBezierTo(c.x - hw * .93f, c.y + hh * .99f, c.x - hw * .95f, c.y + hh * .8f)
    cubicTo(c.x - hw * 1.04f, c.y + hh * .3f, c.x - hw * 1.04f, c.y - hh * .3f, c.x - hw * .95f, c.y - hh * .8f)
    quadraticBezierTo(c.x - hw * .93f, c.y - hh * .99f, c.x - hw * .72f, c.y - hh)
    close()
}
private fun DrawScope.gear(c: Offset, r: Float, teeth: Int, deg: Float, m: Metal, spokes: Int = 5) {
    rotate(deg, c) {
        for (k in 0 until teeth) drawLine(m.mid, at(c, k * 360f / teeth, r * .9f), at(c, k * 360f / teeth, r * 1.06f), r * 2.4f / teeth * 1.2f)
        drawCircle(m.sweep(c), r * .92f, c, style = Stroke(r * .16f))
        for (k in 0 until spokes) drawLine(m.mid, c, at(c, k * 360f / spokes, r * .85f), r * .1f)
        drawCircle(m.sweep(c), r * .22f, c)
    }
    drawCircle(Color(0xFFB0102A), r * .1f, c); drawCircle(Color.White.copy(alpha = .5f), r * .04f, c - Offset(r * .03f, r * .03f))
}

internal fun DrawScope.wRichard(s: VisState, d: Rm) {
    val c = wc(); val R = wr(); val tm = wNow(); val hw = R * .86f; val hh = R; val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * 1.18f, R * .85f, d.strap)
    sidePusher(c.x + hw * .97f, c.y, R * .17f, R * .26f, d.screws, knurl = true)
    sidePusher(c.x + hw * .95f, c.y - R * .44f, R * .15f, R * .16f, d.screws, d.accent2); sidePusher(c.x + hw * .95f, c.y + R * .44f, R * .15f, R * .16f, d.screws, d.accent2)
    val shape = tonneau(c, hw, hh)
    translate(R * .02f, R * .05f) { drawPath(shape, Color.Black.copy(alpha = .5f)) }
    drawPath(shape, Brush.linearGradient(listOf(lerp(d.case, Color.White, .25f), d.case, lerp(d.case, Color.Black, .35f), d.case, lerp(d.case, Color.White, .15f)), Offset(c.x - hw, c.y - hh), Offset(c.x + hw, c.y + hh)))
    d.layers?.let { lay -> clipPath(shape) {   // layered carbon / quartz composite
        val step = R * .034f; var y = c.y - hh * 1.05f; var i = 0
        while (y < c.y + hh * 1.05f) { val p = Path(); var x = c.x - hw * 1.1f; p.moveTo(x, y)
            while (x < c.x + hw * 1.1f) { x += R * .05f; p.lineTo(x, y + sin(x / R * 7f + i * 1.7f) * R * .018f + sin(x / R * 2.3f + i * .6f) * R * .02f) }
            drawPath(p, lay.copy(alpha = if (i % 2 == 0) .45f else .15f), style = Stroke(R * .016f)); y += step; i++ } } }
    drawPath(tonneau(c, hw * .9f, hh * .9f), Color.Black.copy(alpha = .35f), style = Stroke(R * .01f))
    for (x in floatArrayOf(-.58f, -.2f, .2f, .58f)) for (dir in intArrayOf(-1, 1)) { val p = Offset(c.x + hw * x, c.y + dir * hh * .945f)
        drawCircle(d.screws.sweep(p), R * .036f, p)
        drawPath(Path().apply { for (k in 0 until 5) { val q = at(p, k * 72f + x * 90f, R * .018f); if (k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close() }, d.screws.lo) }
    // the open dial: the movement shows through
    val dial = tonneau(c, hw * .8f, hh * .82f)
    clipPath(dial) {
        drawRect(Color(0xFF0B0C0E), Offset(c.x - hw, c.y - hh), Size(hw * 2, hh * 2))
        val u = R
        fun P(x: Float, y: Float) = Offset(c.x + x * u, c.y + y * u)
        val spin = s.spin
        gear(P(0f, -.42f), u * .2f, 40, spin * 8f, d.wheels)
        gear(P(.3f, -.12f), u * .12f, 28, -spin * 30f, d.wheels, 4)
        gear(P(-.28f, .22f), u * .11f, 26, spin * 45f, d.wheels, 4)
        gear(P(.3f, .36f), u * .07f, 18, -spin * 140f, d.wheels, 3)
        // balance wheel: swings harder with the bass
        val bal = P(-.05f, .5f); val osc = sin(spin * 9f) * (60f + 140f * s.wLo())
        rotate(osc, bal) { drawCircle(d.wheels.sweep(bal), u * .15f, bal, style = Stroke(u * .025f)); for (k in 0 until 4) drawLine(d.wheels.mid, bal, at(bal, k * 90f + 45f, u * .15f), u * .012f) }
        drawCircle(Color(0xFFB0102A), u * .015f, bal)
        // skeleton bridges
        val bl = listOf(listOf(P(-.5f, -.62f), P(-.5f, .62f)), listOf(P(.5f, -.62f), P(.5f, .62f)), listOf(P(-.5f, -.2f), P(0f, -.02f), P(.5f, -.42f)),
            listOf(P(-.5f, .3f), P(.02f, .14f), P(.5f, .5f)), listOf(P(-.5f, .62f), P(.5f, .62f)))
        for (b in bl) for (i in 0 until b.size - 1) { drawLine(Color.Black.copy(alpha = .6f), b[i], b[i + 1], u * .1f, StrokeCap.Round); drawLine(d.bridge.mid, b[i], b[i + 1], u * .075f, StrokeCap.Round)
            drawLine(d.bridge.hi.copy(alpha = .5f), b[i] - Offset(0f, u * .02f), b[i + 1] - Offset(0f, u * .02f), u * .015f, StrokeCap.Round) }
        for (b in bl) for (p in b) { drawCircle(d.screws.sweep(p), u * .025f, p); drawLine(d.screws.lo, p - Offset(u * .015f, 0f), p + Offset(u * .015f, 0f), u * .006f) }
        // flange with the minute track
        drawPath(dial, d.flange, style = Stroke(R * .16f))
        val a = hw * .8f * .9f; val b = hh * .82f * .9f
        fun ell(deg: Float, k: Float) = Offset(c.x + a * k * sin(Math.toRadians(deg.toDouble())).toFloat(), c.y - b * k * cos(Math.toRadians(deg.toDouble())).toFloat())
        for (k in 0 until 60) { val deg = k * 6f; drawLine(d.ink.copy(alpha = .8f), ell(deg, if (k % 5 == 0) .93f else .965f), ell(deg, 1f), if (k % 5 == 0) R * .01f else R * .004f) }
        for (h in 1..12) { val p = ell(h * 30f, .86f); wtc("$h", p.x, p.y, R * .065f, if (h % 3 == 0) d.accent else d.ink, HF.semi) }
    }
    // counters and big date over the movement
    sub(Offset(c.x - R * .3f, c.y - R * .05f), R * .17f, s.wLo(), null, d.ink, d.accent, listOf("15", "30", "45", "60"), SolidColor(d.accent), .06f, snail = false, font = HF.semi)
    sub(Offset(c.x + R * .12f, c.y + R * .3f), R * .14f, s.wMid(), null, d.ink, d.accent, listOf("6", "12"), SolidColor(d.ink.copy(alpha = .6f)), .06f, snail = false, font = HF.semi)
    val dd = "%02d".format(tm.day)
    for (i in 0..1) { val x = c.x - R * .055f + i * R * .11f; val y = c.y - R * .6f
        drawRoundRect(d.screws.sweep(Offset(x, y)), Offset(x - R * .05f, y - R * .06f), Size(R * .1f, R * .12f), CornerRadius(R * .02f))
        drawRoundRect(Color.White, Offset(x - R * .04f, y - R * .05f), Size(R * .08f, R * .1f), CornerRadius(R * .015f)); wtc(dd[i].toString(), x, y, R * .085f, Color.Black, HF.semi) }
    val fn = listOf("W", "N", "H"); val on = ((s.spin * .4f).toInt() % 3)
    fn.forEachIndexed { i, l -> wtc(l, c.x + R * .4f + (i - 1) * R * .065f, c.y + R * .05f, R * .055f, if (i == on) d.accent else d.ink.copy(alpha = .5f), HF.semi) }
    hand(c, tm.h * 30f, HS.SKEL, R * .4f, R * .07f, PLAT, d.lume(), glow, solid = d.ink)
    hand(c, tm.m * 6f, HS.SKEL, R * .6f, R * .06f, PLAT, d.lume(), glow, solid = d.ink)
    chrono(c, s.chronoDeg(), R * .66f, R * .012f, d.accent, big = true)
    clipPath(dial) { drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = .12f), Color.Transparent), Offset(c.x - hw, c.y - hh), c), Offset(c.x - hw, c.y - hh), Size(hw * 2, hh * 2)) }
}
private fun Rm.lume() = Color(0xFFDDF5E8)

// ---------------------------------------------------------------- Patek Philippe style: 5170 chronograph, 5270 perpetual

internal class Pp(val case: Metal, val dial: Color, val sun: Float, val sub: Color, val ink: Color, val tachInk: Color, val idx: Metal, val hands: Metal, val chrono: Color,
                  val strap: Strap, val aura: Color, val perpetual: Boolean = false)

internal fun DrawScope.wPatek(s: VisState, d: Pp) {
    val c = wc(); val R = wr(); val tm = wNow()
    aura(s, c, R, d.aura)
    strap(c, R * .88f, R * .8f, d.strap); lugs(c, R, R * .88f, d.case, reach = 1.18f, thick = .11f)
    pusher(c, R, d.case, 58f, .11f, .12f); pusher(c, R, d.case, 122f, .11f, .12f); crown(c, R, d.case, w = .22f, len = .12f)
    caseRound(c, R, d.case)
    ring(c, R * .88f, R * .985f, d.case.sweep(c))
    drawCircle(d.case.hi.copy(alpha = .6f), R * .885f, c, style = Stroke(R * .01f))
    dialFace(c, R * .88f, d.dial, d.sun)
    track(c, R * .86f, 300, R * .012f, R * .0025f, d.ink, every = 5, bigLen = R * .025f, bigW = R * .005f)
    drawCircle(d.ink.copy(alpha = .6f), R * .83f, c, style = Stroke(R * .003f))
    tachy(c, R * .785f, R * .04f, d.tachInk, SERIF, label = false)
    drawCircle(d.ink.copy(alpha = .4f), R * .745f, c, style = Stroke(R * .003f))
    for (h in 1..12) { if (h == 3 || h == 9 || (d.perpetual && (h == 12 || h == 6))) continue
        val p = at(c, h * 30f, R * .62f); wtm("$h", p.x, p.y, R * .12f, d.idx, SERIF) }
    if (d.perpetual) {
        for ((i, txt) in listOf(DOW[tm.dow - 1], MONTHS[tm.mon - 1]).withIndex()) { val x = c.x + (i * 2 - 1) * R * .13f
            dateWin(x, c.y - R * .5f, R * .075f, txt, Color(0xFFF7F4EC), d.ink, d.idx, R * .17f, SERIF_B) }
        // moon phase window over the date at 6
        val mc = Offset(c.x, c.y + R * .56f); val mr = R * .13f
        clipPath(Path().apply { addArc(Rect(mc, mr), 180f, 180f); close() }) {
            drawCircle(Color(0xFF15245A), mr, mc)
            for (k in 0 until 9) drawCircle(GOLD.hi, R * .006f, Offset(mc.x + sin(k * 2.1f) * mr * .85f, mc.y - abs(cos(k * 1.3f)) * mr * .8f))
            // the shadow slides off the moon as it waxes and back over it as it wanes
            val mo = Offset(mc.x, mc.y - mr * .5f); val rr = mr * .32f; val k = cos(2 * PI * tm.moon).toFloat()
            drawCircle(GOLD.mid, rr, mo)
            drawCircle(Color(0xFF15245A), rr * 1.02f, mo + Offset((if (tm.moon < .5f) -1f else 1f) * (1f - k) * rr, 0f))
        }
        drawArc(d.idx.sweep(mc), 180f, 180f, false, Offset(mc.x - mr, mc.y - mr), Size(mr * 2, mr * 2), style = Stroke(R * .012f))
        wtc("${tm.day}", c.x, c.y + R * .66f, R * .05f, d.ink, SERIF_B)
    }
    wtc("ORIONMD", c.x, c.y - R * (if (d.perpetual) .35f else .38f), R * .052f, d.ink, SERIF_B, .1f)
    wtc("CHRONOGRAPH", c.x, c.y + R * (if (d.perpetual) .28f else .34f), R * .03f, d.ink, SERIF, .2f)
    sub(Offset(c.x + R * .33f, c.y), R * .19f, s.wLo(), d.sub, d.ink, d.hands.mid, listOf("10", "20", "30"), font = SERIF)
    sub(Offset(c.x - R * .33f, c.y), R * .19f, tm.s / 60f, d.sub, d.ink, d.hands.mid, listOf("20", "40", "60"), font = SERIF)
    hand(c, tm.h * 30f, HS.LEAF, R * .45f, R * .075f, d.hands)
    hand(c, tm.m * 6f, HS.LEAF, R * .7f, R * .06f, d.hands)
    chrono(c, s.chronoDeg(), R * .8f, R * .007f, d.chrono)
    glare(c, R * .88f)
}

// ---------------------------------------------------------------- Patek Philippe style: Nautilus and Aquanaut chronographs

internal class Naut(val case: Metal, val dial: Color, val edge: Color, val ink: Color, val accent: Color, val idx: Metal, val hands: Metal, val lume: Color, val strap: Strap,
                    val aura: Color, val aqua: Boolean = false)

/** An octagon with flat top and sides; [round] 0 = sharp corners, 1 = fully rounded. */
private fun oct(c: Offset, r: Float, round: Float): Path = Path().apply {
    val v = (0 until 8).map { at(c, 22.5f + 45f * it, r) }
    fun mix(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    for (i in 0 until 8) { val a = v[i]; val p1 = mix(a, v[(i + 7) % 8], round * .5f); val p2 = mix(a, v[(i + 1) % 8], round * .5f)
        if (i == 0) moveTo(p1.x, p1.y) else lineTo(p1.x, p1.y); quadraticBezierTo(a.x, a.y, p2.x, p2.y) }
    close()
}

internal fun DrawScope.wNautilus(s: VisState, d: Naut) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * 1.08f, R * .75f, d.strap)
    pusher(c, R * 1.02f, d.case, 56f, .12f, .1f); pusher(c, R * 1.02f, d.case, 124f, .12f, .1f); crown(c, R * 1.1f, d.case, w = .2f, len = .07f)
    for (side in intArrayOf(-1, 1)) { val x = if (side < 0) c.x - R * 1.1f else c.x + R * .92f   // the "ears"
        drawRoundRect(d.case.vt(c.y - R * .28f, c.y + R * .28f), Offset(x, c.y - R * .28f), Size(R * .18f, R * .56f), CornerRadius(R * .07f)) }
    val body = oct(c, R * 1.04f, .75f)
    translate(R * .02f, R * .05f) { drawPath(body, Color.Black.copy(alpha = .5f)) }
    drawPath(body, d.case.diag(c, R))
    drawPath(oct(c, R * .96f, .75f), Brush.verticalGradient(listOf(d.case.hi, d.case.mid, d.case.lo, d.case.mid, d.case.hi), c.y - R, c.y + R))
    drawPath(oct(c, R * .82f, .75f), d.case.hi, style = Stroke(R * .03f))
    val dial = oct(c, R * .8f, .75f)
    clipPath(dial) {
        drawRect(Brush.radialGradient(listOf(lerp(d.dial, Color.White, .14f), d.dial, d.edge), c, R * .85f), Offset(c.x - R, c.y - R), Size(R * 2, R * 2))
        if (d.aqua) { val st = R * .1f; var x = c.x - R; while (x < c.x + R) { var y = c.y - R; while (y < c.y + R) {
            drawRoundRect(Color.Black.copy(alpha = .3f), Offset(x + st * .08f, y + st * .1f), Size(st * .84f, st * .84f), CornerRadius(st * .2f))
            drawRoundRect(Color.White.copy(alpha = .06f), Offset(x + st * .06f, y + st * .06f), Size(st * .84f, st * .84f), CornerRadius(st * .2f)); y += st }; x += st } }
        else { var y = c.y - R; while (y < c.y + R) { drawLine(Color.Black.copy(alpha = .3f), Offset(c.x - R, y), Offset(c.x + R, y), R * .012f)
            drawLine(Color.White.copy(alpha = .07f), Offset(c.x - R, y + R * .013f), Offset(c.x + R, y + R * .013f), R * .008f); y += R * .038f } }
    }
    track(c, R * .76f, 60, R * .025f, R * .005f, d.ink, every = 5, bigLen = R * .04f, bigW = R * .01f)
    if (d.aqua) {
        for (h in 1..12) when (h) {
            2, 4, 8, 10, 12 -> { val p = at(c, h * 30f, R * .6f); wtc("$h", p.x + R * .008f, p.y + R * .012f, R * .15f, Color.Black.copy(alpha = .4f), HF.semi)
                wtc("$h", p.x, p.y, R * .15f, lerp(d.lume, Color.White, glow * .4f), HF.semi, glow = if (glow > .1f) d.lume else null) }
            1, 5, 7, 11 -> applied(c, h * 30f, R * .6f, R * .7f, R * .04f, d.idx, d.lume, glow)
        }
    } else for (h in 1..12) when (h) {
        3, 6 -> {}
        12 -> { applied(c, -3f, R * .5f, R * .68f, R * .045f, d.idx, d.lume, glow); applied(c, 3f, R * .5f, R * .68f, R * .045f, d.idx, d.lume, glow) }
        else -> applied(c, h * 30f, R * .5f, R * .68f, R * .05f, d.idx, d.lume, glow)
    }
    wtc("ORIONMD", c.x, c.y - R * .36f, R * .05f, d.ink, SERIF_B, .08f)
    wtc("AUTOMATIC", c.x, c.y - R * .3f, R * .026f, d.ink, spacing = .15f)
    if (d.aqua) wtc("FLYBACK CHRONOGRAPH", c.x, c.y - R * .24f, R * .026f, d.accent, HF.semi, .08f)
    dateWin(c.x + R * .6f, c.y, R * .08f, "${tm.day}", lerp(d.dial, Color.Black, .3f), d.ink, d.idx)
    sub(Offset(c.x, c.y + R * .33f), R * .23f, s.wLo(), lerp(d.dial, Color.Black, .18f), d.ink, d.accent, listOf("15", "30", "45", "60"), font = HF.semi, v2 = s.wMid())
    hand(c, tm.h * 30f, HS.BATON, R * .42f, R * .055f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.BATON, R * .64f, R * .045f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .72f, R * .009f, d.accent)
    clipPath(dial) { glare(c, R * .8f) }
}

// ---------------------------------------------------------------- Audemars Piguet style: Royal Oak and Royal Oak Offshore

internal class Ro(val case: Metal, val dial: Color, val sub: Color, val subRing: Metal?, val ink: Color, val subInk: Color, val idx: Metal, val hands: Metal, val lume: Color,
                  val accent: Color, val strap: Strap, val aura: Color, val screws: Metal, val offshore: Boolean = false)

internal fun DrawScope.wRoyalOak(s: VisState, d: Ro) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * (if (d.offshore) 1.0f else 1.12f), R * .8f, d.strap)
    if (d.offshore) { pusher(c, R * 1.02f, d.case, 56f, .17f, .14f, cap = Color(0xFF141414)); pusher(c, R * 1.02f, d.case, 124f, .17f, .14f, cap = Color(0xFF141414))
        crown(c, R * 1.02f, d.case, w = .26f, len = .13f, cap = Color(0xFF141414)) }
    else { pusher(c, R * 1.02f, d.case, 58f, .11f, .1f); pusher(c, R * 1.02f, d.case, 122f, .11f, .1f); crown(c, R * 1.02f, d.case, w = .22f, len = .1f) }
    val tl = Offset(c.x - R * 1.02f, c.y - R * 1.0f); val sz = Size(R * 2.04f, R * 2.0f)
    drawRoundRect(Color.Black.copy(alpha = .5f), tl + Offset(R * .02f, R * .05f), sz, CornerRadius(R * .55f))
    drawRoundRect(d.case.diag(c, R), tl, sz, CornerRadius(R * .55f))
    if (d.offshore) drawPath(oct(c, R * 1.0f, 0f), Color(0xFF111111))   // the gasket
    val bez = oct(c, R * .95f, 0f)
    drawPath(bez, Brush.verticalGradient(listOf(d.case.mid, d.case.hi, d.case.mid, d.case.lo, d.case.mid), c.y - R, c.y + R))
    drawPath(bez, d.case.hi.copy(alpha = .8f), style = Stroke(R * .02f))
    drawPath(oct(c, R * .82f, 0f), d.case.lo, style = Stroke(R * .012f))
    for (i in 0 until 8) { val p = at(c, 22.5f + 45f * i, R * .86f); val hx = Path().apply { for (k in 0 until 6) { val q = at(p, k * 60f + 30f, R * .042f); if (k == 0) moveTo(q.x, q.y) else lineTo(q.x, q.y) }; close() }
        drawPath(hx, d.screws.sweep(p)); drawPath(hx, d.screws.lo, style = Stroke(R * .006f)) }
    // flange with the minute track, then the "tapisserie" dial
    drawCircle(lerp(d.dial, Color.Black, .25f), R * .79f, c)
    track(c, R * .79f, 60, R * .03f, R * .006f, d.ink, every = 5, bigLen = R * .05f, bigW = R * .01f)
    val dr = R * .73f
    drawCircle(Brush.radialGradient(listOf(lerp(d.dial, Color.White, .1f), d.dial, lerp(d.dial, Color.Black, .25f)), c, dr), dr, c)
    clipPath(Path().apply { addOval(Rect(c, dr)) }) {
        val st = R * (if (d.offshore) .085f else .048f); var p = c.x - dr
        while (p < c.x + dr) { drawLine(Color.Black.copy(alpha = .35f), Offset(p, c.y - dr), Offset(p, c.y + dr), st * .2f)
            drawLine(Color.White.copy(alpha = .1f), Offset(p + st * .14f, c.y - dr), Offset(p + st * .14f, c.y + dr), st * .08f); p += st }
        p = c.y - dr
        while (p < c.y + dr) { drawLine(Color.Black.copy(alpha = .35f), Offset(c.x - dr, p), Offset(c.x + dr, p), st * .2f)
            drawLine(Color.White.copy(alpha = .1f), Offset(c.x - dr, p + st * .14f), Offset(c.x + dr, p + st * .14f), st * .08f); p += st }
    }
    for (h in 1..12) when (h) {
        3, 6, 9 -> {}
        12 -> { applied(c, -2.8f, R * .5f, R * .68f, R * .045f, d.idx, d.lume, glow); applied(c, 2.8f, R * .5f, R * .68f, R * .045f, d.idx, d.lume, glow) }
        else -> applied(c, h * 30f, R * .52f, R * .68f, R * .05f, d.idx, d.lume, glow)
    }
    val dp = at(c, 135f, R * .6f); dateWin(dp.x, dp.y, R * .07f, "${tm.day}", frame = d.idx)
    wtc("ORIONMD", c.x, c.y - R * .39f, R * .05f, d.ink, HF.semi, .05f)
    wtc(if (d.offshore) "OFFSHORE CHRONOGRAPH" else "AUTOMATIC", c.x, c.y - R * .33f, R * .025f, d.ink, spacing = .12f)
    val sr = R * .17f; val sd = R * .33f
    sub(Offset(c.x + sd, c.y), sr, s.wLo(), d.sub, d.subInk, d.hands.mid, listOf("10", "20", "30"), d.subRing?.sweep(Offset(c.x + sd, c.y)), font = HF.semi, tip = d.accent)
    sub(Offset(c.x, c.y + sd), sr, s.wMid(), d.sub, d.subInk, d.hands.mid, listOf("3", "6", "9", "12"), d.subRing?.sweep(Offset(c.x, c.y + sd)), font = HF.semi, tip = d.accent)
    sub(Offset(c.x - sd, c.y), sr, tm.s / 60f, d.sub, d.subInk, d.hands.mid, listOf("20", "40", "60"), d.subRing?.sweep(Offset(c.x - sd, c.y)), font = HF.semi)
    hand(c, tm.h * 30f, HS.BATON, R * .43f, R * .06f, d.hands, d.lume, glow)
    hand(c, tm.m * 6f, HS.BATON, R * .66f, R * .05f, d.hands, d.lume, glow)
    chrono(c, s.chronoDeg(), R * .72f, R * .01f, d.hands.hi, tip = d.accent)
    glare(c, R * .79f)
}

// ---------------------------------------------------------------- IWC style: Portugieser chronograph

internal class Port(val case: Metal, val dial: Color, val sun: Float, val ink: Color, val num: Metal, val hands: Metal, val chrono: Color, val sub: Color, val strap: Strap, val aura: Color)

internal fun DrawScope.wPortugieser(s: VisState, d: Port) {
    val c = wc(); val R = wr(); val tm = wNow()
    aura(s, c, R, d.aura)
    strap(c, R * .86f, R * .82f, d.strap); lugs(c, R, R * .86f, d.case, reach = 1.18f, thick = .11f)
    pusher(c, R, d.case, 58f, .1f, .14f); pusher(c, R, d.case, 122f, .1f, .14f); crown(c, R, d.case, w = .25f, len = .14f)
    caseRound(c, R, d.case)
    ring(c, R * .925f, R * .985f, d.case.sweep(c))
    dialFace(c, R * .925f, d.dial, d.sun)
    // railway minute track
    drawCircle(d.ink, R * .885f, c, style = Stroke(R * .004f)); drawCircle(d.ink, R * .845f, c, style = Stroke(R * .004f))
    track(c, R * .885f, 240, R * .04f, R * .0025f, d.ink, every = 4, bigLen = R * .04f, bigW = R * .008f)
    for (h in 1..11) if (h != 6) { val p = at(c, h * 30f, R * .7f); wtm("$h", p.x, p.y, R * .14f, d.num, LIGHT) }
    wtc("ORIONMD", c.x, c.y - R * .12f, R * .045f, d.ink, SERIF_B, .1f)
    wtc("CHRONOGRAPH", c.x, c.y + R * .14f, R * .028f, d.ink, LIGHT, .25f)
    sub(Offset(c.x, c.y - R * .4f), R * .2f, s.wLo(), d.sub, d.ink, d.hands.mid, listOf("10", "20", "30"), SolidColor(d.ink.copy(alpha = .5f)), .03f, font = LIGHT)
    sub(Offset(c.x, c.y + R * .4f), R * .2f, tm.s / 60f, d.sub, d.ink, d.hands.mid, listOf("20", "40", "60"), SolidColor(d.ink.copy(alpha = .5f)), .03f, font = LIGHT)
    hand(c, tm.h * 30f, HS.LEAF, R * .5f, R * .085f, d.hands)
    hand(c, tm.m * 6f, HS.LEAF, R * .78f, R * .065f, d.hands)
    chrono(c, s.chronoDeg(), R * .84f, R * .007f, d.chrono)
    glare(c, R * .925f)
}

// ---------------------------------------------------------------- IWC style: Pilot's chronograph

internal class Pil(val case: Metal, val dial: Color, val ink: Color, val lume: Color, val handCol: Color, val chrono: Color, val sub: Color, val strap: Strap, val aura: Color)

internal fun DrawScope.wPilot(s: VisState, d: Pil) {
    val c = wc(); val R = wr(); val tm = wNow(); val glow = s.wLo()
    aura(s, c, R, d.aura)
    strap(c, R * .95f, R * .8f, d.strap); lugs(c, R, R * .95f, d.case)
    pusher(c, R, d.case, 58f, .13f, .13f); pusher(c, R, d.case, 122f, .13f, .13f); crown(c, R, d.case, w = .3f, len = .16f)
    caseRound(c, R, d.case)
    ring(c, R * .88f, R * .975f, d.case.sweep(c))
    dialFace(c, R * .88f, d.dial, 0f)
    track(c, R * .865f, 60, R * .035f, R * .007f, d.ink, every = 5, bigLen = R * .06f, bigW = R * .014f) { it == 0 }
    drawPath(Path().apply { val a = at(c, -4.5f, R * .86f); val b = at(c, 4.5f, R * .86f); val p = at(c, 0f, R * .75f); moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(p.x, p.y); close() }, lerp(d.lume, Color.White, glow * .4f))
    drawCircle(d.ink, R * .016f, at(c, -9f, R * .83f)); drawCircle(d.ink, R * .016f, at(c, 9f, R * .83f))
    for (h in intArrayOf(1, 2, 4, 5, 7, 8, 10, 11)) { val p = at(c, h * 30f, R * .67f)
        wtc("$h", p.x, p.y, R * .15f, lerp(d.lume, Color.White, glow * .3f), COND, glow = if (glow > .2f) d.lume.copy(alpha = .6f) else null) }
    wtc("ORIONMD", c.x, c.y - R * .11f, R * .042f, d.ink, HF.semi, .1f)
    wtc("PILOT'S CHRONOGRAPH", c.x, c.y + R * .12f, R * .025f, d.ink, spacing = .1f)
    sub(Offset(c.x, c.y - R * .37f), R * .18f, s.wLo(), d.sub, d.ink, d.handCol, listOf("10", "20", "30"), font = COND, snail = false)
    sub(Offset(c.x, c.y + R * .37f), R * .18f, s.wMid(), d.sub, d.ink, d.handCol, listOf("3", "6", "9", "12"), font = COND, snail = false)
    sub(Offset(c.x - R * .37f, c.y), R * .18f, tm.s / 60f, d.sub, d.ink, d.handCol, listOf("20", "40", "60"), font = COND, snail = false)
    dateWin(c.x + R * .5f, c.y, R * .085f, "${DOW[tm.dow - 1]} ${tm.day}", Color(0xFF0E0E0E), Color.White, STEEL, R * .26f, COND)
    hand(c, tm.h * 30f, HS.SWORD, R * .46f, R * .085f, STEEL, d.lume, glow, solid = d.handCol)
    hand(c, tm.m * 6f, HS.SWORD, R * .72f, R * .065f, STEEL, d.lume, glow, solid = d.handCol)
    chrono(c, s.chronoDeg(), R * .8f, R * .01f, d.chrono)
    glare(c, R * .88f)
}

// ---------------------------------------------------------------- the colourways

private val BLACK = Color(0xFF0C0C0D)
private val JET = Metal(Color(0xFF5A5A5A), Color(0xFF1A1A1A), Color.Black)
private val LUME = Color(0xFFDDEFE8)
private val OLD_LUME = Color(0xFFE6D3A3)
private val RED = Color(0xFFD7262E)
private val ORANGE = Color(0xFFFF6A13)

internal val DAYTONAS = listOf(
    "panda" to Dayt(STEEL, BLACK, Color(0xFFE6E6E6), Color(0xFFF4F4F2), .04f, Color(0xFF121212), STEEL, Color(0xFF151515), Color(0xFFEDEDED), PLAT, PLAT, LUME, Color(0xFFC8102E),
        Strap(SK.OYSTER, STEEL), Color(0xFFB0C4DE)),
    "black" to Dayt(STEEL, null, Color(0xFF1A1A1A), Color(0xFF0E0E10), .12f, Color(0xFFE2E4E7), JET, Color(0xFFEDEDED),
        Color(0xFF151515), PLAT, PLAT, LUME, Color(0xFFE0453A), Strap(SK.OYSTER, STEEL), Color(0xFF9FB3C8)),
    "green & gold" to Dayt(GOLD, null, Color(0xFF3A2A0E), Color(0xFF1E5B3A), .22f, Color(0xFF0E0E0E), GOLD, Color(0xFFE8D7A3), Color(0xFFE8D7A3), GOLD, GOLD, LUME, Color(0xFFE8D7A3),
        Strap(SK.OYSTER, GOLD), Color(0xFF4CC38A)),
    "Paul Newman" to Dayt(STEEL, Color(0xFF101010), Color(0xFFF2F2F2), Color(0xFFEDE6D3), 0f, Color(0xFF111111), null, Color(0xFF151515), Color(0xFFEDE6D3), STEEL, STEEL, OLD_LUME,
        Color(0xFFB3261E), Strap(SK.OYSTER, STEEL), Color(0xFFE0C9A6), newman = true),
    "platinum ice blue" to Dayt(PLAT, Color(0xFF5A3A28), Color(0xFFF1E9DD), Color(0xFFBFD9E8), .15f, Color(0xFFAECBDD), PLAT, Color(0xFF1D2A35), Color(0xFF1D2A35), PLAT, PLAT, LUME,
        Color(0xFF6B3B2A), Strap(SK.OYSTER, PLAT), Color(0xFF9DD6F2)),
    "rose gold chocolate" to Dayt(ROSE, BLACK, Color(0xFFE7B8A0), Color(0xFF3A2219), .18f, Color(0xFF0E0E0E), ROSE, Color(0xFFE7B8A0), Color(0xFFE7B8A0), ROSE, ROSE, LUME,
        Color(0xFFE7B8A0), Strap(SK.RUBBER, col = Color(0xFF141414)), Color(0xFFE59A7A)),
)
internal val SUBS = listOf(
    "black" to SubW(STEEL, BLACK, Color(0xFFDDE1E5), BLACK, .03f, Color.White, PLAT, PLAT, LUME, RED, Strap(SK.OYSTER, STEEL), Color(0xFF7FA7C9)),
    "green \"Hulk\"" to SubW(STEEL, Color(0xFF0E5A34), Color(0xFFDDE1E5), Color(0xFF0F6B3A), .3f, Color.White, PLAT, PLAT, LUME, Color(0xFF9BE15D), Strap(SK.OYSTER, STEEL), Color(0xFF39D27A)),
    "green bezel \"Kermit\"" to SubW(STEEL, Color(0xFF1C7A45), Color(0xFFDDE1E5), BLACK, .03f, Color.White, PLAT, PLAT, LUME, Color(0xFF3FD07F), Strap(SK.OYSTER, STEEL), Color(0xFF3FD07F)),
    "blue \"Smurf\"" to SubW(PLAT, Color(0xFF173E8A), Color(0xFFDDE1E5), Color(0xFF1C4FA8), .3f, Color.White, PLAT, PLAT, LUME, Color(0xFF7FB2FF), Strap(SK.OYSTER, PLAT), Color(0xFF5B8CFF)),
    "two-tone \"Bluesy\"" to SubW(STEEL, Color(0xFF16357A), Color(0xFFE9C66B), Color(0xFF1A3F8F), .3f, Color(0xFFE9C66B), GOLD, GOLD, LUME, Color(0xFFE9C66B),
        Strap(SK.OYSTER, STEEL, centre = GOLD), Color(0xFF6E90E0), bezelRing = GOLD),
    "vintage gilt" to SubW(STEEL, Color(0xFF3B4250), Color(0xFFD9D2BE), Color(0xFF0A0A0A), .05f, Color(0xFFD4AF62), GOLD, STEEL, OLD_LUME, Color(0xFFD4AF62),
        Strap(SK.OYSTER, STEEL), Color(0xFFD4AF62)),
)
internal val MONACOS = listOf(
    "blue" to Mono(STEEL, Color(0xFF1F4E9C), .25f, Color(0xFFEDEDED), Color(0xFF111111), Color.White, Color(0xFFE2231A), STEEL, STEEL, Color.White,
        Strap(SK.LEATHER, col = Color(0xFF14254A), stitch = Color(0xFF8DA4C9)), Color(0xFF4F86E8)),
    "black" to Mono(STEEL, Color(0xFF111214), .15f, Color(0xFF1C1D20), Color.White, Color.White, Color(0xFFE2231A), STEEL, STEEL, Color.White,
        Strap(SK.LEATHER, col = Color(0xFF111111), stitch = Color(0xFF777777)), Color(0xFFE2231A)),
    "Gulf" to Mono(STEEL, Color(0xFF1D3F78), .25f, Color(0xFFEDEDED), Color(0xFF111111), Color.White, Color(0xFFF37021), STEEL, STEEL, Color.White,
        Strap(SK.LEATHER, col = Color(0xFF1C2E52), stitch = Color(0xFFF37021)), Color(0xFF8FC6E8), gulf = true),
)
internal val CARRERAS = listOf(
    "black" to Carr(STEEL, Color(0xFF0E0F11), .1f, Color(0xFF16181B), Color.White, Color(0xFF1B1D21), STEEL, Color.White, Color.White, Color(0xFFE2231A), STEEL, STEEL, Color.White,
        Strap(SK.OYSTER, STEEL), Color(0xFFE2231A)),
    "panda" to Carr(STEEL, Color(0xFFE8E9EA), .08f, Color(0xFF141414), Color.White, Color(0xFF151515), null, Color.White, Color(0xFF151515), Color(0xFFE2231A), STEEL, STEEL, Color.White,
        Strap(SK.LEATHER, col = Color(0xFF111111), stitch = Color(0xFFDDDDDD)), Color(0xFFDADDE2)),
    "green" to Carr(STEEL, Color(0xFF1F4D3A), .25f, Color(0xFF173A2C), Color.White, Color(0xFF173A2C), STEEL, Color.White, Color.White, Color(0xFFF2C14E), STEEL, STEEL, Color.White,
        Strap(SK.LEATHER, col = Color(0xFF5A3A1E), stitch = Color(0xFFCDB89A)), Color(0xFF42B883)),
)
internal val PLANETS = listOf(
    "black & orange" to Po(STEEL, BLACK, Color(0xFFE8E8E8), ORANGE, Color(0xFF0B0B0C), .05f, Color.White, ORANGE, STEEL, STEEL, LUME, Color(0xFF111214),
        Strap(SK.RUBBER, col = Color(0xFFE85A12)), ORANGE),
    "blue" to Po(STEEL, Color(0xFF14284F), Color(0xFFE8E8E8), null, Color(0xFF173469), .25f, Color.White, ORANGE, STEEL, STEEL, LUME, Color(0xFF142C5A),
        Strap(SK.OYSTER, STEEL), Color(0xFF4F7FE0)),
    "white" to Po(STEEL, BLACK, Color(0xFFE8E8E8), ORANGE, Color(0xFFF2F2F0), .03f, Color(0xFF151515), ORANGE, STEEL, STEEL, LUME, Color(0xFFE6E6E4),
        Strap(SK.RUBBER, col = Color(0xFF151515)), Color(0xFFFFB27A), Color(0xFF151515), Color.White),
    "titanium grey" to Po(TITAN, Color(0xFF3A3D42), Color(0xFFE8E8E8), ORANGE, Color(0xFF55595F), .25f, Color.White, ORANGE, TITAN, TITAN, LUME, Color(0xFF4A4E54),
        Strap(SK.RUBBER, col = Color(0xFF2A2C2F)), Color(0xFFB8C0C8)),
)
internal val RICHARDS = listOf(
    "carbon & red" to Rm(Color(0xFF1E1F22), Color(0xFF5A5D63), TITAN, Color(0xFF15161A), Color.White, Color(0xFFE01E2B), Color(0xFFE01E2B), TITAN, STEEL,
        Strap(SK.RUBBER, col = Color(0xFF16171A)), Color(0xFFE01E2B)),
    "white & red" to Rm(Color(0xFFF2F2F0), Color(0xFFC9CACC), STEEL, Color(0xFFE8E8E8), Color(0xFF151515), Color(0xFFE01E2B), Color(0xFFE01E2B), TITAN, ROSE,
        Strap(SK.RUBBER, col = Color(0xFFEDEDED)), Color(0xFFFF6B6B)),
    "orange" to Rm(Color(0xFFFF6A1A), Color(0xFFFFB077), TITAN, Color(0xFF1A1A1A), Color.White, Color(0xFFFF6A1A), Color(0xFF00B2FF), TITAN, STEEL,
        Strap(SK.RUBBER, col = Color(0xFFFF6A1A)), Color(0xFFFF6A1A)),
    "blue & lime" to Rm(Color(0xFF1B3D8F), Color(0xFF4C79D6), TITAN, Color(0xFF101A33), Color.White, Color(0xFFB8F23A), Color(0xFFB8F23A), TITAN, STEEL,
        Strap(SK.RUBBER, col = Color(0xFF14295C)), Color(0xFFB8F23A)),
)
internal val PATEKS = listOf(
    "5170 chronograph — white gold, blue" to Pp(PLAT, Color(0xFF1B3B78), .3f, Color(0xFF173468), Color(0xFFE6E9EF), Color(0xFFE6E9EF), PLAT, PLAT, Color(0xFFE6E9EF),
        Strap(SK.LEATHER, col = Color(0xFF151C33), stitch = Color(0xFF2E3A5C)), Color(0xFF6E8FD8)),
    "5170 chronograph — yellow gold, silver" to Pp(GOLD, Color(0xFFEDEBE4), .04f, Color(0xFFE4E1D8), Color(0xFF2B2B2B), Color(0xFF2B5DA8), GOLD, GOLD, GOLD.lo,
        Strap(SK.LEATHER, col = Color(0xFF4A2A17), stitch = Color(0xFF6A4630)), Color(0xFFE8C66B)),
    "5270 perpetual chronograph — salmon" to Pp(PLAT, Color(0xFFE9A98E), .12f, Color(0xFFE19D82), Color(0xFF2A1A14), Color(0xFF2A1A14), PLAT, PLAT, Color(0xFF2A1A14),
        Strap(SK.LEATHER, col = Color(0xFF141414), stitch = Color(0xFF2A2A2A)), Color(0xFFFFB199), perpetual = true),
)
internal val NAUTILUSES = listOf(
    "Nautilus chrono — blue" to Naut(STEEL, Color(0xFF203B66), Color(0xFF0D1830), Color.White, Color.White, PLAT, PLAT, LUME, Strap(SK.INTEGRATED, STEEL), Color(0xFF5B8CFF)),
    "Nautilus chrono — rose gold" to Naut(ROSE, Color(0xFF5A3A2A), Color(0xFF231510), Color(0xFFF2D8C8), ROSE.hi, ROSE, ROSE, LUME,
        Strap(SK.LEATHER, col = Color(0xFF3B2416), stitch = Color(0xFF5A3A24)), Color(0xFFE59A7A)),
    "Aquanaut chrono — orange" to Naut(STEEL, Color(0xFF2B2D30), Color(0xFF0E0F10), Color.White, Color(0xFFFF6B1A), STEEL, STEEL, LUME, Strap(SK.RUBBER, col = Color(0xFFFF6B1A)),
        Color(0xFFFF6B1A), aqua = true),
    "Aquanaut chrono — khaki" to Naut(PLAT, Color(0xFF4A5A3E), Color(0xFF1E261A), Color(0xFFF2F2E8), Color(0xFFF2F2E8), PLAT, PLAT, LUME, Strap(SK.RUBBER, col = Color(0xFF55633F)),
        Color(0xFF9DB07A), aqua = true),
)
internal val ROYALS = listOf(
    "blue" to Ro(STEEL, Color(0xFF1C3B78), Color(0xFF17326A), null, Color.White, Color.White, PLAT, PLAT, LUME, RED, Strap(SK.INTEGRATED, STEEL), Color(0xFF5B8CFF), PLAT),
    "panda" to Ro(STEEL, Color(0xFFEDEDEB), Color(0xFF151618), STEEL, Color(0xFF1A1A1A), Color.White, PLAT, PLAT, LUME, RED, Strap(SK.INTEGRATED, STEEL), Color(0xFFDADDE2), PLAT),
    "rose gold" to Ro(ROSE, Color(0xFF4A2B22), Color(0xFF3C221B), null, Color(0xFFF0D2C2), Color(0xFFF0D2C2), ROSE, ROSE, LUME, Color(0xFFF0D2C2), Strap(SK.INTEGRATED, ROSE),
        Color(0xFFE59A7A), PLAT),
    "black ceramic" to Ro(CERAMIC, Color(0xFF0F1012), Color(0xFF16171A), null, Color.White, Color.White, PLAT, PLAT, LUME, Color(0xFFE6E6E6), Strap(SK.INTEGRATED, CERAMIC),
        Color(0xFFB0B8C4), PLAT),
    "Offshore, black & red" to Ro(TITAN, Color(0xFF121314), Color(0xFF1A1B1E), null, Color.White, Color.White, PLAT, PLAT, LUME, Color(0xFFE31B23),
        Strap(SK.RUBBER, col = Color(0xFF141414)), Color(0xFFE31B23), STEEL, offshore = true),
)
internal val PORTUGIESERS = listOf(
    "silver & blue" to Port(STEEL, Color(0xFFE9EAEB), .05f, Color(0xFF1E2A4A), BLUED, BLUED, Color(0xFF2C4C9C), Color(0xFFE2E3E5),
        Strap(SK.LEATHER, col = Color(0xFF1C1C1E), stitch = Color(0xFF333336)), Color(0xFF6E8FD8)),
    "blue & red gold" to Port(ROSE, Color(0xFF1C2C5A), .25f, Color(0xFFF0D6C4), ROSE, ROSE, ROSE.hi, Color(0xFF192752),
        Strap(SK.LEATHER, col = Color(0xFF3A2618), stitch = Color(0xFF5A3D28)), Color(0xFFE59A7A)),
    "green" to Port(STEEL, Color(0xFF2E5B49), .2f, Color.White, STEEL, STEEL, STEEL.hi, Color(0xFF285040),
        Strap(SK.LEATHER, col = Color(0xFF2B2016), stitch = Color(0xFF4A3828)), Color(0xFF42B883)),
)
internal val PILOTS = listOf(
    "black" to Pil(STEEL, Color(0xFF111214), Color.White, Color(0xFFEDEBDF), Color(0xFFEDEDED), Color(0xFFE53935), Color(0xFF16171A),
        Strap(SK.LEATHER, col = Color(0xFF5A3A1E), stitch = Color(0xFFCDB89A)), Color(0xFFE0E0E0)),
    "Top Gun ceramic" to Pil(CERAMIC, Color(0xFF151618), Color(0xFFDADADA), Color(0xFFE9E3CD), Color(0xFFE6E6E6), Color(0xFFE6E6E6), Color(0xFF1B1C1F),
        Strap(SK.RUBBER, col = Color(0xFF121212)), Color(0xFF8A9099)),
    "Spitfire bronze" to Pil(BRONZE, Color(0xFF2E3B2C), Color(0xFFE8E2CC), Color(0xFFE3D7B0), Color(0xFFE6DFC8), Color(0xFFE6DFC8), Color(0xFF28342A),
        Strap(SK.LEATHER, col = Color(0xFF6B4423), stitch = Color(0xFFD9C3A0)), Color(0xFFC79A5B)),
    "Le Petit Prince blue" to Pil(STEEL, Color(0xFF1A2A55), Color.White, Color(0xFFEDEBDF), Color.White, Color.White, Color(0xFF16244A),
        Strap(SK.LEATHER, col = Color(0xFF5A3A1E), stitch = Color(0xFFCDB89A)), Color(0xFF6E8FD8)),
)
