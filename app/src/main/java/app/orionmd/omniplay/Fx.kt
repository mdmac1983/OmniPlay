package app.orionmd.omniplay

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer

/**
 * Sound for the music player: an equaliser (Android's built-in, with its presets or your own band levels) and a volume
 * boost (LoudnessEnhancer, up to +15 dB). Both are tied to the player's audio session and kept in settings:
 * "eqOn", "eqPreset" (-1 = custom), "eqLevels" (millibels per band), "boost" (millibels).
 */
object Fx {
    private var eq: Equalizer? = null
    private var loud: LoudnessEnhancer? = null
    var session = 0; private set
    /** true once the player has run (the equaliser needs a playing session to be set up). */
    val ready get() = eq != null

    fun attach(ctx: Context, sid: Int) {
        release(); session = sid
        try { eq = Equalizer(0, sid) } catch (_: Throwable) { eq = null }
        try { loud = LoudnessEnhancer(sid) } catch (_: Throwable) { loud = null }
        apply(Store(ctx)); Sound.load(Store(ctx))
    }
    fun release() {
        try { eq?.release() } catch (_: Throwable) {}; try { loud?.release() } catch (_: Throwable) {}
        eq = null; loud = null
    }

    fun apply(store: Store) {
        eq?.let { e -> try {
            // the built-in equaliser is replaced by our own sound engine (Dsp); kept off
            val on = false
            val preset = store.getPref("eqPreset", "-1").toIntOrNull() ?: -1
            if (on) {
                if (preset >= 0 && preset < e.numberOfPresets) e.usePreset(preset.toShort())
                else levels(store).forEachIndexed { b, v -> if (b < e.numberOfBands) e.setBandLevel(b.toShort(), v.toShort()) }
            }
            e.enabled = on
        } catch (_: Throwable) {} }
        loud?.let { l -> try {
            val g = store.getPref("boost", "0").toIntOrNull() ?: 0
            l.setTargetGain(g); l.enabled = g > 0
        } catch (_: Throwable) {} }
    }

    /** (centre frequency in Hz, min mB, max mB) per band. */
    fun bands(): List<Triple<Int, Int, Int>> = eq?.let { e -> try {
        val r = e.bandLevelRange
        (0 until e.numberOfBands).map { b -> Triple(e.getCenterFreq(b.toShort()) / 1000, r[0].toInt(), r[1].toInt()) }
    } catch (_: Throwable) { null } } ?: emptyList()
    fun presets(): List<String> = eq?.let { e -> try { (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } } catch (_: Throwable) { null } } ?: emptyList()
    /** Current band levels (from a preset or saved custom levels). */
    fun levels(store: Store): List<Int> {
        val saved = store.getPref("eqLevels", "").split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (saved.isNotEmpty()) saved else eq?.let { e -> try { (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() } } catch (_: Throwable) { null } } ?: emptyList()
    }
    /** Band levels after picking a preset (so the sliders can show them). */
    fun presetLevels(store: Store, p: Int): List<Int> = eq?.let { e -> try {
        e.usePreset(p.toShort()); (0 until e.numberOfBands).map { e.getBandLevel(it.toShort()).toInt() }.also { apply(store) }
    } catch (_: Throwable) { null } } ?: emptyList()
}
