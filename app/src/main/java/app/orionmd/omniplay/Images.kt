package app.orionmd.omniplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What picture to show: a song's embedded cover / a video frame (from the file), a TMDB poster, or an online cover. */
sealed class Img {
    abstract val key: String
    /** Made from the file itself: [cat] 3 = embedded album art, 2 = a frame from the video. */
    data class Art(val id: Long, val uri: String, val cat: Int, val large: Boolean = false) : Img() { override val key get() = "a$id${if (large) "L" else ""}" }
    data class Poster(val size: String, val path: String) : Img() { override val key get() = "t$size$path" }
    data class Cover(val url: String) : Img() { override val key get() = "c$url" }
    /** A photo / video thumbnail straight from the phone's media store (kept in memory only — galleries are big). */
    data class Thumb(val uri: String, val video: Boolean = false) : Img() { override val key get() = "g$uri" }
}

object Net {
    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { true }
    fun wifi(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true || c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    } catch (_: Exception) { false }

    /** Downloads [url] into [f], encrypted (pictures in the cache). */
    fun download(url: String, f: File): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode != 200) false else { Vault.writeBytes(f, c.inputStream.use { it.readBytes() }); true }
        } finally { c.disconnect() }
    } catch (_: Exception) { false }

    const val UA = "OrionMD-MediaHub/1.0 (Android; https://github.com/mdmac1983)"

    /** GET returning the body (throws on HTTP errors). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, body)
            return body
        } finally { c.disconnect() }
    }
}

class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

/**
 * Loads pictures for the UI: decrypted from the offline cache, made from the file, or downloaded and cached when online.
 * Decoded bitmaps stay in memory only.
 */
object ImageLoader {
    private lateinit var app: Context
    private lateinit var store: Store
    private val disk = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val net = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_MS = 90_000L
    private val locks = ConcurrentHashMap<String, Any>()
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    var epoch by mutableIntStateOf(0)
        private set
    fun bump() { epoch++ }

    /** Files with no embedded picture (don't try again). */
    val noArt: MutableSet<Long> = java.util.Collections.newSetFromMap(ConcurrentHashMap())

    fun init(ctx: Context, s: Store) { app = ctx.applicationContext; store = s }
    fun cached(req: Img, px: Int): Bitmap? = mem.get(req.key + "@" + bucket(px))
    fun clear() { mem.evictAll(); failedAt.clear() }
    private fun bucket(px: Int) = if (px <= 160) 160 else if (px <= 400) 400 else 1024

    suspend fun load(req: Img, px: Int): Bitmap? {
        val mk = req.key + "@" + bucket(px)
        mem.get(mk)?.let { return it }
        if (req is Img.Thumb) return withContext(disk) { mem.get(mk) ?: thumb(req, bucket(px))?.also { mem.put(mk, it) } }
        val f = localFile(req) ?: return null
        if (!f.exists()) {
            if (req is Img.Art && req.id in noArt) return null
            val last = failedAt[req.key]
            if (last != null && System.currentTimeMillis() - last < RETRY_MS) return null
            if (req !is Img.Art && !Net.online(app)) return null
            if (!withContext(if (req is Img.Art) disk else net) { fetch(req, f) }) return null
        }
        return withContext(disk) { mem.get(mk) ?: decode(f, bucket(px))?.also { mem.put(mk, it) } }
    }

    /** Media-store thumbnail (Android 10+), or a sampled decode on older phones. */
    fun thumb(req: Img.Thumb, px: Int): Bitmap? = try {
        val u = Uri.parse(req.uri)
        if (android.os.Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(u, android.util.Size(px, px), null)
        else if (req.video) MediaMetadataRetriever().let { m -> try { m.setDataSource(app, u); m.frameAtTime?.let { fit(it, px) } } finally { m.release() } }
        else PhotoOps.decodeSampled(app, u, px)
    } catch (_: Throwable) { null }

    private fun localFile(req: Img): File? = when (req) {
        is Img.Art -> File(store.artDir, "${req.id}${if (req.large) "_l" else ""}.jpg")
        is Img.Poster -> if (req.path.isBlank()) null else File(store.tmdbImgDir, req.size + "_" + req.path.trimStart('/'))
        is Img.Cover -> if (!MusicMeta.coverAllowed(req.url)) null else MusicMeta.coverFile(store, req.url)
        is Img.Thumb -> null
    }

    /** Local file for the picture, fetching it first when missing (e.g. for the notification). */
    fun file(req: Img): File? {
        val f = localFile(req) ?: return null
        if (f.exists()) return f
        if (req !is Img.Art && !Net.online(app)) return null
        return if (fetch(req, f)) f else null
    }

    private fun fetch(req: Img, f: File): Boolean = synchronized(locks.getOrPut(req.key) { Any() }) {
        if (f.exists()) return true
        val ok = try {
            when (req) {
                is Img.Art -> makeArt(req, f).also { if (!it && !req.uri.startsWith("omni://")) { noArt.add(req.id) } }
                is Img.Poster -> Tmdb.image(store, req.size, req.path) != null
                is Img.Cover -> MusicMeta.cover(store, req.url) != null
                is Img.Thumb -> false
            }
        } catch (_: Throwable) { false }
        if (ok) failedAt.remove(req.key) else failedAt[req.key] = System.currentTimeMillis()
        ok && f.exists()
    }

    /** Embedded cover (songs) or a frame from the first seconds (videos); cloud files only when they stream fast enough. */
    private fun makeArt(req: Img.Art, dest: File): Boolean {
        if (req.uri.startsWith("omni://")) return false
        val px = if (req.large) 1024 else 300
        val m = MediaMetadataRetriever()
        val bmp: Bitmap? = try {
            m.setDataSource(app, Uri.parse(req.uri))
            if (req.cat == 3) m.embeddedPicture?.let { p ->
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(p, 0, p.size, b)
                val o = BitmapFactory.Options().apply { inSampleSize = 1; while (minOf(b.outWidth, b.outHeight) / (inSampleSize * 2) >= px) inSampleSize *= 2 }
                BitmapFactory.decodeByteArray(p, 0, p.size, o)
            } else (m.getFrameAtTime(8_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: m.frameAtTime)
        } catch (_: Throwable) { null } finally { try { m.release() } catch (_: Exception) {} }
        bmp ?: return false
        val out = if (req.cat == 3) square(bmp, px) else fit(bmp, if (req.large) 1024 else 480)
        val bo = ByteArrayOutputStream(); out.compress(Bitmap.CompressFormat.JPEG, 85, bo)
        Vault.writeBytes(dest, bo.toByteArray())
        return true
    }
    private fun square(b: Bitmap, px: Int): Bitmap {
        val side = minOf(b.width, b.height)
        val c = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side > px) Bitmap.createScaledBitmap(c, px, px, true) else c
    }
    private fun fit(b: Bitmap, px: Int): Bitmap {
        val s = px.toFloat() / maxOf(b.width, b.height)
        return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    private fun decode(f: File, px: Int): Bitmap? = try {
        val bytes = Vault.readBytes(f)
        if (bytes == null) null else {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
            val o = BitmapFactory.Options().apply { inSampleSize = 1; if (px <= 160) inPreferredConfig = Bitmap.Config.RGB_565 }
            while (minOf(b.outWidth, b.outHeight) / (o.inSampleSize * 2) >= px) o.inSampleSize *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        }
    } catch (_: Throwable) { null }
}
