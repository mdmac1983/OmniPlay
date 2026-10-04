package app.orionmd.omniplay.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.orionmd.omniplay.Visual
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Supercar gauge clusters: two looks each in the style of Ferrari / LaFerrari, Lexus LFA, Porsche 911 GT3, Nissan GT-R,
 * Mercedes G-Class, Audi (virtual cockpit), Acura NSX and Honda Civic Type R. No badges or logos. The music drives them:
 * the beat is the rev counter, the mids are the speed, the spectrum fills the bars and screens.
 */

// ---------------------------------------------------------------- what the music drives

private fun VisState.bv(k: Int) = disp[k.coerceIn(0, Visual.BANDS - 1)].coerceIn(0f, 1f)
private fun VisState.mn(a: Int, z: Int) = (a until z).sumOf { bv(it).toDouble() }.toFloat() / (z - a)
private fun VisState.rpm() = (mn(0, 10) * .65f + level * .55f).coerceIn(0f, 1f)
private fun VisState.spd() = (mn(8, 30) * 1.35f).coerceIn(0f, 1f)
private fun VisState.hi() = (mn(28, Visual.BANDS) * 1.8f).coerceIn(0f, 1f)
private fun VisState.gear() = 1 + ((spin * .35f).toInt() % 6)
private fun VisState.spec(n: Int) = FloatArray(n) { i ->
    val a = (Visual.BANDS * (i.toFloat() / n).pow(1.3f)).toInt().coerceIn(0, Visual.BANDS - 1)
    val z = (Visual.BANDS * ((i + 1f) / n).pow(1.3f)).toInt().coerceIn(a + 1, Visual.BANDS)
    (mn(a, z) * 1.3f).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------- drawing kit

private fun DrawScope.t(s: String, x: Float, y: Float, px: Float, c: Color, font: Typeface? = null, align: Paint.Align = Paint.Align.CENTER, glow: Color? = null, spacing: Float = 0f, bold: Boolean = true, skew: Float = 0f) {
    if (px < 3f) return
    drawIntoCanvas { cv -> cv.nativeCanvas.drawText(s, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c.toArgb(); textSize = px; textAlign = align; typeface = font ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        letterSpacing = spacing; textSkewX = skew; if (glow != null) setShadowLayer(px * .4f, 0f, 0f, glow.toArgb())
    }) }
}
private fun pol(c: Offset, deg: Float, r: Float) = Offset(c.x + r * cos(Math.toRadians(deg.toDouble())).toFloat(), c.y + r * sin(Math.toRadians(deg.toDouble())).toFloat())

/** The cluster's box: wide and short, centred, in any canvas. */
private class CBox(val l: Float, val t: Float, val w: Float, val h: Float) { fun x(u: Float) = l + w * u; fun y(v: Float) = t + h * v; val small get() = h < 150f }
private fun DrawScope.box(ratio: Float = .5f): CBox {
    val w = min(size.width * .98f, size.height * .98f / ratio); val h = min(size.height * .98f, w * ratio)
    return CBox((size.width - w) / 2, (size.height - h) / 2, w, h)
}
/** Cluster housing: a dark binnacle with a soft top glare. */
private fun DrawScope.housing(b: CBox, top: Color = Color(0xFF15171A), bottom: Color = Color(0xFF050506), rim: Color = Color(0xFF2A2D31), r: Float = .06f) {
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), b.t, b.t + b.h), Offset(b.l, b.t), Size(b.w, b.h), CornerRadius(b.h * r))
    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .06f), Color.Transparent), b.t, b.t + b.h * .4f), Offset(b.l, b.t), Size(b.w, b.h * .4f), CornerRadius(b.h * r))
    drawRoundRect(rim, Offset(b.l, b.t), Size(b.w, b.h), CornerRadius(b.h * r), style = Stroke(b.h * .012f))
}
private fun DrawScope.carbonFill(l: Float, tp: Float, w: Float, h: Float) {
    drawRect(Color(0xFF111214), Offset(l, tp), Size(w, h))
    clipRect(l, tp, l + w, tp + h) { val s = h / 18f; var y = tp; var k = 0
        while (y < tp + h) { var x = l + (if (k % 2 == 0) 0f else s); while (x < l + w) { drawRect(Color(0xFF1C1E21), Offset(x, y), Size(s, s)); x += s * 2 }; y += s; k++ } }
}

/** Everything a round dial can look like. */
private class Dial(
    val face: Brush?, val rim: Color, val tick: Color, val num: Color, val needle: Color, val red: Color? = Color(0xFFE53935),
    val start: Float = 135f, val sweep: Float = 270f, val labels: List<String> = (0..8).map { "$it" }, val redFrom: Float = .8f,
    val font: Typeface? = null, val hub: Color = Color(0xFF1A1A1A), val minor: Int = 4, val arc: Brush? = null, val arcW: Float = .07f,
    val rimW: Float = .05f, val needleLen: Float = .86f, val numR: Float = .64f, val numPx: Float = .15f,
)
private fun DrawScope.dial(c: Offset, r: Float, v: Float, D: Dial, title: String = "", sub: String = "") {
    D.face?.let { drawCircle(it, r, c) }
    if (D.rimW > 0) drawCircle(D.rim, r, c, style = Stroke(r * D.rimW))
    D.arc?.let { a -> drawArc(Color.White.copy(alpha = .07f), D.start, D.sweep, false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(r * D.arcW))
        drawArc(a, D.start, D.sweep * v.coerceIn(0f, 1f), false, Offset(c.x - r * .8f, c.y - r * .8f), Size(r * 1.6f, r * 1.6f), style = Stroke(r * D.arcW, cap = StrokeCap.Butt)) }
    D.red?.let { drawArc(it, D.start + D.sweep * D.redFrom, D.sweep * (1 - D.redFrom), false, Offset(c.x - r * .9f, c.y - r * .9f), Size(r * 1.8f, r * 1.8f), style = Stroke(r * .06f)) }
    val major = D.labels.size - 1
    for (k in 0..major * D.minor) {
        val a = D.start + D.sweep * k / (major * D.minor); val big = k % D.minor == 0
        drawLine(if (D.red != null && k >= major * D.minor * D.redFrom) D.red else D.tick, pol(c, a, r * (if (big) .76f else .82f)), pol(c, a, r * .92f), if (big) r * .03f else r * .013f)
        if (big && r > 26) { val p = pol(c, a, r * D.numR); t(D.labels[k / D.minor], p.x, p.y + r * D.numPx * .36f, r * D.numPx, D.num, D.font) }
    }
    if (title.isNotEmpty()) t(title, c.x, c.y + r * .42f, r * .11f, D.num.copy(alpha = .8f), D.font, spacing = .1f)
    if (sub.isNotEmpty()) t(sub, c.x, c.y + r * .58f, r * .09f, D.num.copy(alpha = .6f), D.font, spacing = .1f)
    val a = D.start + D.sweep * v.coerceIn(0f, 1f)
    drawLine(D.needle.copy(alpha = .3f), pol(c, a + 180f, r * .12f), pol(c, a, r * D.needleLen), r * .07f, cap = StrokeCap.Round)
    drawLine(D.needle, pol(c, a + 180f, r * .12f), pol(c, a, r * D.needleLen), r * .03f, cap = StrokeCap.Round)
    drawCircle(D.hub, r * .09f, c); drawCircle(D.needle, r * .035f, c)
}
/** A screen (TFT / LCD) panel. */
private fun DrawScope.panel(l: Float, tp: Float, w: Float, h: Float, bg: Color = Color(0xFF050608), glow: Color? = null, rim: Color = Color(0xFF222428), rad: Float = .06f) {
    drawRoundRect(bg, Offset(l, tp), Size(w, h), CornerRadius(h * rad))
    glow?.let { drawRoundRect(Brush.radialGradient(listOf(it.copy(alpha = .14f), Color.Transparent), Offset(l + w / 2, tp + h / 2), maxOf(w, h) * .7f), Offset(l, tp), Size(w, h), CornerRadius(h * rad)) }
    drawRoundRect(rim, Offset(l, tp), Size(w, h), CornerRadius(h * rad), style = Stroke(2f))
}
/** Bars from the spectrum in a box. */
private fun DrawScope.specBars(v: FloatArray, l: Float, tp: Float, w: Float, h: Float, color: (Int, Float) -> Color, gap: Float = .3f, segs: Int = 0) {
    val cw = w / v.size
    v.forEachIndexed { i, x ->
        if (segs > 0) { val sh = h / segs; val lit = (x * segs).toInt(); for (k in 0 until segs) drawRect(if (k < lit) color(i, k.toFloat() / segs) else color(i, 0f).copy(alpha = .08f), Offset(l + i * cw + cw * gap / 2, tp + h - (k + 1) * sh + sh * .15f), Size(cw * (1 - gap), sh * .7f)) }
        else { val hh = h * x; drawRect(color(i, x), Offset(l + i * cw + cw * gap / 2, tp + h - hh), Size(cw * (1 - gap), hh)) }
    }
}
/** A level bar with its track (segmented, like a TFT gauge). */
private fun DrawScope.hbar(l: Float, tp: Float, w: Float, h: Float, v: Float, col: Color = Color.White) {
    val n = 10; val sw = w / n; val lit = (v.coerceIn(0f, 1f) * n).toInt()
    for (k in 0 until n) drawRect(if (k < lit) col else col.copy(alpha = .12f), Offset(l + k * sw + sw * .1f, tp), Size(sw * .8f, h))
}
/** Shift lights: a row of LEDs green → yellow → red → blue that light with the revs. */
private fun DrawScope.shiftLights(cx: Float, y: Float, w: Float, n: Int, v: Float, flash: Boolean = true) {
    val lit = (v * n * 1.05f).toInt(); val r = w / n * .32f; val all = lit >= n && flash
    for (k in 0 until n) {
        val col = if (all) Color(0xFF4FA8FF) else when { k < n * .4f -> Color(0xFF3CE06A); k < n * .7f -> Color(0xFFFFD23C); else -> Color(0xFFFF3B3B) }
        val on = k < lit
        val p = Offset(cx - w / 2 + w * (k + .5f) / n, y)
        drawCircle(if (on) col else col.copy(alpha = .12f), r, p)
        if (on) drawCircle(col.copy(alpha = .3f), r * 1.8f, p)
    }
}
/** Horizontal rev bar (Type R / GT3 track style): fills left→right, colour by zone. */
private fun DrawScope.revBar(l: Float, tp: Float, w: Float, h: Float, v: Float, segs: Int, warn: Float = .7f, red: Float = .88f, labels: List<String> = emptyList(), lc: Color = Color.White) {
    val sw = w / segs; val lit = (v * segs).toInt()
    for (k in 0 until segs) {
        val f = k.toFloat() / segs; val col = when { f >= red -> Color(0xFFFF2D2D); f >= warn -> Color(0xFFFFC21C); else -> Color.White }
        val hh = h * (.45f + .55f * f)
        drawRect(if (k < lit) col else col.copy(alpha = .1f), Offset(l + k * sw + sw * .12f, tp + h - hh), Size(sw * .76f, hh))
    }
    labels.forEachIndexed { i, s -> t(s, l + w * i / (labels.size - 1).coerceAtLeast(1), tp + h + h * .5f, h * .38f, lc.copy(alpha = .8f)) }
}
private fun DrawScope.gForce(c: Offset, r: Float, s: VisState, col: Color) {
    drawCircle(col.copy(alpha = .25f), r, c, style = Stroke(2f)); drawCircle(col.copy(alpha = .15f), r * .5f, c, style = Stroke(1.5f))
    drawLine(col.copy(alpha = .2f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f); drawLine(col.copy(alpha = .2f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
    val gx = (s.mn(0, 8) - s.mn(30, 48)) * 1.4f; val gy = (s.mn(8, 20) - .35f) * 1.6f
    val p = Offset(c.x + r * gx.coerceIn(-1f, 1f) * .9f, c.y - r * gy.coerceIn(-1f, 1f) * .9f)
    drawCircle(col.copy(alpha = .3f), r * .16f, p); drawCircle(col, r * .09f, p)
}
private fun kmh(s: VisState) = (s.spd() * 312).toInt().toString()
private fun rpmText(s: VisState, max: Int) = "%,d".format(((s.rpm() * max) / 100).toInt() * 100)

private val F = HifiFonts
private val RED = Color(0xFFE10600)

// ---------------------------------------------------------------- Ferrari style

/** Prancing-horse yellow: a big yellow-faced rev counter in the middle, two screens either side. */
internal fun DrawScope.carFerrari(s: VisState) {
    val b = box(); housing(b)
    val c = Offset(b.x(.5f), b.y(.55f)); val r = b.h * .4f
    panel(b.x(.04f), b.y(.18f), b.w * .27f, b.h * .66f, glow = Color(0xFFFFD000))
    panel(b.x(.69f), b.y(.18f), b.w * .27f, b.h * .66f, glow = Color(0xFFFFD000))
    // left screen: speed + gear, right screen: the spectrum as a telemetry trace
    t(kmh(s), b.x(.175f), b.y(.52f), b.h * .2f, Color.White, F.semi)
    t("km/h", b.x(.175f), b.y(.62f), b.h * .05f, Color(0xFFBBBBBB), spacing = .1f)
    t("RACE", b.x(.175f), b.y(.76f), b.h * .055f, Color(0xFFFFD000), spacing = .2f)
    specBars(s.spec(14), b.x(.71f), b.y(.25f), b.w * .23f, b.h * .5f, { _, v -> if (v > .8f) RED else Color(0xFFFFD000) }, segs = 10)
    t("TELEMETRY", b.x(.825f), b.y(.8f), b.h * .045f, Color(0xFFBBBBBB), spacing = .15f)
    val D = Dial(Brush.radialGradient(listOf(Color(0xFFFFE34A), Color(0xFFF5C400), Color(0xFFD9A700)), c, r), Color(0xFF2A2A2A), Color(0xFF111111), Color(0xFF111111), RED,
        labels = (0..10).map { "$it" }, redFrom = .8f, start = 140f, sweep = 260f, font = F.semi, hub = Color(0xFF111111), rimW = .07f)
    dial(c, r, s.rpm(), D, "x1000 rpm")
    t("${s.gear()}", c.x, c.y + r * .3f, r * .28f, Color(0xFF111111), F.semi)
    if (!b.small) shiftLights(c.x, b.y(.07f), b.w * .3f, 10, s.rpm())
}

/** Hypercar (LaFerrari style): carbon, a digital rev arc that glows red, big gear in the middle, KERS-style charge bar. */
internal fun DrawScope.carLaFerrari(s: VisState) {
    val b = box(); housing(b); carbonFill(b.x(.02f), b.y(.04f), b.w * .96f, b.h * .92f)
    val c = Offset(b.x(.5f), b.y(.57f)); val r = b.h * .42f
    drawCircle(Color(0xFF050505), r * .98f, c)
    val segs = 44; val lit = (s.rpm() * segs).toInt()
    for (k in 0 until segs) {
        val a = 150f + 240f * k / (segs - 1); val f = k / (segs - 1f)
        val col = if (f > .82f) RED else if (f > .6f) Color(0xFFFFB300) else Color(0xFFFFE34A)
        drawLine(if (k < lit) col else col.copy(alpha = .08f), pol(c, a, r * .74f), pol(c, a, r * .92f), r * .05f)
    }
    (0..10).forEach { k -> val p = pol(c, 150f + 24f * k, r * .62f); t("$k", p.x, p.y + r * .04f, r * .1f, Color(0xFFE8E8E8), F.semi) }
    t("${s.gear()}", c.x, c.y + r * .14f, r * .48f, Color(0xFFFFE34A), F.semi, glow = Color(0xFFFFB300))
    t(kmh(s) + " km/h", c.x, c.y + r * .38f, r * .13f, Color.White, F.semi)
    // hybrid charge bars left (bass) and right (highs)
    listOf(Triple(b.x(.12f), s.mn(0, 8), "HY-KERS"), Triple(b.x(.82f), s.hi(), "BATT")).forEach { (x, v, lab) ->
        val tp = b.y(.24f); val h = b.h * .52f; val w = b.w * .06f
        drawRoundRect(Color(0xFF202225), Offset(x, tp), Size(w, h), CornerRadius(4f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF39FF8E), Color(0xFF00C2FF)), tp, tp + h), Offset(x, tp + h * (1 - v)), Size(w, h * v), CornerRadius(4f))
        t(lab, x + w / 2, tp + h + b.h * .07f, b.h * .04f, Color(0xFFBBBBBB), spacing = .1f)
    }
    if (!b.small) shiftLights(c.x, b.y(.08f), b.w * .4f, 12, s.rpm())
}

// ---------------------------------------------------------------- Lexus LFA style

private fun DrawScope.lfaRing(c: Offset, r: Float, s: VisState) {
    // the brushed-metal ring around the screen
    drawCircle(Brush.sweepGradient(listOf(Color(0xFFDADDE0), Color(0xFF7C8186), Color(0xFFF4F6F7), Color(0xFF6A6F74), Color(0xFFDADDE0)), c), r, c)
    drawCircle(Color(0xFF07080A), r * .9f, c)
    val segs = 50; val lit = (s.rpm() * segs).toInt()
    for (k in 0 until segs) {
        val a = 135f + 270f * k / (segs - 1); val f = k / (segs - 1f)
        val col = if (f > .85f) Color(0xFFFF3030) else Color(0xFFEFF6FF)
        drawLine(if (k < lit) col else col.copy(alpha = .08f), pol(c, a, r * .7f), pol(c, a, r * .84f), r * .025f)
    }
    listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9").forEachIndexed { k, l -> val p = pol(c, 135f + 30f * k, r * .58f); t(l, p.x, p.y + r * .04f, r * .1f, if (k >= 9) Color(0xFFFF3030) else Color.White, F.wide, bold = false) }
    val a = 135f + 270f * s.rpm()
    drawLine(Color(0xFFFF3030), pol(c, a, r * .45f), pol(c, a, r * .86f), r * .03f, cap = StrokeCap.Round)
    t("${s.gear()}", c.x, c.y + r * .1f, r * .32f, Color.White, F.wide, bold = false)
    t(kmh(s), c.x, c.y + r * .36f, r * .14f, Color(0xFF9FD3FF), F.wide, bold = false)
    t("km/h", c.x, c.y + r * .48f, r * .06f, Color(0xFF9FD3FF), spacing = .2f)
}
/** The ring in the middle, info either side. */
internal fun DrawScope.carLfa(s: VisState) {
    val b = box(); housing(b)
    val c = Offset(b.x(.5f), b.y(.5f)); val r = b.h * .44f
    lfaRing(c, r, s)
    t("OIL", b.x(.12f), b.y(.3f), b.h * .05f, Color(0xFF9AA3AA), F.wide, spacing = .2f, bold = false)
    hbar(b.x(.07f), b.y(.35f), b.w * .1f, b.h * .035f, s.mn(0, 6))
    t("WATER", b.x(.12f), b.y(.55f), b.h * .05f, Color(0xFF9AA3AA), F.wide, spacing = .2f, bold = false)
    hbar(b.x(.07f), b.y(.6f), b.w * .1f, b.h * .035f, s.mn(10, 20))
    t("FUEL", b.x(.88f), b.y(.3f), b.h * .05f, Color(0xFF9AA3AA), F.wide, spacing = .2f, bold = false)
    hbar(b.x(.83f), b.y(.35f), b.w * .1f, b.h * .035f, .72f)
    t("SPORT", b.x(.88f), b.y(.62f), b.h * .07f, Color(0xFFFF3030), F.wide, spacing = .1f, bold = false)
}
/** Ring slid to the right (as the real one does in its info mode), spectrum on the freed screen. */
internal fun DrawScope.carLfaSlide(s: VisState) {
    val b = box(); housing(b)
    panel(b.x(.04f), b.y(.12f), b.w * .5f, b.h * .76f, glow = Color(0xFF9FD3FF))
    t("AUDIO · SPECTRUM", b.x(.07f), b.y(.22f), b.h * .045f, Color(0xFF9FD3FF), F.wide, Paint.Align.LEFT, spacing = .15f, bold = false)
    specBars(s.spec(20), b.x(.07f), b.y(.28f), b.w * .44f, b.h * .5f, { _, v -> if (v > .85f) Color(0xFFFF3030) else Color(0xFFEFF6FF) }, segs = 12)
    t("LAP 1:${"%02d".format(((s.spin * 7) % 60).toInt())}.${((s.spin * 70) % 10).toInt()}", b.x(.07f), b.y(.85f), b.h * .06f, Color.White, F.wide, Paint.Align.LEFT, bold = false)
    lfaRing(Offset(b.x(.75f), b.y(.5f)), b.h * .44f, s)
}

// ---------------------------------------------------------------- Porsche 911 GT3 style

private val PGREEN = Color(0xFF8BC34A)
/** Five round dials, the rev counter (white on black, 9,000 rpm) in the middle. */
internal fun DrawScope.carGt3(s: VisState) {
    val b = box(.45f); housing(b, rim = Color(0xFF3A3D41))
    val big = b.h * .42f; val sm = b.h * .27f
    val side = Dial(Brush.radialGradient(listOf(Color(0xFF151618), Color(0xFF050506))), Color(0xFF6B6F74), Color.White, Color.White, Color(0xFFFF4D00), null, labels = listOf("0", "", "", "", ""), minor = 3, rimW = .06f)
    dial(Offset(b.x(.11f), b.y(.58f)), sm * .75f, s.mn(0, 6), side, "OIL")
    dial(Offset(b.x(.29f), b.y(.53f)), sm, s.spd(), Dial(Brush.radialGradient(listOf(Color(0xFF151618), Color(0xFF050506))), Color(0xFF6B6F74), Color.White, Color.White, Color(0xFFFF4D00), null,
        labels = listOf("0", "40", "80", "120", "160", "200", "240", "280", "320"), minor = 2, rimW = .06f, numPx = .13f), "km/h")
    val tach = Dial(Brush.radialGradient(listOf(Color(0xFF1A1B1D), Color(0xFF050506))), Color(0xFF8A8E93), Color.White, Color.White, Color(0xFFFF4D00),
        labels = (0..9).map { "$it" }, redFrom = .9f, start = 120f, sweep = 300f, minor = 5, rimW = .05f, numPx = .14f)
    dial(Offset(b.x(.5f), b.y(.5f)), big, s.rpm(), tach, "1/min x1000")
    t("${s.gear()}", b.x(.5f), b.y(.5f) + big * .32f, big * .22f, PGREEN, F.semi)
    dial(Offset(b.x(.71f), b.y(.53f)), sm, s.hi(), Dial(Brush.radialGradient(listOf(Color(0xFF151618), Color(0xFF050506))), Color(0xFF6B6F74), Color.White, Color.White, Color(0xFFFF4D00), null,
        labels = listOf("0", "0.5", "1.0", "1.5", "2.0"), minor = 4, rimW = .06f, numPx = .13f), "BOOST bar")
    dial(Offset(b.x(.89f), b.y(.58f)), sm * .75f, s.level, side, "WATER")
}
/** Track screen: one long rev bar, shift lights, huge gear, lap delta — the dash at full attack. */
internal fun DrawScope.carGt3Track(s: VisState) {
    val b = box(.45f); housing(b, rim = Color(0xFF3A3D41))
    panel(b.x(.04f), b.y(.08f), b.w * .92f, b.h * .84f)
    if (!b.small) shiftLights(b.x(.5f), b.y(.16f), b.w * .6f, 15, s.rpm())
    revBar(b.x(.08f), b.y(.25f), b.w * .84f, b.h * .14f, s.rpm(), 36, .72f, .9f, (0..9).map { "$it" })
    t("${s.gear()}", b.x(.5f), b.y(.86f), b.h * .4f, Color.White, F.semi)
    t(kmh(s), b.x(.2f), b.y(.75f), b.h * .18f, Color.White, F.semi); t("km/h", b.x(.2f), b.y(.84f), b.h * .05f, Color(0xFFAAAAAA))
    val delta = (s.mn(10, 30) - .4f) * 2
    t("%+.2f".format(delta), b.x(.8f), b.y(.75f), b.h * .16f, if (delta < 0) PGREEN else Color(0xFFFF4D4D), F.semi)
    t("DELTA", b.x(.8f), b.y(.84f), b.h * .05f, Color(0xFFAAAAAA), spacing = .15f)
}

// ---------------------------------------------------------------- Nissan GT-R style

/** R35 style: big rev counter in the middle with a red ring, speedo right, small dials left. */
internal fun DrawScope.carGtr(s: VisState) {
    val b = box(); housing(b, Color(0xFF101114), Color(0xFF030304))
    val rim = Color(0xFFB0B5BA)
    val tach = Dial(Brush.radialGradient(listOf(Color(0xFF16181B), Color(0xFF060607))), rim, Color.White, Color.White, Color(0xFFFF2A2A), labels = (0..8).map { "$it" },
        redFrom = .82f, start = 135f, sweep = 270f, rimW = .05f, arc = Brush.sweepGradient(listOf(Color(0xFFFF5A1F), Color(0xFFFF2A2A), Color(0xFFFF5A1F)), Offset(b.x(.5f), b.y(.5f))), arcW = .05f)
    dial(Offset(b.x(.5f), b.y(.5f)), b.h * .42f, s.rpm(), tach, "x1000 r/min")
    t("${s.gear()}", b.x(.5f), b.y(.5f) + b.h * .1f, b.h * .12f, Color(0xFFFF8A3D), F.semi)
    dial(Offset(b.x(.82f), b.y(.53f)), b.h * .33f, s.spd(), Dial(Brush.radialGradient(listOf(Color(0xFF16181B), Color(0xFF060607))), rim, Color.White, Color.White, Color(0xFFFF2A2A), null,
        labels = listOf("0", "40", "80", "120", "160", "200", "240", "280", "320"), minor = 2, rimW = .05f, numPx = .12f), "km/h")
    val sm = Dial(Brush.radialGradient(listOf(Color(0xFF16181B), Color(0xFF060607))), rim, Color.White, Color.White, Color(0xFFFF2A2A), null, start = 180f, sweep = 180f, labels = listOf("C", "", "H"), minor = 3, rimW = .06f, numPx = .2f)
    dial(Offset(b.x(.17f), b.y(.38f)), b.h * .18f, s.mn(0, 8), sm, "OIL")
    dial(Offset(b.x(.17f), b.y(.78f)), b.h * .18f, s.hi(), sm.let { Dial(it.face, rim, Color.White, Color.White, Color(0xFFFF2A2A), null, start = 180f, sweep = 180f, labels = listOf("E", "", "F"), minor = 3, rimW = .06f, numPx = .2f) }, "FUEL")
}
/** Multi-function display: boost, oil temp, torque split, G-force and throttle — the famous game-designed screens. */
internal fun DrawScope.carGtrMfd(s: VisState) {
    val b = box(); housing(b, Color(0xFF101114), Color(0xFF030304))
    panel(b.x(.03f), b.y(.06f), b.w * .94f, b.h * .88f, Color(0xFF04070B), Color(0xFF3AA0FF), Color(0xFF1A2633))
    val cy = Color(0xFF7FD3FF); val or = Color(0xFFFF8A3D)
    t("MULTI FUNCTION METER", b.x(.06f), b.y(.16f), b.h * .045f, cy, align = Paint.Align.LEFT, spacing = .15f)
    // four round meters: boost, oil temp, oil press, gear + speed
    listOf(Triple("BOOST", s.mn(0, 8), "bar"), Triple("OIL TEMP", s.mn(8, 20), "°C"), Triple("OIL PRESS", s.mn(20, 34), "kPa")).forEachIndexed { i, (lab, v, u) ->
        val c = Offset(b.x(.13f + i * .21f), b.y(.47f)); val r = b.h * .2f
        drawArc(cy.copy(alpha = .15f), 150f, 240f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .14f))
        drawArc(Brush.sweepGradient(listOf(cy, or, cy), c), 150f, 240f * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(r * .14f))
        t("%.1f".format(v * (if (u == "bar") 1.8f else if (u == "°C") 130f else 600f)), c.x, c.y + r * .15f, r * .4f, Color.White, F.semi)
        t(lab, c.x, c.y + r * 1.25f, r * .22f, cy, spacing = .1f); t(u, c.x, c.y + r * .5f, r * .2f, cy)
    }
    // G-force circle
    gForce(Offset(b.x(.79f), b.y(.45f)), b.h * .2f, s, or)
    t("G", b.x(.79f), b.y(.45f) + b.h * .27f, b.h * .045f, cy)
    // throttle / brake / torque split bars along the bottom
    listOf("THROTTLE" to s.rpm(), "BRAKE" to s.hi() * .6f, "TORQUE F/R" to s.mn(10, 30)).forEachIndexed { i, (lab, v) ->
        val x = b.x(.06f + i * .31f); val y = b.y(.8f); val w = b.w * .27f; val h = b.h * .05f
        drawRect(Color(0xFF14202B), Offset(x, y), Size(w, h)); drawRect(if (lab == "BRAKE") Color(0xFFFF3B3B) else cy, Offset(x, y), Size(w * v, h))
        t(lab, x, y + h + b.h * .055f, b.h * .04f, cy, align = Paint.Align.LEFT, spacing = .1f)
    }
}

// ---------------------------------------------------------------- Mercedes G-Class style

/** Classic twin analogue dials with chrome rings and orange-white lighting. */
internal fun DrawScope.carGClassic(s: VisState) {
    val b = box(); housing(b, Color(0xFF17181A), Color(0xFF070708), Color(0xFF55595E), .2f)
    val chrome = Color(0xFFD5D8DB)
    val D = { labels: List<String>, title: String -> Dial(Brush.radialGradient(listOf(Color(0xFF141517), Color(0xFF070708))), chrome, Color(0xFFF2F2F2), Color(0xFFF2F2F2), Color(0xFFFF7A1A),
        null, start = 135f, sweep = 270f, labels = labels, minor = 2, rimW = .08f, numPx = .13f, hub = Color(0xFF2A2A2A)) to title }
    val (sp, st1) = D(listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180", "200", "220", "240"), "km/h")
    dial(Offset(b.x(.26f), b.y(.5f)), b.h * .4f, s.spd(), sp, st1, kmh(s))
    val (tc, st2) = D((0..7).map { "$it" }, "1/min x1000")
    dial(Offset(b.x(.74f), b.y(.5f)), b.h * .4f, s.rpm(), Dial(tc.face, chrome, Color(0xFFF2F2F2), Color(0xFFF2F2F2), Color(0xFFFF7A1A), Color(0xFFE53935), labels = tc.labels, redFrom = .86f, minor = 2, rimW = .08f, numPx = .14f, hub = Color(0xFF2A2A2A)), st2)
    // centre column: temperature and fuel bars + the three locking-differential lamps
    val x = b.x(.5f)
    listOf("1", "2", "3").forEachIndexed { i, l -> val on = s.mn(i * 10, i * 10 + 10) > .5f
        drawCircle(if (on) Color(0xFFFFA000) else Color(0xFF3A2A10), b.h * .035f, Offset(x, b.y(.28f + i * .12f))); t(l, x, b.y(.28f + i * .12f) + b.h * .015f, b.h * .04f, Color.Black) }
    t("DIFF LOCK", x, b.y(.68f), b.h * .03f, Color(0xFFCCCCCC), spacing = .1f)
}
/** Widescreen digital cockpit: two glowing round displays on one glass panel. */
internal fun DrawScope.carGWide(s: VisState) {
    val b = box(); housing(b, Color(0xFF0B0C0E), Color(0xFF030304), Color(0xFF3A3D41), .08f)
    panel(b.x(.02f), b.y(.06f), b.w * .96f, b.h * .88f, Color(0xFF020305), Color(0xFF2EC5FF), Color(0xFF1A2129), .08f)
    val cy = Color(0xFF6FE3FF)
    listOf(Triple(.24f, s.spd(), "km/h"), Triple(.76f, s.rpm(), "x1000 rpm")).forEach { (u, v, lab) ->
        val c = Offset(b.x(u), b.y(.52f)); val r = b.h * .38f
        drawCircle(Brush.radialGradient(listOf(Color(0xFF0A1A24), Color(0xFF020305)), c, r), r, c)
        drawArc(cy.copy(alpha = .12f), 135f, 270f, false, Offset(c.x - r * .86f, c.y - r * .86f), Size(r * 1.72f, r * 1.72f), style = Stroke(r * .08f))
        drawArc(Brush.sweepGradient(listOf(Color(0xFF2EC5FF), Color(0xFFB5F3FF), Color(0xFF2EC5FF)), c), 135f, 270f * v, false, Offset(c.x - r * .86f, c.y - r * .86f), Size(r * 1.72f, r * 1.72f), style = Stroke(r * .08f))
        for (k in 0..12) drawLine(Color.White.copy(alpha = .6f), pol(c, 135f + 22.5f * k, r * .7f), pol(c, 135f + 22.5f * k, r * .78f), 2f)
        val a = 135f + 270f * v; drawLine(Color.White, pol(c, a, r * .3f), pol(c, a, r * .9f), r * .035f, cap = StrokeCap.Round)
        t(if (lab == "km/h") kmh(s) else "%.1f".format(v * 7), c.x, c.y + r * .12f, r * .32f, Color.White, F.semi)
        t(lab, c.x, c.y + r * .34f, r * .1f, cy, spacing = .1f)
    }
    // middle: off-road inclinometer
    val c = Offset(b.x(.5f), b.y(.5f)); val r = b.h * .14f
    drawCircle(cy.copy(alpha = .3f), r, c, style = Stroke(2f))
    val tilt = (s.mn(0, 10) - s.mn(30, 48)) * 30f
    drawLine(Color.White, pol(c, 180f + tilt, r * .9f), pol(c, tilt, r * .9f), 3f)
    t("%+.0f°".format(tilt), c.x, c.y + r * 1.6f, r * .4f, Color.White, F.semi)
    t("OFFROAD", c.x, c.y - r * 1.3f, r * .3f, cy, spacing = .15f)
}

// ---------------------------------------------------------------- Audi style

private val AUDI_RED = Color(0xFFE5202E)
/** Virtual cockpit, classic view: two large round dials on a full-width screen, info in between. */
internal fun DrawScope.carAudi(s: VisState) {
    val b = box(); housing(b, Color(0xFF08090A), Color(0xFF020203), Color(0xFF262A2E), .04f)
    panel(b.x(.02f), b.y(.05f), b.w * .96f, b.h * .9f, Color(0xFF040506), null, Color(0xFF1C1F22), .04f)
    val D = { labels: List<String>, red: Color? -> Dial(Brush.radialGradient(listOf(Color(0xFF15181B), Color(0xFF040506))), Color(0xFF3A3F44), Color(0xFFDDDDDD), Color(0xFFDDDDDD), AUDI_RED, red,
        start = 125f, sweep = 290f, labels = labels, minor = 2, rimW = .02f, numPx = .12f, font = F.wide) }
    dial(Offset(b.x(.2f), b.y(.52f)), b.h * .37f, s.rpm(), D((0..8).map { "$it" }, AUDI_RED), "1/min x1000")
    dial(Offset(b.x(.8f), b.y(.52f)), b.h * .37f, s.spd(), D(listOf("0", "40", "80", "120", "160", "200", "240", "280", "320"), null), "km/h")
    t(kmh(s), b.x(.8f), b.y(.78f), b.h * .065f, Color.White, F.wide, bold = false)
    // centre: the "map" area shows the spectrum as a road of light
    specBars(s.spec(14), b.x(.42f), b.y(.28f), b.w * .16f, b.h * .42f, { _, v -> if (v > .8f) AUDI_RED else Color(0xFFDDDDDD) })
    t("D${s.gear()}", b.x(.5f), b.y(.85f), b.h * .08f, Color.White, F.wide, bold = false)
}
/** Sport view: one big central rev counter with a red sweep, speed in the middle, side readouts. */
internal fun DrawScope.carAudiSport(s: VisState) {
    val b = box(); housing(b, Color(0xFF08090A), Color(0xFF020203), Color(0xFF262A2E), .04f)
    panel(b.x(.02f), b.y(.05f), b.w * .96f, b.h * .9f, Color(0xFF040506), Color(0xFFE5202E), Color(0xFF1C1F22), .04f)
    val c = Offset(b.x(.5f), b.y(.55f)); val r = b.h * .46f
    val segs = 60; val lit = (s.rpm() * segs).toInt()
    for (k in 0 until segs) { val a = 140f + 260f * k / (segs - 1); val f = k / (segs - 1f)
        drawLine(if (k < lit) (if (f > .8f) AUDI_RED else Color.White) else Color.White.copy(alpha = .08f), pol(c, a, r * .78f), pol(c, a, r * .9f), r * .03f) }
    (0..8).forEach { k -> val p = pol(c, 140f + 32.5f * k, r * .66f); t("$k", p.x, p.y + r * .04f, r * .1f, Color(0xFFDDDDDD), F.wide, bold = false) }
    t(kmh(s), c.x, c.y + r * .08f, r * .26f, Color.White, F.wide, bold = false)
    t("km/h", c.x, c.y + r * .22f, r * .08f, Color(0xFFAAAAAA), spacing = .2f)
    t("S${s.gear()}", c.x, c.y + r * .48f, r * .14f, AUDI_RED, F.wide, bold = false)
    listOf("POWER" to s.rpm(), "TORQUE" to s.mn(0, 12), "G" to s.hi()).forEachIndexed { i, (l, v) ->
        t(l, b.x(.08f), b.y(.3f + i * .2f), b.h * .045f, Color(0xFFAAAAAA), F.wide, Paint.Align.LEFT, spacing = .1f, bold = false)
        drawRect(Color(0xFF1C1F22), Offset(b.x(.08f), b.y(.33f + i * .2f)), Size(b.w * .16f, b.h * .025f))
        drawRect(if (l == "POWER") AUDI_RED else Color.White, Offset(b.x(.08f), b.y(.33f + i * .2f)), Size(b.w * .16f * v, b.h * .025f))
    }
    t("%d%%".format((s.rpm() * 100).toInt()), b.x(.88f), b.y(.45f), b.h * .12f, Color.White, F.wide, bold = false)
    t("BOOST", b.x(.88f), b.y(.53f), b.h * .045f, Color(0xFFAAAAAA), F.wide, spacing = .15f, bold = false)
}

// ---------------------------------------------------------------- Acura NSX style

/** First-generation style: analogue rev counter and speedo, white on black, orange needles. */
internal fun DrawScope.carNsxClassic(s: VisState) {
    val b = box(); housing(b, Color(0xFF141414), Color(0xFF050505), Color(0xFF2E2E2E), .3f)
    val orange = Color(0xFFFF8A1E)
    val D = { labels: List<String>, red: Color? -> Dial(Brush.radialGradient(listOf(Color(0xFF111111), Color(0xFF050505))), Color(0xFF222222), Color.White, Color.White, orange, red,
        start = 135f, sweep = 270f, labels = labels, minor = 2, rimW = .03f, numPx = .15f, redFrom = .8f) }
    dial(Offset(b.x(.33f), b.y(.53f)), b.h * .41f, s.rpm(), D((0..10).map { "$it" }, Color(0xFFE53935)), "x1000 r/min")
    dial(Offset(b.x(.69f), b.y(.55f)), b.h * .34f, s.spd(), D(listOf("0", "20", "40", "60", "80", "100", "120", "140", "160", "180"), null), "MPH")
    val sm = Dial(Brush.radialGradient(listOf(Color(0xFF111111), Color(0xFF050505))), Color(0xFF222222), Color.White, Color.White, orange, null, start = 200f, sweep = 140f, labels = listOf("", ""), minor = 4, rimW = .03f)
    dial(Offset(b.x(.075f), b.y(.36f)), b.h * .12f, s.mn(0, 8), sm, "OIL")
    dial(Offset(b.x(.075f), b.y(.72f)), b.h * .12f, s.hi(), sm, "TEMP")
    dial(Offset(b.x(.925f), b.y(.36f)), b.h * .12f, .7f, sm, "FUEL")
    dial(Offset(b.x(.925f), b.y(.72f)), b.h * .12f, s.level, sm, "VOLT")
}
/** Second-generation style: round TFT rev counter whose ring changes colour with the drive mode (red = track). */
internal fun DrawScope.carNsx(s: VisState) {
    val b = box(); housing(b, Color(0xFF0E0F11), Color(0xFF030304), Color(0xFF2A2D31), .1f)
    val track = s.rpm() > .55f
    val ring = if (track) Color(0xFFFF2D2D) else Color(0xFF2EA8FF)
    val c = Offset(b.x(.5f), b.y(.52f)); val r = b.h * .44f
    drawCircle(Brush.radialGradient(listOf(ring.copy(alpha = .25f), Color.Transparent), c, r * 1.15f), r * 1.15f, c)
    drawCircle(ring, r, c, style = Stroke(r * .06f))
    drawCircle(Color(0xFF050608), r * .96f, c)
    val segs = 40; val lit = (s.rpm() * segs).toInt()
    for (k in 0 until segs) { val a = 135f + 270f * k / (segs - 1)
        drawLine(if (k < lit) (if (k > segs * .82f) Color(0xFFFF2D2D) else Color.White) else Color.White.copy(alpha = .08f), pol(c, a, r * .76f), pol(c, a, r * .9f), r * .035f) }
    (0..9).forEach { k -> val p = pol(c, 135f + 30f * k, r * .64f); t("$k", p.x, p.y + r * .04f, r * .1f, Color.White, F.semi) }
    t(kmh(s), c.x, c.y + r * .08f, r * .36f, Color.White, F.semi); t("MPH", c.x, c.y + r * .25f, r * .08f, Color(0xFFAAAAAA), spacing = .2f)
    t(if (track) "TRACK" else "SPORT+", c.x, c.y + r * .5f, r * .12f, ring, F.semi, spacing = .15f)
    // side panels: hybrid power flow and the spectrum
    panel(b.x(.04f), b.y(.18f), b.w * .22f, b.h * .64f, glow = ring)
    t("POWER", b.x(.15f), b.y(.28f), b.h * .045f, Color(0xFFAAAAAA), spacing = .15f)
    listOf("ENG" to s.rpm(), "FRONT" to s.mn(10, 24), "REAR" to s.mn(0, 10)).forEachIndexed { i, (l, v) ->
        t(l, b.x(.06f), b.y(.42f + i * .14f), b.h * .04f, Color.White, align = Paint.Align.LEFT)
        drawRect(Color(0xFF1C1F22), Offset(b.x(.13f), b.y(.39f + i * .14f)), Size(b.w * .11f, b.h * .035f)); drawRect(ring, Offset(b.x(.13f), b.y(.39f + i * .14f)), Size(b.w * .11f * v, b.h * .035f))
    }
    panel(b.x(.74f), b.y(.18f), b.w * .22f, b.h * .64f, glow = ring)
    specBars(s.spec(10), b.x(.76f), b.y(.26f), b.w * .18f, b.h * .45f, { _, v -> if (v > .8f) Color(0xFFFF2D2D) else Color.White }, segs = 10)
}

// ---------------------------------------------------------------- Honda Civic Type R style

private val TYPE_R = Color(0xFFE3001B)
/** Digital meter: a rising rev bar across the top, gear in the middle, speed and boost. */
internal fun DrawScope.carTypeR(s: VisState) {
    val b = box(.45f); housing(b, Color(0xFF0E0F11), Color(0xFF030304))
    panel(b.x(.03f), b.y(.06f), b.w * .94f, b.h * .88f, Color(0xFF040506), TYPE_R, Color(0xFF26292D))
    // the arched rev bar
    val segs = 34; val lit = (s.rpm() * segs).toInt(); val l = b.x(.08f); val w = b.w * .84f
    for (k in 0 until segs) {
        val f = k / (segs - 1f); val x = l + w * f; val y = b.y(.42f) - b.h * .22f * sin(Math.PI * f).toFloat() * .7f - b.h * .12f * f
        val col = if (f > .85f) TYPE_R else if (f > .7f) Color(0xFFFFC21C) else Color.White
        drawRect(if (k < lit) col else col.copy(alpha = .08f), Offset(x, y), Size(w / segs * .7f, b.h * .09f))
    }
    (0..8).forEach { k -> val f = k / 8f; t("$k", l + w * f, b.y(.6f) - b.h * .22f * sin(Math.PI * f).toFloat() * .7f - b.h * .12f * f, b.h * .05f, Color(0xFFBBBBBB), F.semi) }
    t("${s.gear()}", b.x(.5f), b.y(.86f), b.h * .34f, Color.White, F.semi)
    t(kmh(s), b.x(.22f), b.y(.8f), b.h * .16f, Color.White, F.semi); t("km/h", b.x(.22f), b.y(.88f), b.h * .045f, Color(0xFFAAAAAA))
    t("%.2f".format(s.mn(0, 10) * 1.6f), b.x(.78f), b.y(.8f), b.h * .16f, Color.White, F.semi); t("BOOST bar", b.x(.78f), b.y(.88f), b.h * .045f, Color(0xFFAAAAAA))
    t("+R", b.x(.92f), b.y(.18f), b.h * .07f, TYPE_R, F.semi)
}
/** +R mode: red theme with shift lights, a G-meter, boost and the spectrum as engine telemetry. */
internal fun DrawScope.carTypeRPlus(s: VisState) {
    val b = box(.45f); housing(b, Color(0xFF140406), Color(0xFF050102), Color(0xFF3A0A10))
    panel(b.x(.03f), b.y(.06f), b.w * .94f, b.h * .88f, Color(0xFF070203), TYPE_R, Color(0xFF3A0A10))
    if (!b.small) shiftLights(b.x(.5f), b.y(.14f), b.w * .55f, 13, s.rpm())
    revBar(b.x(.07f), b.y(.22f), b.w * .86f, b.h * .12f, s.rpm(), 40, .7f, .86f, (0..8).map { "$it" }, Color(0xFFFFB3B3))
    t("${s.gear()}", b.x(.5f), b.y(.86f), b.h * .36f, Color.White, F.semi, glow = TYPE_R)
    gForce(Offset(b.x(.17f), b.y(.66f)), b.h * .17f, s, Color(0xFFFF5A6A))
    t("G-METER", b.x(.17f), b.y(.92f), b.h * .04f, Color(0xFFFFB3B3), spacing = .15f)
    specBars(s.spec(12), b.x(.7f), b.y(.5f), b.w * .24f, b.h * .3f, { _, v -> if (v > .8f) Color.White else TYPE_R }, segs = 8)
    t("ENGINE", b.x(.82f), b.y(.88f), b.h * .04f, Color(0xFFFFB3B3), spacing = .15f)
    t(kmh(s), b.x(.34f), b.y(.62f), b.h * .12f, Color.White, F.semi); t("km/h", b.x(.34f), b.y(.69f), b.h * .04f, Color(0xFFFFB3B3))
}
