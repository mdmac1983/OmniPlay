package app.orionmd.omniplay.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.orionmd.omniplay.*

/**
 * Your saved sound presets: tap one to switch to it, "Save current sound" to keep what you're hearing now,
 * press and hold one to rename, update or delete it. [onApplied] runs after the sound changed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoundPresetsPanel(st: AppState, onApplied: () -> Unit = {}) {
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick, st.prefGen) { SoundPresets.all(st.store) }
    val last = remember(tick, st.prefGen) { SoundPresets.last(st.store) }
    var saving by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<SoundPresets.Preset?>(null) }
    var renaming by remember { mutableStateOf<SoundPresets.Preset?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (list.isEmpty()) Muted("No saved presets yet. Set up the equaliser and effects the way you like, then save them here — every sound setting is kept (EQ, headphone correction, effects, ReplayGain, volume boost).", size = 12.sp)
        list.forEach { p ->
            val on = p.id == last
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.ac.copy(alpha = .22f) else C.s2)
                .border(1.dp, if (on) C.ac else C.line, RoundedCornerShape(12.dp))
                .combinedClickable(onClick = {
                    SoundPresets.apply(st.store, p); Profiles.saveCurrent(st); st.prefGen++; tick++; st.say("Sound: ${p.name}"); onApplied()
                }, onLongClick = { menu = p })
                .padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, color = if (on) C.ac else C.tx, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted(SoundPresets.describe(p.cfg) + if (p.boost > 0) " · boost +${p.boost / 100} dB" else "", size = 11.sp)
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Btn("Save current sound…", { saving = true }, small = true, icon = Ic.save)
            if (list.isNotEmpty()) Muted("  Hold a preset to rename or delete it", size = 11.sp)
        }
    }
    if (saving) PresetNameDialog("Save sound preset", "", { saving = false }) { n ->
        val exists = list.any { it.name.equals(n, true) }
        val p = SoundPresets.saveCurrent(st.store, n); st.store.setPref("soundPresetLast", p.id); tick++
        st.say(if (exists) "Updated \"${p.name}\"" else "Saved \"${p.name}\"")
    }
    menu?.let { p ->
        Dialog({ menu = null }) {
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Btn("Update with the current sound", { SoundPresets.saveCurrent(st.store, p.name); tick++; st.say("Updated \"${p.name}\""); menu = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                Btn("Rename", { renaming = p; menu = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
                Btn("Delete", { SoundPresets.delete(st.store, p.id); tick++; st.say("Deleted \"${p.name}\""); menu = null }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
                Btn("Close", { menu = null }, Modifier.fillMaxWidth(), kind = BtnKind.Ghost)
            }
        }
    }
    renaming?.let { p -> PresetNameDialog("Rename preset", p.name, { renaming = null }) { n -> SoundPresets.rename(st.store, p.id, n); tick++ } }
}

@Composable
private fun PresetNameDialog(title: String, initial: String, onDone: () -> Unit, ok: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Dialog(onDone) {
        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(C.s1).padding(16.dp).widthIn(min = 280.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Muted("Saving with an existing name updates that preset.", size = 12.sp)
            Field(v, { v = it.take(40) }, "e.g. Car, Bass night, Movie dialogue", Modifier.fillMaxWidth().padding(vertical = 10.dp))
            Row { Btn("Cancel", onDone, Modifier.weight(1f), kind = BtnKind.Ghost); Spacer(Modifier.width(8.dp)); Btn("Save", { if (v.isNotBlank()) { ok(v.trim()); onDone() } }, Modifier.weight(1f)) }
        }
    }
}

/** The music player's Options sheet: sound presets, effects on/off and shortcuts to everything else. */
@Composable
fun MusicOptions(st: AppState, onDone: () -> Unit) {
    var on by remember { mutableStateOf(Dsp.cfg.on) }
    Dialog(onDone, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.94f).fillMaxHeight(.88f).clip(RoundedCornerShape(18.dp)).background(C.s1).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Options", fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                IconBtn(Ic.close, onDone)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SectionHeader("Sound presets")
                SoundPresetsPanel(st)
                SectionHeader("Sound")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Sound effects", fontWeight = FontWeight.SemiBold); Muted(if (on) "Equaliser and effects are on" else "Playing the original sound", size = 12.sp) }
                    Switch(on, { v -> on = v; Sound.save(st, Dsp.cfg.copy(on = v)); Profiles.saveCurrent(st) }, colors = SwitchDefaults.colors(checkedTrackColor = C.ac))
                }
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DspCfg.PRESETS.forEach { (n, l) ->
                        val sel = Dsp.cfg.bands == 10 && Dsp.cfg.eq == l
                        Text(n, fontSize = 13.sp, color = if (sel) androidx.compose.ui.graphics.Color.Black else C.tx,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) C.ac else C.s2)
                                .clickable { Sound.save(st, Dsp.cfg.copy(on = true, bands = 10, eq = l)); Profiles.saveCurrent(st); on = true; st.prefGen++; st.say("Equaliser: $n") }
                                .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
                SectionHeader("Loudness (preamp)")
                var pre by remember { mutableStateOf(Dsp.cfg.preamp) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0f, 3f, 6f, 9f, 12f).forEach { v ->
                        val sel = kotlin.math.abs(pre - v) < .25f
                        Text(if (v == 0f) "0 dB" else "+${v.toInt()} dB", fontSize = 13.sp, color = if (sel) androidx.compose.ui.graphics.Color.Black else C.tx,
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) C.ac else C.s2)
                                .clickable { pre = v; on = true; Sound.save(st, Dsp.cfg.copy(on = true, preamp = v)); Profiles.saveCurrent(st) }.padding(horizontal = 14.dp, vertical = 6.dp))
                    }
                }
                Muted("Louder before the limiter, like Poweramp's preamp. Fine-tune it on the Sound screen.", Modifier.padding(top = 4.dp), 12.sp)
                SectionHeader("More")
                OptionRow(Ic.tune, "Sound & equaliser", "All settings: 10/31-band or parametric EQ, headphone correction, effects, output, DVC") { onDone(); st.soundOpen = true }
                OptionRow(Ic.skin, "Player skin", "41 looks — also styles the video player") { onDone(); st.dialog = Dlg.SkinPick }
                OptionRow(Ic.eq, "Visualizer style", "") { onDone(); st.dialog = Dlg.VisPick }
                OptionRow(Ic.sleep, "Sleep timer", "") { onDone(); st.dialog = Dlg.Sleep }
                OptionRow(Ic.playlistAdd, "Save the queue as a playlist", "") {
                    onDone(); st.dialog = Dlg.AddToPlaylist(Player.queue.let { q -> (0 until q.length()).map { q.getJSONObject(it).optLong("i") } }, queue = true)
                }
                OptionRow(Ic.expand, "Visualizer full screen", "") { onDone(); st.visFull = true }
            }
        }
    }
}

@Composable
private fun OptionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(icon, null, tint = C.ac, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 14.dp)) { Text(title, fontSize = 15.sp); if (sub.isNotEmpty()) Muted(sub, size = 12.sp) }
    }
}
