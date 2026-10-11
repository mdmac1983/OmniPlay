package app.orionmd.omniplay

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * One piece of a movie: a photo (shown for [photoMs]) or a stretch of a video ([startMs]–[endMs]).
 * [filter] overrides the movie's filter for this clip (null = same as the movie).
 */
data class Clip(val item: GItem, val startMs: Long = 0, val endMs: Long = 0, val photoMs: Long = 3000, val caption: String = "",
                val filter: String? = null, val id: Long = nextClipId()) {
    val lenMs get() = if (item.video) (endMs - startMs).coerceAtLeast(300) else photoMs
}
private var clipIds = 0L
private fun nextClipId() = ++clipIds

/** Colour filters: a 3×4 colour matrix (rows r, g, b: three weights then an offset, 0..1 scale). */
object MovieFx {
    val FILTERS = listOf("none" to "Original", "vivid" to "Vivid", "warm" to "Warm", "cool" to "Cool", "golden" to "Golden hour", "fade" to "Faded",
        "vintage" to "Vintage", "sepia" to "Sepia", "bw" to "Black & white", "noir" to "Noir", "teal" to "Teal & orange", "invert" to "Negative")
    private fun id() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
    /** a · b (apply b first, then a) */
    private fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(12)
        for (i in 0..2) { for (j in 0..2) r[i * 4 + j] = (0..2).sumOf { k -> (a[i * 4 + k] * b[k * 4 + j]).toDouble() }.toFloat()
            r[i * 4 + 3] = (0..2).sumOf { k -> (a[i * 4 + k] * b[k * 4 + 3]).toDouble() }.toFloat() + a[i * 4 + 3] }
        return r
    }
    private fun sat(s: Float): FloatArray { val lr = .2126f * (1 - s); val lg = .7152f * (1 - s); val lb = .0722f * (1 - s)
        return floatArrayOf(lr + s, lg, lb, 0f, lr, lg + s, lb, 0f, lr, lg, lb + s, 0f) }
    private fun contrast(c: Float): FloatArray { val o = .5f * (1 - c); return floatArrayOf(c, 0f, 0f, o, 0f, c, 0f, o, 0f, 0f, c, o) }
    private fun bright(b: Float) = floatArrayOf(1f, 0f, 0f, b, 0f, 1f, 0f, b, 0f, 0f, 1f, b)
    private fun gains(r: Float, g: Float, b: Float, o: Float = 0f) = floatArrayOf(r, 0f, 0f, o, 0f, g, 0f, o, 0f, 0f, b, o)
    private fun preset(f: String): FloatArray = when (f) {
        "vivid" -> mul(contrast(1.12f), sat(1.45f))
        "warm" -> gains(1.1f, 1.02f, .88f)
        "cool" -> gains(.9f, 1f, 1.12f)
        "golden" -> mul(gains(1.12f, 1.03f, .8f, .02f), sat(1.15f))
        "fade" -> mul(gains(.84f, .84f, .84f, .09f), sat(.75f))
        "vintage" -> mul(gains(.9f, .88f, .8f, .07f), floatArrayOf(.6f + .4f * .393f, .4f * .769f, .4f * .189f, 0f, .4f * .349f, .6f + .4f * .686f, .4f * .168f, 0f, .4f * .272f, .4f * .534f, .6f + .4f * .131f, 0f))
        "sepia" -> floatArrayOf(.393f, .769f, .189f, 0f, .349f, .686f, .168f, 0f, .272f, .534f, .131f, 0f)
        "bw" -> sat(0f)
        "noir" -> mul(contrast(1.45f), sat(0f))
        "teal" -> mul(contrast(1.08f), floatArrayOf(1.1f, .05f, -.1f, .02f, 0f, 1.02f, 0f, 0f, -.05f, .1f, 1.05f, .04f))
        "invert" -> floatArrayOf(-1f, 0f, 0f, 1f, 0f, -1f, 0f, 1f, 0f, 0f, -1f, 1f)
        else -> id()
    }
    /** The whole look: filter, then brightness / contrast / saturation (each -1..1, 0 = unchanged). */
    fun matrix(filter: String, brightness: Float, contrast: Float, saturation: Float): FloatArray =
        mul(bright(brightness * .25f), mul(contrast(1f + contrast * .6f), mul(sat(1f + saturation), preset(filter))))
    fun isIdentity(m: FloatArray) = m.indices.all { abs(m[it] - id()[it]) < 1e-4f }
    /** For the preview (Compose ColorMatrix: 4×5, offsets in 0..255). */
    fun compose(m: FloatArray) = floatArrayOf(m[0], m[1], m[2], 0f, m[3] * 255, m[4], m[5], m[6], 0f, m[7] * 255, m[8], m[9], m[10], 0f, m[11] * 255, 0f, 0f, 0f, 1f, 0f)
    /** For the export (media3: 4×4, column-major, applied to (r, g, b, 1)). */
    fun gl(m: FloatArray) = floatArrayOf(m[0], m[4], m[8], 0f, m[1], m[5], m[9], 0f, m[2], m[6], m[10], 0f, m[3], m[7], m[11], 1f)
}

/** Everything about a movie / reel being made. */
data class Reel(
    val clips: List<Clip> = emptyList(),
    /** "9:16" (Reel / Story), "16:9" (Movie), "1:1" (Square), "4:5" (Portrait post) */
    val format: String = "9:16",
    /** true = fill the frame (crops edges); false = fit the whole picture (black bars) */
    val fill: Boolean = true,
    /** "none" | "fade" (dip to black between clips) */
    val transition: String = "fade",
    /** slow zoom on photos */
    val kenBurns: Boolean = true,
    val title: String = "",
    val music: Item? = null,
    val musicStartMs: Long = 0,
    val musicVol: Float = 0.8f,
    val clipVol: Float = 1f,
    /** picture size: the short side in pixels (480, 720, 1080, 2160) */
    val res: Int = 1080,
    val fps: Int = 30,
    /** "low" | "medium" | "high": how much detail is kept (less = smaller file) */
    val quality: String = "medium",
    // look
    val filter: String = "none",
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val vignette: Boolean = false,
    val soft: Boolean = false,
    val mirror: Boolean = false,
) {
    val totalMs get() = clips.sumOf { it.lenMs }
    fun size(): Pair<Int, Int> {
        // even numbers (video encoders need them)
        fun ev(x: Float) = (x.toInt() / 2) * 2
        val r = res.toFloat()
        return when (format) { "16:9" -> ev(r * 16 / 9) to res; "1:1" -> res to res; "4:5" -> res to ev(r * 5 / 4); else -> res to ev(r * 16 / 9) }
    }
    /** Video bitrate for the chosen size, frame rate and quality. */
    fun bitrate(): Int { val (w, h) = size(); val bpp = when (quality) { "low" -> .045f; "high" -> .13f; else -> .08f }
        return (w.toLong() * h * fps * bpp).toInt().coerceIn(400_000, 60_000_000) }
    /** About how big the file will be. */
    fun estimateBytes(): Long = (bitrate() + 128_000L) * totalMs / 8000
    fun look(c: Clip) = MovieFx.matrix(c.filter ?: filter, brightness, contrast, saturation)
}

/**
 * Movies & Reels: joins photos and video clips into one MP4 — trimmed clips, photo slideshows with a slow zoom,
 * fade transitions, captions, a title and background music — using Android's media3 Transformer (hardware video
 * encoder). Everything happens on the phone; the result goes to Movies/OmniPlay.
 */
object MovieMaker {

    /** Builds and saves the movie. [progress] gets 0..1. Returns the saved video's URI, or throws with a readable message. */
    suspend fun export(ctx: Context, st: AppState, reel: Reel, progress: (Float) -> Unit): Uri {
        require(reel.clips.isNotEmpty()) { "Add some photos or videos first" }
        val dir = File(ctx.cacheDir, "reel").apply { deleteRecursively(); mkdirs() }
        val (w, h) = reel.size()

        // photos → upright JPEGs no bigger than the output (decoding 50 MP originals on the GPU fails on many phones)
        progress(0f)
        val photoFiles = withContext(Dispatchers.IO) {
            reel.clips.mapIndexedNotNull { i, c ->
                if (c.item.video) null else {
                    val b = PhotoOps.load(ctx, c.item, maxOf(w, h))?.let { PhotoOps.rotate(it, Gallery.rotation(st, c.item.uri)) }
                        ?: throw IllegalStateException("Couldn't open \"${c.item.name}\"")
                    val f = File(dir, "p$i.jpg"); f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    i to f
                }
            }.toMap()
        }

        val fadeUs = 450_000L
        val vig = if (reel.vignette) vignette(w, h) else null   // one picture shared by every clip
        val items = reel.clips.mapIndexed { i, c ->
            val lenUs = c.lenMs * 1000
            val video = mutableListOf<androidx.media3.common.Effect>()
            video += Presentation.createForWidthAndHeight(w, h, if (reel.fill) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT)
            if (!c.item.video && reel.kenBurns) video += KenBurns(lenUs, zoomIn = i % 2 == 0)
            if (c.item.video && reel.fps < 60) video += androidx.media3.effect.FrameDropEffect.createDefaultFrameDropEffect(reel.fps.toFloat())
            if (reel.mirror) video += androidx.media3.effect.ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
            val look = reel.look(c)
            if (!MovieFx.isIdentity(look)) video += Look(MovieFx.gl(look))
            if (reel.soft) video += androidx.media3.effect.GaussianBlur(2.2f)
            if (vig != null) video += OverlayEffect(ImmutableList.of(androidx.media3.effect.BitmapOverlay.createStaticBitmapOverlay(vig)))
            if (reel.transition == "fade") video += Fade(lenUs, fadeUs, fadeIn = true, fadeOut = true)
            else if (i == 0 || i == reel.clips.lastIndex) video += Fade(lenUs, fadeUs, fadeIn = i == 0, fadeOut = i == reel.clips.lastIndex)
            val texts = mutableListOf<androidx.media3.effect.TextureOverlay>()
            if (i == 0 && reel.title.isNotBlank()) texts += text(reel.title, 0f, 0f, 2.2f, w)
            if (c.caption.isNotBlank()) texts += text(c.caption, 0f, -0.78f, 1.3f, w)
            if (texts.isNotEmpty()) video += OverlayEffect(ImmutableList.copyOf(texts))
            val audio = mutableListOf<AudioProcessor>()
            if (c.item.video && reel.clipVol < 0.99f) audio += Volume(reel.clipVol)

            val mi = if (c.item.video) MediaItem.Builder().setUri(c.item.uri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
            else MediaItem.Builder().setUri(Uri.fromFile(photoFiles.getValue(i))).setMimeType(MimeTypes.IMAGE_JPEG).setImageDurationMs(c.photoMs).build()
            EditedMediaItem.Builder(mi).apply {
                if (!c.item.video) { setDurationUs(lenUs); setFrameRate(reel.fps) }
                setEffects(Effects(audio, video))
            }.build()
        }
        val sequences = mutableListOf(EditedMediaItemSequence(items))
        reel.music?.let { m ->
            val mi = MediaItem.Builder().setUri(m.u)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(reel.musicStartMs).build()).build()
            val em = EditedMediaItem.Builder(mi).setRemoveVideo(true)
                .setEffects(Effects(if (reel.musicVol < 0.99f) listOf<AudioProcessor>(Volume(reel.musicVol)) else emptyList(), emptyList())).build()
            sequences += EditedMediaItemSequence(listOf(em), /* isLooping = */ true)
        }
        val composition = Composition.Builder(sequences).experimentalSetForceAudioTrack(true).build()
        val out = File(dir, "movie.mp4")
        withContext(Dispatchers.Main) { run(ctx, composition, out, reel.bitrate(), progress) }

        return withContext(Dispatchers.IO) {
            val name = "Movie_${PhotoOps.stamp()}.mp4"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Media Hub")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: throw IllegalStateException("Couldn't save to Movies")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> out.inputStream().use { it.copyTo(o) } } ?: throw IllegalStateException("Couldn't save to Movies")
            dir.deleteRecursively()
            uri
        }
    }

    /** Runs the Transformer on the main thread, reporting progress; cancelling the coroutine cancels the export. */
    private suspend fun run(ctx: Context, composition: Composition, out: File, bitrate: Int, progress: (Float) -> Unit) = coroutineScope {
        val main = Handler(Looper.getMainLooper())
        var tf: Transformer? = null
        val poll = launch { val ph = ProgressHolder(); while (true) { delay(300); tf?.let { if (it.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) progress(ph.progress / 100f) } } }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val t = Transformer.Builder(ctx)
                    .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    // the chosen quality: a lower bitrate makes a smaller file (the phone falls back if it can't do the size)
                    .setEncoderFactory(androidx.media3.transformer.DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(androidx.media3.transformer.VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true).build())
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (cont.isActive) cont.resume(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException(readable(exportException))))
                        }
                    }).build()
                tf = t
                cont.invokeOnCancellation { main.post { t.cancel() } }
                t.start(composition, out.path)
            }
        } finally { poll.cancel() }
        progress(1f)
    }

    private fun readable(e: ExportException) = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_DECODER_INIT_FAILED -> "One of the videos uses a format this phone can't decode"
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED -> "This phone's video encoder refused the size — try Square or Movie (16:9)"
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, ExportException.ERROR_CODE_IO_NO_PERMISSION -> "A clip or the music file couldn't be opened"
        else -> "Export failed (${e.errorCodeName})"
    }

    private fun text(s: String, x: Float, y: Float, maxScale: Float, frameW: Int): TextOverlay {
        // the overlay is drawn at ~100 px text; shrink long lines so they stay inside the frame (~55 px per character)
        val scale = minOf(maxScale, 0.9f * frameW / ((s.trim().length + 2) * 55f)).coerceAtLeast(0.5f)
        val sp = SpannableString(" ${s.trim()} ")
        sp.setSpan(ForegroundColorSpan(Color.WHITE), 0, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(BackgroundColorSpan(0x88000000.toInt()), 0, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextOverlay.createStaticTextOverlay(sp, OverlaySettings.Builder().setBackgroundFrameAnchor(x, y).setScale(scale, scale).build())
    }

    /** A colour look (filter + brightness / contrast / saturation) as one colour matrix. */
    private class Look(val m: FloatArray) : RgbMatrix { override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean) = m }

    /** Darkened corners, the size of the frame. */
    private fun vignette(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val r = kotlin.math.hypot(w / 2f, h / 2f)
        android.graphics.Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(w / 2f, h / 2f, r, intArrayOf(0, 0, 0xA0000000.toInt()), floatArrayOf(0f, .55f, 1f), android.graphics.Shader.TileMode.CLAMP) })
        return b
    }

    /** Fades to/from black at the clip's ends. Times are measured from the clip's first frame. */
    private class Fade(val lenUs: Long, val fadeUs: Long, val fadeIn: Boolean, val fadeOut: Boolean) : RgbMatrix {
        private var first = Long.MIN_VALUE
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            if (first == Long.MIN_VALUE) first = presentationTimeUs
            val t = presentationTimeUs - first
            var a = 1f
            if (fadeIn) a = minOf(a, t.toFloat() / fadeUs)
            if (fadeOut) a = minOf(a, (lenUs - t).toFloat() / fadeUs)
            a = a.coerceIn(0f, 1f)
            return floatArrayOf(a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 1f)
        }
    }

    /** Slow zoom (in or out) across a photo's time on screen. */
    private class KenBurns(val lenUs: Long, val zoomIn: Boolean) : MatrixTransformation {
        private var first = Long.MIN_VALUE
        override fun getMatrix(presentationTimeUs: Long): android.graphics.Matrix {
            if (first == Long.MIN_VALUE) first = presentationTimeUs
            val p = ((presentationTimeUs - first).toFloat() / lenUs).coerceIn(0f, 1f)
            val s = 1f + 0.12f * (if (zoomIn) p else 1f - p)
            return android.graphics.Matrix().apply { setScale(s, s) }
        }
    }

    /** Volume for 16-bit PCM (clip sound / music level). */
    private class Volume(private val gain: Float) : BaseAudioProcessor() {
        override fun onConfigure(f: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            if (f.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(f)
            return f
        }
        override fun queueInput(input: ByteBuffer) {
            val n = input.remaining(); if (n == 0) return
            val out = replaceOutputBuffer(n)
            input.order(ByteOrder.nativeOrder())
            while (input.remaining() >= 2) out.putShort((input.short * gain).toInt().coerceIn(-32768, 32767).toShort())
            input.position(input.limit())
            out.flip()
        }
    }
}
