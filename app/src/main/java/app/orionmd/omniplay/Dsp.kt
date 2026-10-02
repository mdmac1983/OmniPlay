package app.orionmd.omniplay

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The music player's own sound engine (runs inside ExoPlayer's audio chain, so it works the same on every device and
 * doesn't depend on the phone's built-in effects): 10-band graphic equaliser, headphone correction (parametric filters
 * measured for a model, see [Headphones]), bass / treble, vocal (dialogue) enhancer, stereo width, surround
 * virtualizer, headphone crossfeed, reverb, volume leveler (compressor) and a soft limiter so nothing clips.
 * Settings are a [DspCfg] (saved as JSON in pref "dsp"); a new one takes effect on the next audio buffer.
 */
data class DspCfg(
    val on: Boolean = true,
    val eq: List<Float> = List(10) { 0f },          // dB per band, ±12
    val corr: String = "",                          // headphone model id ([Headphones]), "" = none
    val corrAmt: Float = 1f,                        // 0..1 how much of the correction to apply
    val bass: Float = 0f,                           // dB, 0..12 (low shelf ~90 Hz)
    val treble: Float = 0f,                         // dB, -6..9 (high shelf ~9 kHz)
    val vocal: Float = 0f,                          // dB, 0..8 (centre-channel presence)
    val width: Float = 1f,                          // stereo width 0 (mono) .. 2 (extra wide)
    val surround: Float = 0f,                       // 0..1 virtual surround / 3D
    val crossfeed: Float = 0f,                      // 0..1 (headphones: less "inside your head")
    val reverb: Float = 0f,                         // 0..1 wet mix
    val room: String = "room",                      // room | hall | stadium
    val leveler: Float = 0f,                        // 0..1 volume leveler (compressor)
    val gain: Float = 0f,                           // dB output trim, -6..+6
) {
    fun json(): JSONObject = JSONObject().put("on", on).put("eq", JSONArray(eq.map { it.toDouble() })).put("corr", corr).put("corrAmt", corrAmt.toDouble())
        .put("bass", bass.toDouble()).put("treble", treble.toDouble()).put("vocal", vocal.toDouble()).put("width", width.toDouble())
        .put("surround", surround.toDouble()).put("crossfeed", crossfeed.toDouble()).put("reverb", reverb.toDouble()).put("room", room)
        .put("leveler", leveler.toDouble()).put("gain", gain.toDouble())

    companion object {
        val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        fun parse(s: String?): DspCfg = try {
            val o = JSONObject(s ?: "")
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            DspCfg(o.optBoolean("on", true), o.optJSONArray("eq")?.let { a -> List(10) { a.optDouble(it, 0.0).toFloat() } } ?: List(10) { 0f },
                o.optString("corr"), f("corrAmt", 1f), f("bass", 0f), f("treble", 0f), f("vocal", 0f), f("width", 1f), f("surround", 0f),
                f("crossfeed", 0f), f("reverb", 0f), o.optString("room", "room"), f("leveler", 0f), f("gain", 0f))
        } catch (_: Exception) { DspCfg() }

        /** One-tap sound styles for the 10 bands (like Dolby's "intelligent equaliser" + the usual genre presets). */
        val PRESETS: List<Pair<String, List<Float>>> = listOf(
            "Flat" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Open" to listOf(1f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f, 3f),
            "Rich" to listOf(3f, 3f, 2f, 1f, 0f, 0f, 1f, 1f, 1f, 1f),
            "Focused" to listOf(-1f, -1f, 0f, 1f, 2f, 3f, 3f, 2f, 0f, -1f),
            "Warm" to listOf(3f, 3f, 2f, 1f, 0f, -1f, -1f, -2f, -2f, -3f),
            "Bright" to listOf(-1f, -1f, 0f, 0f, 0f, 1f, 2f, 3f, 4f, 4f),
            "Bass boost" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
            "Bass head" to listOf(9f, 8f, 6f, 3f, 0f, -1f, 0f, 1f, 1f, 1f),
            "Vocal" to listOf(-2f, -2f, -1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f),
            "Rock" to listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f),
            "Pop" to listOf(-1f, 1f, 3f, 4f, 3f, 0f, -1f, -1f, 0f, 1f),
            "Hip-hop" to listOf(6f, 5f, 3f, 1f, -1f, -1f, 1f, 1f, 2f, 3f),
            "Electronic" to listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 2f, 4f, 5f),
            "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
            "Classical" to listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f),
            "Acoustic" to listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f),
            "Late night" to listOf(2f, 2f, 1f, 0f, 1f, 2f, 2f, 1f, -1f, -2f),
            "Treble cut" to listOf(0f, 0f, 0f, 0f, 0f, 0f, -1f, -3f, -5f, -7f),
        )
    }
}

/** One RBJ biquad filter (stereo state). */
private class Biquad(type: Char, f: Double, dB: Double, q: Double, sr: Double) {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var x1l = 0.0; private var x2l = 0.0; private var y1l = 0.0; private var y2l = 0.0
    private var x1r = 0.0; private var x2r = 0.0; private var y1r = 0.0; private var y2r = 0.0
    init {
        val fc = f.coerceIn(10.0, sr * .45)
        val a = 10.0.pow(dB / 40); val w = 2 * PI * fc / sr; val cw = cos(w); val sw = sin(w); val al = sw / (2 * q)
        val c = when (type) {
            'L' -> { val s = 2 * sqrt(a) * al
                doubleArrayOf(a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
            'H' -> { val s = 2 * sqrt(a) * al
                doubleArrayOf(a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
            'l' -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + al, -2 * cw, 1 - al)   // low-pass
            'h' -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + al, -2 * cw, 1 - al) // high-pass
            else -> doubleArrayOf(1 + al * a, -2 * cw, 1 - al * a, 1 + al / a, -2 * cw, 1 - al / a) // peaking
        }
        b0 = c[0] / c[3]; b1 = c[1] / c[3]; b2 = c[2] / c[3]; a1 = c[4] / c[3]; a2 = c[5] / c[3]
    }
    fun l(x: Double): Double { val y = b0 * x + b1 * x1l + b2 * x2l - a1 * y1l - a2 * y2l; x2l = x1l; x1l = x; y2l = y1l; y1l = y; return y }
    fun r(x: Double): Double { val y = b0 * x + b1 * x1r + b2 * x2r - a1 * y1r - a2 * y2r; x2r = x1r; x1r = x; y2r = y1r; y1r = y; return y }
    /** Gain (dB) of this filter at [f] Hz — used to work out how much headroom the settings need. */
    fun dbAt(f: Double, sr: Double): Double {
        val w = 2 * PI * f / sr; val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2); val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        return 10 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

/** Small Freeverb-style reverb (mono in, stereo out). */
private class Reverb(sr: Int, room: String) {
    private val scale = sr / 44100.0
    private val size = when (room) { "hall" -> 1.35; "stadium" -> 1.9; else -> 1.0 }
    private val fb = when (room) { "hall" -> .84; "stadium" -> .88; else -> .76 }
    private val combL = intArrayOf(1116, 1188, 1277, 1356).map { FloatArray((it * scale * size).toInt().coerceAtLeast(1)) }
    private val combR = intArrayOf(1139, 1211, 1300, 1379).map { FloatArray((it * scale * size).toInt().coerceAtLeast(1)) }
    private val cpL = IntArray(4); private val cpR = IntArray(4); private val lpL = FloatArray(4); private val lpR = FloatArray(4)
    private val apL = intArrayOf(556, 441).map { FloatArray((it * scale).toInt().coerceAtLeast(1)) }
    private val apR = intArrayOf(579, 464).map { FloatArray((it * scale).toInt().coerceAtLeast(1)) }
    private val apPL = IntArray(2); private val apPR = IntArray(2)
    private fun combs(x: Float, bufs: List<FloatArray>, pos: IntArray, lp: FloatArray): Float {
        var out = 0f
        for (i in 0 until 4) { val b = bufs[i]; val p = pos[i]; val y = b[p]; lp[i] = y * .7f + lp[i] * .3f; b[p] = x + lp[i] * fb.toFloat(); pos[i] = (p + 1) % b.size; out += y }
        return out
    }
    private fun aps(x0: Float, bufs: List<FloatArray>, pos: IntArray): Float {
        var x = x0
        for (i in 0 until 2) { val b = bufs[i]; val p = pos[i]; val bo = b[p]; val y = -x + bo; b[p] = x + bo * .5f; pos[i] = (p + 1) % b.size; x = y }
        return x
    }
    fun l(x: Float) = aps(combs(x * .015f, combL, cpL, lpL), apL, apPL)
    fun r(x: Float) = aps(combs(x * .015f, combR, cpR, lpR), apR, apPR)
}

@OptIn(UnstableApi::class)
object Dsp : BaseAudioProcessor() {
    @Volatile var cfg = DspCfg(); private set
    private var built: DspCfg? = null
    private var sr = 44100
    private var ch = 2
    private var enc = C.ENCODING_PCM_16BIT

    private var filters = emptyArray<Biquad>()
    private var vocalF: Biquad? = null
    private var sideHp: Biquad? = null
    private var xfL: Biquad? = null
    private var verb: Reverb? = null
    private var pre = 1.0
    private var post = 1.0
    private val delay = FloatArray(4096); private var dPos = 0; private var dLen = 1
    private var env = 0.0

    fun set(c: DspCfg) { cfg = c }
    /** "Hold to compare": skips the effects while held. */
    @Volatile var bypass = false

    // what the engine is doing (shown on the Sound screen, so you can see it's really running)
    @Volatile var format = ""; private set
    @Volatile var seen = 0L; private set          // bytes that passed through
    @Volatile var processed = 0L; private set     // bytes that were processed (effects on)
    @Volatile var lastSeen = 0L; private set
    val running get() = System.currentTimeMillis() - lastSeen < 2000

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        sr = inputAudioFormat.sampleRate; ch = inputAudioFormat.channelCount; enc = e; built = null
        format = "${sr / 1000.0} kHz · ${ch} ch · ${if (e == C.ENCODING_PCM_FLOAT) "32-bit float" else "16-bit"}"
        return inputAudioFormat
    }

    override fun onFlush() { built = null }
    override fun onReset() { built = null }

    /** Filters for [c] at sample rate [sr] (also used by the UI to draw the response curve). */
    private fun chain(c: DspCfg, sr: Double): Array<Biquad> {
        val l = mutableListOf<Biquad>()
        c.eq.forEachIndexed { i, g -> if (abs(g) > .05f) l += Biquad(if (i == 0) 'L' else if (i == 9) 'H' else 'P', DspCfg.FREQS[i].toDouble(), g.toDouble(), if (i == 0 || i == 9) .8 else 1.41, sr) }
        Headphones.of(c.corr)?.let { h -> if (c.corrAmt > .01f) h.filters.forEach { (t, f, g, q) -> l += Biquad(t, f.toDouble(), (g * c.corrAmt).toDouble(), q.toDouble(), sr) } }
        if (c.bass > .05f) l += Biquad('L', 90.0, c.bass.toDouble(), .7, sr)
        if (abs(c.treble) > .05f) l += Biquad('H', 9000.0, c.treble.toDouble(), .7, sr)
        return l.toTypedArray()
    }

    /** Overall frequency response (dB) of [c] at [freqs] — drawn as the curve above the sliders. */
    fun response(c: DspCfg, freqs: List<Double>): List<Double> { val f = chain(c, 48000.0); return freqs.map { hz -> f.sumOf { it.dbAt(hz, 48000.0) } } }

    /** Headroom: turn everything down by the biggest boost so loud songs don't distort. */
    private fun headroom(f: Array<Biquad>): Double {
        var peak = 0.0; var hz = 20.0
        while (hz < 20000) { peak = maxOf(peak, f.sumOf { it.dbAt(hz, sr.toDouble()) }); hz *= 1.12 }
        return peak
    }

    private fun build(c: DspCfg) {
        val f = chain(c, sr.toDouble())
        filters = f
        val boost = (headroom(f) + c.vocal * .5) * .55   // part of the boost is kept (the soft limiter catches peaks), so changes are clearly heard
        pre = 10.0.pow(-boost / 20)
        post = 10.0.pow(c.gain / 20.0) * (1 + c.leveler * 1.2) // leveler makeup gain
        vocalF = if (c.vocal > .05f) Biquad('P', 2500.0, c.vocal.toDouble(), .8, sr.toDouble()) else null
        sideHp = Biquad('h', 180.0, 0.0, .7, sr.toDouble())
        xfL = if (c.crossfeed > .01f) Biquad('l', 700.0, 0.0, .7, sr.toDouble()) else null
        verb = if (c.reverb > .01f) Reverb(sr, c.room) else null
        dLen = (sr * .012).toInt().coerceIn(1, delay.size - 1); delay.fill(0f); dPos = 0
        built = c
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val c = cfg
        seen += size; lastSeen = System.currentTimeMillis()
        if (!c.on || bypass || ch < 1) { out.put(inputBuffer); out.flip(); return }
        processed += size
        if (built !== c) build(c)
        val float = enc == C.ENCODING_PCM_FLOAT
        val bps = if (float) 4 else 2
        val frames = size / (bps * ch)
        val inB = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        val stereo = ch >= 2
        val width = c.width.toDouble(); val sur = c.surround.toDouble(); val xf = c.crossfeed.toDouble() * .45
        val wet = c.reverb.toDouble() * .6; val lev = c.leveler.toDouble()
        val att = exp(-1.0 / (sr * .01)); val rel = exp(-1.0 / (sr * .25))
        val thr = 0.18; val ratio = 1 + lev * 4
        fun rd(): Double = if (float) inB.float.toDouble() else inB.short / 32768.0
        fun wr(v: Double) { val s = soft(v); if (float) out.putFloat(s.toFloat()) else out.putShort((s * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
        for (n in 0 until frames) {
            var l = rd() * pre
            var r = if (stereo) rd() * pre else l
            for (f in filters) { l = f.l(l); r = f.r(r) }
            if (stereo) {
                var m = (l + r) * .5; var s = (l - r) * .5
                vocalF?.let { m = it.l(m) }
                s *= width
                if (sur > .01) {
                    // virtual surround: a short (12 ms) echo of the side signal widens the stage past the speakers
                    val sh = sideHp!!.l(s)
                    val d = delay[(dPos - dLen + delay.size) % delay.size].toDouble()
                    delay[dPos] = sh.toFloat(); dPos = (dPos + 1) % delay.size
                    s += d * sur * .9 + sh * sur * .35
                }
                l = m + s; r = m - s
                xfL?.let { f -> val cl = f.l(r); val cr = f.r(l); l = (l + cl * xf) / (1 + xf * .5); r = (r + cr * xf) / (1 + xf * .5) }
            } else vocalF?.let { l = it.l(l); r = l }
            verb?.let { v -> val mono = ((l + r) * .5).toFloat(); val vl = v.l(mono); val vr = v.r(mono); l = l * (1 - wet * .4) + vl * wet; r = r * (1 - wet * .4) + vr * wet }
            if (lev > .01) {
                val lvl = maxOf(abs(l), abs(r))
                env = if (lvl > env) att * env + (1 - att) * lvl else rel * env + (1 - rel) * lvl
                if (env > thr) { val g = (thr * (env / thr).pow(1 / ratio)) / env; l *= g; r *= g }
            }
            l *= post; r *= post
            wr(l); if (stereo) wr(r)
            for (k in 2 until ch) { val x = rd(); wr(x) } // extra channels pass through
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    /** Soft limiter: untouched below 0.9, rounds off above instead of clipping. */
    private fun soft(x: Double): Double { val a = abs(x); return if (a <= .9) x else (if (x < 0) -1 else 1) * (.9 + .1 * tanh((a - .9) / .1)) }
}

/**
 * Headphone correction: parametric filters that flatten a model's measured frequency response towards a neutral
 * (Harman) target. Measurements and filters come from the AutoEQ project (github.com/jaakkopasanen/AutoEq), which
 * collects published measurements (oratory1990, Rtings, crinacle, Super Review…).
 */
data class Headphone(val id: String, val name: String, val kind: String, val note: String, val filters: List<Filt>)
data class Filt(val type: Char, val f: Float, val g: Float, val q: Float)

object Headphones {
    private fun p(s: String): List<Filt> = s.trim().split(';').filter { it.isNotBlank() }.map { e ->
        val (t, f, g, q) = e.trim().split(' ').filter { it.isNotEmpty() }
        Filt(when (t) { "LS" -> 'L'; "HS" -> 'H'; else -> 'P' }, f.toFloat(), g.toFloat(), q.toFloat())
    }

    val ALL = listOf(
        Headphone("koss_r10", "Koss R/10", "headphones",
            "No published measurement of the R/10, so this uses Koss's closed home headphone with the same design, the UR20 (Rtings): bass a little lower and a lift in the upper mids for clarity.",
            p("LS 105 4.1 0.70; PK 2796 7.3 1.20; PK 338 -3.2 0.18; PK 7263 -6.2 1.03; PK 1463 6.7 1.65; HS 10000 4.3 0.70; PK 4042 -3.0 6.00; PK 8666 -2.0 3.74; PK 3522 2.4 6.00; PK 277 0.6 2.70")),
        Headphone("koss_porta", "Koss Porta Pro", "headphones", "Measured by oratory1990.",
            p("LS 105 9.2 0.70; PK 83 -8.7 0.47; PK 3336 4.3 4.55; PK 8898 5.8 2.75; PK 6711 4.5 4.32; HS 10000 4.5 0.70; PK 4725 -3.8 6.00; PK 1892 -1.6 2.53; PK 658 0.6 1.45; PK 2756 1.5 4.62")),
        Headphone("jvc_gumy", "JVC earbuds (HA-FX7 Gumy)", "earbuds",
            "Measured by Super Review. Trims the boomy upper bass and opens up the mids and treble.",
            p("LS 105 -1.7 0.70; PK 2169 5.4 0.91; PK 164 -6.3 0.48; PK 796 5.0 1.18; PK 6256 -7.2 4.61; HS 10000 4.1 0.70; PK 8422 -2.2 3.83; PK 4410 1.3 3.78; PK 3271 -1.1 3.82; PK 38 -0.3 2.01")),
        Headphone("jvc_fr15", "JVC earbuds (HA-FR15)", "earbuds", "Measured by kr0mka.",
            p("LS 105 -5.6 0.70; PK 2122 4.7 1.41; PK 184 -4.5 0.83; PK 6436 -7.6 1.75; PK 982 4.9 0.73; HS 10000 -3.9 0.70; PK 68 0.7 1.60; PK 3745 -1.3 4.36; PK 5150 1.2 4.67; PK 119 -0.6 2.55")),
        Headphone("skull_dime", "Skullcandy Dime", "earbuds", "Measured by Rtings. Tames the hot treble and fills in the mids.",
            p("LS 105 -1.8 0.70; PK 735 3.6 2.26; PK 205 -2.4 1.10; PK 2277 2.6 2.29; PK 517 1.0 1.66; HS 10000 -6.8 0.70; PK 7225 -3.2 4.31; PK 4107 2.9 2.79; PK 68 1.0 2.96; PK 3170 -0.9 2.02")),
        Headphone("skull_dime2", "Skullcandy Dime 2 / 3", "earbuds", "Measured by Rtings.",
            p("LS 105 -0.2 0.70; PK 220 -2.3 0.98; PK 720 3.0 2.06; PK 3885 2.6 0.49; PK 7776 -5.5 1.46; HS 10000 -5.9 0.70; PK 8712 2.6 3.01; PK 2179 1.6 3.15; PK 1452 -0.9 2.37; PK 3125 -1.1 4.00")),
        Headphone("jlab_gopop", "JLab Go Pop+", "bluetooth",
            "No published measurement of the Go Pop+ yet, so this uses its sibling with the same 6 mm driver, the JLab GO Air POP (Rtings): less upper bass, smoother sharp treble — matching reviews of the Go Pop+ (elevated bass, occasionally sharp highs).",
            p("LS 105 -0.9 0.70; PK 109 -5.1 0.61; PK 669 4.6 0.64; PK 5015 3.8 4.20; PK 1210 -2.4 2.28; HS 10000 -7.5 0.70; PK 7899 -4.0 1.71; PK 5874 3.9 5.02; PK 280 0.5 2.80; PK 4309 1.4 5.17")),
    )
    fun of(id: String): Headphone? = if (id.isEmpty()) null else ALL.firstOrNull { it.id == id }
}

/** Keeps the [Dsp] settings in prefs. */
object Sound {
    fun load(store: Store): DspCfg {
        // v5.2: effects used to start switched off (so changes made then did nothing) — switch every saved profile on once
        if (store.getPref("dspV2", "") != "1") {
            store.setPref("dspV2", "1")
            store.getPref("dsp", "").takeIf { it.isNotEmpty() }?.let { store.setPref("dsp", DspCfg.parse(it).copy(on = true).json().toString()) }
            for (id in Profiles.IDS) store.getPref("aprof_$id", "").takeIf { it.isNotEmpty() }?.let { raw -> try {
                val o = org.json.JSONObject(raw); o.put("dsp", DspCfg.parse(o.optString("dsp")).copy(on = true).json().toString()); store.setPref("aprof_$id", o.toString())
            } catch (_: Exception) {} }
        }
        return DspCfg.parse(store.getPref("dsp", "")).also { Dsp.set(it) }
    }
    fun save(st: AppState, c: DspCfg) { Dsp.set(c); st.setPref("dsp", c.json().toString()) }
}
