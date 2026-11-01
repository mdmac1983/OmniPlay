package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own saved sound presets: every sound-engine setting that can be saved — equaliser (10-band, 31-band or
 * parametric), headphone correction, bass / treble, dialogue, stereo width, surround, crossfeed, balance, reverb,
 * leveller, output trim, ReplayGain — plus the volume boost. Loading one keeps the output settings (hi-res output,
 * resampler, effects for videos), which belong to the device rather than to a sound. Works from the music player,
 * the Sound screen and the video player.
 */
object SoundPresets {
    data class Preset(val id: String, val name: String, val cfg: DspCfg, val boost: Int, val created: Long)

    private const val KEY = "soundPresets"

    fun all(store: Store): List<Preset> = try {
        val a = JSONArray(store.getPref(KEY, "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Preset(o.optString("id"), o.optString("name"), DspCfg.parse(o.optString("dsp")), o.optInt("boost"), o.optLong("created"))
        }
    } catch (_: Exception) { emptyList() }

    private fun write(store: Store, l: List<Preset>) = store.setPref(KEY, JSONArray(l.map {
        JSONObject().put("id", it.id).put("name", it.name).put("dsp", it.cfg.json().toString()).put("boost", it.boost).put("created", it.created)
    }).toString())

    /** Saves what's playing now under [name] (a preset with the same name is replaced). */
    fun saveCurrent(store: Store, name: String): Preset {
        val n = name.trim().ifEmpty { "My sound" }
        val old = all(store)
        val prev = old.firstOrNull { it.name.equals(n, true) }
        val p = Preset(prev?.id ?: System.currentTimeMillis().toString(36), n, Dsp.cfg, store.getPref("boost", "0").toIntOrNull() ?: 0, prev?.created ?: System.currentTimeMillis())
        write(store, if (prev != null) old.map { if (it.id == prev.id) p else it } else old + p)
        return p
    }

    /** Makes [p] the current sound (output settings stay as they are). */
    fun apply(store: Store, p: Preset) {
        val cur = Dsp.cfg
        val c = p.cfg.copy(on = true, hiRes = cur.hiRes, resample = cur.resample, video = cur.video)
        Dsp.set(c); store.setPref("dsp", c.json().toString())
        store.setPref("boost", p.boost.toString()); Fx.apply(store)
        store.setPref("soundPresetLast", p.id)
    }

    fun rename(store: Store, id: String, name: String) = write(store, all(store).map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it })
    fun delete(store: Store, id: String) = write(store, all(store).filter { it.id != id })
    fun last(store: Store) = store.getPref("soundPresetLast", "")

    /** A short summary for lists, e.g. "Parametric · 5 filters · bass +4 · surround". */
    fun describe(c: DspCfg): String {
        val parts = mutableListOf<String>()
        parts += when (c.bands) { 0 -> "Parametric · ${c.peq.size} filters"; 31 -> "31-band EQ"; else -> DspCfg.PRESETS.firstOrNull { it.second == c.eq }?.first?.let { "EQ: $it" } ?: "10-band EQ" }
        Headphones.of(c.corr)?.let { parts += it.name }
        if (c.bass > .1f) parts += "bass +%.0f".format(c.bass)
        if (c.vocal > .1f) parts += "dialogue"
        if (c.surround > .05f) parts += "surround"
        if (c.reverb > .05f) parts += "reverb"
        if (c.leveler > .05f) parts += "leveller"
        if (c.rg != "off") parts += "ReplayGain"
        return parts.joinToString(" · ")
    }
}
