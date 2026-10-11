package app.orionmd.omniplay

import org.junit.Test
import kotlin.math.*

/** Diagnostic: how loud / how wide the engine's output is for a loud, mostly-centred master. Prints a table. */
class LoudnessDiag {
    private val sr = 48000
    private fun music(n: Int, sideAmt: Double): Pair<DoubleArray, DoubleArray> {
        val rnd = java.util.Random(3)
        val L = DoubleArray(n); val R = DoubleArray(n)
        var lp = 0.0; var hp = 0.0; var prev = 0.0; var sn = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val beat = (t * 2) % 1.0
            val kick = sin(2 * PI * 55 * t) * exp(-beat * 9) * 0.9
            val bass = sin(2 * PI * 110 * t + sin(2 * PI * 0.5 * t)) * 0.35
            val w = rnd.nextGaussian(); lp += (w - lp) * 0.08; hp = 0.9 * (hp + w - prev); prev = w
            val vocal = lp * 0.9 * (0.6 + 0.4 * sin(2 * PI * 3 * t))
            val hats = hp * 0.08 * exp(-((t * 8) % 1.0) * 20)
            sn += (rnd.nextGaussian() - sn) * 0.2
            val m = kick + bass + vocal + hats
            L[i] = m + sn * sideAmt; R[i] = m - sn * sideAmt
        }
        val pk = max(L.maxOf { abs(it) }, R.maxOf { abs(it) }); for (i in 0 until n) { L[i] *= .98 / pk; R[i] *= .98 / pk }
        return L to R
    }
    private fun rmsDb(x: DoubleArray, from: Int) = 10 * log10(x.drop(from).sumOf { it * it } / (x.size - from))
    private fun msDb(L: DoubleArray, R: DoubleArray, from: Int): Pair<Double, Double> {
        var m = 0.0; var s = 0.0; for (i in from until L.size) { m += ((L[i] + R[i]) / 2).pow(2); s += ((L[i] - R[i]) / 2).pow(2) }
        return 10 * log10(m) to 10 * log10(s)
    }
    /** Level of the vocal range (≈1–4 kHz, two band-pass filters) — what the ear hears as "loud". */
    private fun vocalDb(L: DoubleArray, from: Int): Double {
        var a = 0.0; var y1 = 0.0; var y2 = 0.0; var z1 = 0.0; var z2 = 0.0
        val w = 2 * PI * 2000 / sr; val al = sin(w) / (2 * 0.7); val b0 = al; val a0 = 1 + al; val a1 = -2 * cos(w); val a2 = 1 - al
        var x1 = 0.0; var x2 = 0.0; var u1 = 0.0; var u2 = 0.0
        for (i in L.indices) {
            val x = L[i]; val y = (b0 * x - b0 * x2 - a1 * y1 - a2 * y2) / a0; x2 = x1; x1 = x; y2 = y1; y1 = y
            val z = (b0 * y - b0 * u2 - a1 * z1 - a2 * z2) / a0; u2 = u1; u1 = y; z2 = z1; z1 = z
            if (i >= from) a += z * z
        }
        return 10 * log10(a / (L.size - from))
    }
    private fun run(name: String, c: DspCfg, side: Double = .12) {
        Dsp.set(c); val core = DspCore(false); core.configure(sr)
        val (L, R) = music(sr * 6, side); val (iM, iS) = msDb(L, R, sr)
        val inRms = (rmsDb(L, sr) + rmsDb(R, sr)) / 2; val inV = vocalDb(L, sr)
        val bl = 1024; var i = 0
        while (i < L.size) { val n = min(bl, L.size - i); val a = L.copyOfRange(i, i + n); val b = R.copyOfRange(i, i + n); core.process(a, b, n, true); a.copyInto(L, i); b.copyInto(R, i); i += n }
        val out = (rmsDb(L, sr) + rmsDb(R, sr)) / 2; val (oM, oS) = msDb(L, R, sr)
        println("%-38s overall %+6.1f dB   vocals %+6.1f dB   width (side vs centre) %+5.1f dB".format(name, out - inRms, vocalDb(L, sr) - inV, (oS - oM) - (iS - iM)))
    }
    @Test fun table() {
        val f = DspCfg()
        run("flat", f)
        run("preamp +6", f.copy(preamp = 6f))
        run("10-band bass boost preset", f.copy(eq = DspCfg.PRESETS.first { it.first == "Bass boost" }.second))
        run("31-band: +8 dB at 40-100 Hz", f.copy(bands = 31, eq31 = DspCfg.FREQS31.map { if (it in 35.0..110.0) 8f else 0f }))
        run("31-band: smile (+6 lows/highs)", f.copy(bands = 31, eq31 = DspCfg.FREQS31.map { if (it < 150 || it > 6000) 6f else 0f }))
        run("parametric: low shelf +8", f.copy(bands = 0, peq = listOf(Filt('L', 105f, 8f, .7f))))
        run("bass +8 (Sound slider)", f.copy(bass = 8f))
        run("leveler 0.5", f.copy(leveler = .5f))
        run("leveler 1.0", f.copy(leveler = 1f))
        run("reverb 0.5", f.copy(reverb = .5f))
        run("width 2.0", f.copy(width = 2f))
        run("surround 1.0", f.copy(surround = 1f))
        run("width 2 + surround 1", f.copy(width = 2f, surround = 1f))
        run("surround 1.0 (mono-ish song)", f.copy(surround = 1f), side = .02)
        run("crossfeed 1.0", f.copy(crossfeed = 1f))
    }
}
