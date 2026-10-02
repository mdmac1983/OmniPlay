package app.orionmd.omniplay

import android.app.WallpaperManager
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Photo adjustments for the editor (0 = unchanged). */
data class Adjust(val bright: Float = 0f, val contrast: Float = 0f, val sat: Float = 0f, val warmth: Float = 0f, val filter: String = "none") {
    val isIdentity get() = bright == 0f && contrast == 0f && sat == 0f && warmth == 0f && filter == "none"
}

/**
 * Everything the Gallery does to pixels: decoding (EXIF-aware), rotate / flip / crop, colour adjustments and filters,
 * saving copies to Pictures/OmniPlay, wallpaper, collages, animated GIFs, long-exposure blends, the eraser (fills the
 * painted area from its surroundings) and sticker cut-outs (Google ML Kit subject segmentation, on the device).
 * Edits are always saved as a new copy — originals are never overwritten.
 */
object PhotoOps {
    val FILTERS = listOf("none" to "Original", "vivid" to "Vivid", "warm" to "Warm", "cool" to "Cool", "mono" to "B&W", "noir" to "Noir",
        "sepia" to "Sepia", "fade" to "Fade", "dramatic" to "Dramatic", "invert" to "Invert")

    // ---------- decode ----------
    fun decodeSampled(ctx: Context, uri: Uri, px: Int): Bitmap? {
        val r = ctx.contentResolver
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = 1; while (max(b.outWidth, b.outHeight) / (inSampleSize * 2) >= px) inSampleSize *= 2 }
        val bm = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val rot = try { r.openInputStream(uri)?.use { when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } } ?: 0f } catch (_: Exception) { 0f }
        return if (rot != 0f) Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(rot) }, true) else bm
    }
    /** A photo at up to [px] on its long side, or a frame of a video. */
    fun load(ctx: Context, g: GItem, px: Int = 2048): Bitmap? = try {
        if (g.video) MediaMetadataRetriever().let { m -> try { m.setDataSource(ctx, Uri.parse(g.uri)); m.frameAtTime?.let { fit(it, px) } } finally { m.release() } }
        else decodeSampled(ctx, Uri.parse(g.uri), px)
    } catch (_: Throwable) { null }

    fun fit(b: Bitmap, px: Int): Bitmap { val s = px.toFloat() / max(b.width, b.height); return if (s >= 1f) b else Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true) }

    // ---------- transforms ----------
    fun rotate(b: Bitmap, deg: Int): Bitmap = if (deg % 360 == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)
    fun flip(b: Bitmap, horizontal: Boolean = true): Bitmap = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { if (horizontal) postScale(-1f, 1f) else postScale(1f, -1f) }, true)
    /** [r] in 0..1 of the image. */
    fun crop(b: Bitmap, r: RectF): Bitmap {
        val x = (r.left * b.width).roundToInt().coerceIn(0, b.width - 1); val y = (r.top * b.height).roundToInt().coerceIn(0, b.height - 1)
        val w = ((r.right - r.left) * b.width).roundToInt().coerceIn(1, b.width - x); val h = ((r.bottom - r.top) * b.height).roundToInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    fun matrix(a: Adjust): ColorMatrix {
        val m = ColorMatrix()
        // filter first
        when (a.filter) {
            "mono" -> m.setSaturation(0f)
            "noir" -> { m.setSaturation(0f); m.postConcat(contrastM(1.45f)) }
            "sepia" -> { m.setSaturation(0f); m.postConcat(ColorMatrix(floatArrayOf(1.07f, 0f, 0f, 0f, 20f, 0f, .95f, 0f, 0f, 8f, 0f, 0f, .82f, 0f, -10f, 0f, 0f, 0f, 1f, 0f))) }
            "vivid" -> { m.setSaturation(1.45f); m.postConcat(contrastM(1.1f)) }
            "warm" -> m.set(ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 12f, 0f, 1.02f, 0f, 0f, 4f, 0f, 0f, .88f, 0f, -8f, 0f, 0f, 0f, 1f, 0f)))
            "cool" -> m.set(ColorMatrix(floatArrayOf(.9f, 0f, 0f, 0f, -6f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 1.1f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
            "fade" -> { m.setSaturation(.7f); m.postConcat(ColorMatrix(floatArrayOf(.85f, 0f, 0f, 0f, 30f, 0f, .85f, 0f, 0f, 30f, 0f, 0f, .85f, 0f, 34f, 0f, 0f, 0f, 1f, 0f))) }
            "dramatic" -> { m.setSaturation(.85f); m.postConcat(contrastM(1.4f)) }
            "invert" -> m.set(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
        }
        if (a.sat != 0f) m.postConcat(ColorMatrix().apply { setSaturation(1f + a.sat) })
        if (a.contrast != 0f) m.postConcat(contrastM(1f + a.contrast))
        if (a.bright != 0f) { val v = a.bright * 100f; m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, v, 0f, 1f, 0f, 0f, v, 0f, 0f, 1f, 0f, v, 0f, 0f, 0f, 1f, 0f))) }
        if (a.warmth != 0f) { val w = a.warmth * 30f; m.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, w, 0f, 1f, 0f, 0f, w * .3f, 0f, 0f, 1f, 0f, -w, 0f, 0f, 0f, 1f, 0f))) }
        return m
    }
    private fun contrastM(c: Float): ColorMatrix { val t = 128f * (1f - c); return ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)) }

    fun applyAdjust(b: Bitmap, a: Adjust): Bitmap {
        if (a.isIdentity) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix(a)) })
        return out
    }

    // ---------- saving ----------
    /** Saves to Pictures/OmniPlay[/sub] and returns its content URI. */
    fun save(ctx: Context, name: String, mime: String, sub: String = "", write: (OutputStream) -> Unit): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/OmniPlay" + (if (sub.isNotEmpty()) "/$sub" else ""))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use(write) ?: return null
        return uri
    }
    fun stamp() = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    fun saveJpeg(ctx: Context, b: Bitmap, prefix: String = "OmniPlay") = save(ctx, "${prefix}_${stamp()}.jpg", "image/jpeg") { b.compress(Bitmap.CompressFormat.JPEG, 94, it) }
    fun savePng(ctx: Context, b: Bitmap, prefix: String, sub: String = "") = save(ctx, "${prefix}_${stamp()}.png", "image/png", sub) { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    /** which: 1 = home, 2 = lock, 3 = both. */
    fun setWallpaper(ctx: Context, b: Bitmap, which: Int) {
        val wm = WallpaperManager.getInstance(ctx)
        val dm = ctx.resources.displayMetrics
        val target = max(dm.widthPixels, dm.heightPixels)
        val img = fit(b, max(target, 1080))
        val flags = when (which) { 1 -> WallpaperManager.FLAG_SYSTEM; 2 -> WallpaperManager.FLAG_LOCK; else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK }
        wm.setBitmap(img, null, true, flags)
    }

    // ---------- collage ----------
    /** Grid layouts as cells (x, y, w, h in 0..1) for n photos. */
    fun layouts(n: Int): List<Pair<String, List<RectF>>> {
        fun grid(cols: Int, rows: Int) = (0 until cols * rows).take(n).map { i -> val c = i % cols; val r = i / cols; RectF(c / cols.toFloat(), r / rows.toFloat(), (c + 1) / cols.toFloat(), (r + 1) / rows.toFloat()) }
        return when (n) {
            1 -> listOf("Single" to listOf(RectF(0f, 0f, 1f, 1f)))
            2 -> listOf("Side by side" to grid(2, 1), "Stacked" to grid(1, 2))
            3 -> listOf("Big + 2" to listOf(RectF(0f, 0f, .6f, 1f), RectF(.6f, 0f, 1f, .5f), RectF(.6f, .5f, 1f, 1f)), "Row of 3" to grid(3, 1), "Column of 3" to grid(1, 3),
                "Top + 2" to listOf(RectF(0f, 0f, 1f, .55f), RectF(0f, .55f, .5f, 1f), RectF(.5f, .55f, 1f, 1f)))
            4 -> listOf("Grid" to grid(2, 2), "Big + 3" to listOf(RectF(0f, 0f, 1f, .62f), RectF(0f, .62f, .333f, 1f), RectF(.333f, .62f, .667f, 1f), RectF(.667f, .62f, 1f, 1f)), "Row of 4" to grid(4, 1))
            5, 6 -> listOf("Grid" to grid(3, 2), "Tall grid" to grid(2, 3))
            else -> listOf("Grid" to grid(3, (n + 2) / 3))
        }
    }
    fun renderCollage(photos: List<Bitmap>, cells: List<RectF>, size: Int, aspect: Float, gap: Int, bg: Int, radius: Float): Bitmap {
        val w = size; val h = (size / aspect).roundToInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(out); c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        cells.forEachIndexed { i, r ->
            val b = photos.getOrNull(i) ?: return@forEachIndexed
            val dst = RectF(r.left * w + gap, r.top * h + gap, r.right * w - gap, r.bottom * h - gap)
            // centre-crop the photo into the cell
            val s = max(dst.width() / b.width, dst.height() / b.height)
            val sw = dst.width() / s; val sh = dst.height() / s
            val src = Rect(((b.width - sw) / 2).toInt(), ((b.height - sh) / 2).toInt(), ((b.width + sw) / 2).toInt(), ((b.height + sh) / 2).toInt())
            c.save(); val path = android.graphics.Path().apply { addRoundRect(dst, radius, radius, android.graphics.Path.Direction.CW) }; c.clipPath(path)
            c.drawBitmap(b, src, dst, p); c.restore()
        }
        return out
    }

    // ---------- video frames ----------
    fun frames(ctx: Context, uri: String, startMs: Long, endMs: Long, count: Int, px: Int): List<Bitmap> {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(ctx, Uri.parse(uri))
            val n = count.coerceAtLeast(1)
            (0 until n).mapNotNull { i ->
                val t = startMs + (endMs - startMs) * i / max(1, n - 1)
                (if (Build.VERSION.SDK_INT >= 27) m.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, px, px) else m.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST))?.let { fit(it, px) }
            }
        } catch (_: Exception) { emptyList() } finally { try { m.release() } catch (_: Exception) {} }
    }

    /** Long exposure: averages the frames (moving things blur into streaks, still things stay sharp). */
    fun longExposure(frames: List<Bitmap>): Bitmap? {
        val first = frames.firstOrNull() ?: return null
        val w = first.width; val h = first.height
        val r = LongArray(w * h); val g = LongArray(w * h); val bl = LongArray(w * h); val px = IntArray(w * h)
        var n = 0
        for (f in frames) {
            val b = if (f.width == w && f.height == h) f else Bitmap.createScaledBitmap(f, w, h, true)
            b.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) { val c = px[i]; r[i] += (c shr 16) and 255; g[i] += (c shr 8) and 255; bl[i] += c and 255 }
            n++
        }
        for (i in px.indices) px[i] = (0xFF shl 24) or ((r[i] / n).toInt() shl 16) or ((g[i] / n).toInt() shl 8) or (bl[i] / n).toInt()
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- eraser ----------
    /**
     * Fills the painted area ([mask], true = erase, same size as [b]) from the pixels around it, ring by ring from the
     * edge inwards, then smooths it. Works best on small objects against simple backgrounds (sky, walls, sand, grass).
     */
    fun erase(b: Bitmap, mask: BooleanArray): Bitmap {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val known = BooleanArray(w * h) { !mask[it] }
        val fr = FloatArray(w * h); val fg = FloatArray(w * h); val fb = FloatArray(w * h)
        for (i in px.indices) { fr[i] = ((px[i] shr 16) and 255).toFloat(); fg[i] = ((px[i] shr 8) and 255).toFloat(); fb[i] = (px[i] and 255).toFloat() }
        var remaining = mask.count { it }
        var guard = 0
        while (remaining > 0 && guard++ < 4000) {
            val fill = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x; if (known[i]) continue
                var sr = 0f; var sg = 0f; var sb = 0f; var sw = 0f
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = x + dx; val yy = y + dy; if (xx !in 0 until w || yy !in 0 until h) continue
                    val j = yy * w + xx; if (!known[j]) continue
                    val wt = 1f / (dx * dx + dy * dy).coerceAtLeast(1)
                    sr += fr[j] * wt; sg += fg[j] * wt; sb += fb[j] * wt; sw += wt
                }
                if (sw > 0f) { fr[i] = sr / sw; fg[i] = sg / sw; fb[i] = sb / sw; fill.add(i) }
            }
            if (fill.isEmpty()) break
            fill.forEach { known[it] = true }; remaining -= fill.size
        }
        // smooth the filled region a little so the rings don't show
        repeat(3) {
            val r2 = fr.copyOf(); val g2 = fg.copyOf(); val b2 = fb.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x; if (!mask[i]) continue
                var sr = 0f; var sg = 0f; var sb = 0f
                for (dy in -1..1) for (dx in -1..1) { val j = i + dy * w + dx; sr += r2[j]; sg += g2[j]; sb += b2[j] }
                fr[i] = sr / 9; fg[i] = sg / 9; fb[i] = sb / 9
            }
        }
        for (i in px.indices) if (mask[i]) px[i] = (0xFF shl 24) or (fr[i].toInt().coerceIn(0, 255) shl 16) or (fg[i].toInt().coerceIn(0, 255) shl 8) or fb[i].toInt().coerceIn(0, 255)
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // ---------- stickers (ML Kit subject segmentation, on the device) ----------
    /** Cuts the main subject(s) out of [b] on a transparent background, trimmed to the subject. Null if nothing found. */
    suspend fun cutOut(b: Bitmap): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            val seg = com.google.mlkit.vision.segmentation.subject.SubjectSegmentation.getClient(
                com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
            seg.process(com.google.mlkit.vision.common.InputImage.fromBitmap(b, 0))
                .addOnSuccessListener { r -> cont.resume(r.foregroundBitmap?.let { trim(it) }); seg.close() }
                .addOnFailureListener { cont.resume(null); seg.close() }
        } catch (_: Throwable) { cont.resume(null) }
    }
    private fun trim(b: Bitmap): Bitmap {
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var l = w; var t = h; var r = -1; var bo = -1
        for (y in 0 until h) for (x in 0 until w) if ((px[y * w + x] ushr 24) > 16) { l = min(l, x); r = max(r, x); t = min(t, y); bo = max(bo, y) }
        if (r < l || bo < t) return b
        val pad = 8; l = max(0, l - pad); t = max(0, t - pad); r = min(w - 1, r + pad); bo = min(h - 1, bo + pad)
        return Bitmap.createBitmap(b, l, t, r - l + 1, bo - t + 1)
    }

    // ---------- animated GIF ----------
    /** Writes frames as an animated GIF ([delayMs] per frame, looping). 252-colour palette with dithering. */
    fun writeGif(frames: List<Bitmap>, delayMs: Int, out: OutputStream) = GifWriter(out).use { g -> frames.forEach { g.frame(it, delayMs) } }
}

/** Small animated-GIF encoder: fixed 6×7×6 colour cube + Floyd–Steinberg dithering, LZW-compressed, loops forever. */
class GifWriter(private val out: OutputStream) : AutoCloseable {
    private var started = false
    private val palette = ByteArray(256 * 3).also { p ->
        var k = 0
        for (r in 0 until 6) for (g in 0 until 7) for (b in 0 until 6) { p[k * 3] = (r * 51).toByte(); p[k * 3 + 1] = (g * 255 / 6).toByte(); p[k * 3 + 2] = (b * 51).toByte(); k++ }
    }
    private fun idx(r: Int, g: Int, b: Int) = ((r * 5 + 127) / 255) * 42 + ((g * 6 + 127) / 255) * 6 + ((b * 5 + 127) / 255)

    fun frame(bmp: Bitmap, delayMs: Int) {
        val w = bmp.width; val h = bmp.height
        if (!started) {
            started = true
            out.write("GIF89a".toByteArray()); short(w); short(h)
            out.write(0xF7); out.write(0); out.write(0)            // global colour table, 256 entries
            out.write(palette)
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))   // loop forever
        }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0)); short((delayMs / 10).coerceAtLeast(2)); out.write(0); out.write(0)
        out.write(0x2C); short(0); short(0); short(w); short(h); out.write(0)
        // dither to the palette
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val er = IntArray(w + 2); val eg = IntArray(w + 2); val eb = IntArray(w + 2)
        var nr = IntArray(w + 2); var ng = IntArray(w + 2); var nb = IntArray(w + 2)
        val ind = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = (((c shr 16) and 255) + er[x + 1] / 16).coerceIn(0, 255); val g = (((c shr 8) and 255) + eg[x + 1] / 16).coerceIn(0, 255); val b = ((c and 255) + eb[x + 1] / 16).coerceIn(0, 255)
                val i = idx(r, g, b); ind[y * w + x] = i.toByte()
                val dr = r - (palette[i * 3].toInt() and 255); val dg = g - (palette[i * 3 + 1].toInt() and 255); val db = b - (palette[i * 3 + 2].toInt() and 255)
                er[x + 2] += dr * 7; eg[x + 2] += dg * 7; eb[x + 2] += db * 7
                nr[x] += dr * 3; ng[x] += dg * 3; nb[x] += db * 3
                nr[x + 1] += dr * 5; ng[x + 1] += dg * 5; nb[x + 1] += db * 5
                nr[x + 2] += dr; ng[x + 2] += dg; nb[x + 2] += db
            }
            System.arraycopy(nr, 0, er, 0, w + 2); System.arraycopy(ng, 0, eg, 0, w + 2); System.arraycopy(nb, 0, eb, 0, w + 2)
            nr = IntArray(w + 2); ng = IntArray(w + 2); nb = IntArray(w + 2)
        }
        lzw(ind)
    }

    private fun lzw(data: ByteArray) {
        val minCode = 8; out.write(minCode)
        val clear = 1 shl minCode; val eoi = clear + 1
        val buf = ByteArrayOutputStream()
        var bits = 0; var acc = 0; var codeSize = minCode + 1
        fun emit(code: Int) { acc = acc or (code shl bits); bits += codeSize; while (bits >= 8) { buf.write(acc and 255); acc = acc ushr 8; bits -= 8 } }
        var dict = HashMap<Int, Int>(); var next = eoi + 1
        emit(clear)
        var prefix = data.firstOrNull()?.toInt()?.and(255) ?: run { emit(eoi); flush(buf, acc, bits); return }
        for (k in 1 until data.size) {
            val c = data[k].toInt() and 255
            val key = (prefix shl 8) or c
            val hit = dict[key]
            if (hit != null) { prefix = hit; continue }
            emit(prefix)
            if (next < 4096) { dict[key] = next++; if (next > (1 shl codeSize) && codeSize < 12) codeSize++ }
            else { emit(clear); dict = HashMap(); next = eoi + 1; codeSize = minCode + 1 }
            prefix = c
        }
        emit(prefix); emit(eoi)
        flush(buf, acc, bits)
    }
    private fun flush(buf: ByteArrayOutputStream, acc: Int, bits: Int) {
        if (bits > 0) buf.write(acc and 255)
        val bytes = buf.toByteArray(); var i = 0
        while (i < bytes.size) { val n = min(255, bytes.size - i); out.write(n); out.write(bytes, i, n); i += n }
        out.write(0)
    }
    private fun short(v: Int) { out.write(v and 255); out.write((v shr 8) and 255) }
    override fun close() { out.write(0x3B); out.flush() }
}
