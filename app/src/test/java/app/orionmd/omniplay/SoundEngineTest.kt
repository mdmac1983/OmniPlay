package app.orionmd.omniplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SoundEngineTest {
    private fun sine(hz: Double, rate: Int, n: Int, amp: Double = 0.5) = DoubleArray(n) { amp * sin(2 * PI * hz * it / rate) }

    private fun resample(x: DoubleArray, from: Int, to: Int, chunk: Int = x.size): DoubleArray {
        val r = Resampler(); r.setup(from, to)
        val out = ArrayList<Double>()
        var oL = DoubleArray(0); var oR = DoubleArray(0)
        var i = 0
        while (i < x.size) {
            val n = minOf(chunk, x.size - i)
            val a = x.copyOfRange(i, i + n)
            val k = r.process(a, a.copyOf(), n) { need -> if (oL.size < need) { oL = DoubleArray(need); oR = DoubleArray(need) }; oL to oR }
            for (j in 0 until k) { out += oL[j]; assertEquals("left and right differ", oL[j], oR[j], 1e-12) }
            i += n
        }
        return out.toDoubleArray()
    }

    /** Least-squares fit of a sine at [hz] over the steady middle part: (amplitude, residual in dB relative to the signal). */
    private fun fit(y: DoubleArray, hz: Double, rate: Int): Pair<Double, Double> {
        val a = y.size / 4; val b = y.size * 3 / 4
        var ss = 0.0; var cc = 0.0; var sc = 0.0; var ys = 0.0; var yc = 0.0
        for (i in a until b) { val sn = sin(2 * PI * hz * i / rate); val cs = kotlin.math.cos(2 * PI * hz * i / rate)
            ss += sn * sn; cc += cs * cs; sc += sn * cs; ys += y[i] * sn; yc += y[i] * cs }
        val det = ss * cc - sc * sc; val s = (ys * cc - yc * sc) / det; val c = (yc * ss - ys * sc) / det
        var err = 0.0; var pow = 0.0
        for (i in a until b) { val m = s * sin(2 * PI * hz * i / rate) + c * kotlin.math.cos(2 * PI * hz * i / rate); err += (y[i] - m) * (y[i] - m); pow += y[i] * y[i] }
        return sqrt(s * s + c * c) to 10 * log10(err / pow)
    }

    @Test fun upsample_44k_to_48k_is_clean() {
        val y = resample(sine(1000.0, 44100, 44100), 44100, 48000)
        assertTrue("length ${y.size}", abs(y.size - 48000) < 40)
        val (amp, noiseDb) = fit(y, 1000.0, 48000)
        assertEquals(0.5, amp, 0.0005)
        assertTrue("distortion+noise $noiseDb dB", noiseDb < -90)
    }

    @Test fun chunked_equals_one_shot() {
        val x = sine(997.0, 44100, 20000)
        val a = resample(x, 44100, 48000)
        val b = resample(x, 44100, 48000, chunk = 333)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-9)
    }

    @Test fun downsample_96k_keeps_audio_and_blocks_aliases() {
        val (passAmp, _) = fit(resample(sine(18000.0, 96000, 96000), 96000, 48000), 18000.0, 48000)
        assertTrue("18 kHz level $passAmp", abs(20 * log10(passAmp / 0.5)) < 0.2)
        // 30 kHz can't exist at 48 kHz: it must be removed, not folded down to 18 kHz
        val y = resample(sine(30000.0, 96000, 96000), 96000, 48000)
        val rms = sqrt(y.copyOfRange(y.size / 4, y.size * 3 / 4).sumOf { it * it } / (y.size / 2))
        val db = 20 * log10(rms / (0.5 / sqrt(2.0)))
        assertTrue("alias $db dB", db < -90)
    }

    @Test fun flat_engine_is_transparent() {
        Dsp.set(DspCfg(on = true))
        val core = DspCore(video = false); core.configure(48000)
        val x = sine(440.0, 48000, 4800, 0.7); val l = x.copyOf(); val r = x.copyOf()
        core.process(l, r, x.size, true)
        for (i in x.indices) { assertEquals(x[i], l[i], 1e-9); assertEquals(x[i], r[i], 1e-9) }
    }

    @Test fun balance_and_replaygain() {
        Dsp.set(DspCfg(on = true, balance = 0.5f, rg = "track", rgPre = 0f)); Dsp.rgTrack = -6f
        val core = DspCore(video = false); core.configure(48000)
        val l = DoubleArray(10) { 0.4 }; val r = DoubleArray(10) { 0.4 }
        core.process(l, r, 10, true)
        val g = Math.pow(10.0, -6 / 20.0)
        assertEquals(0.4 * g * 0.5, l[5], 1e-9); assertEquals(0.4 * g, r[5], 1e-9)
        Dsp.rgTrack = Float.NaN
    }

    @Test fun eq31_curve_from_10_band_preset() {
        val p = DspCfg.PRESETS.first { it.first == "Bass boost" }.second
        val e = DspCfg.to31(p)
        assertEquals(31, e.size); assertEquals(6f, e[0], 0.01f); assertEquals(0f, e[30], 0.01f)
        val c = DspCfg(bands = 31, eq31 = e)
        val resp = Dsp.response(c, listOf(31.5, 1000.0, 16000.0))
        assertTrue("bass ${resp[0]}", resp[0] > 4.5); assertTrue("mid ${resp[1]}", abs(resp[1]) < 0.6); assertTrue("treble ${resp[2]}", abs(resp[2]) < 0.6)
    }

    @Test fun parametric_eq() {
        val c = DspCfg(bands = 0, peq = listOf(Filt('P', 1000f, 6f, 1f), Filt('h', 80f, 0f, .7f), Filt('H', 10000f, -4f, .7f)))
        val r = Dsp.response(c, listOf(20.0, 1000.0, 300.0, 18000.0))
        assertEquals(6.0, r[1], 0.1)                       // peak lands where it's set
        assertTrue("low cut ${r[0]}", r[0] < -15)          // 80 Hz low cut removes deep bass
        assertTrue("300 Hz ${r[2]}", abs(r[2]) < 1.5)      // little effect away from the filters
        assertTrue("shelf ${r[3]}", r[3] < -3.5)
        // saved and loaded intact (profiles store this JSON)
        val back = DspCfg.parse(c.json().toString())
        assertEquals(0, back.bands); assertEquals(c.peq, back.peq)
    }
}
