package app.orionmd.omniplay

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.*

/** No sound setting may make the engine go silent or produce NaN (random settings, both 44.1 and 48 kHz, mono and stereo). */
class SilenceFuzz {
    @Test fun neverSilent() {
        val rnd = java.util.Random(7)
        val bad = mutableListOf<String>()
        repeat(400) { k ->
            fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
            val bands = listOf(10, 31, 0)[rnd.nextInt(3)]
            val c = DspCfg(on = true, eq = List(10) { f(-12f, 12f) }, corr = listOf("", "koss_porta", "jvc_gumy")[rnd.nextInt(3)], corrAmt = f(0f, 1f),
                bass = f(0f, 12f), treble = f(-6f, 9f), vocal = f(0f, 8f), width = f(0f, 2f), surround = f(0f, 1f), crossfeed = f(0f, 1f),
                reverb = f(0f, 1f), room = listOf("room", "hall", "stadium")[rnd.nextInt(3)], leveler = f(0f, 1f), gain = f(-6f, 6f), balance = f(-1f, 1f),
                bands = bands, peq = List(rnd.nextInt(8)) { Filt("PLH"[rnd.nextInt(3)], f(20f, 20000f), f(-12f, 12f), f(.1f, 10f)) },
                eq31 = List(31) { f(-12f, 12f) }, preamp = f(-12f, 15f), autoHeadroom = rnd.nextBoolean())
            Dsp.set(c)
            val sr = if (k % 2 == 0) 48000 else 44100; val stereo = k % 5 != 0
            val core = DspCore(false); core.configure(sr)
            val n = sr; val L = DoubleArray(n) { .5 * sin(2 * PI * 220 * it / sr) + .2 * sin(2 * PI * 3000 * it / sr) }; val R = DoubleArray(n) { .4 * sin(2 * PI * 330 * it / sr) }
            var i = 0; while (i < n) { val m = min(777, n - i); val a = L.copyOfRange(i, i + m); val b = R.copyOfRange(i, i + m); core.process(a, b, m, stereo); a.copyInto(L, i); b.copyInto(R, i); i += m }
            val nan = L.any { it.isNaN() } || R.any { it.isNaN() }
            val rms = sqrt((n / 2 until n).sumOf { L[it] * L[it] + R[it] * R[it] } / n)
            if (nan || rms < 1e-3 && abs(c.balance) < .99f) bad += "#$k nan=$nan rms=$rms sr=$sr stereo=$stereo $c"
        }
        bad.take(5).forEach { println(it) }
        assertTrue("${bad.size} silent / NaN cases", bad.isEmpty())
    }
}
