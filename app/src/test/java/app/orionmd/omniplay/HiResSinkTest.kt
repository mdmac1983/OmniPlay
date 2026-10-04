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

    @Test fun switches_to_standard_output_when_hi_res_fails_completely() {
        // the hi-res output's sink refuses everything; the standard one (what 2.1 used) works
        val bad = FakeSink { false }; val good = FakeSink { true }
        val sink = SwitchingSink(HiResSink(bad, false, 48000, null)) { good }
        sink.setVolume(.5f)
        sink.configure(input, 0, null); sink.play()
        repeat(10) { k -> val b = buffer(4410); var tries = 0; while (!sink.handleBuffer(b, k * 100_000L, 1)) { tries++; assertTrue("stuck", tries < 10) } }
        assertEquals(3, HiResSink.mode)
        assertTrue(good.format === input)                                    // the player's own format, untouched
        assertEquals(10L * 4410 * 4, good.bytes)                             // every buffer reached the standard output
    }

    @Test fun switches_when_setup_itself_fails() {
        val bad = object : AudioSink by FakeSink({ true }) {
            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) { throw AudioSink.ConfigurationException("no", inputFormat) }
        }
        val good = FakeSink { true }
        val sink = SwitchingSink(bad) { good }
        sink.configure(input, 0, null)
        assertTrue(sink.handleBuffer(buffer(4410), 0, 1))
        assertEquals(4410L * 4, good.bytes)
    }
}
