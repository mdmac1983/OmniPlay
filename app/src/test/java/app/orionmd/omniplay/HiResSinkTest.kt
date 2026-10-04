package app.orionmd.omniplay

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** A stand-in for Android's audio output that only accepts some formats (like a picky device). */
private class FakeSink(val accepts: (Format) -> Boolean) : AudioSink {
    var format: Format? = null; var bytes = 0L; var configures = 0
    override fun setListener(listener: AudioSink.Listener) {}
    override fun supportsFormat(format: Format) = true
    override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
    override fun getCurrentPositionUs(sourceEnded: Boolean) = 0L
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) { format = inputFormat; configures++ }
    override fun play() {}
    override fun handleDiscontinuity() {}
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val f = format!!
        if (!accepts(f)) throw AudioSink.InitializationException(0, f.sampleRate, 0, 0, f, false, null)
        bytes += buffer.remaining(); buffer.position(buffer.limit()); return true
    }
    override fun playToEndOfStream() {}
    override fun isEnded() = true
    override fun hasPendingData() = false
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
    override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}
    override fun getSkipSilenceEnabled() = false
    override fun setAudioAttributes(audioAttributes: AudioAttributes) {}
    override fun getAudioAttributes(): AudioAttributes? = null
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun pause() {}
    override fun flush() {}
    override fun reset() {}
}

class HiResSinkTest {
    private val input = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(44100).build()

    private fun buffer(frames: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) { val v = (sin(2 * PI * 440 * i / 44100) * 12000).toInt().toShort(); b.putShort(v); b.putShort(v) }
        b.flip(); return b
    }

    /** Feeds one second of audio like the player does (re-sending a buffer the sink didn't take). */
    private fun play(sink: HiResSink) {
        sink.configure(input, 0, null)
        repeat(10) { k -> val b = buffer(4410); var tries = 0; while (!sink.handleBuffer(b, k * 100_000L, 1)) { tries++; assertTrue("stuck", tries < 10) } }
    }

    @Before fun setUp() { HiResSink.setMode(0); Dsp.set(DspCfg()) }

    @Test fun float_output_when_the_device_accepts_it() {
        val fake = FakeSink { true }; val sink = HiResSink(fake, false, 48000, null)
        play(sink)
        assertEquals(C.ENCODING_PCM_FLOAT, fake.format!!.pcmEncoding); assertEquals(48000, fake.format!!.sampleRate)
        assertTrue("got ${fake.bytes}", fake.bytes in 380_000L..390_000L)   // ~1 s of 48 kHz float stereo (8 bytes/frame)
    }

    @Test fun steps_down_to_16_bit_when_float_is_refused() {
        val fake = FakeSink { it.pcmEncoding != C.ENCODING_PCM_FLOAT }; val sink = HiResSink(fake, false, 48000, null)
        play(sink)
        assertEquals(1, HiResSink.mode)
        assertEquals(C.ENCODING_PCM_16BIT, fake.format!!.pcmEncoding); assertEquals(48000, fake.format!!.sampleRate)
        assertTrue("got ${fake.bytes}", fake.bytes > 170_000)                // nearly all of it reached the output
    }

    @Test fun falls_back_to_the_files_own_rate_then_standard_output() {
        // refuses anything it was not originally given (48 kHz, float)
        val fake = FakeSink { it.sampleRate == 44100 && it.pcmEncoding == C.ENCODING_PCM_16BIT && it === input }
        val sink = HiResSink(fake, false, 48000, null)
        play(sink)
        assertEquals(3, HiResSink.mode)
        assertTrue(fake.format === input)
        assertTrue("got ${fake.bytes}", fake.bytes >= 10 * 4410 * 4 - 4410 * 4)
    }
}
