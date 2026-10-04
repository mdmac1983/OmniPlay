package app.orionmd.omniplay

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-quality sample-rate converter (windowed-sinc, Kaiser β 9, 16 zero crossings per side, 4096 interpolated
 * phases). Converts to the phone's native output rate so Android's own (faster, rougher) resampler isn't used.
 * Works on 64-bit samples; keeps its history between buffers so there are no clicks at buffer edges.
 */
class Resampler {
    var inRate = 0; private set
    var outRate = 0; private set
    private var step = 1.0              // input samples per output sample
    private var half = 0                // taps each side
    private val phases = 4096
    private var table = FloatArray(0)   // (phases + 1) rows × 2·half taps
    private var hL = DoubleArray(0); private var hR = DoubleArray(0); private var hn = 0   // pending input
    private var pos = 0.0               // position of the next output sample in the pending input
    val passthrough get() = inRate == outRate

    fun setup(inputRate: Int, outputRate: Int) {
        if (inputRate == inRate && outputRate == outRate) return
        inRate = inputRate; outRate = outputRate
        if (passthrough) { reset(); return }
        step = inRate.toDouble() / outRate
        val cut = min(1.0, 1 / step) * 0.97            // cutoff, as a fraction of the input Nyquist (anti-alias margin)
        half = ceil(16 / min(1.0, 1 / step)).toInt()   // more taps when going down, so the filter stays as steep
        val taps = 2 * half
        table = FloatArray((phases + 1) * taps)
        for (p in 0..phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            for (k in 0 until taps) {
                val x = (k - half + 1) - frac            // distance from the output point, in input samples
                val h = cut * sinc(cut * x) * kaiser(x / half)
                table[p * taps + k] = h.toFloat(); sum += h
            }
            for (k in 0 until taps) table[p * taps + k] = (table[p * taps + k] / sum).toFloat()  // unity gain at DC
        }
        reset()
    }

    fun reset() { hn = 0; hL = DoubleArray(0); hR = DoubleArray(0); pos = 0.0; if (!passthrough && half > 0) { prime() } }
    /** Starts with silence before the first sample so the very first output lines up with it. */
    private fun prime() { ensure(half); for (i in 0 until half - 1) { hL[i] = 0.0; hR[i] = 0.0 }; hn = half - 1; pos = (half - 1).toDouble() }

    private fun ensure(n: Int) { if (hL.size < n) { hL = hL.copyOf(maxOf(n, hL.size * 2)); hR = hR.copyOf(hL.size) } }

    /** Converts [n] input frames; returns the number of output frames written into [oL]/[oR] (grown as needed via [grow]). */
    fun process(iL: DoubleArray, iR: DoubleArray, n: Int, out: (Int) -> Pair<DoubleArray, DoubleArray>): Int {
        ensure(hn + n)
        System.arraycopy(iL, 0, hL, hn, n); System.arraycopy(iR, 0, hR, hn, n); hn += n
        val taps = 2 * half
        val maxOut = ((hn - pos) / step).toInt() + 2
        val (oL, oR) = out(maxOut)
        var o = 0
        while (true) {
            val ip = floor(pos).toInt()
            if (ip + half >= hn) break                       // need more input for the right-hand taps
            val fp = (pos - ip) * phases
            val p0 = fp.toInt(); val t = fp - p0
            val r0 = p0 * taps; val r1 = r0 + taps
            var sl = 0.0; var sr = 0.0
            val base = ip - half + 1
            for (k in 0 until taps) {
                val c = table[r0 + k] + (table[r1 + k] - table[r0 + k]) * t
                sl += hL[base + k] * c; sr += hR[base + k] * c
            }
            oL[o] = sl; oR[o] = sr; o++
            pos += step
        }
        // drop input no longer needed (keep the left-hand taps of the next output)
        val keep = floor(pos).toInt() - half + 1
        if (keep > 0) {
            System.arraycopy(hL, keep, hL, 0, hn - keep); System.arraycopy(hR, keep, hR, 0, hn - keep)
            hn -= keep; pos -= keep
        }
        return o
    }

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
    private fun kaiser(x: Double): Double { if (x <= -1 || x >= 1) return 0.0; val b = 9.0; return i0(b * sqrt(1 - x * x)) / i0(b) }
    private fun i0(x: Double): Double { var s = 1.0; var t = 1.0; var k = 1; while (k < 40) { t *= (x / (2 * k)) * (x / (2 * k)); s += t; k++ }; return s }
}

/**
 * OmniPlay's hi-res output — the part that makes the sound engine work like Poweramp's instead of ExoPlayer's
 * standard chain:
 *  - the decoder hands over its full-resolution samples (24-bit / 32-bit float, not cut down to 16-bit first),
 *  - everything (downmix, [DspCore] effects) runs on 64-bit samples,
 *  - [Resampler] converts to the phone's native output rate, so Android's mixer doesn't resample again,
 *  - the result goes to the speaker/DAC as 32-bit float.
 * Anything that isn't plain PCM (e.g. Dolby passthrough to a TV) goes straight through.
 */
@OptIn(UnstableApi::class)
class HiResSink(
    private val inner: AudioSink,
    private val video: Boolean,
    private val nativeRate: Int,
    private val tee: TeeAudioProcessor.AudioBufferSink?,
) : ForwardingAudioSink(inner) {
    private val core = DspCore(video)
    private val rs = Resampler()
    private var bypass = true
    private var inEnc = C.ENCODING_PCM_16BIT; private var inCh = 2; private var inRate = 44100; private var outRate = 48000
    private var trim = 0                                   // encoder-delay frames still to drop (gapless)
    private var pending: ByteBuffer? = null; private var pendingPts = 0L
    private var L = DoubleArray(0); private var R = DoubleArray(0)
    private var oL = DoubleArray(0); private var oR = DoubleArray(0)
    private var teeRate = 0
    private var lastGain = Dvc.gain
    private var delayFrames = 0                             // audio inserted (+) / dropped (−) for the audio delay
    private var playing = false

    private fun bytesPerSample(e: Int) = when (e) { C.ENCODING_PCM_8BIT -> 1; C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4; else -> 0 }

    private var lastFormat: Format? = null; private var lastSize = 0; private var lastChannels: IntArray? = null
    private val outFloat get() = mode == 0

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        lastFormat = inputFormat; lastSize = specifiedBufferSize; lastChannels = outputChannels
        val pcm = MimeTypes.AUDIO_RAW == inputFormat.sampleMimeType && bytesPerSample(inputFormat.pcmEncoding) > 0 && inputFormat.channelCount > 0
        if (!pcm || mode >= 3) {
            bypass = true
            if (pcm) Dsp.format = "${inputFormat.sampleRate / 1000.0} kHz · ${inputFormat.channelCount} ch (standard output — this device refused the hi-res formats)"
            super.configure(inputFormat, specifiedBufferSize, outputChannels); return
        }
        bypass = false
        inEnc = inputFormat.pcmEncoding; inCh = inputFormat.channelCount; inRate = inputFormat.sampleRate
        val c = Dsp.cfg
        outRate = if (c.resample && mode < 2 && nativeRate in 8000..384000) nativeRate else inRate
        rs.setup(inRate, outRate)
        core.configure(outRate)
        trim = inputFormat.encoderDelay
        if (tee != null) { tee.flush(outRate, 2, C.ENCODING_PCM_FLOAT); teeRate = outRate }   // the visualizer counts from each song's start
        val bits = when (inEnc) { C.ENCODING_PCM_8BIT -> "8-bit"; C.ENCODING_PCM_16BIT -> "16-bit"; C.ENCODING_PCM_24BIT -> "24-bit"; C.ENCODING_PCM_32BIT -> "32-bit"; else -> "32-bit float" }
        Dsp.format = "${inRate / 1000.0} kHz $bits · $inCh ch → " + (if (outRate != inRate) "${outRate / 1000.0} kHz " else "") +
            (if (outFloat) "32-bit float (hi-res output)" else "16-bit dithered (hi-res engine; this device's output is 16-bit)")
        try {
            super.configure(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(if (outFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT)
                .setChannelCount(2).setSampleRate(outRate).build(), 0, null)
        } catch (e: AudioSink.ConfigurationException) {
            if (mode >= 1) throw e
            setMode(1); configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val startPos = buffer.position()
        try {
            if (bypass) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            pending?.let { p -> if (!super.handleBuffer(p, pendingPts, 1)) return false; pending = null }
            if (!buffer.hasRemaining()) return true
            var pts = presentationTimeUs
            val out = convert(buffer) { dropped -> pts += dropped * 1_000_000L / inRate }
            pts += delayFrames * 1_000_000L / outRate
            if (out != null && out.hasRemaining()) {
                if (tee != null && outFloat) tee.handleBuffer(out.duplicate().order(ByteOrder.nativeOrder()))
                if (!super.handleBuffer(out, pts, 1)) { pending = out; pendingPts = pts }
            }
            return true
        } catch (e: AudioSink.InitializationException) {
            // this device's audio output refused 32-bit float: try 16-bit; if that fails too, SwitchingSink takes over
            if (mode >= 1 || lastFormat == null) throw e
            stepDown()
            buffer.position(startPos)          // not used yet — the player hands it over again
            return false
        }
    }

    private fun stepDown() {
        setMode(mode + 1)
        pending = null; rs.reset(); core.reset()
        try { super.flush() } catch (_: Exception) {}
        configure(lastFormat!!, lastSize, lastChannels)
    }

    /** PCM in (any bit depth / channel count) → float stereo out at [outRate], with the effects applied. */
    private fun convert(buf: ByteBuffer, onTrim: (Int) -> Unit): ByteBuffer? {
        val bps = bytesPerSample(inEnc); val stride = bps * inCh
        val b = buf.order(ByteOrder.LITTLE_ENDIAN)
        var frames = b.remaining() / stride
        var start = b.position()
        Dsp.seen += b.remaining(); Dsp.lastSeen = System.currentTimeMillis(); Dsp.source = if (video) "video" else "music"
        if (trim > 0) { val d = min(trim, frames); trim -= d; frames -= d; start += d * stride; onTrim(d) }
        b.position(b.limit())
        if (frames <= 0) return null
        if (L.size < frames) { L = DoubleArray(frames); R = DoubleArray(frames) }
        fun s(p: Int): Double = when (inEnc) {
            C.ENCODING_PCM_16BIT -> b.getShort(p) / 32768.0
            C.ENCODING_PCM_FLOAT -> b.getFloat(p).toDouble()
            C.ENCODING_PCM_24BIT -> ((b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)) / 8388608.0
            C.ENCODING_PCM_32BIT -> b.getInt(p) / 2147483648.0
            else -> ((b.get(p).toInt() and 0xFF) - 128) / 128.0   // 8-bit unsigned
        }
        val k = .7071
        val norm = if (inCh > 2) 1.6 / (1 + k + k + (if (inCh > 6) k else 0.0)) else 1.0
        for (i in 0 until frames) {
            val p = start + i * stride
            when {
                inCh == 1 -> { L[i] = s(p); R[i] = L[i] }
                inCh == 2 -> { L[i] = s(p); R[i] = s(p + bps) }
                else -> {   // 5.1 / 7.1 → stereo (FL FR FC LFE BL BR [SL SR])
                    val c = s(p + 2 * bps) * k; val lfe = if (inCh > 3) s(p + 3 * bps) * .5 else 0.0
                    var l = s(p) + c + lfe; var r = s(p + bps) + c + lfe
                    if (inCh > 4) { l += s(p + 4 * bps) * k; if (inCh > 5) r += s(p + 5 * bps) * k }
                    if (inCh > 7) { l += s(p + 6 * bps) * k; r += s(p + 7 * bps) * k }
                    L[i] = l * norm; R[i] = r * norm
                }
            }
        }
        // effects at the output rate (so filters are designed for what's actually played)
        var n = frames; var xL = L; var xR = R
        if (!rs.passthrough) {
            n = rs.process(L, R, frames) { need -> if (oL.size < need) { oL = DoubleArray(need); oR = DoubleArray(need) }; oL to oR }
            xL = oL; xR = oR
        }
        if (core.active()) { Dsp.processed += frames.toLong() * stride; core.process(xL, xR, n, true) }
        // Direct Volume Control: the volume is applied here, in 64-bit (ramped across the buffer so changes don't click)
        val g1 = Dvc.gain
        if (g1 != 1.0 || lastGain != 1.0) {
            val g0 = lastGain
            for (i in 0 until n) { val g = g0 + (g1 - g0) * (i + 1) / n; xL[i] *= g; xR[i] *= g }
            lastGain = g1
        }
        // audio delay (videos): insert silence to make the sound later, drop sound to make it earlier
        if (video) {
            val target = (Dsp.avDelayMs.toLong() * outRate / 1000).toInt()
            val diff = target - delayFrames
            if (diff > 0) {
                val nL = DoubleArray(n + diff); val nR = DoubleArray(n + diff)
                System.arraycopy(xL, 0, nL, diff, n); System.arraycopy(xR, 0, nR, diff, n)
                xL = nL; xR = nR; n += diff; delayFrames += diff
            } else if (diff < 0) {
                val d = min(-diff, n)
                if (d < n) { System.arraycopy(xL, d, xL, 0, n - d); System.arraycopy(xR, d, xR, 0, n - d) }
                n -= d; delayFrames -= d
            }
            if (n <= 0) return null
        }
        // a fresh buffer each time: the inner sink may keep writing from it after accepting it
        if (outFloat) {
            val out = ByteBuffer.allocate(n * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until n) { out.putFloat(xL[i].toFloat()); out.putFloat(xR[i].toFloat()) }
            out.flip(); return out
        }
        // 16-bit output with TPDF dither (keeps quiet detail as low-level noise instead of distortion)
        val out = ByteBuffer.allocate(n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n) { out.putShort(q16(xL[i])); out.putShort(q16(xR[i])) }
        out.flip(); return out
    }

    private var rnd = 0x2545F491L
    private fun r01(): Double { rnd = rnd xor (rnd shl 13); rnd = rnd xor (rnd ushr 7); rnd = rnd xor (rnd shl 17); return (rnd ushr 11).toDouble() / (1L shl 53).toDouble() }
    private fun q16(v: Double): Short = Math.round(v * 32767 + (r01() - r01())).toInt().coerceIn(-32768, 32767).toShort()

    override fun playToEndOfStream() {
        pending?.let { p -> if (!super.handleBuffer(p, pendingPts, 1)) return; pending = null }
        super.playToEndOfStream()
    }
    override fun isEnded() = pending == null && super.isEnded()
    override fun hasPendingData() = pending != null || super.hasPendingData()
    // DVC needs to know when Media Hub is actually playing (system volume goes back down when it isn't)
    override fun play() { super.play(); if (!playing) { playing = true; Dvc.sinkPlaying() } }
    override fun pause() { super.pause(); if (playing) { playing = false; Dvc.sinkStopped() } }
    override fun release() { if (playing) { playing = false; Dvc.sinkStopped() }; super.release() }

    override fun flush() { pending = null; trim = 0; delayFrames = 0; rs.reset(); core.reset(); if (tee != null && teeRate > 0) tee.flush(teeRate, 2, C.ENCODING_PCM_FLOAT); super.flush() }
    override fun reset() { if (playing) { playing = false; Dvc.sinkStopped() }; pending = null; trim = 0; delayFrames = 0; rs.reset(); core.reset(); super.reset() }

    companion object {
        /** The phone's native output sample rate (usually 48 kHz). */
        fun nativeRate(ctx: Context): Int = try {
            (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        } catch (_: Exception) { 48000 }

        /** Whether this run of the app uses the hi-res output — fixed at start-up (the setting applies after a restart). */
        @Volatile var active = true

        /**
         * Output format this device accepts (learned, then remembered): 0 = 32-bit float at the native rate,
         * 1 = 16-bit dithered at the native rate, 2 = 16-bit at the file's rate, 3 = standard output (no effects).
         */
        @Volatile var mode = 0; private set
        private var prefs: android.content.SharedPreferences? = null
        fun initMode(ctx: Context) { prefs = ctx.getSharedPreferences("hires", Context.MODE_PRIVATE); mode = prefs!!.getInt("mode", 0).coerceIn(0, 3) }
        internal fun setMode(m: Int) {
            mode = m.coerceIn(0, 3); prefs?.edit()?.putInt("mode", mode)?.apply()
            if (mode >= 3) Dvc.setEnabled(false)   // no engine volume any more: give the volume back to Android
        }
        /** Start again from the best format (e.g. after turning the hi-res output off and on). */
        fun resetMode() { setMode(0); prefs?.edit()?.remove("err")?.apply() }
        /** Why this device fell back (shown on the Sound screen). */
        val lastError get() = prefs?.getString("err", "") ?: ""
        internal fun noteError(e: Throwable) { prefs?.edit()?.putString("err", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160))?.apply() }

        /** The audio output for a player: hi-res (default, with an automatic way back) or the standard chain. */
        fun build(ctx: Context, video: Boolean, tee: TeeAudioProcessor.AudioBufferSink?, enableFloat: Boolean, trackParams: Boolean): AudioSink =
            if (active && mode < 3) SwitchingSink(HiResSink(
                DefaultAudioSink.Builder(ctx).setEnableFloatOutput(true).setEnableAudioTrackPlaybackParams(true).build(),
                video, nativeRate(ctx), tee)) { standard(ctx, video, tee) }
            else standard(ctx, video, tee)

        /** ExoPlayer's standard 16-bit output with the engine as an audio processor (how 2.1 and earlier played). */
        fun standard(ctx: Context, video: Boolean, tee: TeeAudioProcessor.AudioBufferSink?): AudioSink =
            DefaultAudioSink.Builder(ctx).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
                .setAudioProcessors(if (video) Dsp.videoProcessors() else if (tee != null) arrayOf(DspProcessor(), TeeAudioProcessor(tee)) else arrayOf(DspProcessor())).build()
    }
}

/**
 * Runs the hi-res output, and if it fails in any way on this device (the output refuses the format, an unexpected
 * error…), swaps in the standard output on the spot — same song, same position, effects still on — and remembers
 * that for next time. Everything the player set on the sink (listener, volume, speed, session…) is carried over.
 */
@OptIn(UnstableApi::class)
class SwitchingSink(private var cur: AudioSink, private val makeStandard: () -> AudioSink) : AudioSink {
    private var switched = false
    private var listener: AudioSink.Listener? = null
    private var playerId: androidx.media3.exoplayer.analytics.PlayerId? = null
    private var clock: androidx.media3.common.util.Clock? = null
    private var params: androidx.media3.common.PlaybackParameters? = null
    private var skipSilence: Boolean? = null
    private var attrs: androidx.media3.common.AudioAttributes? = null
    private var session: Int? = null
    private var aux: androidx.media3.common.AuxEffectInfo? = null
    private var device: android.media.AudioDeviceInfo? = null; private var deviceSet = false
    private var streamOffset: Long? = null
    private var tunneling: Boolean? = null
    private var volume: Float? = null
    private var fmt: Format? = null; private var size = 0; private var channels: IntArray? = null
    private var playing = false

    private fun switchOver(e: Throwable) {
        if (switched) throw e
        switched = true
        HiResSink.noteError(e); HiResSink.setMode(3)
        try { cur.reset(); cur.release() } catch (_: Throwable) {}
        val s = makeStandard()
        listener?.let { s.setListener(it) }; playerId?.let { s.setPlayerId(it) }; clock?.let { s.setClock(it) }
        attrs?.let { s.setAudioAttributes(it) }; session?.let { s.setAudioSessionId(it) }; aux?.let { s.setAuxEffectInfo(it) }
        if (deviceSet) s.setPreferredDevice(device); streamOffset?.let { s.setOutputStreamOffsetUs(it) }
        if (tunneling == true) s.enableTunnelingV21(); params?.let { s.setPlaybackParameters(it) }; skipSilence?.let { s.setSkipSilenceEnabled(it) }
        volume?.let { s.setVolume(it) }
        cur = s
        fmt?.let { s.configure(it, size, channels) }
        if (playing) s.play()
        Dsp.format = "Standard output (hi-res output isn't supported on this device: ${e.javaClass.simpleName})"
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        fmt = inputFormat; size = specifiedBufferSize; channels = outputChannels
        try { cur.configure(inputFormat, specifiedBufferSize, outputChannels) } catch (e: Exception) { switchOver(e) }
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val pos = buffer.position()
        return try { cur.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
        catch (e: Exception) { switchOver(e); buffer.position(pos); false }   // the player hands the same buffer over again
    }
    override fun playToEndOfStream() { try { cur.playToEndOfStream() } catch (e: Exception) { switchOver(e); cur.playToEndOfStream() } }
    override fun play() { playing = true; try { cur.play() } catch (e: Exception) { switchOver(e) } }
    override fun pause() { playing = false; cur.pause() }

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener; cur.setListener(listener) }
    override fun setPlayerId(playerId: androidx.media3.exoplayer.analytics.PlayerId?) { this.playerId = playerId; cur.setPlayerId(playerId) }
    override fun setClock(clock: androidx.media3.common.util.Clock) { this.clock = clock; cur.setClock(clock) }
    override fun supportsFormat(format: Format) = cur.supportsFormat(format)
    override fun getFormatSupport(format: Format) = cur.getFormatSupport(format)
    override fun getFormatOffloadSupport(format: Format): androidx.media3.exoplayer.audio.AudioOffloadSupport = cur.getFormatOffloadSupport(format)
    override fun getCurrentPositionUs(sourceEnded: Boolean) = cur.getCurrentPositionUs(sourceEnded)
    override fun handleDiscontinuity() = cur.handleDiscontinuity()
    override fun isEnded() = cur.isEnded()
    override fun hasPendingData() = cur.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters) { params = playbackParameters; cur.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters() = cur.playbackParameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { skipSilence = skipSilenceEnabled; cur.setSkipSilenceEnabled(skipSilenceEnabled) }
    override fun getSkipSilenceEnabled() = cur.skipSilenceEnabled
    override fun setAudioAttributes(audioAttributes: androidx.media3.common.AudioAttributes) { attrs = audioAttributes; cur.setAudioAttributes(audioAttributes) }
    override fun getAudioAttributes(): androidx.media3.common.AudioAttributes? = cur.audioAttributes
    override fun setAudioSessionId(audioSessionId: Int) { session = audioSessionId; cur.setAudioSessionId(audioSessionId) }
    override fun setAuxEffectInfo(auxEffectInfo: androidx.media3.common.AuxEffectInfo) { aux = auxEffectInfo; cur.setAuxEffectInfo(auxEffectInfo) }
    override fun setPreferredDevice(audioDeviceInfo: android.media.AudioDeviceInfo?) { device = audioDeviceInfo; deviceSet = true; cur.setPreferredDevice(audioDeviceInfo) }
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { streamOffset = outputStreamOffsetUs; cur.setOutputStreamOffsetUs(outputStreamOffsetUs) }
    override fun enableTunnelingV21() { tunneling = true; cur.enableTunnelingV21() }
    override fun disableTunneling() { tunneling = false; cur.disableTunneling() }
    override fun setOffloadMode(offloadMode: Int) = cur.setOffloadMode(offloadMode)
    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = cur.setOffloadDelayPadding(delayInFrames, paddingInFrames)
    override fun setVolume(volume: Float) { this.volume = volume; cur.setVolume(volume) }
    override fun flush() = cur.flush()
    override fun reset() = cur.reset()
    override fun release() = cur.release()
}

/** Renderers using OmniPlay's audio output ([HiResSink.build]). */
@OptIn(UnstableApi::class)
class OmniRenderers(ctx: Context, private val video: Boolean, private val tee: TeeAudioProcessor.AudioBufferSink? = null, decoder: String = "auto") : DefaultRenderersFactory(ctx) {
    init {
        // like VLC's hardware-acceleration setting: prefer the phone's hardware decoders, or software ones (slower but
        // they play some files hardware chokes on). Either way, the other kind is still tried if the first fails.
        setEnableDecoderFallback(true)
        if (decoder != "auto") setMediaCodecSelector { mime, secure, tunneling ->
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
                .sortedBy { if (decoder == "sw") (if (it.softwareOnly) 0 else 1) else (if (it.hardwareAccelerated) 0 else 1) }
        }
    }
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        HiResSink.build(context, video, tee, enableFloatOutput, enableAudioTrackPlaybackParams)
}
