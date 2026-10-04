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
 * virtualizer, headphone crossfeed, reverb, volume leveler (compressor), a preamp and a peak limiter so nothing clips.
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
    val balance: Float = 0f,                        // -1 (left) .. +1 (right)
    val rg: String = "off",                         // ReplayGain: off | track | album
    val rgPre: Float = 0f,                          // dB added to ReplayGain-tagged songs, -6..+12
    val video: Boolean = true,                      // use the same engine for videos
    val bands: Int = 10,                            // equaliser: 10 or 31 graphic bands, 0 = parametric
    val peq: List<Filt> = emptyList(),              // parametric equaliser filters (up to 12)
    val eq31: List<Float> = List(31) { 0f },        // dB per band (31-band mode), ±12
    val hiRes: Boolean = true,                      // OmniPlay's own hi-res output (32-bit float, own resampler)
    val resample: Boolean = true,                   // convert to the phone's native rate ourselves (skips Android's resampler)
    val preamp: Float = 0f,                         // dB before the limiter, -12..+15 (like Poweramp's preamp)
    val autoHeadroom: Boolean = false,              // turn everything down by part of the EQ's biggest boost (quieter, never limits)
) {
    fun json(): JSONObject = JSONObject().put("on", on).put("eq", JSONArray(eq.map { it.toDouble() })).put("corr", corr).put("corrAmt", corrAmt.toDouble())
        .put("bass", bass.toDouble()).put("treble", treble.toDouble()).put("vocal", vocal.toDouble()).put("width", width.toDouble())
        .put("surround", surround.toDouble()).put("crossfeed", crossfeed.toDouble()).put("reverb", reverb.toDouble()).put("room", room)
        .put("leveler", leveler.toDouble()).put("gain", gain.toDouble())
        .put("balance", balance.toDouble()).put("rg", rg).put("rgPre", rgPre.toDouble()).put("video", video)
        .put("preamp", preamp.toDouble()).put("autoHeadroom", autoHeadroom).put("bands", bands).put("peq", JSONArray(peq.map { JSONObject().put("t", it.type.toString()).put("f", it.f.toDouble()).put("g", it.g.toDouble()).put("q", it.q.toDouble()) })).put("eq31", JSONArray(eq31.map { it.toDouble() })).put("hiRes", hiRes).put("resample", resample)

    companion object {
        val FREQS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        /** Parametric filter types: peak, low shelf, high shelf, low cut (high-pass), high cut (low-pass). */
        val PEQ_TYPES = listOf('P' to "Peak", 'L' to "Low shelf", 'H' to "High shelf", 'h' to "Low cut", 'l' to "High cut")
        /** Starting point when switching to parametric: three gentle, flat filters. */
        val PEQ_START = listOf(Filt('L', 105f, 0f, .7f), Filt('P', 1000f, 0f, 1f), Filt('H', 8000f, 0f, .7f))
        /** ISO third-octave centres for the 31-band equaliser. */
        val FREQS31 = doubleArrayOf(20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0,
            1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0)
        /** A 10-band curve spread over the 31 bands (smooth in log-frequency) — for presets and switching modes. */
        fun to31(eq: List<Float>): List<Float> = FREQS31.map { hz ->
            val x = kotlin.math.ln(hz); val xs = FREQS.map { kotlin.math.ln(it.toDouble()) }
            when {
                x <= xs.first() -> eq.first()
                x >= xs.last() -> eq.last()
                else -> { val i = xs.indexOfLast { it <= x }; val t = ((x - xs[i]) / (xs[i + 1] - xs[i])).toFloat(); eq[i] + (eq[i + 1] - eq[i]) * t }
            }.let { Math.round(it * 2) / 2f }
        }
        fun parse(s: String?): DspCfg = try {
            val o = JSONObject(s ?: "")
            fun f(k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()
            DspCfg(o.optBoolean("on", true), o.optJSONArray("eq")?.let { a -> List(10) { a.optDouble(it, 0.0).toFloat() } } ?: List(10) { 0f },
                o.optString("corr"), f("corrAmt", 1f), f("bass", 0f), f("treble", 0f), f("vocal", 0f), f("width", 1f), f("surround", 0f),
                f("crossfeed", 0f), f("reverb", 0f), o.optString("room", "room"), f("leveler", 0f), f("gain", 0f),
                f("balance", 0f), o.optString("rg", "off"), f("rgPre", 0f), o.optBoolean("video", true),
                o.optInt("bands", 10).let { if (it == 31 || it == 0) it else 10 },
                o.optJSONArray("peq")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { f ->
                    Filt(f.optString("t", "P").firstOrNull() ?: 'P', f.optDouble("f", 1000.0).toFloat(), f.optDouble("g", 0.0).toFloat(), f.optDouble("q", 1.0).toFloat()) } } ?: emptyList(),
                o.optJSONArray("eq31")?.let { a -> List(31) { a.optDouble(it, 0.0).toFloat() } } ?: List(31) { 0f },
                o.optBoolean("hiRes", true), o.optBoolean("resample", true), f("preamp", 0f), o.optBoolean("autoHeadroom", false))
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
/** Filters for [c] at sample rate [sr] (also used by the UI to draw the response curve). */
private fun dspChain(c: DspCfg, sr: Double): Array<Biquad> {
    val l = mutableListOf<Biquad>()
    if (c.bands == 0) c.peq.forEach { (t, f, g, q) ->
        // cuts have no gain; peaks and shelves with no gain do nothing
        if (t == 'h' || t == 'l' || abs(g) > .05f) l += Biquad(t, f.toDouble(), g.toDouble(), q.toDouble().coerceIn(.1, 20.0), sr)
    }
    else if (c.bands == 31) c.eq31.forEachIndexed { i, g ->
        // third-octave peaking filters (Q 4.3); bands too close to the top of this sample rate are left out
        val hz = DspCfg.FREQS31[i]; if (abs(g) > .05f && hz < sr * .45) l += Biquad('P', hz, g.toDouble(), 4.3, sr)
    }
    else c.eq.forEachIndexed { i, g -> if (abs(g) > .05f) l += Biquad(if (i == 0) 'L' else if (i == 9) 'H' else 'P', DspCfg.FREQS[i].toDouble(), g.toDouble(), if (i == 0 || i == 9) .8 else 1.41, sr) }
    Headphones.of(c.corr)?.let { h -> if (c.corrAmt > .01f) h.filters.forEach { (t, f, g, q) -> l += Biquad(t, f.toDouble(), (g * c.corrAmt).toDouble(), q.toDouble(), sr) } }
    if (c.bass > .05f) l += Biquad('L', 90.0, c.bass.toDouble(), .7, sr)
    if (abs(c.treble) > .05f) l += Biquad('H', 9000.0, c.treble.toDouble(), .7, sr)
    return l.toTypedArray()
}

/**
 * Shared settings and status for the sound engine. Every player (music, video, gallery videos) gets its own
 * [DspProcessor] — they all follow the same [cfg], so the Sound screen tunes music and videos together.
 */
object Dsp {
    @Volatile var cfg = DspCfg(); private set
    fun set(c: DspCfg) { cfg = c }
    /** "Hold to compare": skips the effects while held. */
    @Volatile var bypass = false
    /** Video players: audio delay in ms (+ = sound later, − = sound earlier), for out-of-sync files — like VLC's. */
    @Volatile var avDelayMs = 0
    /** ReplayGain of the song playing now (dB, NaN = not tagged), read from its tags by the music player. */
    @Volatile var rgTrack = Float.NaN
    @Volatile var rgAlbum = Float.NaN

    // what the engine is doing (shown on the Sound screen, so you can see it's really running)
    @Volatile var format = ""; internal set
    @Volatile var seen = 0L; internal set          // bytes that passed through
    @Volatile var processed = 0L; internal set     // bytes that were processed (effects on)
    @Volatile var lastSeen = 0L; internal set
    @Volatile var source = ""; internal set        // "music" | "video"
    val running get() = System.currentTimeMillis() - lastSeen < 2000

    /** Overall frequency response (dB) of [c] at [freqs] — drawn as the curve above the sliders. */
    fun response(c: DspCfg, freqs: List<Double>): List<Double> { val f = dspChain(c, 48000.0); return freqs.map { hz -> f.sumOf { it.dbAt(hz, 48000.0) } } }

    /** Audio chain for a video player: surround → stereo downmix (so dialogue and effects work), then the engine. */
    fun videoProcessors(): Array<AudioProcessor> = arrayOf(Downmix(), DspProcessor(video = true))

    /** Reads ReplayGain tags (ID3 TXXX, Vorbis/FLAC comments, MP4 iTunes atoms) from the playing song's format. */
    fun readReplayGain(meta: androidx.media3.common.Metadata?) {
        var t = Float.NaN; var a = Float.NaN
        fun num(v: String?) = v?.trim()?.removeSuffix("dB")?.removeSuffix("db")?.trim()?.replace(',', '.')?.toFloatOrNull()
        fun take(k: String?, v: String?) {
            when (k?.trim()?.uppercase()) { "REPLAYGAIN_TRACK_GAIN" -> num(v)?.let { t = it }; "REPLAYGAIN_ALBUM_GAIN" -> num(v)?.let { a = it } }
        }
        if (meta != null) for (i in 0 until meta.length()) when (val e = meta.get(i)) {
            is androidx.media3.extractor.metadata.id3.TextInformationFrame -> take(e.description, e.values.firstOrNull())
            is androidx.media3.extractor.metadata.vorbis.VorbisComment -> take(e.key, e.value)
            is androidx.media3.extractor.metadata.id3.InternalFrame -> take(e.description, e.text)
        }
        rgTrack = t; rgAlbum = a
    }
}

/**
 * The effects themselves, working on 64-bit samples. One instance per player (filters keep state between buffers).
 * Used by [DspProcessor] (classic 16-bit chain) and by the hi-res output ([HiResSink]).
 */
class DspCore(private val video: Boolean) {
    private var built: DspCfg? = null
    private var builtRg = Float.NaN
    private var sr = 44100

    private var filters = emptyArray<Biquad>()
    private var vocalF: Biquad? = null
    private var sideHp: Biquad? = null
    private var xfL: Biquad? = null
    private var verb: Reverb? = null
    private var pre = 1.0
    private var post = 1.0
    private var balL = 1.0; private var balR = 1.0
    private val delay = FloatArray(16384); private var dPos = 0; private var dLen = 1
    private var env = 0.0

    fun configure(sampleRate: Int) { sr = sampleRate; built = null }
    fun reset() { built = null; limG = 1.0 }

    /** Whether effects run for the next buffer (false = pass the sound through untouched). */
    fun active(c: DspCfg = Dsp.cfg) = c.on && !Dsp.bypass && !(video && !c.video)

    /** Headroom: turn everything down by the biggest boost so loud songs don't distort. */
    private fun headroom(f: Array<Biquad>): Double {
        var peak = 0.0; var hz = 20.0
        while (hz < 20000) { peak = maxOf(peak, f.sumOf { it.dbAt(hz, sr.toDouble()) }); hz *= 1.06 }
        return peak
    }

    /** ReplayGain (dB) to apply now: music only, from the playing song's tags; untagged songs are left alone. */
    private fun rgNow(c: DspCfg): Float {
        if (video || c.rg == "off") return 0f
        val g = if (c.rg == "album" && !Dsp.rgAlbum.isNaN()) Dsp.rgAlbum else Dsp.rgTrack
        return if (g.isNaN()) 0f else g + c.rgPre
    }

    private fun build(c: DspCfg) {
        val f = dspChain(c, sr.toDouble())
        filters = f
        // Poweramp-style: the preamp sets the level and the limiter catches the peaks. "Auto headroom" (old behaviour)
        // instead turns everything down by part of the biggest EQ boost — cleaner but quieter.
        val boost = if (c.autoHeadroom) (headroom(f) + c.vocal * .5) * .55 else 0.0
        pre = 10.0.pow((c.preamp - boost) / 20)
        val rg = rgNow(c); builtRg = rg
        post = 10.0.pow((c.gain + rg) / 20.0) * (1 + c.leveler * 1.2) // leveler makeup gain (+ ReplayGain)
        // balance: turn the other side down (constant at the centre)
        balL = if (c.balance > 0) 1.0 - c.balance else 1.0; balR = if (c.balance < 0) 1.0 + c.balance else 1.0
        vocalF = if (c.vocal > .05f) Biquad('P', 2500.0, c.vocal.toDouble(), .8, sr.toDouble()) else null
        sideHp = Biquad('h', 180.0, 0.0, .7, sr.toDouble())
        xfL = if (c.crossfeed > .01f) Biquad('l', 700.0, 0.0, .7, sr.toDouble()) else null
        verb = if (c.reverb > .01f) Reverb(sr, c.room) else null
        dLen = (sr * .012).toInt().coerceIn(1, delay.size - 1); delay.fill(0f); dPos = 0
        built = c
    }

    /** Runs the effects on [n] frames in place ([stereo] false: only [L] is used and copied to [R]). Includes the preamp and the peak limiter. */
    fun process(L: DoubleArray, R: DoubleArray, n: Int, stereo: Boolean) {
        val c = Dsp.cfg
        if (built !== c || rgNow(c) != builtRg) build(c)
        val width = c.width.toDouble(); val sur = c.surround.toDouble(); val xf = c.crossfeed.toDouble() * .45
        val wet = c.reverb.toDouble() * 1.4; val lev = c.leveler.toDouble()
        val att = exp(-1.0 / (sr * .01)); val rel = exp(-1.0 / (sr * .25))
        val thr = 0.18; val ratio = 1 + lev * 4
        for (i in 0 until n) {
            var l = L[i] * pre
            var r = if (stereo) R[i] * pre else l
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
            verb?.let { v -> val mono = ((l + r) * .5).toFloat(); val vl = v.l(mono); val vr = v.r(mono); l = l * (1 - c.reverb * .35) + vl * wet; r = r * (1 - c.reverb * .35) + vr * wet }
            if (lev > .01) {
                val lvl = maxOf(abs(l), abs(r))
                env = if (lvl > env) att * env + (1 - att) * lvl else rel * env + (1 - rel) * lvl
                if (env > thr) { val g = (thr * (env / thr).pow(1 / ratio)) / env; l *= g; r *= g }
            }
            l *= post * balL; r *= post * balR
            // peak limiter (stereo-linked): instant attack, ~60 ms release, ceiling just under full scale
            val peak = maxOf(abs(l), abs(r))
            val target = if (peak > CEIL) CEIL / peak else 1.0
            limG = if (target < limG) target else limG + (target - limG) * limRel
            L[i] = soft(l * limG); R[i] = soft(r * limG)
        }
    }
    private var limG = 1.0
    private val limRel get() = 1 - exp(-1.0 / (sr * .06))
    private companion object { const val CEIL = 0.977 }   // −0.2 dBFS

    /** Safety net after the limiter: untouched below 0.98, rounds off anything above instead of clipping. */
    private fun soft(x: Double): Double { val a = abs(x); return if (a <= .98) x else (if (x < 0) -1 else 1) * (.98 + .02 * tanh((a - .98) / .02)) }
}

/** The engine as an audio processor for ExoPlayer's standard (16-bit) chain — used when hi-res output is off. */
class DspProcessor(private val video: Boolean = false) : BaseAudioProcessor() {
    private val core = DspCore(video)
    private var ch = 2
    private var enc = C.ENCODING_PCM_16BIT
    private var L = DoubleArray(0); private var R = DoubleArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        ch = inputAudioFormat.channelCount; enc = e; core.configure(inputAudioFormat.sampleRate)
        Dsp.format = "${inputAudioFormat.sampleRate / 1000.0} kHz · ${ch} ch · ${if (e == C.ENCODING_PCM_FLOAT) "32-bit float" else "16-bit"} (standard output)"
        return inputAudioFormat
    }
    override fun onFlush() { core.reset() }
    override fun onReset() { core.reset() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        Dsp.seen += size; Dsp.lastSeen = System.currentTimeMillis(); Dsp.source = if (video) "video" else "music"
        if (!core.active() || ch < 1) { out.put(inputBuffer); out.flip(); return }
        Dsp.processed += size
        val float = enc == C.ENCODING_PCM_FLOAT
        val bps = if (float) 4 else 2
        val frames = size / (bps * ch)
        val inB = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        out.order(ByteOrder.LITTLE_ENDIAN)
        if (L.size < frames) { L = DoubleArray(frames); R = DoubleArray(frames) }
        val stereo = ch >= 2
        val start = inB.position(); val stride = bps * ch
        for (i in 0 until frames) {
            val p = start + i * stride
            L[i] = if (float) inB.getFloat(p).toDouble() else inB.getShort(p) / 32768.0
            R[i] = if (!stereo) L[i] else if (float) inB.getFloat(p + 4).toDouble() else inB.getShort(p + 2) / 32768.0
        }
        core.process(L, R, frames, stereo)
        for (i in 0 until frames) {
            val p = start + i * stride
            fun wr(v: Double) { if (float) out.putFloat(v.toFloat()) else out.putShort((v * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
            wr(L[i]); if (stereo) wr(R[i])
            for (k in 2 until ch) { if (float) out.putFloat(inB.getFloat(p + k * 4)) else out.putShort(inB.getShort(p + k * 2)) } // extra channels pass through
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
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

/**
 * Folds 5.1 / 7.1 film sound down to stereo (ITU-style: centre and surrounds at -3 dB, LFE at -6 dB) so the
 * equaliser, dialogue enhancer and spatial effects work on it. Mono and stereo pass through untouched.
 * Channel order (Android): FL FR FC LFE BL BR [SL SR].
 */
class Downmix : BaseAudioProcessor() {
    private var ch = 2; private var float = false
    override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (f.channelCount <= 2 || !Dsp.cfg.on || !Dsp.cfg.video) return AudioProcessor.AudioFormat.NOT_SET
        if (f.encoding != C.ENCODING_PCM_16BIT && f.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        ch = f.channelCount; float = f.encoding == C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(f.sampleRate, 2, f.encoding)
    }
    override fun queueInput(input: ByteBuffer) {
        val bps = if (float) 4 else 2
        val frames = input.remaining() / (bps * ch); if (frames == 0) return
        val out = replaceOutputBuffer(frames * 2 * bps).order(ByteOrder.LITTLE_ENDIAN)
        val inB = input.order(ByteOrder.LITTLE_ENDIAN)
        val x = DoubleArray(ch)
        val k = .7071; val norm = 1.0 / (1 + k + k + (if (ch > 6) k else 0.0))   // keeps full-scale input from clipping
        for (n in 0 until frames) {
            for (i in 0 until ch) x[i] = if (float) inB.float.toDouble() else inB.short / 32768.0
            val c = x[2] * k; val lfe = if (ch > 3) x[3] * .5 else 0.0
            var l = x[0] + c + lfe; var r = x[1] + c + lfe
            if (ch > 4) { l += x[4] * k; r += x.getOrElse(5) { 0.0 } * k }
            if (ch > 6) { l += x[6] * k; r += x.getOrElse(7) { 0.0 } * k }
            l *= norm * 1.6; r *= norm * 1.6   // a little louder back: film mixes are quiet; the engine's limiter catches peaks
            if (float) { out.putFloat(l.toFloat()); out.putFloat(r.toFloat()) }
            else { out.putShort((l * 32767).toInt().coerceIn(-32768, 32767).toShort()); out.putShort((r * 32767).toInt().coerceIn(-32768, 32767).toShort()) }
        }
        input.position(input.limit()); out.flip()
    }
}
