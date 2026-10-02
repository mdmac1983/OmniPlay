package app.orionmd.omniplay

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Music visualizer data. The music player's audio passes through here on its way to the speaker (a "tee" in ExoPlayer's
 * audio chain — no microphone permission needed). Every 512 samples a 1024-point FFT turns it into 48 frequency bands
 * plus a short waveform, stamped with its position in the song. The screen asks for the frame matching what's audible
 * right now, so the picture stays in sync with the sound even though audio is decoded a little ahead.
 */
@OptIn(UnstableApi::class)
object Visual : TeeAudioProcessor.AudioBufferSink {
    const val BANDS = 48
    const val WAVE = 128
    private const val N = 1024
    private const val HOP = 512
    private const val FRAMES = 256

    /** The visualizer is on screen: analysis only runs while something has asked for a frame in the last half second. */
    @Volatile private var lastRead = 0L
    private val active get() = System.nanoTime() - lastRead < 500_000_000L
    /** Song position (ms) of the first sample after the last flush — set by the player when a song starts or seeks. */
    @Volatile var basePosMs = 0L

    @Volatile private var rate = 44100
    @Volatile private var channels = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT
    private var samples = 0L
    private val win = FloatArray(N)
    private var wpos = 0
    private var sinceHop = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val hann = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private var edges = IntArray(BANDS + 1)
    private val rev = IntArray(N).also { r -> val bits = 10; for (i in 0 until N) { var x = i; var y = 0; repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y } }
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }

    private val lock = Any()
    private val fPos = LongArray(FRAMES)
    private val fBands = Array(FRAMES) { FloatArray(BANDS) }
    private val fWave = Array(FRAMES) { FloatArray(WAVE) }
    private val fLevel = FloatArray(FRAMES)
    private var head = -1
    private var count = 0

    init { makeEdges(44100) }

    /** Band edges on a log scale from 40 Hz to 16 kHz (at least one FFT bin each). */
    private fun makeEdges(sr: Int) {
        val lo = 40.0; val hi = minOf(16000.0, sr / 2.0 - 1)
        val e = IntArray(BANDS + 1)
        for (k in 0..BANDS) {
            val f = lo * (hi / lo).pow(k.toDouble() / BANDS)
            e[k] = (f * N / sr).toInt().coerceIn(1, N / 2 - 1)
        }
        for (k in 1..BANDS) if (e[k] <= e[k - 1]) e[k] = minOf(N / 2, e[k - 1] + 1)
        edges = e
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        rate = if (sampleRateHz > 0) sampleRateHz else 44100; channels = maxOf(1, channelCount); this.encoding = encoding
        makeEdges(rate)
        samples = 0; wpos = 0; sinceHop = 0; win.fill(0f)
        synchronized(lock) { head = -1; count = 0 }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val enc = encoding; val ch = channels
        val bps = when (enc) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT -> 4; C.ENCODING_PCM_24BIT -> 3; C.ENCODING_PCM_32BIT -> 4; C.ENCODING_PCM_8BIT -> 1; else -> return }
        val frame = bps * ch
        var p = b.position(); val end = b.limit()
        while (p + frame <= end) {
            var sum = 0f
            for (c in 0 until ch) {
                val i = p + c * bps
                sum += when (enc) {
                    C.ENCODING_PCM_16BIT -> b.getShort(i) / 32768f
                    C.ENCODING_PCM_FLOAT -> b.getFloat(i)
                    C.ENCODING_PCM_32BIT -> b.getInt(i) / 2147483648f
                    C.ENCODING_PCM_24BIT -> ((b.get(i).toInt() and 0xff) or ((b.get(i + 1).toInt() and 0xff) shl 8) or (b.get(i + 2).toInt() shl 16)) / 8388608f
                    else -> ((b.get(i).toInt() and 0xff) - 128) / 128f
                }
            }
            p += frame
            win[wpos] = sum / ch; wpos = (wpos + 1) and (N - 1); samples++
            if (++sinceHop >= HOP) { sinceHop = 0; if (active) analyse() }
        }
    }

    private fun analyse() {
        // window in time order, oldest first
        var sq = 0f
        for (k in 0 until N) { val v = win[(wpos + k) and (N - 1)]; sq += v * v; re[rev[k]] = v * hann[k]; im[rev[k]] = 0f }
        // iterative radix-2 FFT
        var size = 2
        while (size <= N) {
            val half = size / 2; val step = N / size
            var start = 0
            while (start < N) {
                for (j in 0 until half) {
                    val wr = cosT[j * step]; val wi = -sinT[j * step]
                    val a = start + j; val bI = a + half
                    val tr = re[bI] * wr - im[bI] * wi; val ti = re[bI] * wi + im[bI] * wr
                    re[bI] = re[a] - tr; im[bI] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
        val slot: Int
        synchronized(lock) { head = (head + 1) % FRAMES; if (count < FRAMES) count++; slot = head }
        val bands = fBands[slot]; val e = edges
        for (k in 0 until BANDS) {
            var s = 0f; val a = e[k]; val z = maxOf(a + 1, e[k + 1])
            for (bin in a until z) s += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val mag = s / (z - a) * 4f / N
            val db = 20f * log10(mag + 1e-7f)
            bands[k] = (((db + 62f) / 58f) + k * 0.12f / BANDS).coerceIn(0f, 1f)
        }
        val w = fWave[slot]
        for (k in 0 until WAVE) w[k] = win[(wpos + N - WAVE * 4 + k * 4) and (N - 1)].coerceIn(-1f, 1f)
        fLevel[slot] = sqrt(sq / N).coerceIn(0f, 1f)
        fPos[slot] = basePosMs + (samples - N / 2) * 1000L / rate
    }

    /** Copies the frame for song position [posMs] into [bands] / [wave]; returns its loudness (0..1), or -1 when there's none. */
    fun frameAt(posMs: Long, bands: FloatArray, wave: FloatArray): Float = synchronized(lock) {
        lastRead = System.nanoTime()
        if (count == 0) return -1f
        var best = -1; var bestPos = Long.MIN_VALUE; var oldest = -1; var oldestPos = Long.MAX_VALUE
        for (n in 0 until count) {
            val i = (head - n + FRAMES) % FRAMES
            val p = fPos[i]
            if (p <= posMs && p > bestPos) { best = i; bestPos = p }
            if (p < oldestPos) { oldest = i; oldestPos = p }
        }
        val i = if (best >= 0) best else oldest
        if (posMs - fPos[i] > 1500) return -1f   // nothing recent (paused a while, or a new song hasn't started)
        System.arraycopy(fBands[i], 0, bands, 0, BANDS)
        System.arraycopy(fWave[i], 0, wave, 0, WAVE)
        fLevel[i]
    }

    /** Renderers for the music player with the visualizer tee in the audio chain. */
    class Renderers(ctx: Context) : DefaultRenderersFactory(ctx) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(Dsp, TeeAudioProcessor(Visual))).build()
    }
}
