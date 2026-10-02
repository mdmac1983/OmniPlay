package app.orionmd.omniplay

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * AI Eraser — MI-GAN (Picsart AI Research, MIT licence), an image-inpainting model that removes the painted object and
 * generates a matching background. Runs entirely on the phone with ONNX Runtime. The ~28 MB model is downloaded once
 * the first time it's used (checked against its SHA-256), then works offline.
 *
 * Model I/O: image uint8 [1,3,H,W] RGB, mask uint8 [1,1,H,W] (0 = erase, 255 = keep) → result uint8 [1,3,H,W].
 */
object AiEraser {
    private const val URL = "https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx"
    private const val SHA256 = "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b"
    const val SIZE_MB = 28
    /** Long side the photo is processed at (keeps memory and time reasonable on phones). */
    private const val MAX_SIDE = 1536

    private fun file(ctx: Context) = File(File(ctx.filesDir, "models").apply { mkdirs() }, "migan_v2.onnx")
    fun isReady(ctx: Context) = file(ctx).let { it.exists() && it.length() > 20_000_000 }

    @Volatile private var session: OrtSession? = null

    /** Downloads and verifies the model. Blocking (Dispatchers.IO). Returns null on success or the reason it failed. */
    fun download(ctx: Context, progress: (Float) -> Unit): String? {
        val dest = file(ctx); val tmp = File(dest.path + ".part")
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
        try {
            client.newCall(Request.Builder().url(URL).header("User-Agent", Net.UA).build()).execute().use { r ->
                if (!r.isSuccessful) return "the download server answered ${r.code}"
                val body = r.body ?: return "empty download"
                val total = body.contentLength().takeIf { it > 0 } ?: (SIZE_MB * 1_000_000L)
                val md = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input -> tmp.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024); var done = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); done += n; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != SHA256) { tmp.delete(); return "the downloaded model didn't match (corrupted download) — try again" }
            }
            if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
            return null
        } catch (e: Exception) { tmp.delete(); return e.message ?: "download failed" }
    }

    fun remove(ctx: Context) { session?.close(); session = null; file(ctx).delete() }

    @Synchronized private fun session(ctx: Context): OrtSession =
        session ?: OrtEnvironment.getEnvironment().createSession(file(ctx).path, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }).also { session = it }

    /**
     * Removes the [mask]ed area (true = erase; same size as [src]) and fills it in. Blocking (Dispatchers.Default).
     * The result has [src]'s size; only the erased area changes.
     */
    fun erase(ctx: Context, src: Bitmap, mask: BooleanArray): Bitmap {
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(src.width, src.height))
        val w = maxOf(8, (src.width * scale).toInt()); val h = maxOf(8, (src.height * scale).toInt())
        val work = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src.copy(Bitmap.Config.ARGB_8888, false)
        val px = IntArray(w * h); work.getPixels(px, 0, w, 0, 0, w, h)
        // image: planar RGB
        val img = ByteBuffer.allocateDirect(3 * w * h)
        for (c in 0 until 3) { val sh = 16 - c * 8; for (i in 0 until w * h) img.put(((px[i] shr sh) and 255).toByte()) }
        img.rewind()
        // mask at the working size (nearest), grown by 2 px so object edges go too
        val mw = src.width; val mh = src.height
        val m = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; mask[(minOf(mh - 1, (y / scale).toInt())) * mw + minOf(mw - 1, (x / scale).toInt())] }
        val grown = m.copyOf()
        for (y in 0 until h) for (x in 0 until w) if (m[y * w + x]) for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) grown[yy * w + xx] = true }
        val mb = ByteBuffer.allocateDirect(w * h); for (i in 0 until w * h) mb.put(if (grown[i]) 0 else 255.toByte()); mb.rewind()

        val env = OrtEnvironment.getEnvironment()
        val out = OnnxTensor.createTensor(env, img, longArrayOf(1, 3, h.toLong(), w.toLong()), ai.onnxruntime.OnnxJavaType.UINT8).use { ti ->
            OnnxTensor.createTensor(env, mb, longArrayOf(1, 1, h.toLong(), w.toLong()), ai.onnxruntime.OnnxJavaType.UINT8).use { tm ->
                session(ctx).run(mapOf("image" to ti, "mask" to tm)).use { r ->
                    val t = r[0] as OnnxTensor
                    val bb = t.byteBuffer; val res = IntArray(w * h)
                    for (i in 0 until w * h) {
                        val rr = bb.get(i).toInt() and 255; val gg = bb.get(w * h + i).toInt() and 255; val b2 = bb.get(2 * w * h + i).toInt() and 255
                        res[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or b2
                    }
                    res
                }
            }
        }
        val filled = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        if (scale >= 1f) return filled
        // full resolution: keep the original pixels outside the erased area, use the upscaled fill inside
        val up = Bitmap.createScaledBitmap(filled, mw, mh, true)
        val orig = IntArray(mw * mh); src.getPixels(orig, 0, mw, 0, 0, mw, mh)
        val upx = IntArray(mw * mh); up.getPixels(upx, 0, mw, 0, 0, mw, mh)
        for (i in orig.indices) { val x = i % mw; val y = i / mw; if (grown[minOf(h - 1, (y * scale).toInt()) * w + minOf(w - 1, (x * scale).toInt())]) orig[i] = upx[i] }
        return Bitmap.createBitmap(orig, mw, mh, Bitmap.Config.ARGB_8888)
    }
}
