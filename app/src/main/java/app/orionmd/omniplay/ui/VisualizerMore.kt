package app.orionmd.omniplay.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import app.orionmd.omniplay.Visual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/*
 * The extra visualizer styles (the originals are in Visualizer.kt). Every style draws with the current palette PAL, so one
 * shape can come in several colour schemes. VIS_REG lists them all in their groups; VIS_STYLES (tap-to-cycle order) comes from it.
 */

internal val PAL_FIRE = listOf(Color(0xFF8B0000), Color(0xFFFF3D00), Color(0xFFFF8A00), Color(0xFFFFC400), Color(0xFFFFF59D))
internal val PAL_ICE = listOf(Color(0xFF0D47A1), Color(0xFF1E88E5), Color(0xFF4FC3F7), Color(0xFF80DEEA), Color(0xFFE0F7FA))
internal val PAL_RAINBOW = listOf(Color(0xFFFF3B3B), Color(0xFFFF9F1C), Color(0xFFFFE14D), Color(0xFF4DFF88), Color(0xFF3BA7FF), Color(0xFFB45CFF))
internal val PAL_NEON = listOf(Color(0xFF00FFA3), Color(0xFF00E5FF), Color(0xFFFF00E5), Color(0xFFFFEA00), Color(0xFF7C4DFF))
internal val PAL_SUNSET = listOf(Color(0xFF4A2360), Color(0xFF7B2869), Color(0xFFC94277), Color(0xFFFF7E5F), Color(0xFFFEB47B))

internal class VisDef(val id: String, val name: String, val group: String, val pal: List<Color>?, val draw: DrawScope.(VisState) -> Unit)

internal val VIS_REG: List<VisDef> = run {
    val l = mutableListOf<VisDef>()
    fun g(group: String, vararg d: Triple<String, String, Pair<List<Color>?, DrawScope.(VisState) -> Unit>>) = d.forEach { l += VisDef(it.first, it.second, group, it.third.first, it.third.second) }
    fun t(id: String, name: String, pal: List<Color>?, f: DrawScope.(VisState) -> Unit) = Triple(id, name, pal to f)
    g("Bars",
        t("bars", "Bars", null) { bars(it) }, t("mirror", "Mirror", null) { mirror(it) }, t("blocks", "Blocks", null) { blocks(it) },
        t("thin", "Needles", PAL_NEON) { thin(it) }, t("dots", "Bouncing dots", null) { dots(it) }, t("center", "Center out", PAL_SUNSET) { centerOut(it) },
        t("topdown", "Top & bottom", PAL_ICE) { topDown(it) }, t("flames", "Flames", PAL_FIRE) { flames(it) }, t("led", "LED matrix", null) { led(it) },
        t("skyline", "Skyline", PAL_SUNSET) { skyline(it) }, t("tri", "Pyramids", null) { tri(it) },
        t("bars_ice", "Bars — ice", PAL_ICE) { bars(it) }, t("bars_rainbow", "Bars — rainbow", PAL_RAINBOW) { bars(it) })
    g("Waves",
        t("wave", "Wave", null) { wave(it) }, t("wavefill", "Filled wave", PAL_NEON) { waveFill(it) }, t("waves3", "Three waves", null) { waves3(it) },
        t("scope", "Echo scope", PAL_ICE) { echo(it) }, t("oscillo", "Lissajous", PAL_NEON) { lissajous(it) }, t("mountain", "Mountain", null) { mountain(it) },
        t("mountain_sunset", "Mountain — sunset", PAL_SUNSET) { mountain(it) }, t("aurora", "Aurora", PAL_NEON) { aurora(it) }, t("dna", "DNA", null) { dna(it) },
        t("wave_neon", "Wave — neon", PAL_NEON) { wave(it) })
    g("Circles",
        t("circle", "Circle", null) { circle(it) }, t("rings", "Rings", null) { rings(it) }, t("radial", "Blob", null) { blob(it) },
        t("sunburst", "Sunburst", PAL_SUNSET) { sunburst(it) }, t("flower", "Flower", PAL_RAINBOW) { flower(it) }, t("kaleido", "Kaleidoscope", PAL_RAINBOW) { kaleido(it) },
        t("polar", "Polar wave", PAL_ICE) { polar(it) }, t("pulse", "Pulse", null) { pulse(it) }, t("arc", "Gauge", PAL_FIRE) { gauge(it) },
        t("star", "Star", PAL_SUNSET) { starShape(it) }, t("spiral", "Spiral", null) { spiral(it) },
        t("circle_fire", "Circle — fire", PAL_FIRE) { circle(it) }, t("rings_ice", "Rings — ice", PAL_ICE) { rings(it) })
    g("Space & particles",
        t("stars", "Starfield", null) { stars(it) }, t("rain", "Digital rain", PAL_NEON) { rain(it) }, t("bubbles", "Bubbles", PAL_ICE) { bubbles(it) },
        t("sparkle", "Sparkles", PAL_RAINBOW) { sparkle(it) }, t("orbit", "Orbits", null) { orbit(it) }, t("tunnel", "Tunnel", PAL_NEON) { tunnel(it) },
        t("glow", "Glow", null) { glow(it) }, t("lava", "Lava lamp", PAL_FIRE) { lava(it) }, t("heart", "Heartbeat", PAL_SUNSET) { heart(it) })
    g("Retro & grids",
        t("spectro", "Spectrogram", PAL_FIRE) { spectro(it) }, t("ridges", "Ridgelines", null) { ridges(it) }, t("hex", "Honeycomb", PAL_RAINBOW) { hex(it) },
        t("vu", "VU meters", PAL_FIRE) { vu(it) }, t("grid", "Pixel grid", PAL_NEON) { pixelGrid(it) })
    g("Instrument cluster",
        t("cl_sport", "Sport cluster", null) { clusterSport(it) }, t("cl_classic", "Classic chrome", null) { clusterClassic(it) },
        t("cl_digital", "Digital dash", null) { clusterDigital(it) }, t("cl_cockpit", "Aviation panel", null) { clusterCockpit(it) },
        t("cl_ev", "EV rings", null) { clusterEv(it) }, t("cl_combo", "Full cockpit (VU + gauges + bars + wave)", null) { cockpitCombo(it) },
        t("cl_supercar", "Supercar (carbon)", null) { clusterSupercar(it) }, t("cl_muscle", "Muscle car", null) { clusterMuscle(it) },
        t("cl_chrono", "Luxury chronograph", null) { clusterChrono(it) }, t("cl_rally", "Rally white-face", null) { clusterRally(it) },
        t("cl_moto", "Motorbike", null) { clusterMoto(it) }, t("cl_vintage", "Vintage '50s ribbon", null) { clusterVintage(it) },
        t("cl_five", "Racing five", null) { clusterFive(it) }, t("cl_truck", "Truck (amber)", null) { clusterTruck(it) },
        t("cl_neon", "Neon night", null) { clusterNeon(it) }, t("cl_steam", "Steampunk brass", null) { clusterSteam(it) })
    g("Digital gauges",
        t("dg_amber", "Amber LED rings", null) { dgAmber(it) }, t("dg_lcd", "Green LCD", null) { dgLcd(it) }, t("dg_tach80", "'80s stepped tach", null) { dgTach80(it) },
        t("dg_hud", "Green HUD", null) { dgHud(it) }, t("dg_rings", "Concentric rings", null) { dgRings(it) }, t("dg_shift", "Shift lights", null) { dgShift(it) },
        t("dg_dots", "Dot matrix", null) { dgDots(it) }, t("dg_vfd", "VFD columns", null) { dgVfd(it) }, t("dg_scifi", "Sci-fi hex", null) { dgScifi(it) },
        t("dg_minimal", "Minimal EV", null) { dgMinimal(it) },
        t("dg_supertach", "Supercar tachs", null) { dgSuperTach(it) }, t("dg_delta", "Race deltas", null) { dgDelta(it) }, t("dg_gears", "Gear indicators", null) { dgGears(it) },
        t("dg_peak", "Peak bargraph", null) { dgPeak(it) }, t("dg_radial", "Radial sweep", null) { dgRadial(it) }, t("dg_oled", "OLED white", null) { dgOled(it) },
        t("dg_nixie", "Nixie tubes", null) { dgNixie(it) }, t("dg_dotamber", "Amber dot matrix", null) { dgDotAmber(it) }, t("dg_hex", "Hex meters", null) { dgHex(it) },
        t("dg_split", "Split rings", null) { dgSplit(it) })
    g("Digital dashboards",
        t("db_supercar", "Supercar", null) { dbSupercar(it) }, t("db_ev", "Electric", null) { dbEv(it) }, t("db_80s", "'80s digital dash", null) { db80s(it) },
        t("db_race", "Race wheel", null) { dbRace(it) }, t("db_jet", "Fighter-jet HUD", null) { dbJet(it) }, t("db_truck", "Big rig", null) { dbTruck(it) },
        t("db_cyber", "Cyberpunk", null) { dbCyber(it) }, t("db_luxury", "Luxury sedan", null) { dbLuxury(it) }, t("db_rally", "Rally", null) { dbRally(it) },
        t("db_space", "Starship console", null) { dbSpace(it) },
        t("db_hyper", "Hypercar", null) { dbHyper(it) }, t("db_f1", "F1 steering wheel", null) { dbF1(it) }, t("db_gt3", "GT3 race dash", null) { dbGt3(it) },
        t("db_hexcar", "Supercar hexagon", null) { dbHexcar(it) }, t("db_drift", "Drift", null) { dbDrift(it) }, t("db_lemans", "Le Mans endurance", null) { dbLeMans(it) },
        t("db_drag", "Drag strip", null) { dbDrag(it) }, t("db_synth", "Synthwave racer", null) { dbSynth(it) }, t("db_bike", "Superbike TFT", null) { dbBike(it) },
        t("db_raid", "Rally raid", null) { dbRaid(it) }, t("db_fe", "Formula E", null) { dbFormulaE(it) })
    // inspired by classic amps and receivers (no brand names or logos; OrionMD lettering in a matching style)
    g("Hi-fi faceplates",
        t("hf_mac_twin", "Blue watt meters (McIntosh style)", null) { hfMacTwin(it) }, t("hf_mac_mono", "Blue monoblock (McIntosh style)", null) { hfMacMono(it) },
        t("hf_mz_dial", "Champagne dial (Marantz style)", null) { hfMarantzDial(it) }, t("hf_mz_port", "Black porthole (Marantz style)", null) { hfMarantzPorthole(it) },
        t("hf_ok_teal", "Teal VFD (Onkyo style)", null) { hfOnkyoTeal(it) }, t("hf_ok_white", "Ice VFD (Onkyo style)", null) { hfOnkyoWhite(it) },
        t("hf_es_amber", "Amber peak meters (Sony ES style)", null) { hfSonyAmber(it) }, t("hf_es_champ", "Champagne spectrum (Sony ES style)", null) { hfSonyChampagne(it) },
        t("hf_kw_spec", "Silver spectrum (Kenwood style)", null) { hfKenwoodSpectrum(it) }, t("hf_kw_meters", "Amber power meters (Kenwood style)", null) { hfKenwoodMeters(it) },
        t("hf_pe_amber", "Piano black amber (Pioneer Elite style)", null) { hfEliteAmber(it) }, t("hf_pe_blue", "Piano black blue (Pioneer Elite style)", null) { hfEliteBlue(it) })
    // supercar clusters (no badges; the brand only names the style)
    g("Supercar clusters",
        t("car_ferrari", "Yellow rev counter (Ferrari style)", null) { carFerrari(it) }, t("car_laferrari", "Hypercar carbon (LaFerrari style)", null) { carLaFerrari(it) },
        t("car_lfa", "Moving ring (Lexus LFA style)", null) { carLfa(it) }, t("car_lfa_slide", "Ring slid aside (Lexus LFA style)", null) { carLfaSlide(it) },
        t("car_gt3", "Five dials (Porsche 911 GT3 style)", null) { carGt3(it) }, t("car_gt3_track", "Track screen (Porsche 911 GT3 style)", null) { carGt3Track(it) },
        t("car_gtr", "Red-ring cluster (Nissan GT-R style)", null) { carGtr(it) }, t("car_gtr_mfd", "Multi-function display (Nissan GT-R style)", null) { carGtrMfd(it) },
        t("car_g_classic", "Chrome twin dials (Mercedes G-Class style)", null) { carGClassic(it) }, t("car_g_wide", "Widescreen cockpit (Mercedes G-Class style)", null) { carGWide(it) },
        t("car_audi", "Virtual cockpit (Audi style)", null) { carAudi(it) }, t("car_audi_sport", "Sport view (Audi style)", null) { carAudiSport(it) },
        t("car_nsx_classic", "'90s analogue (Acura NSX style)", null) { carNsxClassic(it) }, t("car_nsx", "Drive-mode ring (Acura NSX style)", null) { carNsx(it) },
        t("car_typer", "Digital rev arc (Honda Civic Type R style)", null) { carTypeR(it) }, t("car_typer_plus", "+R mode (Honda Civic Type R style)", null) { carTypeRPlus(it) },
        t("car_m3", "Digital cockpit (BMW M3 Competition style)", null) { carM3(it) }, t("car_m3_track", "M Track mode (BMW M3 Competition style)", null) { carM3Track(it) },
        t("car_e36", "Classic analogue (BMW E36 M3 style)", null) { carE36(it) }, t("car_e36_night", "Night lighting (BMW E36 M3 style)", null) { carE36Night(it) },
        t("car_e36_us", "US mph (BMW E36 M3 style)", null) { carE36Us(it) })
    // luxury chronographs (no logos; the brand only names the style). The hands keep real time, the music runs the chronograph
    fun <T> w(id: String, model: String, brand: String, list: List<Pair<String, T>>, f: DrawScope.(VisState, T) -> Unit) =
        list.forEachIndexed { i, (name, d) -> l += VisDef("w_${id}_$i", (if (model.isEmpty()) name else "$model — $name") + " ($brand style)", "Watches", null) { f(it, d) } }
    w("daytona", "Daytona", "Rolex", DAYTONAS) { s, d -> wDaytona(s, d) }
    w("sub", "Submariner chrono", "Rolex", SUBS) { s, d -> wSub(s, d) }
    w("monaco", "Monaco", "TAG Heuer", MONACOS) { s, d -> wMonaco(s, d) }
    w("carrera", "Carrera", "TAG Heuer", CARRERAS) { s, d -> wCarrera(s, d) }
    w("planet", "Seamaster Planet Ocean", "Omega", PLANETS) { s, d -> wPlanet(s, d) }
    w("rm", "RM chronograph", "Richard Mille", RICHARDS) { s, d -> wRichard(s, d) }
    w("patek", "", "Patek Philippe", PATEKS) { s, d -> wPatek(s, d) }
    w("naut", "", "Patek Philippe", NAUTILUSES) { s, d -> wNautilus(s, d) }
    w("ro", "Royal Oak chrono", "Audemars Piguet", ROYALS) { s, d -> wRoyalOak(s, d) }
    w("port", "Portugieser chrono", "IWC", PORTUGIESERS) { s, d -> wPortugieser(s, d) }
    w("pilot", "Pilot's chrono", "IWC", PILOTS) { s, d -> wPilot(s, d) }
    l
}

/** Colour from the palette, 0 = first, 1 = last, blended in between. */
internal fun pc(t: Float): Color {
    val p = PAL; val x = t.coerceIn(0f, 1f) * (p.size - 1); val i = x.toInt().coerceAtMost(p.size - 2)
    return lerp(p[i], p[i + 1], x - i)
}
private fun VisState.v(k: Int) = disp[k.coerceIn(0, Visual.BANDS - 1)].coerceIn(0f, 1f)
private fun VisState.avg(r: IntRange) = r.sumOf { v(it).toDouble() }.toFloat() / r.count()
private fun VisState.bass() = avg(0 until 6)
private fun VisState.mids() = avg(6 until 24)
private fun VisState.highs() = avg(24 until Visual.BANDS)
private val TAU = 2 * PI.toFloat()

// ---------------------------------------------------------------- bars

private fun DrawScope.blocks(s: VisState) {
    val cols = 24; val per = Visual.BANDS / cols; val segs = 16
    val cw = size.width / cols; val sh = size.height / segs
    for (c in 0 until cols) {
        val v = (0 until per).maxOf { s.v(c * per + it) }
        val lit = (v * segs).toInt()
        for (r in 0 until segs) drawRect(pc(r.toFloat() / segs).copy(alpha = if (r < lit) 1f else .07f), Offset(c * cw + cw * .1f, size.height - (r + 1) * sh + sh * .14f), Size(cw * .8f, sh * .72f))
    }
}

private fun DrawScope.thin(s: VisState) {
    val n = Visual.BANDS; val step = size.width / n; val h = size.height * .9f
    for (k in 0 until n) {
        val x = k * step + step / 2; val y = size.height - s.v(k) * h
        drawLine(pc(k.toFloat() / n).copy(alpha = .8f), Offset(x, size.height), Offset(x, y), strokeWidth = 2.5f)
        drawCircle(pc(k.toFloat() / n), 3f + s.v(k) * 5f, Offset(x, y))
        drawCircle(Color.White.copy(alpha = .7f), 2f, Offset(x, size.height - s.peak[k].coerceIn(0f, 1f) * h))
    }
}

private fun DrawScope.dots(s: VisState) {
    val n = Visual.BANDS; val step = size.width / n; val h = size.height * .85f
    for (k in 0 until n) {
        val x = k * step + step / 2; val y = size.height - 8f - s.v(k) * h
        drawLine(pc(k.toFloat() / n).copy(alpha = .18f), Offset(x, size.height), Offset(x, y), strokeWidth = step * .5f, cap = StrokeCap.Round)
        drawCircle(pc(k.toFloat() / n), step * .42f + s.v(k) * step * .3f, Offset(x, y))
    }
}

private fun DrawScope.centerOut(s: VisState) {
    val n = Visual.BANDS; val half = size.width / 2; val step = half / n; val w = step * .7f; val h = size.height * .92f
    for (k in 0 until n) {
        val bh = maxOf(3f, s.v(k) * h); val c = pc(k.toFloat() / n)
        for (side in listOf(-1, 1)) drawRoundRect(c, Offset(half + side * (k * step + step / 2) - w / 2, size.height - bh), Size(w, bh), CornerRadius(w / 2))
    }
}

private fun DrawScope.topDown(s: VisState) {
    val n = Visual.BANDS; val step = size.width / n; val w = step * .7f; val h = size.height * .46f
    for (k in 0 until n) {
        val up = maxOf(2f, s.v(k) * h); val down = maxOf(2f, s.v(n - 1 - k) * h)
        drawRoundRect(pc(k.toFloat() / n), Offset(k * step, size.height - up), Size(w, up), CornerRadius(w / 3))
        drawRoundRect(pc(1f - k.toFloat() / n).copy(alpha = .75f), Offset(k * step, 0f), Size(w, down), CornerRadius(w / 3))
    }
}

private fun DrawScope.flames(s: VisState) {
    val n = 32; val per = Visual.BANDS / n; val step = size.width / n; val h = size.height * .95f
    val g = Brush.verticalGradient(PAL.reversed(), 0f, size.height)
    for (c in 0 until n) {
        val v = (0 until per).maxOf { s.v(c * per + it) }
        val flick = sin(s.spin * 9f + c * 1.7f) * .06f * (v + .1f)
        val bh = maxOf(4f, (v + flick).coerceIn(0f, 1f) * h)
        val x = c * step
        val p = Path().apply {
            moveTo(x, size.height); lineTo(x, size.height - bh * .7f)
            quadraticBezierTo(x + step * .5f, size.height - bh * 1.05f, x + step, size.height - bh * .7f)
            lineTo(x + step, size.height); close()
        }
        drawPath(p, g, alpha = .9f)
    }
}

private fun DrawScope.skyline(s: VisState) {
    drawRect(Brush.verticalGradient(listOf(PAL[0], PAL[PAL.size - 1].copy(alpha = .5f)), 0f, size.height), Offset.Zero, size, alpha = .5f)
    drawCircle(PAL.last().copy(alpha = .6f + s.bass() * .4f), min(size.width, size.height) * (.12f + s.bass() * .05f), Offset(size.width * .72f, size.height * .35f))
    val cols = 16; val cw = size.width / cols; val per = Visual.BANDS / cols
    for (c in 0 until cols) {
        val v = (0 until per).maxOf { s.v(c * per + it) }
        val bw = cw * (.7f + ((c * 37) % 5) * .06f)
        val bh = size.height * (.12f + ((c * 53) % 7) * .03f + v * .6f)
        val x = c * cw + (cw - bw) / 2; val top = size.height - bh
        drawRect(Color(0xFF0B0A14), Offset(x, top), Size(bw, bh))
        val rows = (bh / 14f).toInt(); val wins = 3
        for (r in 0 until rows) for (w in 0 until wins) {
            val on = ((c * 7 + r * 3 + w) % 5) < (1 + v * 5).toInt()
            if (on) drawRect(pc(r.toFloat() / maxOf(1, rows)).copy(alpha = .85f), Offset(x + bw * (.15f + w * .27f), top + 6f + r * 14f), Size(bw * .16f, 6f))
        }
    }
}

private fun DrawScope.tri(s: VisState) {
    val n = 24; val per = Visual.BANDS / n; val step = size.width / n; val h = size.height * .92f
    for (c in 0 until n) {
        val v = (0 until per).maxOf { s.v(c * per + it) }
        val bh = maxOf(4f, v * h); val x = c * step
        val p = Path().apply { moveTo(x + step * .05f, size.height); lineTo(x + step / 2, size.height - bh); lineTo(x + step * .95f, size.height); close() }
        drawPath(p, Brush.verticalGradient(listOf(pc(c.toFloat() / n), pc(c.toFloat() / n).copy(alpha = .2f)), size.height - bh, size.height))
    }
}

// ---------------------------------------------------------------- waves

private fun DrawScope.waveFill(s: VisState) {
    val n = Visual.WAVE; val mid = size.height / 2; val amp = size.height * .7f
    val p = Path()
    for (k in 0 until n) { val x = size.width * k / (n - 1); val y = mid - abs(s.wdisp[k].coerceIn(-1f, 1f)) * amp; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
    for (k in n - 1 downTo 0) { val x = size.width * k / (n - 1); p.lineTo(x, mid + abs(s.wdisp[k].coerceIn(-1f, 1f)) * amp) }
    p.close()
    drawPath(p, Brush.horizontalGradient(PAL, 0f, size.width), alpha = .85f)
    drawLine(Color.White.copy(alpha = .4f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.5f)
}

private fun DrawScope.waves3(s: VisState) {
    val lv = listOf(s.bass(), s.mids(), s.highs()); val freqs = listOf(1.5f, 3f, 6f)
    for (i in 0 until 3) {
        val p = Path(); val amp = size.height * (.05f + lv[i] * .4f); val mid = size.height / 2
        val steps = 80
        for (k in 0..steps) {
            val x = size.width * k / steps
            val y = mid + sin(k.toFloat() / steps * TAU * freqs[i] + s.spin * (1.5f + i)) * amp
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        val c = PAL[(i * 2).coerceAtMost(PAL.size - 1)]
        drawPath(p, c.copy(alpha = .3f), style = Stroke(width = 14f, cap = StrokeCap.Round))
        drawPath(p, c, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.echo(s: VisState) {
    val n = Visual.WAVE; val mid = size.height / 2
    for (e in 3 downTo 0) {
        val amp = size.height * .5f * (1f - e * .2f); val p = Path()
        for (k in 0 until n) { val x = size.width * k / (n - 1); val y = mid - s.wdisp[k].coerceIn(-1f, 1f) * amp * 1.5f; if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        drawPath(p, pc(e / 3f).copy(alpha = 1f - e * .22f), style = Stroke(width = 5f - e, cap = StrokeCap.Round))
    }
}

private fun DrawScope.lissajous(s: VisState) {
    val n = Visual.WAVE; val c = Offset(size.width / 2, size.height / 2); val r = min(size.width, size.height) * .9f
    val p = Path()
    for (k in 0 until n) {
        val x = c.x + s.wdisp[k].coerceIn(-1f, 1f) * r; val y = c.y + s.wdisp[(k + n / 4) % n].coerceIn(-1f, 1f) * r
        if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    drawPath(p, PAL[1].copy(alpha = .3f), style = Stroke(width = 12f, cap = StrokeCap.Round))
    drawPath(p, PAL[0], style = Stroke(width = 3f, cap = StrokeCap.Round))
    drawCircle(PAL[2].copy(alpha = .15f + s.level * .5f), min(size.width, size.height) * .45f, c, style = Stroke(width = 2f))
}

private fun DrawScope.aurora(s: VisState) {
    for (i in 0 until 4) {
        val lv = listOf(s.bass(), s.mids(), s.highs(), s.level.coerceIn(0f, 1f))[i]
        val base = size.height * (.25f + i * .15f); val amp = size.height * (.05f + lv * .2f)
        val p = Path(); val steps = 60
        for (k in 0..steps) {
            val x = size.width * k / steps
            val y = base + sin(k.toFloat() / steps * TAU * (1.2f + i * .4f) + s.spin * (0.6f + i * .3f)) * amp
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.lineTo(size.width, size.height); p.lineTo(0f, size.height); p.close()
        val c = PAL[i % PAL.size]
        drawPath(p, Brush.verticalGradient(listOf(c.copy(alpha = .55f), Color.Transparent), base - amp, base + size.height * .35f))
    }
}

private fun DrawScope.dna(s: VisState) {
    val steps = 36; val mid = size.height / 2; val amp = size.height * .32f
    for (k in 0 until steps) {
        val x = size.width * (k + .5f) / steps; val a = k.toFloat() / steps * TAU * 2 + s.spin * 2
        val v = s.v(k * Visual.BANDS / steps)
        val y1 = mid + sin(a) * amp * (.7f + v * .5f); val y2 = mid - sin(a) * amp * (.7f + v * .5f)
        val depth = (cos(a) + 1) / 2
        drawLine(pc(k.toFloat() / steps).copy(alpha = .25f + v * .5f), Offset(x, y1), Offset(x, y2), strokeWidth = 2f + v * 4f)
        drawCircle(PAL[0].copy(alpha = .5f + depth * .5f), 4f + depth * 4f + v * 6f, Offset(x, y1))
        drawCircle(PAL[PAL.size - 1].copy(alpha = 1f - depth * .5f), 4f + (1 - depth) * 4f + v * 6f, Offset(x, y2))
    }
}

// ---------------------------------------------------------------- circles

private fun DrawScope.blob(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val r0 = min(size.width, size.height) * .22f; val maxLen = min(size.width, size.height) * .24f
    val n = Visual.BANDS * 2; val p = Path()
    for (k in 0..n) {
        val b = if (k % n < Visual.BANDS) k % n else n - 1 - k % n
        val a = k.toFloat() / n * TAU + s.spin * .3f
        val r = r0 + s.v(b) * maxLen
        val pt = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    drawPath(p, Brush.radialGradient(PAL, c, r0 + maxLen))
    drawPath(p, Color.White.copy(alpha = .6f), style = Stroke(width = 2f))
}

private fun DrawScope.sunburst(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = min(size.width, size.height) * .48f
    val n = Visual.BANDS; val sweep = 360f / (n * 2)
    for (k in 0 until n * 2) {
        val b = if (k < n) k else n * 2 - 1 - k
        val r = maxR * (.2f + s.v(b) * .8f)
        drawArc(pc(b.toFloat() / n).copy(alpha = .85f), k * sweep - 90f + s.spin * 10f, sweep * .85f, true, Offset(c.x - r, c.y - r), Size(r * 2, r * 2))
    }
    drawCircle(Color(0xFF0B0A14), maxR * .16f, c)
}

private fun DrawScope.flower(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = min(size.width, size.height) * .47f
    val petals = 10; val per = Visual.BANDS / petals
    for (i in 0 until petals) {
        val v = (0 until per).maxOf { s.v(i * per + it) }
        val len = maxR * (.3f + v * .7f); val wid = len * .32f
        rotate(i * 360f / petals + s.spin * 12f, c) {
            val p = Path().apply {
                moveTo(c.x, c.y)
                quadraticBezierTo(c.x + wid, c.y - len * .5f, c.x, c.y - len)
                quadraticBezierTo(c.x - wid, c.y - len * .5f, c.x, c.y)
                close()
            }
            drawPath(p, pc(i.toFloat() / petals).copy(alpha = .75f))
        }
    }
    drawCircle(PAL.last(), maxR * (.08f + s.bass() * .06f), c)
}

private fun DrawScope.kaleido(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val maxR = min(size.width, size.height) * .48f
    for (j in 0 until 12) {
        rotate(j * 30f + s.spin * 8f, c) {
            for (k in 0 until 16) {
                val v = s.v(k * 3); val r = maxR * (k + 1) / 16f
                val x = c.x + r; val y = c.y + (if (j % 2 == 0) 1 else -1) * v * maxR * .18f
                drawCircle(pc(k / 16f).copy(alpha = .3f + v * .7f), 2f + v * 10f, Offset(x, y))
            }
        }
    }
}

private fun DrawScope.polar(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val r0 = min(size.width, size.height) * .3f; val amp = min(size.width, size.height) * .3f
    val n = Visual.WAVE; val p = Path()
    for (k in 0..n) {
        val a = k.toFloat() / n * TAU - PI.toFloat() / 2
        val r = r0 + s.wdisp[k % n].coerceIn(-1f, 1f) * amp
        val pt = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    drawPath(p, PAL[1].copy(alpha = .3f), style = Stroke(width = 14f))
    drawPath(p, PAL[3], style = Stroke(width = 3f))
    drawCircle(PAL[0].copy(alpha = .2f + s.level * .5f), r0 * .6f, c)
}

private fun DrawScope.pulse(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val m = min(size.width, size.height)
    for (i in 0 until 4) {
        val f = ((s.spin * .5f + i / 4f) % 1f)
        drawCircle(pc(i / 3f).copy(alpha = (1f - f) * (.3f + s.level * .6f)), m * (.15f + f * .35f), c, style = Stroke(width = 3f + s.bass() * 8f))
    }
    val r = m * (.12f + s.bass() * .14f)
    drawCircle(Brush.radialGradient(listOf(PAL.last(), PAL[0]), c, r), r, c)
}

private fun DrawScope.gauge(s: VisState) {
    val c = Offset(size.width / 2, size.height * .92f); val r0 = min(size.width / 2, size.height) * .35f; val maxLen = min(size.width / 2, size.height) * .5f
    val n = Visual.BANDS
    for (k in 0 until n) {
        val a = PI.toFloat() + k.toFloat() / (n - 1) * PI.toFloat()
        val len = 4f + s.v(k) * maxLen
        drawLine(pc(k.toFloat() / n), Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * (r0 + len), c.y + sin(a) * (r0 + len)), strokeWidth = r0 * PI.toFloat() / n * .7f, cap = StrokeCap.Round)
    }
    val na = PI.toFloat() + s.level.coerceIn(0f, 1f) * PI.toFloat()
    drawLine(Color.White, c, Offset(c.x + cos(na) * r0 * .9f, c.y + sin(na) * r0 * .9f), strokeWidth = 4f, cap = StrokeCap.Round)
    drawCircle(Color.White, 8f, c)
}

private fun DrawScope.starShape(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val m = min(size.width, size.height) * .47f
    val points = 12; val per = Visual.BANDS / points
    for (layer in 0 until 3) {
        val p = Path()
        for (i in 0 until points * 2) {
            val a = i.toFloat() / (points * 2) * TAU + s.spin * (if (layer % 2 == 0) .4f else -.4f)
            val v = if (i % 2 == 0) (0 until per).maxOf { s.v((i / 2) * per + it) } else 0f
            val r = m * (1f - layer * .28f) * (if (i % 2 == 0) .45f + v * .55f else .3f)
            val pt = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
        }
        p.close()
        drawPath(p, pc(layer / 2f).copy(alpha = .75f))
    }
}

// ---------------------------------------------------------------- space & particles

private fun DrawScope.rain(s: VisState) {
    val cols = 28; val cw = size.width / cols
    for (k in s.sz.indices) {
        val col = ((s.sx[k] + 1) / 2 * cols).toInt().coerceIn(0, cols - 1)
        val v = s.v(col * Visual.BANDS / cols)
        val y = (1f - s.sz[k]) * size.height * 1.2f - size.height * .1f
        val len = 20f + v * 90f
        drawLine(Brush.verticalGradient(listOf(Color.Transparent, pc(v)), y - len, y), Offset(col * cw + cw / 2, y - len), Offset(col * cw + cw / 2, y), strokeWidth = cw * .35f)
        drawCircle(Color.White.copy(alpha = .5f + v * .5f), cw * .2f, Offset(col * cw + cw / 2, y))
    }
}

private fun DrawScope.bubbles(s: VisState) {
    for (k in s.sz.indices) {
        val v = s.v(k % Visual.BANDS)
        val x = (s.sx[k] + 1) / 2 * size.width + sin(s.spin * 2 + k) * 10f
        val y = s.sz[k] * size.height * 1.1f
        val r = 4f + v * 18f
        drawCircle(pc(k % Visual.BANDS / Visual.BANDS.toFloat()).copy(alpha = .15f + v * .3f), r, Offset(x, y))
        drawCircle(Color.White.copy(alpha = .25f + v * .4f), r, Offset(x, y), style = Stroke(width = 1.5f))
    }
}

private fun DrawScope.sparkle(s: VisState) {
    for (k in s.sz.indices) {
        val b = k % Visual.BANDS; val v = s.v(b)
        val tw = (.5f + .5f * sin(s.spin * 6f + k * 2.3f)) * v
        if (tw < .05f) continue
        val p = Offset((s.sx[k] + 1) / 2 * size.width, (s.sy[k] + 1) / 2 * size.height)
        val r = 3f + tw * 16f; val c = pc(b.toFloat() / Visual.BANDS).copy(alpha = .4f + tw * .6f)
        drawLine(c, Offset(p.x - r, p.y), Offset(p.x + r, p.y), strokeWidth = 2f)
        drawLine(c, Offset(p.x, p.y - r), Offset(p.x, p.y + r), strokeWidth = 2f)
        drawCircle(Color.White.copy(alpha = tw), 2f + tw * 3f, p)
    }
}

private fun DrawScope.orbit(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val m = min(size.width, size.height) * .47f
    val planets = 6; val per = Visual.BANDS / planets
    drawCircle(Brush.radialGradient(listOf(PAL.last(), PAL[0].copy(alpha = 0f)), c, m * .3f), m * (.12f + s.bass() * .12f), c)
    for (i in 0 until planets) {
        val v = s.avg(i * per until (i + 1) * per); val r = m * (.25f + i * .13f)
        drawCircle(Color.White.copy(alpha = .1f), r, c, style = Stroke(width = 1f))
        val a = s.spin * (1.2f - i * .15f) + i * 1.3f
        drawCircle(pc(i / (planets - 1f)), 5f + v * 18f, Offset(c.x + cos(a) * r, c.y + sin(a) * r))
    }
}

private fun DrawScope.tunnel(s: VisState) {
    val c = Offset(size.width / 2, size.height / 2); val m = maxOf(size.width, size.height)
    val n = 14
    for (i in 0 until n) {
        val f = ((i + (s.spin * 1.2f) % 1f) / n); val sc = f * f
        val side = m * sc * 1.2f
        val v = s.v((i * Visual.BANDS / n).coerceAtMost(Visual.BANDS - 1))
        rotate(s.spin * 20f + i * 6f, c) {
            drawRect(pc(i.toFloat() / n).copy(alpha = (.2f + v * .8f) * f.coerceAtLeast(.15f)), Offset(c.x - side / 2, c.y - side / 2), Size(side, side), style = Stroke(width = 2f + v * 8f))
        }
    }
}

private fun DrawScope.lava(s: VisState) {
    val m = min(size.width, size.height)
    for (i in 0 until 6) {
        val v = listOf(s.bass(), s.mids(), s.highs())[i % 3]
        val x = size.width * (.5f + .35f * sin(s.spin * (.3f + i * .07f) + i * 2f))
        val y = size.height * (.5f + .38f * cos(s.spin * (.25f + i * .05f) + i))
        val r = m * (.12f + v * .22f)
        drawCircle(Brush.radialGradient(listOf(pc(i / 5f), pc(i / 5f).copy(alpha = 0f)), Offset(x, y), r), r, Offset(x, y))
    }
}

private fun DrawScope.heart(s: VisState) {
    val c = Offset(size.width / 2, size.height * .52f); val sc = min(size.width, size.height) * (.022f + s.bass() * .008f)
    val p = Path(); val steps = 100
    for (k in 0..steps) {
        val t = k.toFloat() / steps * TAU
        val x = 16 * sin(t) * sin(t) * sin(t)
        val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
        val pt = Offset(c.x + x * sc, c.y - y * sc)
        if (k == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    drawPath(p, PAL[2].copy(alpha = .25f), style = Stroke(width = 18f + s.level * 20f))
    drawPath(p, Brush.verticalGradient(listOf(PAL.last(), PAL[1]), c.y - 16 * sc, c.y + 16 * sc))
    bars(s, .25f)
}

// ---------------------------------------------------------------- retro & grids

private fun DrawScope.spectro(s: VisState) {
    val cols = s.hist.size; val cw = size.width / cols; val rh = size.height / Visual.BANDS
    for (i in 0 until cols) {
        val row = s.hist[(s.hi + 1 + i) % cols]
        for (b in 0 until Visual.BANDS) {
            val v = row[b].coerceIn(0f, 1f); if (v < .04f) continue
            drawRect(pc(v).copy(alpha = .25f + v * .75f), Offset(i * cw, size.height - (b + 1) * rh), Size(cw + .5f, rh + .5f))
        }
    }
}

private fun DrawScope.ridges(s: VisState) {
    val lines = 16; val gap = size.height * .8f / lines; val amp = size.height * .22f
    val n = Visual.BANDS; val margin = size.width * .12f; val w = size.width - margin * 2
    for (l in 0 until lines) {
        val row = s.hist[(s.hi - (lines - 1 - l) * 2 + s.hist.size * 4) % s.hist.size]
        val base = size.height * .15f + l * gap
        val p = Path(); p.moveTo(margin, base)
        for (k in 0 until n) {
            val x = margin + w * k / (n - 1)
            val bump = row[k].coerceIn(0f, 1f) * (1f - abs(k - n / 2f) / (n / 2f) * .6f)
            p.lineTo(x, base - bump * amp)
        }
        p.lineTo(margin + w, base)
        val fill = Path().apply { addPath(p); lineTo(margin + w, base + gap); lineTo(margin, base + gap); close() }
        drawPath(fill, Color(0xFF0B0A14))
        drawPath(p, pc(l.toFloat() / lines), style = Stroke(width = 2f))
    }
}

private fun DrawScope.hex(s: VisState) {
    val cols = 9; val r = size.width / cols / 1.75f; val h = r * 1.732f
    val rows = (size.height / h).toInt() + 1
    for (row in 0..rows) for (col in 0 until cols) {
        val cx = col * r * 1.75f + r + (if (row % 2 == 1) r * .87f else 0f); val cy = row * h * .86f + r * .6f
        val b = ((col * 5 + row * 3) % Visual.BANDS); val v = s.v(b)
        val p = Path()
        for (i in 0 until 6) { val a = TAU * i / 6 + PI.toFloat() / 6; val px = cx + cos(a) * r * .88f; val py = cy + sin(a) * r * .88f; if (i == 0) p.moveTo(px, py) else p.lineTo(px, py) }
        p.close()
        drawPath(p, pc(b.toFloat() / Visual.BANDS).copy(alpha = .08f + v * .9f))
        drawPath(p, Color.White.copy(alpha = .08f), style = Stroke(width = 1f))
    }
}

private fun DrawScope.vu(s: VisState) {
    val lv = listOf(s.avg(0 until 16), s.avg(16 until Visual.BANDS) * 1.4f)
    val w = size.width / 2
    lv.forEachIndexed { i, raw ->
        val v = raw.coerceIn(0f, 1f)
        val c = Offset(w * i + w / 2, size.height * .85f); val r = min(w * .45f, size.height * .7f)
        drawArc(Color.White.copy(alpha = .15f), 210f, 120f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = 10f))
        drawArc(Brush.sweepGradient(PAL, c), 210f, 120f * v, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(width = 10f))
        for (t in 0..10) { val a = (210f + t * 12f) / 180f * PI.toFloat(); drawLine(Color.White.copy(alpha = .5f), Offset(c.x + cos(a) * r * .85f, c.y + sin(a) * r * .85f), Offset(c.x + cos(a) * r * .93f, c.y + sin(a) * r * .93f), strokeWidth = 2f) }
        val a = (210f + 120f * v) / 180f * PI.toFloat()
        drawLine(if (v > .85f) PAL.last() else Color.White, c, Offset(c.x + cos(a) * r * .95f, c.y + sin(a) * r * .95f), strokeWidth = 3f, cap = StrokeCap.Round)
        drawCircle(Color.White, 6f, c)
    }
}

private fun DrawScope.pixelGrid(s: VisState) {
    val cols = 16; val rows = 10; val cw = size.width / cols; val rh = size.height / rows
    for (c in 0 until cols) for (r in 0 until rows) {
        val b = (c * 3 + r) % Visual.BANDS; val v = s.v(b)
        val sz = minOf(cw, rh) * (.15f + v * .8f)
        drawRoundRect(pc((c + r) / (cols + rows - 2f)).copy(alpha = .2f + v * .8f), Offset(c * cw + (cw - sz) / 2, r * rh + (rh - sz) / 2), Size(sz, sz), CornerRadius(sz * .2f))
    }
}
