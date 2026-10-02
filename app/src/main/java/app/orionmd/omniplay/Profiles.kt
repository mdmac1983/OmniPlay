package app.orionmd.omniplay

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Sound profiles for the music player: Default, Headphones, Earbuds, Bluetooth and Custom. Each one keeps everything —
 * the sound engine ([DspCfg]: equaliser, headphone correction, stereo / surround, effects), the volume boost, and the
 * player's look (skin, visualizer style, lyrics / visualizer options). Switching saves the one you leave, so changes
 * stick to the profile they were made in. Optionally switches by itself when Bluetooth or a wired headset connects.
 */
object Profiles {
    val IDS = listOf("default", "headphones", "earbuds", "bluetooth", "custom")
    val NAMES = mapOf("default" to "Default (speaker)", "headphones" to "Headphones", "earbuds" to "Earbuds", "bluetooth" to "Bluetooth", "custom" to "Custom")
    /** Player-look settings kept per profile. */
    val DISPLAY = listOf("playerSkin", "visStyle", "lyricsVis", "visLyrics", "visMini")

    fun current(st: AppState) = st.pref("aprofile", "default")
    fun name(id: String) = NAMES[id] ?: id

    /** Starting point for a profile that hasn't been changed yet. */
    fun defaults(id: String): DspCfg = when (id) {
        "headphones" -> DspCfg(corr = "koss_r10", corrAmt = 1f, bass = 3f, crossfeed = .3f, width = 1.15f)
        "earbuds" -> DspCfg(corr = "jvc_gumy", corrAmt = 1f, bass = 2.5f, width = 1.2f, crossfeed = .2f)
        "bluetooth" -> DspCfg(corr = "jlab_gopop", corrAmt = 1f, bass = 2f, width = 1.3f, surround = .3f)
        "custom" -> DspCfg(eq = DspCfg.PRESETS.first { it.first == "Rich" }.second, width = 1.25f)
        else -> DspCfg()
    }

    private fun snapshot(st: AppState): JSONObject {
        val o = JSONObject().put("dsp", st.pref("dsp", DspCfg().json().toString())).put("boost", st.pref("boost", "0"))
        val d = JSONObject(); DISPLAY.forEach { k -> st.pref(k, "").takeIf { it.isNotEmpty() }?.let { d.put(k, it) } }
        return o.put("display", d)
    }

    /** Saves the current settings into the active profile (called after every change on the Sound screen). */
    fun saveCurrent(st: AppState) = st.setPref("aprof_" + current(st), snapshot(st).toString())

    fun switchTo(st: AppState, id: String) {
        if (id == current(st)) return
        saveCurrent(st)
        val o = st.pref("aprof_$id", "").takeIf { it.isNotEmpty() }?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        st.setPref("aprofile", id)
        if (o == null) { Sound.save(st, defaults(id)); saveCurrent(st); return }   // new profile: keep the current look
        Sound.save(st, DspCfg.parse(o.optString("dsp")))
        st.setPref("boost", o.optString("boost", "0")); Fx.apply(st.store)
        o.optJSONObject("display")?.let { d -> DISPLAY.forEach { k -> if (d.has(k)) st.setPref(k, d.optString(k)) } }
        st.visStyle = st.pref("visStyle", st.visStyle)
    }

    /** Back to the profile's starting sound (keeps the look). */
    fun reset(st: AppState) { Sound.save(st, defaults(current(st))); saveCurrent(st) }

    // ---------- switch by itself when the output changes ----------
    private var cb: AudioDeviceCallback? = null
    fun watch(ctx: Context, st: AppState) {
        if (cb != null) return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val c = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pick(am, st)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pick(am, st)
        }
        cb = c
        try { am.registerAudioDeviceCallback(c, Handler(Looper.getMainLooper())) } catch (_: Exception) {}
    }

    /** Which profile the connected output calls for. */
    fun forOutput(am: AudioManager, st: AppState): String {
        val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } } catch (_: Exception) { emptyList() }
        return when {
            outs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } -> "bluetooth"
            outs.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> st.pref("aprofWired", "headphones")
            else -> "default"
        }
    }

    private var last = ""
    private fun pick(am: AudioManager, st: AppState) {
        if (st.pref("aprofAuto", "1") != "1") return
        val id = forOutput(am, st)
        if (id == last) return
        val first = last.isEmpty(); last = id
        if (first) return   // only react to changes (a profile picked by hand stays put when the app starts)
        if (id != current(st)) { switchTo(st, id); st.say("Sound profile: ${name(id)}") }
    }
}
