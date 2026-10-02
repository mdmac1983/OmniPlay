package app.orionmd.omniplay.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orionmd.omniplay.AppState
import app.orionmd.omniplay.Dlg
import app.orionmd.omniplay.Dsp
import app.orionmd.omniplay.DspCfg
import app.orionmd.omniplay.Fx
import app.orionmd.omniplay.Headphones
import app.orionmd.omniplay.Profiles
import app.orionmd.omniplay.Sound
import kotlin.math.ln
import kotlin.math.pow

/**
 * Sound & profiles (the tune button in Now Playing): pick a profile (Default / Headphones / Earbuds / Bluetooth /
 * Custom) and set everything it keeps — headphone model correction, 10-band equaliser with presets, Dolby-style
 * spatial controls (stereo width, surround, crossfeed, dialogue enhancer), effects (bass, treble, reverb, leveler,
 * volume boost) and the player's look.
 */
@Composable
fun SoundScreen(st: AppState) {
    st.prefGen
    val prof = Profiles.current(st)
    var c by remember(prof) { mutableStateOf(Sound.load(st.store)) }
    // changing anything switches the effects on (so a change is always heard)
    fun set(n: DspCfg) { val m = if (n.on == c.on && !n.on) n.copy(on = true) else n; c = m; Sound.save(st, m); Profiles.saveCurrent(st) }
    var boost by remember(prof) { mutableStateOf((st.pref("boost", "0").toIntOrNull() ?: 0).toFloat()) }

    Box(Modifier.fillMaxSize().background(C.bg).clickable(enabled = false) {}) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Ic.back, { st.soundOpen = false })
                    Text("Sound & profiles", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 6.dp))
                }
            }
            item {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.s2).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (c.on) "Sound effects ON" else "Sound effects OFF", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (c.on) C.ac else C.mu)
                            EngineStatus(c.on)
                        }
                        Switch(c.on, { v -> c = c.copy(on = v); Sound.save(st, c); Profiles.saveCurrent(st) }, colors = SwitchDefaults.colors(checkedTrackColor = C.ac))
                    }
                    // A/B: hold to hear the music without the effects
                    var held by remember { mutableStateOf(false) }
                    Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (held) C.warn else C.s3)
                        .pointerInput(Unit) { detectTapGestures(onPress = { held = true; Dsp.bypass = true; try { tryAwaitRelease() } finally { held = false; Dsp.bypass = false } }) }
                        .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(if (held) "Original sound (effects off) — let go to hear the effects" else "Hold to compare with the original sound", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (held) Color.Black else C.tx)
                    }
                    Muted("Effects apply to the music player (a second or so after a change). Headphone corrections are made for that headphone — on the tablet's speaker they mostly change the balance.", Modifier.padding(top = 8.dp), 11.sp)
                }
            }
            item {
                Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                    // ---------- profiles ----------
                    SectionHeader("Profile")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Profiles.IDS.forEach { id ->
                            val on = id == prof
                            Text(Profiles.name(id), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.Black else C.tx,
                                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(if (on) C.ac else C.s2).border(1.dp, if (on) C.ac else C.line, RoundedCornerShape(20.dp))
                                    .clickable { Profiles.switchTo(st, id); c = Sound.load(st.store); boost = (st.pref("boost", "0").toIntOrNull() ?: 0).toFloat() }.padding(horizontal = 14.dp, vertical = 8.dp))
                        }
                    }
                    Muted("Each profile keeps its own equaliser, effects, volume boost and player look. Changes save to the profile you're in.", Modifier.padding(top = 6.dp), 12.sp)
                    ToggleRow("Switch automatically", "Bluetooth → Bluetooth, wired headset → the profile below, nothing plugged in → Default.", st.pref("aprofAuto", "1") == "1") { st.setPref("aprofAuto", if (it) "1" else "0") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Muted("Wired headset uses", Modifier.weight(1f), 13.sp)
                        Select(st.pref("aprofWired", "headphones"), listOf("headphones" to "Headphones", "earbuds" to "Earbuds", "custom" to "Custom"), { st.setPref("aprofWired", it) })
                    }
                    if (!c.on) Muted("Sound effects are off — switch them on above (or change any setting) to hear the changes.", Modifier.padding(top = 10.dp), 13.sp)

                    // ---------- headphone model ----------
                    SectionHeader("Headphone correction")
                    Select(c.corr, listOf("" to "None") + Headphones.ALL.map { it.id to "${it.name}  ·  ${it.kind}" }, { set(c.copy(corr = it)) }, Modifier.fillMaxWidth())
                    Headphones.of(c.corr)?.let { h ->
                        Muted(h.note, Modifier.padding(top = 6.dp), 12.sp)
                        Knob("Strength", c.corrAmt, 0f..1f, { "${(it * 100).toInt()}%" }) { set(c.copy(corrAmt = it)) }
                    } ?: Muted("Optimised settings for your Koss, JVC, Skullcandy and JLab — flattens each model's measured response.", Modifier.padding(top = 6.dp), 12.sp)

                    // ---------- equaliser ----------
                    SectionHeader("Equaliser")
                    Curve(c, Modifier.fillMaxWidth().height(110.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DspCfg.PRESETS.forEach { (n, l) ->
                            val on = c.eq == l
                            Text(n, fontSize = 13.sp, color = if (on) Color.Black else C.tx, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) C.ac else C.s2).clickable { set(c.copy(eq = l)) }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    DspCfg.FREQS.forEachIndexed { i, hz ->
                        Knob(if (hz >= 1000) "${hz / 1000}k" else "$hz", c.eq[i], -12f..12f, { "%+.1f dB".format(Math.round(it * 2) / 2f) }, labelW = 44) { v -> set(c.copy(eq = c.eq.toMutableList().also { it[i] = Math.round(v * 2) / 2f })) }
                    }

                    // ---------- spatial ----------
                    SectionHeader("Spatial (stereo & surround)")
                    Knob("Stereo width", c.width, 0f..2f, { when { it < .05f -> "mono"; it in .95f..1.05f -> "normal"; else -> "${(it * 100).toInt()}%" } }) { set(c.copy(width = it)) }
                    Knob("Surround (3D)", c.surround, 0f..1f, ::pct) { set(c.copy(surround = it)) }
                    Knob("Dialogue / vocals", c.vocal, 0f..8f, ::plusDb) { set(c.copy(vocal = it)) }
                    Knob("Crossfeed", c.crossfeed, 0f..1f, ::pct) { set(c.copy(crossfeed = it)) }
                    Muted("Width spreads the stereo image (Dolby-style stereo expand); surround adds a virtual room around you; crossfeed makes headphones sound less \"inside your head\".", size = 12.sp)

                    // ---------- effects ----------
                    SectionHeader("Effects")
                    Knob("Bass boost", c.bass, 0f..12f, ::plusDb) { set(c.copy(bass = it)) }
                    Knob("Treble", c.treble, -6f..9f, { "%+.1f dB".format(it) }) { set(c.copy(treble = it)) }
                    Knob("Reverb", c.reverb, 0f..1f, ::pct) { set(c.copy(reverb = it)) }
                    if (c.reverb > .01f) Row(Modifier.padding(start = 96.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("room" to "Room", "hall" to "Hall", "stadium" to "Stadium").forEach { (k, n) ->
                            Text(n, fontSize = 13.sp, color = if (c.room == k) Color.Black else C.tx, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (c.room == k) C.ac else C.s2).clickable { set(c.copy(room = k)) }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                    Knob("Volume leveler", c.leveler, 0f..1f, ::pct) { set(c.copy(leveler = it)) }
                    Knob("Output", c.gain, -6f..6f, { "%+.1f dB".format(Math.round(it * 2) / 2f) }) { set(c.copy(gain = Math.round(it * 2) / 2f)) }
                    Knob("Volume boost", boost, 0f..1500f, { if (it < 50) "off" else "+%.1f dB".format(it / 100f) }, done = { st.setPref("boost", boost.toInt().toString()); Fx.apply(st.store); Profiles.saveCurrent(st) }) { boost = it }
                    Muted("Leveler evens out loud and quiet songs; volume boost makes everything louder (turn it down if it distorts). A soft limiter keeps the sound from clipping.", size = 12.sp)

                    // ---------- display ----------
                    SectionHeader("Player look (this profile)")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Skin", fontWeight = FontWeight.SemiBold); Muted(skinOf(st.pref("playerSkin", "midnight")).name, size = 12.sp) }
                        Btn("Choose", { st.dialog = Dlg.SkinPick }, small = true, kind = BtnKind.Ghost)
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Visualizer", fontWeight = FontWeight.SemiBold); Muted(VIS_STYLES.firstOrNull { it.first == st.visStyle }?.second ?: st.visStyle, size = 12.sp) }
                        Btn("Choose", { st.dialog = Dlg.VisPick }, small = true, kind = BtnKind.Ghost)
                    }
                    ToggleRow("Visualizer behind the lyrics", "", st.pref("lyricsVis", "1") == "1") { st.setPref("lyricsVis", if (it) "1" else "0"); Profiles.saveCurrent(st) }
                    ToggleRow("Lyrics over the full-screen visualizer", "", st.pref("visLyrics", "0") == "1") { st.setPref("visLyrics", if (it) "1" else "0"); Profiles.saveCurrent(st) }
                    ToggleRow("Bars in the mini player", "", st.pref("visMini", "1") == "1") { st.setPref("visMini", if (it) "1" else "0"); Profiles.saveCurrent(st) }

                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 30.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Reset ${Profiles.name(prof)}", { Profiles.reset(st); c = Sound.load(st.store) }, Modifier.weight(1f), kind = BtnKind.Ghost)
                        Btn("Done", { st.soundOpen = false }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** "Running · 44.1 kHz · 2 ch · 16-bit" — proof the engine is processing the music. */
@Composable
private fun EngineStatus(on: Boolean) {
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(700); tick++ } }
    tick
    val t = when {
        !Dsp.running -> "Engine waiting — start a song"
        on -> "Engine running · " + Dsp.format + " · ${"%.1f".format(Dsp.processed / 176_400.0 / 60)} min processed"
        else -> "Engine running · " + Dsp.format + " · passing the sound through"
    }
    Muted(t, size = 12.sp)
}

private fun pct(v: Float) = if (v < .01f) "off" else "${(v * 100).toInt()}%"
private fun plusDb(v: Float) = if (v < .1f) "off" else "+%.1f dB".format(v)

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember(on) { mutableStateOf(on) }
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); if (sub.isNotEmpty()) Muted(sub, size = 12.sp) }
        Switch(v, { v = it; onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.ac))
    }
}

/** Label · slider · value. Saves when the finger lifts (or on [done]). */
@Composable
private fun Knob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, shown: (Float) -> String, labelW: Int = 96, done: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = C.mu, modifier = Modifier.width(labelW.dp))
        Slider(v, { v = it; if (done != null) onChange(it) }, Modifier.weight(1f), valueRange = range,
            onValueChangeFinished = { if (done != null) done() else onChange(v) },
            colors = SliderDefaults.colors(thumbColor = C.tx, activeTrackColor = C.ac, inactiveTrackColor = C.s3))
        Text(shown(v), fontSize = 12.sp, color = C.mu, modifier = Modifier.width(62.dp).padding(start = 6.dp))
    }
}

/** The overall frequency response of the current settings (equaliser + correction + bass / treble). */
@Composable
private fun Curve(c: DspCfg, modifier: Modifier) {
    val freqs = remember { (0..80).map { 20.0 * (1000.0).pow(it / 80.0) } }
    val db = remember(c) { Dsp.response(c, freqs) }
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(C.s2)) {
        val h = size.height; val w = size.width
        fun y(d: Double) = (h / 2 - (d / 15.0) * (h / 2 - 8)).toFloat()
        for (g in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) drawLine(if (g == 0.0) C.mu.copy(alpha = .5f) else C.line, Offset(0f, y(g)), Offset(w, y(g)), 1f)
        for (f in DspCfg.FREQS) { val x = (ln(f / 20.0) / ln(1000.0) * w).toFloat(); drawLine(C.line.copy(alpha = .5f), Offset(x, 0f), Offset(x, h), 1f) }
        val p = Path()
        db.forEachIndexed { i, d -> val x = i / 80f * w; if (i == 0) p.moveTo(x, y(d)) else p.lineTo(x, y(d)) }
        drawPath(p, if (c.on) C.ac else C.mu, style = Stroke(3f))
    }
}
