package app.orionmd.omniplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View

/**
 * Ambient light for the video player: every quarter second a tiny copy of the picture (24×14 pixels) is taken, blown
 * up and blurred into a soft glow behind the video, so the black bars around it light up in the picture's colours (like
 * the TV-backlight effect). Each new glow fades in over the last one. [level] 0 = off, up to 1 = strongest.
 */
class AmbientLight(ctx: Context) : View(ctx) {
    /** The player's video surface (it can change when the player is rebuilt). */
    var source: () -> SurfaceView? = { null }
    var level = 0f
        set(v) { field = v; if (v <= 0f) { cur = null; prev = null }; invalidate(); if (v > 0f) start() }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Array(3) { Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888) }
    private var next = 0
    private var cur: Bitmap? = null
    private var prev: Bitmap? = null
    private var fadeAt = 0L
    private var running = false
    private var busy = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint()
    private val loc = IntArray(2); private val mine = IntArray(2)
    private val tmp = RectF()
    private var edgeKey = ""

    init {
        // a real blur where Android can do it; on older phones the blown-up 24-pixel picture is already soft
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(android.graphics.RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || level <= 0f) { running = false; return }
            grab(); ui.postDelayed(this, 250)
        }
    }
    fun start() { if (!running && level > 0f) { running = true; ui.post(tick) } }
    fun stop() { running = false; ui.removeCallbacks(tick) }

    private fun grab() {
        val sv = source() ?: return
        if (busy || sv.width <= 0 || sv.height <= 0 || !sv.holder.surface.isValid) return
        val b = pool[next]
        busy = true
        try {
            PixelCopy.request(sv, b, { r ->
                busy = false
                if (r == PixelCopy.SUCCESS && level > 0f) { prev = cur; cur = b; next = (next + 1) % pool.size; fadeAt = System.currentTimeMillis(); invalidate() }
            }, ui)
        } catch (_: Exception) { busy = false }
    }

    /** Where the video is drawn, in this view's coordinates. */
    private fun videoRect(): RectF? {
        val sv = source() ?: return null
        if (sv.width <= 0 || sv.height <= 0) return null
        sv.getLocationInWindow(loc); getLocationInWindow(mine)
        val l = (loc[0] - mine[0]).toFloat(); val t = (loc[1] - mine[1]).toFloat()
        return RectF(l, t, l + sv.width, t + sv.height)
    }

    override fun onDraw(c: Canvas) {
        val now = cur ?: return
        if (level <= 0f) return
        val r = videoRect() ?: return
        // nothing to light when the video already fills the screen
        if (r.left <= 2 && r.top <= 2 && r.right >= width - 2 && r.bottom >= height - 2) return
        // the glow spreads well past the picture's edges, then fades out towards the screen's edges
        val grow = maxOf(width, height) * 0.22f
        tmp.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow)
        val f = ((System.currentTimeMillis() - fadeAt) / 400f).coerceIn(0f, 1f)
        val a = (level * 255).toInt()
        prev?.let { paint.alpha = (a * (1 - f)).toInt(); c.drawBitmap(it, null, tmp, paint) }
        paint.alpha = (a * (if (prev == null) 1f else f)).toInt(); c.drawBitmap(now, null, tmp, paint)
        // darker towards the screen's edges
        val cx = r.centerX(); val cy = r.centerY(); val rad = maxOf(width, height) * 0.75f
        val key = "$cx,$cy,$rad"
        if (key != edgeKey) { edgeKey = key; edge.shader = RadialGradient(cx, cy, rad, intArrayOf(0x00000000, 0x00000000, 0xE0000000.toInt()), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), edge)
        if (f < 1f) postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stop() }

    companion object {
        /** Off / subtle / normal / strong, as stored in the "vambient" setting. */
        val LEVELS = listOf("0" to "Off", "0.45" to "Subtle", "0.75" to "Normal", "1" to "Strong")
    }
}
