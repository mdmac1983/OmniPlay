package app.orionmd.omniplay.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import app.orionmd.omniplay.Player
import app.orionmd.omniplay.Visual
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Hi-fi faceplates: visualizers drawn as the front of classic amplifiers and receivers, two looks each, inspired by
 * McIntosh, Marantz, Onkyo, Sony ES, Kenwood and Pioneer Elite. No brand names or logos: each carries the OrionMD name
 * in lettering of a similar style. Meters, bars and displays move with the music (the analysis is mono, so "left"
 * leans on the lows and "right" on the highs, which keeps the two needles moving differently).
 */

/** Lettering for the faceplates (open-licence fonts in assets/fonts; plain system fonts until [init] runs). */
internal object HifiFonts {
    var wide: Typeface? = null; var heavy: Typeface? = null; var slab: Typeface? = null; var script: Typeface? = null
    var vfd: Typeface? = null; var semi: Typeface? = null; var cond: Typeface? = null
    fun init(ctx: Context) {
        fun f(n: String) = try { Typeface.createFromAsset(ctx.assets, "fonts/$n") } catch (_: Exception) { null }
        wide = f("Michroma-Regular.ttf"); heavy = f("ArchivoBlack-Regular.ttf"); slab = f("ZillaSlab-Bold.ttf"); script = f("GreatVibes-Regular.ttf")
        vfd = f("VT323-Regular.ttf"); semi = f("SairaSemiCondensed-SemiBold.ttf"); cond = f("SairaCondensed-ExtraBold.ttf")
    }
}

// ---------------------------------------------------------------- helpers

private fun VisState.b(k: Int) = disp[k.coerceIn(0, Visual.BANDS - 1)].coerceIn(0f, 1f)
private fun VisState.mean(a: Int, z: Int) = (a until z).sumOf { b(it).toDouble() }.toFloat() / (z - a)
private fun VisState.left() = (mean(0, 18) * 1.15f).coerceIn(0f, 1f)
private fun VisState.right() = (mean(10, Visual.BANDS) * 1.55f).coerceIn(0f, 1f)
private fun VisState.mono() = ((left() + right()) / 2f).coerceIn(0f, 1f)
/** [n] columns over the spectrum, spaced like the ear hears (more room for the lows). */
private fun VisState.cols(n: Int): FloatArray = FloatArray(n) { i ->
    val a = (Visual.BANDS * (i.toFloat() / n).pow(1.3f)).toInt().coerceIn(0, Visual.BANDS - 1)
    val z = (Visual.BANDS * ((i + 1f) / n).pow(1.3f)).toInt().coerceIn(a + 1, Visual.BANDS)
    (mean(a, z) * 1.3f).coerceIn(0f, 1f)
}
private fun VisState.peakOf(a: Int, z: Int) = (a until z).maxOf { peak[it.coerceIn(0, Visual.BANDS - 1)] }.coerceIn(0f, 1f)
/** A believable volume readout from the level, e.g. "-24.5". */
private fun VisState.db() = "%.1f".format(-60f + 52f * level.coerceIn(0f, 1f))
private fun progress(): Float { val d = Player.durationMs(); return if (d > 0) (Player.positionMs().toFloat() / d).coerceIn(0f, 1f) else 0f }

private fun DrawScope.txt(t: String, x: Float, y: Float, px: Float, color: Color, font: Typeface? = null, align: Paint.Align = Paint.Align.CENTER,
                          glow: Color? = null, spacing: Float = 0f, skew: Float = 0f, bold: Boolean = false) {
    if (px < 3f) return
    drawIntoCanvas { c ->
        c.nativeCanvas.drawText(t, x, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb(); textSize = px; textAlign = align; typeface = font ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            letterSpacing = spacing; textSkewX = skew
            if (glow != null) setShadowLayer(px * .35f, 0f, 0f, glow.toArgb())
        })
    }
}
private fun textWidth(t: String, px: Float, font: Typeface?, spacing: Float = 0f) =
    Paint().apply { textSize = px; typeface = font; letterSpacing = spacing }.measureText(t)

/** The faceplate's box inside the canvas: a wide, short front in any orientation. */
private class Plate(val l: Float, val t: Float, val w: Float, val h: Float) {
    fun x(u: Float) = l + w * u; fun y(v: Float) = t + h * v
    val tiny get() = h < 170f
}
private fun DrawScope.plate(ratio: Float = .8f): Plate {
    val pw = min(size.width * .97f, size.height * .97f / ratio * 1.25f).coerceAtMost(size.width * .97f)
    val ph = min(size.height * .97f, pw * ratio)
    return Plate((size.width - pw) / 2, (size.height - ph) / 2, pw, ph)
}
private fun DrawScope.face(p: Plate, top: Color, bottom: Color, lines: Float = .04f, lineColor: Color = Color.White, radius: Float = .015f) {
    drawRoundRect(Color.Black.copy(alpha = .55f), Offset(p.l + 4f, p.t + 8f), Size(p.w, p.h), CornerRadius(p.w * radius))
    drawRoundRect(Brush.verticalGradient(listOf(top, bottom), p.t, p.t + p.h), Offset(p.l, p.t), Size(p.w, p.h), CornerRadius(p.w * radius))
    if (lines > 0f) clipRect(p.l, p.t, p.l + p.w, p.t + p.h) {   // brushed metal: fine horizontal lines
        var yy = p.t; var k = 0
        while (yy < p.t + p.h) { drawLine(lineColor.copy(alpha = lines * (if (k % 3 == 0) 1f else .45f)), Offset(p.l, yy), Offset(p.l + p.w, yy), 1f); yy += 2.5f; k++ }
    }
    drawRoundRect(Color.White.copy(alpha = .12f), Offset(p.l, p.t), Size(p.w, p.h), CornerRadius(p.w * radius), style = Stroke(1.5f))
}
private fun DrawScope.glare(p: Plate, a: Float = .07f) =
    drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), Offset(p.l, p.t), Offset(p.l + p.w * .4f, p.t + p.h * .5f)), Offset(p.l, p.t), Size(p.w, p.h), CornerRadius(p.w * .015f))
private fun DrawScope.endCaps(p: Plate) {
    val cw = p.w * .035f
    listOf(p.l - cw * .4f, p.l + p.w - cw * .6f).forEach { x ->
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF6D7378), Color(0xFFE7ECEF), Color(0xFF8A9095), Color(0xFF4A4F53)), x, x + cw), Offset(x, p.t), Size(cw, p.h), CornerRadius(cw * .3f))
    }
}
/** A round knob; [chrome] = polished metal, else black with a light pointer. */
private fun DrawScope.knob(c: Offset, r: Float, angle: Float, chrome: Boolean, ring: Color? = null) {
    drawCircle(Color.Black.copy(alpha = .5f), r * 1.04f, c + Offset(0f, r * .12f))
    if (chrome) {
        drawCircle(Brush.sweepGradient(listOf(Color(0xFFF4F4F4), Color(0xFF8B8F93), Color(0xFFFDFDFD), Color(0xFF6E7276), Color(0xFFF0F0F0), Color(0xFF9A9EA2), Color(0xFFF4F4F4)), c), r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent), c + Offset(-r * .3f, -r * .35f), r * .8f), r, c)
    } else {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF4A4F54), Color(0xFF16191C)), c + Offset(-r * .3f, -r * .3f), r * 1.3f), r, c)
        drawCircle(Color(0xFF2A2E32), r, c, style = Stroke(r * .08f))
    }
    ring?.let { drawCircle(it, r * 1.04f, c, style = Stroke(r * .08f)) }
    val a = Math.toRadians((angle - 90).toDouble())
    drawLine(if (chrome) Color(0xFF222222) else Color(0xFFE9EEF0), c + Offset((cos(a) * r * .45f).toFloat(), (sin(a) * r * .45f).toFloat()),
        c + Offset((cos(a) * r * .85f).toFloat(), (sin(a) * r * .85f).toFloat()), strokeWidth = r * .1f, cap = StrokeCap.Round)
}
private fun DrawScope.knobRow(p: Plate, y: Float, r: Float, labels: List<String>, angles: List<Float>, chrome: Boolean, label: Color, font: Typeface? = null, ring: Color? = null, x0: Float = .12f, x1: Float = .88f) {
    if (p.tiny) return
    val n = labels.size
    labels.forEachIndexed { i, l ->
        val x = p.x(x0 + (x1 - x0) * i / (n - 1).coerceAtLeast(1))
        knob(Offset(x, y), r, angles[i], chrome, ring)
        txt(l, x, y + r * 1.75f, r * .42f, label, font, spacing = .15f)
    }
}
private fun DrawScope.screen(l: Float, t: Float, w: Float, h: Float, bg: Color, glow: Color, rim: Color = Color(0xFF111111)) {
    drawRoundRect(bg, Offset(l, t), Size(w, h), CornerRadius(h * .05f))
    drawRoundRect(Brush.radialGradient(listOf(glow.copy(alpha = .16f), Color.Transparent), Offset(l + w / 2, t + h / 2), maxOf(w, h) * .7f), Offset(l, t), Size(w, h), CornerRadius(h * .05f))
    drawRoundRect(rim, Offset(l, t), Size(w, h), CornerRadius(h * .05f), style = Stroke(2.5f))
}
/** Segmented columns (VFD / LED style) inside a box. */
private fun DrawScope.segCols(v: FloatArray, l: Float, t: Float, w: Float, h: Float, segs: Int, on: (Int, Int) -> Color, off: Color, gap: Float = .28f, peaks: FloatArray? = null, peakColor: Color? = null) {
    val cw = w / v.size; val sh = h / segs
    v.forEachIndexed { i, x ->
        val lit = (x * segs).toInt()
        for (k in 0 until segs) {
            val c = if (k < lit) on(k, segs) else off
            drawRect(c, Offset(l + i * cw + cw * gap / 2, t + h - (k + 1) * sh + sh * .18f), Size(cw * (1 - gap), sh * .64f))
        }
        if (peaks != null && peakColor != null) {
            val pk = (peaks[i] * segs).toInt().coerceIn(0, segs - 1)
            if (pk > lit) drawRect(peakColor, Offset(l + i * cw + cw * gap / 2, t + h - (pk + 1) * sh + sh * .18f), Size(cw * (1 - gap), sh * .64f))
        }
    }
}

// ---------------------------------------------------------------- McIntosh style

private val MAC_GREEN = Color(0xFF3CFF8A)
private fun DrawScope.blueMeter(l: Float, t: Float, w: Float, h: Float, v: Float, labels: List<String>, title: String) {
    drawRoundRect(Color.Black, Offset(l - 3, t - 3), Size(w + 6, h + 6), CornerRadius(6f))
    drawRect(Brush.radialGradient(listOf(Color(0xFF4FD2FF), Color(0xFF0F7FD6), Color(0xFF03243F)), Offset(l + w / 2, t + h * .78f), maxOf(w, h) * .85f), Offset(l, t), Size(w, h))
    drawRect(Color.White.copy(alpha = .07f), Offset(l, t), Size(w, h * .42f))
    clipRect(l, t, l + w, t + h) {
        val c = Offset(l + w / 2, t + h * 1.18f); val r = h * .98f
        val a0 = -31f; val sweep = 62f
        fun pt(deg: Float, rr: Float) = Offset(c.x + rr * sin(Math.toRadians(deg.toDouble())).toFloat(), c.y - rr * cos(Math.toRadians(deg.toDouble())).toFloat())
        var prev = pt(a0, r)
        for (k in 1..40) { val p2 = pt(a0 + sweep * k / 40, r); drawLine(Color.White, prev, p2, h * .012f); prev = p2 }
        prev = pt(a0 + sweep * .72f, r - h * .035f)
        for (k in 1..14) { val p2 = pt(a0 + sweep * (.72f + .28f * k / 14), r - h * .035f); drawLine(Color(0xFFFF4D4D), prev, p2, h * .03f); prev = p2 }
        for (k in 0..20) { val d = a0 + sweep * k / 20; drawLine(Color.White, pt(d, r), pt(d, r + h * (if (k % 2 == 0) .07f else .04f)), h * .01f) }
        labels.forEachIndexed { i, s -> val p2 = pt(a0 + sweep * i / (labels.size - 1), r + h * .13f); txt(s, p2.x, p2.y + h * .03f, h * .07f, Color.White) }
        txt(title, c.x, t + h * .72f, h * .08f, Color(0xFFE8F7FF), spacing = .2f)
        val d = a0 + sweep * v.coerceIn(0f, 1f)
        drawLine(Color.Black.copy(alpha = .25f), c + Offset(3f, 3f), pt(d, r + h * .05f) + Offset(3f, 3f), h * .03f)
        drawLine(Color(0xFF0A0A0A), c, pt(d, r + h * .05f), h * .014f)
    }
    drawRect(Color(0xFF01111F).copy(alpha = .85f), Offset(l + w * .36f, t + h * .86f), Size(w * .28f, h * .12f))
}
private fun DrawScope.orionGreen(p: Plate, y: Float, px: Float) {
    val f = HifiFonts.semi
    val w1 = textWidth("Orion", px, f); val w2 = textWidth("MD", px * .62f, f)
    val x0 = p.x(.5f) - (w1 + w2) / 2
    txt("Orion", x0, y, px, MAC_GREEN, f, Paint.Align.LEFT, glow = Color(0xFF28E66E))
    txt("MD", x0 + w1, y - px * .32f, px * .62f, MAC_GREEN, f, Paint.Align.LEFT, glow = Color(0xFF28E66E))
}
private val WATT_LABELS = listOf(".1", ".25", ".5", "1", "2", "5", "10", "∞")

/** Twin blue power meters on black glass. */
internal fun DrawScope.hfMacTwin(s: VisState) {
    val p = plate(); face(p, Color(0xFF0B0D0F), Color.Black, lines = 0f); glare(p); endCaps(p)
    val mw = p.w * .38f; val mh = p.h * .4f
    blueMeter(p.x(.09f), p.y(.07f), mw, mh, s.left(), WATT_LABELS, "WATTS")
    blueMeter(p.x(.53f), p.y(.07f), mw, mh, s.right(), WATT_LABELS, "WATTS")
    orionGreen(p, p.y(.63f), p.h * .12f)
    txt("STEREO POWER OUTPUT · LEFT · WATTS · RIGHT", p.x(.5f), p.y(.7f), p.h * .028f, MAC_GREEN.copy(alpha = .8f), spacing = .2f)
    knobRow(p, p.y(.82f), p.h * .055f, listOf("BASS", "TREBLE", "BALANCE", "VOLUME"), listOf(-30f, 15f, 0f, -40f + 120f * s.level), false, MAC_GREEN.copy(alpha = .85f))
}

/** One wide meter (a monoblock) with power-guard lights. */
internal fun DrawScope.hfMacMono(s: VisState) {
    val p = plate(); face(p, Color(0xFF0B0D0F), Color.Black, lines = 0f); glare(p); endCaps(p)
    blueMeter(p.x(.14f), p.y(.06f), p.w * .72f, p.h * .45f, s.mono(), listOf("0", ".25", "1", "5", "25", "100", "500", "1200"), "WATTS")
    orionGreen(p, p.y(.66f), p.h * .12f)
    val on = s.level > .8f
    txt("OVERLOAD GUARD", p.x(.25f), p.y(.74f), p.h * .03f, if (on) Color(0xFFFF5040) else Color(0xFF5A1E1A), spacing = .2f, glow = if (on) Color.Red else null)
    txt("MONO BLOCK · 1200 W", p.x(.75f), p.y(.74f), p.h * .03f, MAC_GREEN.copy(alpha = .8f), spacing = .2f)
    knobRow(p, p.y(.86f), p.h * .05f, listOf("INPUT", "METER LIGHTS", "POWER"), listOf(-40f, 30f, 0f), false, MAC_GREEN.copy(alpha = .85f))
}

// ---------------------------------------------------------------- Marantz style

private fun DrawScope.roundBlue(c: Offset, r: Float, v: Float, label: String) {
    drawCircle(Color(0xFF1A1408), r * 1.08f, c); drawCircle(Color(0xFF9C8A5C), r * 1.08f, c, style = Stroke(r * .05f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF7FD0FF), Color(0xFF1D7FD0), Color(0xFF06223F)), c + Offset(0f, r * .2f), r), r, c)
    val hub = c + Offset(0f, r * .3f)
    for (k in 0..10) { val a = Math.toRadians((-50 + 10 * k - 90).toDouble()); drawLine(Color(0xFFEAF6FF), hub + Offset((cos(a) * r * .7f).toFloat(), (sin(a) * r * .7f).toFloat()), hub + Offset((cos(a) * r * .82f).toFloat(), (sin(a) * r * .82f).toFloat()), r * .025f) }
    txt(label, c.x, c.y + r * .62f, r * .17f, Color(0xFFEAF6FF), spacing = .1f)
    val a = Math.toRadians((-50 + 100 * v - 90).toDouble())
    drawLine(Color(0xFFFF5A3C), hub, hub + Offset((cos(a) * r * .85f).toFloat(), (sin(a) * r * .85f).toFloat()), r * .035f)
    drawCircle(Color(0xFF111111), r * .07f, hub)
}

/** Champagne receiver: blue-lit dial with the spectrum and a pointer that follows the song, two round meters, flywheel. */
internal fun DrawScope.hfMarantzDial(s: VisState) {
    val p = plate(); face(p, Color(0xFFE2D4AE), Color(0xFFAB9864), lines = .06f, lineColor = Color.White)
    txt("orionmd", p.x(.5f), p.y(.14f), p.h * .075f, Color(0xFF2A2416), HifiFonts.wide, spacing = .25f)
    txt("STEREOPHONIC RECEIVER", p.x(.5f), p.y(.2f), p.h * .025f, Color(0xFF4A4128), HifiFonts.wide, spacing = .2f)
    val r = p.h * .13f
    roundBlue(Offset(p.x(.13f), p.y(.44f)), r, s.left(), "SIGNAL")
    roundBlue(Offset(p.x(.87f), p.y(.44f)), r, s.right(), "LEVEL")
    val dl = p.x(.26f); val dt = p.y(.27f); val dw = p.w * .48f; val dh = p.h * .36f
    screen(dl, dt, dw, dh, Color(0xFF041426), Color(0xFF49B4FF), Color(0xFF2B2412))
    listOf("88", "92", "96", "100", "104", "108").forEachIndexed { i, f -> txt(f, dl + dw * (.08f + i * .17f), dt + dh * .16f, dh * .1f, Color(0xFFE6F6FF)) }
    drawLine(Color(0xFFBFE8FF).copy(alpha = .7f), Offset(dl, dt + dh * .22f), Offset(dl + dw, dt + dh * .22f), 1.5f)
    val c = s.cols(16); val bw = dw / 16f
    c.forEachIndexed { i, v -> val hh = dh * .62f * v
        drawRect(Brush.verticalGradient(listOf(Color(0xFFBFE8FF), Color(0xFF3AA7FF)), dt + dh * .9f - hh, dt + dh * .9f), Offset(dl + i * bw + bw * .2f, dt + dh * .9f - hh), Size(bw * .6f, hh), alpha = .9f) }
    val px = dl + dw * (.03f + .94f * progress())
    drawLine(Color(0xFFFF4B2B), Offset(px, dt + 4), Offset(px, dt + dh - 4), 3f)
    knobRow(p, p.y(.8f), p.h * .06f, listOf("BASS", "MID", "TREBLE", "BALANCE"), listOf(-30f, 10f, 25f, 0f), true, Color(0xFF3B331E), x0 = .09f, x1 = .54f)
    if (!p.tiny) {   // flywheel tuning knob: its ribs drift with the music
        val fl = p.x(.62f); val ft = p.y(.74f); val fw = p.w * .32f; val fh = p.h * .1f
        clipRect(fl, ft, fl + fw, ft + fh) {
            drawRect(Color(0xFF7D8287), Offset(fl, ft), Size(fw, fh))
            val off = (s.spin * 40f) % 8f
            var x = fl - 8 + off; while (x < fl + fw) { drawRect(Color(0xFFE9ECEE), Offset(x, ft), Size(3f, fh)); x += 8f }
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent, Color.Black.copy(alpha = .45f)), ft, ft + fh), Offset(fl, ft), Size(fw, fh))
        }
        txt("FLYWHEEL TUNING", fl + fw / 2, ft + fh + p.h * .06f, p.h * .03f, Color(0xFF3B331E), spacing = .15f)
    }
}

/** Modern black front with a gold trim and the round "porthole" display. */
internal fun DrawScope.hfMarantzPorthole(s: VisState) {
    val p = plate(); face(p, Color(0xFF1B1A18), Color(0xFF060605), lines = .03f)
    val gold = Color(0xFFD9BF7E)
    drawLine(gold, Offset(p.x(.03f), p.y(.9f)), Offset(p.x(.97f), p.y(.9f)), 2f)
    txt("orionmd", p.x(.5f), p.y(.13f), p.h * .07f, gold, HifiFonts.wide, spacing = .3f)
    val c = Offset(p.x(.5f), p.y(.5f)); val r = p.h * .3f
    drawCircle(gold, r * 1.06f, c, style = Stroke(r * .04f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFF1D1408), Color(0xFF050302)), c, r), r, c)
    val n = 48
    for (k in 0 until n) {
        val v = s.b(k * Visual.BANDS / n)
        val a = Math.toRadians((k * 360.0 / n) - 90)
        val r0 = r * .62f; val r1 = r0 + r * .3f * (.15f + v)
        drawLine(Color(0xFFFFCB7A).copy(alpha = .35f + .65f * v), c + Offset((cos(a) * r0).toFloat(), (sin(a) * r0).toFloat()), c + Offset((cos(a) * r1).toFloat(), (sin(a) * r1).toFloat()), r * .045f, cap = StrokeCap.Round)
    }
    txt(s.db(), c.x, c.y + r * .12f, r * .32f, Color(0xFFFFD08A), HifiFonts.vfd, glow = Color(0xFFFFA43A))
    txt("dB · DIRECT", c.x, c.y + r * .34f, r * .1f, Color(0xFFFFD08A), spacing = .2f)
    if (!p.tiny) {
        knob(Offset(p.x(.15f), p.y(.5f)), p.h * .13f, -50f, false, gold)
        knob(Offset(p.x(.85f), p.y(.5f)), p.h * .13f, -40f + 120f * s.level, false, gold)
        txt("INPUT", p.x(.15f), p.y(.74f), p.h * .03f, gold, spacing = .2f)
        txt("VOLUME", p.x(.85f), p.y(.74f), p.h * .03f, gold, spacing = .2f)
    }
}

// ---------------------------------------------------------------- Onkyo style

/** Black AV receiver with a teal VFD spectrum and a big volume knob. */
internal fun DrawScope.hfOnkyoTeal(s: VisState) {
    val p = plate(); face(p, Color(0xFF26292C), Color(0xFF0F1113))
    txt("ORIONMD", p.x(.05f), p.y(.14f), p.h * .08f, Color(0xFFF2F4F5), HifiFonts.heavy, Paint.Align.LEFT, spacing = .12f)
    txt("AV RECEIVER · HI-RES AUDIO", p.x(.95f), p.y(.12f), p.h * .028f, Color(0xFF9AA3A8), align = Paint.Align.RIGHT, spacing = .2f)
    val teal = Color(0xFF7FF6FF)
    val dl = p.x(.2f); val dt = p.y(.22f); val dw = p.w * .53f; val dh = p.h * .5f
    screen(dl, dt, dw, dh, Color(0xFF020A0C), Color(0xFF4EE6FF), Color(0xFF0D2A30))
    txt("STEREO", dl + dw * .05f, dt + dh * .14f, dh * .11f, teal, HifiFonts.vfd, Paint.Align.LEFT, glow = Color(0xFF4EE6FF))
    txt("DIRECT MODE", dl + dw * .95f, dt + dh * .14f, dh * .11f, teal, HifiFonts.vfd, Paint.Align.RIGHT, glow = Color(0xFF4EE6FF))
    segCols(s.cols(16), dl + dw * .04f, dt + dh * .2f, dw * .92f, dh * .6f, 12, { _, _ -> teal }, teal.copy(alpha = .08f))
    txt("VOL ${s.db()}dB", dl + dw * .95f, dt + dh * .94f, dh * .11f, teal, HifiFonts.vfd, Paint.Align.RIGHT, glow = Color(0xFF4EE6FF))
    if (!p.tiny) {
        knob(Offset(p.x(.1f), p.y(.47f)), p.h * .08f, -40f, false); txt("INPUT", p.x(.1f), p.y(.62f), p.h * .028f, Color(0xFF9AA3A8), spacing = .2f)
        knob(Offset(p.x(.865f), p.y(.45f)), p.h * .13f, -40f + 120f * s.level, true); txt("MASTER VOLUME", p.x(.865f), p.y(.66f), p.h * .028f, Color(0xFF9AA3A8), spacing = .15f)
        val labels = listOf("BD/DVD", "CBL/SAT", "GAME", "STRM", "TV", "PHONO"); val bw = p.w * .9f / labels.size
        labels.forEachIndexed { i, l -> val x = p.x(.05f) + i * bw
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF2C3034), Color(0xFF181A1C)), p.y(.8f), p.y(.9f)), Offset(x + 3, p.y(.8f)), Size(bw - 6, p.h * .1f), CornerRadius(5f))
            txt(l, x + bw / 2, p.y(.865f), p.h * .028f, Color(0xFFAABBCC), spacing = .1f) }
    }
}

/** Ice-white display: big level digits and dotted left / right bars. */
internal fun DrawScope.hfOnkyoWhite(s: VisState) {
    val p = plate(); face(p, Color(0xFF1E2023), Color(0xFF09090A))
    val ice = Color(0xFFE6F3FF); val glow = Color(0xFF8FC8FF)
    val dl = p.x(.04f); val dt = p.y(.06f); val dw = p.w * .92f; val dh = p.h * .5f
    screen(dl, dt, dw, dh, Color(0xFF02050A), glow)
    txt(s.db(), dl + dw * .5f, dt + dh * .42f, dh * .36f, ice, HifiFonts.vfd, glow = glow)
    txt("dB", dl + dw * .76f, dt + dh * .42f, dh * .13f, ice, HifiFonts.vfd, Paint.Align.LEFT, glow = glow)
    txt("HI-RES · DIRECT · STEREO", dl + dw * .5f, dt + dh * .55f, dh * .08f, ice.copy(alpha = .8f), spacing = .25f)
    listOf("L" to s.left(), "R" to s.right()).forEachIndexed { i, (ch, v) ->
        val y = dt + dh * (.68f + i * .15f)
        txt(ch, dl + dw * .05f, y + dh * .04f, dh * .08f, ice, HifiFonts.vfd)
        val n = 40; val cw = dw * .85f / n
        for (k in 0 until n) drawCircle(if (k < (v * n).toInt()) ice else ice.copy(alpha = .1f), cw * .3f, Offset(dl + dw * .1f + k * cw, y + dh * .015f))
    }
    txt("ORIONMD", p.x(.05f), p.y(.7f), p.h * .07f, Color(0xFFF2F4F5), HifiFonts.heavy, Paint.Align.LEFT, spacing = .12f)
    txt("NETWORK A/V RECEIVER", p.x(.05f), p.y(.76f), p.h * .026f, Color(0xFF9AA3A8), align = Paint.Align.LEFT, spacing = .2f)
    if (!p.tiny) {
        knob(Offset(p.x(.83f), p.y(.76f)), p.h * .15f, -40f + 120f * s.level, true)
        knob(Offset(p.x(.6f), p.y(.8f)), p.h * .07f, -30f, false)
        txt("LISTENING MODE", p.x(.6f), p.y(.93f), p.h * .024f, Color(0xFF9AA3A8), spacing = .15f)
    }
}

// ---------------------------------------------------------------- Sony ES style

private fun DrawScope.badge(text: String, x: Float, y: Float, px: Float, bg: Brush, fg: Color) {
    val w = textWidth(text, px, HifiFonts.slab, .1f) + px * .7f
    drawRoundRect(bg, Offset(x, y - px * 1.05f), Size(w, px * 1.35f), CornerRadius(px * .15f))
    txt(text, x + w / 2, y - px * .05f, px, fg, HifiFonts.slab, spacing = .1f)
}
private val GOLD = Brush.verticalGradient(listOf(Color(0xFFF3D98C), Color(0xFFB8913C)))

/** Black integrated amp with amber left / right peak meters and a gold badge. */
internal fun DrawScope.hfSonyAmber(s: VisState) {
    val p = plate(); face(p, Color(0xFF1C1D1F), Color(0xFF0A0A0B))
    val px = p.h * .1f
    txt("OrionMD", p.x(.05f), p.y(.17f), px, Color(0xFFF1F1F1), HifiFonts.slab, Paint.Align.LEFT)
    badge("PRO", p.x(.05f) + textWidth("OrionMD", px, HifiFonts.slab) + p.w * .02f, p.y(.165f), px * .4f, GOLD, Color(0xFF1A1408))
    txt("INTEGRATED STEREO AMPLIFIER", p.x(.95f), p.y(.12f), p.h * .025f, Color(0xFFB9A06A), align = Paint.Align.RIGHT, spacing = .2f)
    txt("REFERENCE SERIES", p.x(.95f), p.y(.16f), p.h * .025f, Color(0xFFB9A06A), align = Paint.Align.RIGHT, spacing = .2f)
    val amber = Color(0xFFFFB43A)
    val dl = p.x(.05f); val dt = p.y(.24f); val dw = p.w * .9f; val dh = p.h * .36f
    screen(dl, dt, dw, dh, Color(0xFF0B0602), Color(0xFFFFA032), Color(0xFF2A1A08))
    txt("PEAK LEVEL METER", dl + dw * .04f, dt + dh * .17f, dh * .08f, amber, align = Paint.Align.LEFT, spacing = .2f, glow = Color(0xFFFF9F2A))
    txt("SOURCE DIRECT", dl + dw * .96f, dt + dh * .17f, dh * .08f, amber, align = Paint.Align.RIGHT, spacing = .2f, glow = Color(0xFFFF9F2A))
    listOf(Triple("L", s.left(), s.peakOf(0, 18)), Triple("R", s.right(), s.peakOf(10, Visual.BANDS))).forEachIndexed { i, (ch, v, pk) ->
        val y = dt + dh * (.3f + i * .26f); val n = 30; val sw = dw * .86f / n
        txt(ch, dl + dw * .05f, y + dh * .13f, dh * .12f, amber)
        val lit = (v * n).toInt(); val pkx = (pk * n).toInt().coerceIn(0, n - 1)
        for (k in 0 until n) {
            val c = if (k < lit || k == pkx) (if (k >= 24) Color(0xFFFF5530) else amber) else amber.copy(alpha = .09f)
            drawRect(c, Offset(dl + dw * .1f + k * sw, y), Size(sw * .78f, dh * .16f))
        }
    }
    listOf("-∞", "-40", "-30", "-20", "-10", "-6", "-3", "0", "+3").forEachIndexed { i, t -> txt(t, dl + dw * (.1f + .86f * i / 8), dt + dh * .93f, dh * .07f, amber.copy(alpha = .85f)) }
    knobRow(p, p.y(.78f), p.h * .06f, listOf("INPUT", "BASS", "TREBLE", "BALANCE"), listOf(-50f, -15f, 20f, 0f), false, Color(0xFFB9A06A), x0 = .09f, x1 = .66f)
    if (!p.tiny) knob(Offset(p.x(.88f), p.y(.76f)), p.h * .11f, -40f + 120f * s.level, false, Color(0xFFB8913C))
}

/** Champagne-gold face with a green fluorescent spectrum. */
internal fun DrawScope.hfSonyChampagne(s: VisState) {
    val p = plate(); face(p, Color(0xFFDCCDA6), Color(0xFFA9976A), lines = .06f)
    val px = p.h * .1f; val ink = Color(0xFF2A2418)
    txt("OrionMD", p.x(.05f), p.y(.17f), px, ink, HifiFonts.slab, Paint.Align.LEFT)
    badge("PRO", p.x(.05f) + textWidth("OrionMD", px, HifiFonts.slab) + p.w * .02f, p.y(.165f), px * .4f, Brush.verticalGradient(listOf(Color(0xFF2A2A2A), Color.Black)), Color(0xFFE8C877))
    txt("STEREO RECEIVER · REFERENCE", p.x(.95f), p.y(.14f), p.h * .026f, Color(0xFF4A4128), align = Paint.Align.RIGHT, spacing = .2f)
    val green = Color(0xFF6CFFB0)
    val dl = p.x(.05f); val dt = p.y(.24f); val dw = p.w * .9f; val dh = p.h * .42f
    screen(dl, dt, dw, dh, Color(0xFF030A06), Color(0xFF3CFF96), Color(0xFF3A3220))
    segCols(s.cols(20), dl + dw * .03f, dt + dh * .1f, dw * .94f, dh * .7f, 14, { k, n -> if (k >= n - 2) Color(0xFFFF7A3A) else green }, green.copy(alpha = .07f), peaks = FloatArray(20) { s.peakOf((Visual.BANDS * (it / 20f).pow(1.3f)).toInt(), (Visual.BANDS * ((it + 1) / 20f).pow(1.3f)).toInt().coerceAtLeast((Visual.BANDS * (it / 20f).pow(1.3f)).toInt() + 1)) }, peakColor = green)
    listOf("50", "100", "250", "500", "1k", "2.5k", "5k", "10k", "20k").forEachIndexed { i, t -> txt(t, dl + dw * (.05f + .9f * i / 8), dt + dh * .93f, dh * .06f, green, glow = Color(0xFF3CFF96)) }
    knobRow(p, p.y(.82f), p.h * .06f, listOf("SELECTOR", "BASS", "TREBLE", "VOLUME"), listOf(-50f, -15f, 20f, -40f + 120f * s.level), true, Color(0xFF3B331E))
}

// ---------------------------------------------------------------- Kenwood style

/** Silver equalizer / spectrum analyzer with sliders. */
internal fun DrawScope.hfKenwoodSpectrum(s: VisState) {
    val p = plate(); face(p, Color(0xFFE9ECEE), Color(0xFF9DA2A6), lines = .05f, lineColor = Color.Black)
    txt("ORIONMD", p.x(.05f), p.y(.15f), p.h * .1f, Color(0xFF141618), HifiFonts.cond, Paint.Align.LEFT, skew = -.2f, spacing = .03f)
    txt("STEREO GRAPHIC EQUALIZER", p.x(.95f), p.y(.11f), p.h * .025f, Color(0xFF2D3134), align = Paint.Align.RIGHT, spacing = .2f)
    txt("SPECTRUM ANALYZER", p.x(.95f), p.y(.15f), p.h * .025f, Color(0xFF2D3134), align = Paint.Align.RIGHT, spacing = .2f)
    val dl = p.x(.05f); val dt = p.y(.2f); val dw = p.w * .9f; val dh = p.h * .45f
    screen(dl, dt, dw, dh, Color(0xFF03070A), Color(0xFF39D6FF), Color(0xFF2A2E31))
    val c = s.cols(16)
    val pk = FloatArray(16) { i -> s.peakOf((Visual.BANDS * (i / 16f).pow(1.3f)).toInt(), ((Visual.BANDS * ((i + 1) / 16f).pow(1.3f)).toInt()).coerceAtLeast((Visual.BANDS * (i / 16f).pow(1.3f)).toInt() + 1)) }
    segCols(c, dl + dw * .03f, dt + dh * .08f, dw * .94f, dh * .78f, 15, { k, _ -> when { k >= 12 -> Color(0xFFFF5A5A); k >= 6 -> Color(0xFF5DFFB0); else -> Color(0xFF38C7FF) } }, Color(0xFF50DCFF).copy(alpha = .07f), peaks = pk, peakColor = Color.White)
    listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k").forEachIndexed { i, t -> txt(t, dl + dw * (.05f + .9f * i / 9), dt + dh * .95f, dh * .055f, Color(0xFF7FE2FF)) }
    if (!p.tiny) {
        val labels = listOf("63", "160", "400", "1k", "2.5k", "6.3k", "16k")
        labels.forEachIndexed { i, l ->
            val x = p.x(.07f + .095f * i); val top = p.y(.72f); val hh = p.h * .17f
            drawRoundRect(Color(0xFF2B2F32), Offset(x - 3, top), Size(6f, hh), CornerRadius(3f))
            val v = s.b(i * 6 + 2); val ky = top + hh * (.85f - .7f * v)
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFAFAFA), Color(0xFF9EA3A7)), ky - 7, ky + 7), Offset(x - p.w * .025f, ky - 7), Size(p.w * .05f, 14f), CornerRadius(3f))
            txt(l, x, top + hh + p.h * .05f, p.h * .025f, Color(0xFF2D3134))
        }
        knob(Offset(p.x(.86f), p.y(.8f)), p.h * .1f, -40f + 120f * s.level, true)
        txt("VOLUME", p.x(.86f), p.y(.96f), p.h * .025f, Color(0xFF2D3134), spacing = .2f)
    }
}

/** Black power amp with twin amber-lit analogue power meters. */
internal fun DrawScope.hfKenwoodMeters(s: VisState) {
    val p = plate(); face(p, Color(0xFF1A1B1D), Color(0xFF070708))
    txt("ORIONMD", p.x(.05f), p.y(.14f), p.h * .09f, Color(0xFFF0F0F0), HifiFonts.cond, Paint.Align.LEFT, skew = -.2f, spacing = .03f)
    txt("STEREO POWER AMPLIFIER", p.x(.95f), p.y(.12f), p.h * .026f, Color(0xFFA8ADB2), align = Paint.Align.RIGHT, spacing = .2f)
    listOf(s.left() to "LEFT", s.right() to "RIGHT").forEachIndexed { i, (v, lab) ->
        val l = p.x(.05f + i * .46f); val t = p.y(.2f); val w = p.w * .44f; val h = p.h * .4f
        drawRoundRect(Color.Black, Offset(l - 3, t - 3), Size(w + 6, h + 6), CornerRadius(5f))
        drawRect(Brush.radialGradient(listOf(Color(0xFFFFE6A8), Color(0xFFFFC566), Color(0xFFC07A1C)), Offset(l + w / 2, t + h * .9f), w * .8f), Offset(l, t), Size(w, h))
        clipRect(l, t, l + w, t + h) {
            val c = Offset(l + w / 2, t + h * 1.25f); val r = h * 1.02f
            fun pt(d: Float, rr: Float) = Offset(c.x + rr * sin(Math.toRadians(d.toDouble())).toFloat(), c.y - rr * cos(Math.toRadians(d.toDouble())).toFloat())
            var prev = pt(-31f, r); for (k in 1..30) { val q = pt(-31f + 62f * k / 30, r); drawLine(Color(0xFF2A1A08), prev, q, 2f); prev = q }
            for (k in 0..10) { val d = -31f + 62f * k / 10; drawLine(if (k >= 8) Color(0xFFC0281C) else Color(0xFF2A1A08), pt(d, r), pt(d, r + h * .08f), 2.5f) }
            listOf("0", ".1", ".5", "1", "5", "10", "50", "100", "200").forEachIndexed { k, tx -> val q = pt(-31f + 62f * k / 8, r + h * .15f); txt(tx, q.x, q.y + h * .03f, h * .07f, Color(0xFF2A1A08)) }
            txt("POWER  W · 8Ω", c.x, t + h * .68f, h * .075f, Color(0xFF2A1A08), spacing = .15f)
            val d = -31f + 62f * v
            drawLine(Color(0xFFD02A1A), c, pt(d, r + h * .06f), h * .018f)
        }
        txt(lab, l + w / 2, t + h + p.h * .05f, p.h * .026f, Color(0xFFA8ADB2), spacing = .25f)
    }
    knobRow(p, p.y(.83f), p.h * .06f, listOf("SPEAKERS", "METER RANGE", "INPUT", "LEVEL"), listOf(-40f, 0f, 30f, -40f + 120f * s.level), true, Color(0xFFA8ADB2))
}

// ---------------------------------------------------------------- Pioneer Elite style

private fun DrawScope.eliteHeader(p: Plate, word: Color) {
    val px = p.h * .08f
    txt("ORIONMD", p.x(.05f), p.y(.14f), px, word, HifiFonts.heavy, Paint.Align.LEFT, spacing = .08f)
    txt("Signature", p.x(.05f) + textWidth("ORIONMD", px, HifiFonts.heavy, .08f) + p.w * .03f, p.y(.155f), px * 1.25f, Color(0xFFD9B45A), HifiFonts.script, Paint.Align.LEFT, glow = Color(0x55D9B45A))
    drawCircle(Color(0xFF2A8CFF), p.h * .015f, Offset(p.x(.95f), p.y(.11f)))
    drawCircle(Color(0xFF2A8CFF).copy(alpha = .3f), p.h * .03f, Offset(p.x(.95f), p.y(.11f)))
}
private fun DrawScope.chromeVolume(c: Offset, r: Float, s: VisState) {
    knob(c, r, -40f + 120f * s.level, true)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF3A3A3A), Color(0xFF0B0B0B)), c, r * .62f), r * .62f, c)
    val a = Math.toRadians((-40 + 120 * s.level - 90).toDouble())
    drawLine(Color(0xFF2A8CFF), c + Offset((cos(a) * r * .74f).toFloat(), (sin(a) * r * .74f).toFloat()), c + Offset((cos(a) * r * .9f).toFloat(), (sin(a) * r * .9f).toFloat()), r * .06f, cap = StrokeCap.Round)
}

/** Piano-black receiver with an amber arc level display and a chrome volume knob. */
internal fun DrawScope.hfEliteAmber(s: VisState) {
    val p = plate(); face(p, Color(0xFF141414), Color(0xFF020202), lines = 0f); glare(p, .1f)
    eliteHeader(p, Color(0xFFE9E9E9))
    val amber = Color(0xFFFFB547)
    val dl = p.x(.05f); val dt = p.y(.23f); val dw = p.w * .62f; val dh = p.h * .58f
    screen(dl, dt, dw, dh, Color(0xFF070402), Color(0xFFFFAA3C), Color(0xFF2A1A0A))
    val c = Offset(dl + dw / 2, dt + dh * .62f); val r = dh * .42f
    val n = 40; val lit = (s.level * 1.1f * n).toInt()
    for (k in 0 until n) {
        val d = Math.toRadians((-150 + 120.0 * k / (n - 1)))
        drawLine(if (k < lit) (if (k > 33) Color(0xFFFF6A3A) else amber) else amber.copy(alpha = .12f),
            c + Offset((cos(d) * r * .82f).toFloat(), (sin(d) * r * .82f).toFloat()), c + Offset((cos(d) * r).toFloat(), (sin(d) * r).toFloat()), r * .045f)
    }
    txt(s.db(), c.x, c.y - r * .05f, r * .42f, amber, HifiFonts.vfd, glow = Color(0xFFFF9A2A))
    txt("dB · DIRECT STREAM", c.x, c.y + r * .22f, r * .12f, amber, spacing = .2f)
    txt("HI-RES 24/96", dl + dw * .05f, dt + dh * .93f, dh * .09f, amber, HifiFonts.vfd, Paint.Align.LEFT, glow = Color(0xFFFF9A2A))
    txt("DIRECT", dl + dw * .95f, dt + dh * .93f, dh * .09f, amber, HifiFonts.vfd, Paint.Align.RIGHT, glow = Color(0xFFFF9A2A))
    if (!p.tiny) { chromeVolume(Offset(p.x(.835f), p.y(.5f)), p.h * .17f, s); txt("MASTER VOLUME", p.x(.835f), p.y(.76f), p.h * .028f, Color(0xFF9A9A9A), spacing = .15f) }
    drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF2A8CFF), Color.Transparent), p.l, p.l + p.w), Offset(p.x(.05f), p.y(.9f)), Offset(p.x(.95f), p.y(.9f)), 3f)
}

/** Piano-black receiver with a blue fluorescent spectrum. */
internal fun DrawScope.hfEliteBlue(s: VisState) {
    val p = plate(); face(p, Color(0xFF141414), Color(0xFF020202), lines = 0f); glare(p, .1f)
    eliteHeader(p, Color(0xFFE9E9E9))
    val blue = Color(0xFF7CC8FF)
    val dl = p.x(.05f); val dt = p.y(.23f); val dw = p.w * .62f; val dh = p.h * .58f
    screen(dl, dt, dw, dh, Color(0xFF02060C), Color(0xFF3A9CFF), Color(0xFF0C1C30))
    txt("AUTO EQ · DIRECT", dl + dw * .05f, dt + dh * .13f, dh * .09f, blue, HifiFonts.vfd, Paint.Align.LEFT, glow = Color(0xFF3A9CFF))
    txt("${s.db()}dB", dl + dw * .95f, dt + dh * .13f, dh * .09f, blue, HifiFonts.vfd, Paint.Align.RIGHT, glow = Color(0xFF3A9CFF))
    segCols(s.cols(18), dl + dw * .04f, dt + dh * .2f, dw * .92f, dh * .66f, 13, { k, n -> if (k >= n - 2) Color.White else blue }, blue.copy(alpha = .08f))
    txt("HI-RES 24/96 · STEREO", dl + dw * .5f, dt + dh * .95f, dh * .08f, blue, HifiFonts.vfd, glow = Color(0xFF3A9CFF))
    if (!p.tiny) { chromeVolume(Offset(p.x(.835f), p.y(.5f)), p.h * .17f, s); txt("MASTER VOLUME", p.x(.835f), p.y(.76f), p.h * .028f, Color(0xFF9A9A9A), spacing = .15f) }
    drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF2A8CFF), Color.Transparent), p.l, p.l + p.w), Offset(p.x(.05f), p.y(.9f)), Offset(p.x(.95f), p.y(.9f)), 3f)
}
