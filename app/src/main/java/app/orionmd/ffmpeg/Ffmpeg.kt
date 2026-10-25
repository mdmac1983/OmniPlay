package app.orionmd.ffmpeg

import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Software decoding with FFmpeg (an LGPL build containing only decoders — see licences in the app) for what a phone's
 * hardware can't play: 10-bit H.264 ("Hi10P") and 10-bit HEVC (common in MKV files), MPEG-2 / MPEG-4 video, and AC-3,
 * E-AC-3, DTS, TrueHD and ALAC audio. Used automatically when the hardware decoder can't handle a file, or for
 * everything with "Software first" in the decoder setting.
 */
object FfLib {
    val available: Boolean by lazy {
        try { listOf("avutil", "swresample", "swscale", "avcodec", "mhffmpeg").forEach { System.loadLibrary(it) }; true } catch (_: Throwable) { false }
    }
    val version: String get() = if (available) try { nVersion() } catch (_: Throwable) { "" } else ""
    @JvmStatic private external fun nVersion(): String

    fun videoCodec(mime: String?): String? = when (mime) {
        MimeTypes.VIDEO_H264 -> "h264"; MimeTypes.VIDEO_H265 -> "hevc"; MimeTypes.VIDEO_MPEG2 -> "mpeg2video"; MimeTypes.VIDEO_MP4V -> "mpeg4"; else -> null
    }
    fun audioCodec(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "ac3"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"; MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dca"
        MimeTypes.AUDIO_TRUEHD -> "truehd"; MimeTypes.AUDIO_ALAC -> "alac"; else -> null
    }
    /** Codec setup data in the form FFmpeg expects (H.264 / HEVC parameter sets with start codes, ALAC cookie…). */
    fun extradata(f: Format): ByteArray? {
        if (f.initializationData.isEmpty()) return null
        return when (f.sampleMimeType) {
            MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265 -> f.initializationData.fold(ByteArray(0)) { acc, b -> acc + b }
            else -> f.initializationData[0]
        }
    }
}

class FfDecoderException(msg: String) : DecoderException(msg)

// ============================================================================ video

@OptIn(UnstableApi::class)
class FfVideoDecoder(format: Format, threads: Int) :
    SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfDecoderException>(arrayOfNulls(8), arrayOfNulls(8)) {
    private var h: Long
    private val info = IntArray(5)
    @Volatile var outputMode = C.VIDEO_OUTPUT_MODE_NONE

    init {
        val name = FfLib.videoCodec(format.sampleMimeType) ?: throw FfDecoderException("unsupported ${format.sampleMimeType}")
        h = nInit(name, FfLib.extradata(format), threads)
        if (h == 0L) throw FfDecoderException("FFmpeg couldn't open $name")
        setInitialInputBufferSize(if (format.maxInputSize > 0) format.maxInputSize else 1 shl 20)
    }

    override fun getName() = "ffmpeg-video"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    override fun createOutputBuffer() = VideoDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = FfDecoderException("FFmpeg: ${error.message}")

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: VideoDecoderOutputBuffer, reset: Boolean): FfDecoderException? {
        if (reset) nFlush(h)
        val data = inputBuffer.data ?: return null
        val size = data.limit()
        if (size > 0) { val r = nSend(h, data, size, inputBuffer.timeUs); if (r < 0) return FfDecoderException("FFmpeg can't decode this video ($r)") }
        val got = nReceive(h, info)
        if (got < 0) return FfDecoderException("FFmpeg decode error ($got)")
        if (got == 0) { outputBuffer.shouldBeSkipped = true; return null }   // the decoder is still filling its reorder buffer
        val w = info[0]; val ht = info[1]
        val pts = (info[2].toLong() shl 32) or (info[3].toLong() and 0xffffffffL)
        outputBuffer.init(pts, outputMode, null)
        val yStride = (w + 15) and 15.inv(); val uvStride = (((w + 1) / 2) + 15) and 15.inv()
        val cs = when (info[4]) { 1 -> VideoDecoderOutputBuffer.COLORSPACE_BT601; 2 -> VideoDecoderOutputBuffer.COLORSPACE_BT709; 3 -> VideoDecoderOutputBuffer.COLORSPACE_BT2020; else -> VideoDecoderOutputBuffer.COLORSPACE_UNKNOWN }
        if (!outputBuffer.initForYuvFrame(w, ht, yStride, uvStride, cs)) { outputBuffer.shouldBeSkipped = true; return null }
        if (nCopy(h, outputBuffer.data!!, yStride, uvStride) < 0) return FfDecoderException("FFmpeg couldn't convert the picture")
        return null
    }

    fun render(b: VideoDecoderOutputBuffer, surface: Surface): Boolean =
        b.data != null && b.yuvStrides != null && nRender(h, surface, b.data!!, b.width, b.height, b.yuvStrides!![0], b.yuvStrides!![1]) == 0

    override fun release() { super.release(); if (h != 0L) { nRelease(h); h = 0 } }

    private external fun nInit(name: String, extra: ByteArray?, threads: Int): Long
    private external fun nSend(h: Long, buf: java.nio.ByteBuffer, size: Int, timeUs: Long): Int
    private external fun nReceive(h: Long, info: IntArray): Int
    private external fun nCopy(h: Long, dst: java.nio.ByteBuffer, yStride: Int, uvStride: Int): Int
    private external fun nFlush(h: Long)
    private external fun nRelease(h: Long)
    private external fun nRender(h: Long, surface: Surface, data: java.nio.ByteBuffer, w: Int, ht: Int, yStride: Int, uvStride: Int): Int
}

@OptIn(UnstableApi::class)
class FfVideoRenderer(joiningMs: Long, handler: Handler?, listener: VideoRendererEventListener?, maxDropped: Int) :
    DecoderVideoRenderer(joiningMs, handler, listener, maxDropped) {
    private var decoder: FfVideoDecoder? = null
    override fun getName() = "FfVideoRenderer"
    override fun supportsFormat(format: Format): Int =
        if (FfLib.videoCodec(format.sampleMimeType) == null) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        else if (!FfLib.available || format.cryptoType != C.CRYPTO_TYPE_NONE) RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        else RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_NOT_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfVideoDecoder =
        FfVideoDecoder(format, Runtime.getRuntime().availableProcessors().coerceIn(1, 8)).also { decoder = it }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val d = decoder ?: throw FfDecoderException("no decoder")
        if (!d.render(outputBuffer, surface)) { outputBuffer.release(); throw FfDecoderException("couldn't draw the frame") }
        outputBuffer.release()
    }
    override fun setDecoderOutputMode(outputMode: Int) { decoder?.outputMode = outputMode }
}

// ============================================================================ audio

@OptIn(UnstableApi::class)
class FfAudioDecoder(format: Format) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfDecoderException>(arrayOfNulls(16), arrayOfNulls(16)) {
    private var h: Long
    private val info = IntArray(2)
    var channels = format.channelCount; private set
    var sampleRate = format.sampleRate; private set

    init {
        val name = FfLib.audioCodec(format.sampleMimeType) ?: throw FfDecoderException("unsupported ${format.sampleMimeType}")
        h = nInit(name, FfLib.extradata(format), format.sampleRate, format.channelCount)
        if (h == 0L) throw FfDecoderException("FFmpeg couldn't open $name")
        setInitialInputBufferSize(if (format.maxInputSize > 0) format.maxInputSize else 1 shl 16)
    }

    override fun getName() = "ffmpeg-audio"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = FfDecoderException("FFmpeg: ${error.message}")

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): FfDecoderException? {
        if (reset) nFlush(h)
        val data = inputBuffer.data ?: return null
        var cap = 1 shl 18   // grows for big TrueHD / 7.1 packets
        while (true) {
            val out = outputBuffer.init(inputBuffer.timeUs, cap)
            val n = nDecode(h, data, data.limit(), out, cap)
            if (n == -1000 && cap < (1 shl 24)) { cap *= 4; continue }
            if (n < 0) return FfDecoderException("FFmpeg can't decode this audio ($n)")
            nInfo(h, info); channels = info[0]; sampleRate = info[1]
            out.position(0); out.limit(n)
            if (n == 0) outputBuffer.shouldBeSkipped = true
            return null
        }
    }
    override fun release() { super.release(); if (h != 0L) { nRelease(h); h = 0 } }

    private external fun nInit(name: String, extra: ByteArray?, rate: Int, channels: Int): Long
    private external fun nDecode(h: Long, input: java.nio.ByteBuffer, size: Int, out: java.nio.ByteBuffer, cap: Int): Int
    private external fun nInfo(h: Long, info: IntArray)
    private external fun nFlush(h: Long)
    private external fun nRelease(h: Long)
}

@OptIn(UnstableApi::class)
class FfAudioRenderer(handler: Handler?, listener: AudioRendererEventListener?, sink: AudioSink) :
    DecoderAudioRenderer<FfAudioDecoder>(handler, listener, sink) {
    override fun getName() = "FfAudioRenderer"
    override fun supportsFormatInternal(format: Format): Int {
        if (FfLib.audioCodec(format.sampleMimeType) == null) return C.FORMAT_UNSUPPORTED_TYPE
        if (!FfLib.available || format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        val ch = if (format.channelCount > 0) format.channelCount else 2; val rate = if (format.sampleRate > 0) format.sampleRate else 48000
        return if (sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_FLOAT, ch, rate)) || sinkSupportsFormat(Util.getPcmFormat(C.ENCODING_PCM_16BIT, ch, rate)))
            C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }
    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?) = FfAudioDecoder(format)
    override fun getOutputFormat(decoder: FfAudioDecoder): Format = Util.getPcmFormat(C.ENCODING_PCM_FLOAT, decoder.channels, decoder.sampleRate)
}
