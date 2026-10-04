package app.orionmd.omniplay

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.orionmd.omniplay.cloud.Cloud
import app.orionmd.omniplay.cloud.CloudProxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs
import androidx.media3.common.Player as M3Player

/**
 * Full-screen video player (Media3 / ExoPlayer). Plays device videos, saved offline copies, and cloud videos (through the
 * local proxy, which handles authentication, seeking and on-the-fly decryption). Subtitles come from sidecar files next
 * to the video (.srt/.vtt/.ass/.ssa) or from online subtitles saved by [Subs]; the CC button switches them.
 * Gestures (double-tap skip, brightness/volume swipes, scrub), speed, picture size, rotation, PiP, lock, sleep, A-B
 * repeat, volume boost, screenshot, resume position and auto-play-next are all here.
 */
class VideoActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private lateinit var msg: TextView
    private lateinit var hud: TextView
    private lateinit var bar: LinearLayout
    private lateinit var unlockBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var sizeBtn: TextView
    private lateinit var rotBtn: TextView
    private lateinit var clockTv: TextView
    private lateinit var sleepBtn: TextView
    private lateinit var abBtn: TextView
    private lateinit var repBtn: TextView
    private lateinit var boostBtn: TextView
    private lateinit var store: Store
    private val ui = Handler(Looper.getMainLooper())

    private var queue = JSONArray()
    private var resolved = 0
    private var resolving = false
    private var gen = 0
    private var startAt = 0L
    private var locked = false
    private var seekStep = 10_000L
    private var gestures = true
    private var resume = true
    private var autoNext = true
    private var dtap = true
    private var sens = 1f
    private var bgAudio = false
    private var boost: android.media.audiofx.LoudnessEnhancer? = null
    private var boostLevel = 0
    private var abA = -1L; private var abB = -1L
    private var sleepAt = 0L

    private val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private val sizes = listOf("fit" to "Fit", "fill" to "Fill", "zoom" to "Zoom", "stretch" to "Stretch")
    private val rots = listOf("sensor" to "Auto-rotate", "landscape" to "Landscape", "portrait" to "Portrait")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Player.isPlaying()) Player.toggle()
        store = Store(this); Cloud.init(store)
        seekStep = store.getPref("vseek", "10").toLongOrNull()?.times(1000) ?: 10_000L
        gestures = store.getPref("vgest", "1") == "1"
        resume = store.getPref("vresume", "1") == "1"
        autoNext = store.getPref("vnext", "1") == "1"
        dtap = store.getPref("vdtap", "1") == "1"
        sens = when (store.getPref("vsens", "normal")) { "low" -> .6f; "high" -> 1.7f; else -> 1f }
        bgAudio = store.getPref("vbgaudio", "0") == "1"
        boostLevel = store.getPref("vboost", "0").toIntOrNull() ?: 0
        applyOrientation(store.getPref("vorient", "sensor"))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            setShowSubtitleButton(true); setShowNextButton(true); setShowPreviousButton(true)
            setShowFastForwardButton(true); setShowRewindButton(true); keepScreenOn = true
            controllerShowTimeoutMs = (store.getPref("vhide", "3").toIntOrNull() ?: 3) * 1000
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(6))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xAA000000.toInt(), 0x00000000))
        }
        title = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setShadowLayer(6f, 0f, 0f, Color.BLACK) }
        clockTv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; setShadowLayer(6f, 0f, 0f, Color.BLACK); setPadding(dp(10), 0, 0, 0) }
        bar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(android.widget.ImageView(this@VideoActivity).apply {
                setImageBitmap(iconBitmap(Icons.AutoMirrored.Rounded.ArrowBack, dp(26))); contentDescription = "Back"
                setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { close() }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x66000000) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
            addView(title, LinearLayout.LayoutParams(0, -2, 1f)); addView(clockTv)
        })
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        bar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        speedBtn = pill("1×", Icons.Rounded.Speed) { cycleSpeed() }
        sizeBtn = pill("Fit", Icons.Rounded.AspectRatio) { cycleSize() }
        rotBtn = pill(rots.first { it.first == store.getPref("vorient", "sensor") }.second, Icons.Rounded.ScreenRotation) { cycleRotation() }
        sleepBtn = pill("Sleep", Icons.Rounded.Bedtime) { cycleSleep() }
        abBtn = pill("A-B", Icons.Rounded.Loop) { cycleAb() }
        repBtn = pill("Off", Icons.Rounded.RepeatOne) { cycleRepeat() }
        boostBtn = pill(boostLabel(), Icons.AutoMirrored.Rounded.VolumeUp) { cycleBoost() }
        val extra = mutableListOf<TextView>()
        if (store.getPref("vintro", "1") == "1") extra += pill("Skip intro", Icons.Rounded.FastForward) { player?.let { it.seekTo(it.currentPosition + (store.getPref("vintrolen", "85").toLongOrNull() ?: 85L) * 1000); toast("Skipped intro") } }
        extra += listOf(sleepBtn, abBtn, repBtn, boostBtn)
        if (store.getPref("vshot", "1") == "1") extra += pill("", Icons.Rounded.PhotoCamera) { screenshot() }
        (listOf(pill("Close", Icons.Rounded.Close) { close() }, speedBtn, sizeBtn, rotBtn) + extra + (if (pipSupported) listOf(pill("Picture-in-picture", Icons.Rounded.PictureInPictureAlt) { enterPip() }) else emptyList()) + listOf(pill("Lock", Icons.Rounded.Lock) { setLocked(true) })).forEach { pills.addView(it) }
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        msg = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(dp(30), 0, dp(30), 0); visibility = View.GONE }
        root.addView(msg, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(dp(18), dp(10), dp(18), dp(10)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xB0000000.toInt()) }
        }
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        unlockBtn = pill("Tap to unlock", Icons.Rounded.LockOpen) { setLocked(false) }.apply { visibility = View.GONE }
        root.addView(unlockBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        setContentView(root)
        hideBars()
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> if (!locked) bar.visibility = if (msg.visibility == View.VISIBLE) View.VISIBLE else v })

        styleSubtitles()
        sizeBtn.text = applySize(store.getPref("vresize", "fit"))
        ui.post(ticker)
        load(savedInstanceState?.getLong("pos"))
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        if (resume) savePos()
        player?.release(); player = null; view.player = null
        setIntent(i); msg.visibility = View.GONE
        load(null)
    }

    private fun load(pos: Long?) {
        queue = try { JSONArray(intent.getStringExtra("queue") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (queue.length() == 0) { finish(); return }
        title.text = queue.getJSONObject(0).optString("t")
        startAt = pos ?: if (resume) savedPos(queue.getJSONObject(0).optLong("i")).let { if (it > 0) maxOf(0L, it - (store.getPref("vback", "0").toLongOrNull() ?: 0L) * 1000) else it } else 0L
        resolved = 0; resolving = false; gen++
        thread {
            try { val first = buildItem(queue.getJSONObject(0)); runOnUiThread { if (!isDestroyed) start(first) } }
            catch (e: Exception) { runOnUiThread { showMsg("Can't play this video (" + (e.message ?: "unknown") + ").") } }
        }
    }

    /** Resolve the video (and its subtitles) to URIs ExoPlayer can play: offline copy, device URI, or cloud proxy URL. */
    private fun buildItem(q: JSONObject): MediaItem {
        val id = q.optLong("i")
        val src = resolve(q.optString("u"), id)
        val auto = store.getPref("subauto", "1") == "1"
        val subs = q.optJSONArray("subs") ?: JSONArray()
        val confs = (0 until subs.length()).mapNotNull { k ->
            val o = subs.getJSONObject(k); val name = o.optString("n")
            val uri = subUri(o) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(when (name.substringAfterLast('.').lowercase()) { "vtt" -> MimeTypes.TEXT_VTT; "ass", "ssa" -> MimeTypes.TEXT_SSA; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(o.optString("lang").ifEmpty { null }).setLabel(o.optString("label").ifEmpty { name })
                .setSelectionFlags(if (k == 0 && auto) C.SELECTION_FLAG_DEFAULT else 0).setId("sub$k").build()
        }
        // subtitles downloaded by "Sync all" (Wyzie Subs) — saved encrypted, decrypted to a temp copy for the player
        val lang = store.getPref("subLang", "en")
        val online = try { Subs.playable(store, cacheDir, id, lang) } catch (_: Exception) { null }
        val all = if (online != null) confs + MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(online)).setMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(lang).setLabel("Online ($lang)").setSelectionFlags(if (confs.isEmpty() && auto) C.SELECTION_FLAG_DEFAULT else 0).setId("subOnline").build() else confs
        return MediaItem.Builder().setUri(src).setMediaId(id.toString()).setSubtitleConfigurations(all).build()
    }

    private fun resolve(u: String, id: Long): Uri {
        Downloads.localUri(this, id)?.let { return it }
        if (u.startsWith("omni://")) { val (acct, fid) = Cloud.resolve(u) ?: throw Exception("cloud account missing"); return Uri.parse(CloudProxy.url(acct.id, fid)) }
        return Uri.parse(u)
    }
    /** A subtitle URI: a saved local copy, a cloud sub pulled to the cache, or a device/file URI. */
    private fun subUri(o: JSONObject): Uri? {
        o.optString("local").takeIf { it.isNotEmpty() && File(it).exists() }?.let { return Uri.fromFile(File(it)) }
        val u = o.optString("u"); if (u.isEmpty()) return null
        if (u.startsWith("omni://")) {
            val (acct, fid) = Cloud.resolve(u) ?: return null
            val f = File(File(cacheDir, "subs").apply { mkdirs() }, "s" + u.hashCode().toUInt().toString(16) + ".srt")
            if (!f.exists()) try { Cloud.open(acct, fid, 0).use { s -> f.outputStream().use { s.input.copyTo(it) } } } catch (_: Exception) { return null }
            return Uri.fromFile(f)
        }
        return Uri.parse(u)
    }

    private fun start(item: MediaItem) {
        val p = ExoPlayer.Builder(this, OmniRenderers(this, video = true)).setSeekBackIncrementMs(seekStep).setSeekForwardIncrementMs(seekStep).build()
        player = p; view.player = p
        try { val sid = androidx.media3.common.util.Util.generateAudioSessionIdV21(this); p.audioSessionId = sid; boost = android.media.audiofx.LoudnessEnhancer(sid); applyBoost() } catch (_: Throwable) { boost = null }
        store.getPref("vspeed", "1").toFloatOrNull()?.takeIf { abs(it - 1f) > .01f }?.let { p.playbackParameters = PlaybackParameters(it); speedBtn.text = fmtSpeed(it) }
        val subsOn = store.getPref("subauto", "1") == "1"
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(if (subsOn) java.util.Locale.getDefault().language else null)
            .setSelectUndeterminedTextLanguage(subsOn).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        p.pauseAtEndOfMediaItems = !autoNext
        p.addListener(object : M3Player.Listener {
            override fun onPlayerError(error: PlaybackException) { showMsg("Can't play this video on this device (format not supported or connection lost).") }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == M3Player.STATE_ENDED) { forget(currentId()); finish() }
                if (state == M3Player.STATE_READY) { msg.visibility = View.GONE; prefetchNext() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                abA = -1L; abB = -1L; abBtn.text = "A-B"
                val prevIdx = p.previousMediaItemIndex
                if (reason == M3Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prevIdx >= 0) p.getMediaItemAt(prevIdx).mediaId.toLongOrNull()?.let { forget(it) }
                val q = queueEntry(mediaItem?.mediaId?.toLongOrNull() ?: 0L)
                title.text = q?.optString("t") ?: ""
                if (reason != M3Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && q != null) toast(q.optString("t"))
                prefetchNext()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && sleepAt == -1L && reason == M3Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    sleepAt = 0L; sleepIdx = 0; sleepBtn.text = "Sleep"; toast("Sleep timer: stopped"); ui.postDelayed({ close() }, 1500)
                }
            }
            override fun onTrackSelectionParametersChanged(prm: androidx.media3.common.TrackSelectionParameters) {
                val off = (prm.ignoredTextSelectionFlags and C.SELECTION_FLAG_DEFAULT) != 0 && prm.overrides.values.none { it.type == C.TRACK_TYPE_TEXT }
                if (off && (prm.preferredTextLanguages.isNotEmpty() || prm.selectUndeterminedTextLanguage || C.TRACK_TYPE_TEXT !in prm.disabledTrackTypes)) {
                    p.trackSelectionParameters = prm.buildUpon().setPreferredTextLanguage(null).setSelectUndeterminedTextLanguage(false).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    toast("Subtitles off")
                }
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updatePipParams() }
        })
        p.setMediaItem(item, startAt); resolved = 1
        if (startAt > 5000) toast("Resuming at " + clock(startAt))
        p.prepare(); p.playWhenReady = true
    }

    private fun prefetchNext() {
        val p = player ?: return
        if (resolving || resolved >= queue.length() || p.currentMediaItemIndex < p.mediaItemCount - 1) return
        resolving = true
        val q = queue.getJSONObject(resolved); val g = gen
        thread {
            val item = try { buildItem(q) } catch (_: Exception) { null }
            runOnUiThread { if (g != gen) return@runOnUiThread; resolving = false; if (item != null && player != null) { player?.addMediaItem(item); resolved++ } }
        }
    }

    private fun queueEntry(id: Long): JSONObject? = (0 until queue.length()).map { queue.getJSONObject(it) }.firstOrNull { it.optLong("i") == id }
    private fun currentId() = player?.currentMediaItem?.mediaId?.toLongOrNull() ?: 0L

    // resume positions (pref "vpos": {id:{p,d,t,n}})
    private fun positions(): JSONObject = try { JSONObject(store.getPref("vpos", "{}")) } catch (_: Exception) { JSONObject() }
    private fun savedPos(id: Long): Long = positions().optJSONObject(id.toString())?.optLong("p") ?: 0L
    private fun forget(id: Long) { val o = positions(); if (o.has(id.toString())) { o.remove(id.toString()); store.setPref("vpos", o.toString()) } }
    private fun savePos() {
        val p = player ?: return; val id = currentId(); if (id == 0L) return
        val pos = p.currentPosition; val dur = p.duration; val o = positions()
        if (pos < 30_000 || (dur > 0 && pos > dur - 90_000)) o.remove(id.toString())
        else o.put(id.toString(), JSONObject().put("p", pos).put("d", dur).put("t", System.currentTimeMillis()).put("n", title.text.toString()))
        if (o.length() > 80) o.keys().asSequence().toList().sortedBy { o.getJSONObject(it).optLong("t") }.take(o.length() - 80).forEach { o.remove(it) }
        store.setPref("vpos", o.toString())
    }

    private fun cycleSpeed() { val p = player ?: return; val cur = p.playbackParameters.speed; val next = speeds[(speeds.indexOfFirst { abs(it - cur) < .01f } + 1) % speeds.size]; p.playbackParameters = PlaybackParameters(next); speedBtn.text = fmtSpeed(next); store.setPref("vspeed", next.toString()); toast("Speed " + fmtSpeed(next)) }
    private fun fmtSpeed(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

    private var sizeKey = "fit"
    private fun applySize(k: String): String {
        sizeKey = k
        view.resizeMode = when (k) { "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH; "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
        return sizes.first { it.first == k }.second
    }
    private fun cycleSize() { val next = sizes[(sizes.indexOfFirst { it.first == sizeKey } + 1) % sizes.size].first; sizeBtn.text = applySize(next); store.setPref("vresize", next); toast(sizes.first { it.first == next }.second) }

    private var rotKey = "sensor"
    private fun applyOrientation(k: String) { rotKey = k; requestedOrientation = when (k) { "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR } }
    private fun cycleRotation() { val next = rots[(rots.indexOfFirst { it.first == rotKey } + 1) % rots.size]; applyOrientation(next.first); rotBtn.text = next.second; store.setPref("vorient", next.first); toast(next.second) }

    private fun setLocked(on: Boolean) {
        locked = on; view.useController = !on
        if (on) { view.hideController(); bar.visibility = View.GONE; unlockBtn.visibility = View.VISIBLE; ui.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500); toast("Screen locked") }
        else { unlockBtn.visibility = View.GONE; view.showController() }
    }

    private fun styleSubtitles() {
        val scale = when (store.getPref("subsize", "normal")) { "tiny" -> .65f; "small" -> .8f; "large" -> 1.3f; "xlarge" -> 1.6f; "huge" -> 2f; else -> 1f }
        val fg = when (store.getPref("subcolor", "white")) { "yellow" -> 0xFFFFE14D.toInt(); "cyan" -> 0xFF7FE7FF.toInt(); "green" -> 0xFF8CFF8C.toInt(); "pink" -> 0xFFFF9AD5.toInt(); "orange" -> 0xFFFFB347.toInt(); else -> Color.WHITE }
        val bgKind = store.getPref("subbg", "shadow")
        val alpha = (store.getPref("subbgop", "80").toIntOrNull() ?: 80).coerceIn(0, 100) * 255 / 100
        val bg = if (bgKind == "box") (alpha shl 24) else 0x00000000
        val win = if (bgKind == "window") (alpha shl 24) else Color.TRANSPARENT
        val edge = when (store.getPref("subedge", "outline")) { "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE }
        view.subtitleView?.apply {
            setApplyEmbeddedStyles(store.getPref("subembed", "1") == "1" && bgKind == "shadow")
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
            setStyle(CaptionStyleCompat(fg, bg, win, edge, Color.BLACK, null))
            setBottomPaddingFraction(store.getPref("subpos", "8").toFloatOrNull()?.div(100f) ?: SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
    }

    private val sleeps = listOf(0L, 15L, 30L, 45L, 60L, 90L, -1L)
    private var sleepIdx = 0
    private fun cycleSleep() {
        sleepIdx = (sleepIdx + 1) % sleeps.size; val m = sleeps[sleepIdx]
        sleepAt = when { m == 0L -> 0L; m < 0 -> -1L; else -> System.currentTimeMillis() + m * 60_000 }
        sleepBtn.text = when { m == 0L -> "Sleep"; m < 0 -> "End"; else -> "${m}m" }
        toast(when { m == 0L -> "Sleep timer off"; m < 0 -> "Stop after this video"; else -> "Stop in $m min" })
        player?.pauseAtEndOfMediaItems = if (m < 0) true else !autoNext
    }
    private fun cycleAb() {
        val p = player ?: return
        when {
            abA < 0 -> { abA = p.currentPosition; abBtn.text = "A-…"; toast("A set at " + clock(abA)) }
            abB < 0 -> { if (p.currentPosition <= abA + 1000) { toast("B must be after A"); return }; abB = p.currentPosition; abBtn.text = "A-B ✓"; p.seekTo(abA); toast("Repeating " + clock(abA) + " – " + clock(abB)) }
            else -> { abA = -1L; abB = -1L; abBtn.text = "A-B"; toast("A-B repeat off") }
        }
    }
    private fun cycleRepeat() { val p = player ?: return; p.repeatMode = if (p.repeatMode == M3Player.REPEAT_MODE_ONE) M3Player.REPEAT_MODE_OFF else M3Player.REPEAT_MODE_ONE; val on = p.repeatMode == M3Player.REPEAT_MODE_ONE; repBtn.text = if (on) "One" else "Off"; toast(if (on) "Repeat this video" else "Repeat off") }

    private val boosts = listOf(0, 300, 600, 900, 1200, 1500)
    private fun boostLabel() = if (boostLevel == 0) "Boost" else "+${boostLevel / 100}dB"
    private fun applyBoost() { try { boost?.setTargetGain(boostLevel); boost?.enabled = boostLevel > 0 } catch (_: Throwable) {} }
    private fun cycleBoost() {
        if (boost == null) { toast("Volume boost isn't available on this device"); return }
        boostLevel = boosts[(boosts.indexOf(boostLevel).coerceAtLeast(0) + 1) % boosts.size]; store.setPref("vboost", boostLevel.toString()); applyBoost()
        boostBtn.text = boostLabel(); toast(if (boostLevel == 0) "Volume boost off" else "Volume boost +${boostLevel / 100} dB")
    }

    private fun screenshot() {
        val sv = view.videoSurfaceView as? android.view.SurfaceView; val vs = player?.videoSize
        if (sv == null || vs == null || vs.width <= 0) { toast("Nothing to capture yet"); return }
        val bmp = android.graphics.Bitmap.createBitmap(sv.width.coerceAtLeast(1), sv.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(sv, bmp, { r ->
                if (r != android.view.PixelCopy.SUCCESS) { toast("Couldn't capture this frame"); return@request }
                thread { val ok = try { saveShot(bmp); true } catch (_: Exception) { false }; runOnUiThread { toast(if (ok) "Saved to Pictures" else "Couldn't save the screenshot") } }
            }, ui)
        } catch (_: Exception) { toast("Couldn't capture this frame") }
    }
    private fun saveShot(bmp: android.graphics.Bitmap) {
        val name = "OmniPlay_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/OmniPlay")
        }
        val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: error("no uri")
        contentResolver.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
    }

    private val showClock by lazy { store.getPref("vclock", "1") == "1" }
    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && abA >= 0 && abB > abA && p.currentPosition >= abB) p.seekTo(abA)
            if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0L; sleepIdx = 0; sleepBtn.text = "Sleep"; p?.pause(); toast("Sleep timer: stopped"); savePos(); ui.postDelayed({ close() }, 1500) }
            if (showClock) {
                val left = if (p != null && p.duration > 0) "  −" + clock(maxOf(0, p.duration - p.currentPosition)) else ""
                clockTv.text = android.text.format.DateFormat.getTimeFormat(this@VideoActivity).format(java.util.Date()) + left
                if (sleepAt > 0) sleepBtn.text = clock(sleepAt - System.currentTimeMillis())
            }
            ui.postDelayed(this, 500)
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val vs = player?.videoSize
        val r = if (vs != null && vs.width > 0 && vs.height > 0) Rational(vs.width, vs.height).let { if (it.toFloat() > 2.39f) Rational(239, 100) else if (it.toFloat() < .42f) Rational(42, 100) else it } else Rational(16, 9)
        return PictureInPictureParams.Builder().setAspectRatio(r).apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(store.getPref("vpip", "1") == "1") }.build()
    }
    private fun updatePipParams() { try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {} }
    private val pipSupported by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private fun enterPip() { if (!pipSupported) { toast("Picture-in-picture isn't available"); return }; try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) { toast("Picture-in-picture is turned off for this app in Android settings") } }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT < 31 && pipSupported && store.getPref("vpip", "1") == "1" && player?.isPlaying == true) enterPip() }
    private fun close() { if (resume) savePos(); player?.stop(); player?.release(); player = null; finishAndRemoveTask() }
    @Deprecated("Deprecated in Java") override fun onBackPressed() { if (locked) { unlockBtn.visibility = View.VISIBLE; return }; close() }

    private var wasInPip = false
    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (inPip) wasInPip = true
        view.useController = !inPip && !locked
        if (inPip) { view.hideController(); bar.visibility = View.GONE; hud.visibility = View.GONE }
    }

    private enum class Drag { NONE, BRIGHT, VOLUME, SEEK, PASS }
    private var drag = Drag.NONE
    private var dragStart = 0f; private var startVal = 0f; private var seekTarget = 0L; private var eatUntilUp = false; private var startX = 0f
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val detector by lazy { GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!dtap) return false; val p = player ?: return false; val w = view.width.toFloat()
            return when {
                e.x < w * .38f -> { p.seekTo(maxOf(0, p.currentPosition - seekStep)); hudShow("${seekStep / 1000}s", Icons.Rounded.FastRewind); eatUntilUp = true; true }
                e.x > w * .62f -> { p.seekTo(p.currentPosition + seekStep); hudShow("${seekStep / 1000}s", Icons.Rounded.FastForward); eatUntilUp = true; true }
                else -> { if (p.isPlaying) p.pause() else p.play(); hudShow("", if (p.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause); eatUntilUp = true; true }
            }
        }
    }) }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isInPictureInPictureMode) return super.dispatchTouchEvent(ev)
        if (locked) {
            if (ev.actionMasked == MotionEvent.ACTION_UP && unlockBtn.visibility != View.VISIBLE) { unlockBtn.visibility = View.VISIBLE; ui.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500); return true }
            return if (unlockBtn.visibility == View.VISIBLE) super.dispatchTouchEvent(ev) else true
        }
        if (!gestures || player == null) return super.dispatchTouchEvent(ev)
        detector.onTouchEvent(ev)
        val h = view.height.toFloat(); val w = view.width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { drag = if (ev.y < h * .15f || ev.y > h * .80f) Drag.PASS else Drag.NONE; dragStart = ev.y; startX = ev.x; if (eatUntilUp) return true }
            MotionEvent.ACTION_MOVE -> {
                if (eatUntilUp) return true
                if (drag == Drag.NONE) {
                    val dx = ev.x - startX; val dy = ev.y - dragStart; val slop = dp(18)
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) { drag = if (startX < w / 2) Drag.BRIGHT else Drag.VOLUME; startVal = if (drag == Drag.BRIGHT) currentBrightness() else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat(); dragStart = ev.y; cancelChild(ev) }
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) { drag = Drag.SEEK; startX = ev.x; seekTarget = player?.currentPosition ?: 0; startVal = seekTarget.toFloat(); cancelChild(ev) }
                }
                when (drag) {
                    Drag.BRIGHT -> { val v = (startVal + (dragStart - ev.y) / (h * .7f) * sens).coerceIn(.01f, 1f); setBrightness(v); hudShow("${(v * 100).toInt()}%", Icons.Rounded.BrightnessMedium); return true }
                    Drag.VOLUME -> { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val v = (startVal + (dragStart - ev.y) / (h * .7f) * max * sens).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); hudShow("${v * 100 / max}%", if (v == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp); return true }
                    Drag.SEEK -> { val dur = player?.duration?.takeIf { it > 0 } ?: 0L; seekTarget = (startVal + (ev.x - startX) / w * 120_000f * sens).toLong().coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE); val d = seekTarget - startVal.toLong(); hudShow((if (d >= 0) "+" else "−") + clock(abs(d)) + "   " + clock(seekTarget) + if (dur > 0) " / " + clock(dur) else ""); return true }
                    else -> {}
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val d = drag; drag = Drag.NONE
                if (eatUntilUp) { eatUntilUp = false; return true }
                if (d == Drag.SEEK) { player?.seekTo(seekTarget); return true }
                if (d == Drag.BRIGHT || d == Drag.VOLUME) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }
    private fun cancelChild(ev: MotionEvent) { val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(c); c.recycle() }
    private fun currentBrightness(): Float { val b = window.attributes.screenBrightness; if (b >= 0) return b; return try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { .5f } }
    private fun setBrightness(v: Float) { window.attributes = window.attributes.apply { screenBrightness = v } }

    private fun icon(v: androidx.compose.ui.graphics.vector.ImageVector, sizeDp: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.BitmapDrawable(resources, iconBitmap(v, dp(sizeDp))).apply { setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }
    private fun pill(text: String, ic: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(if (text.isEmpty()) 9 else 12), dp(6), dp(if (text.isEmpty()) 9 else 12), dp(6)); gravity = Gravity.CENTER_VERTICAL
        if (ic != null) { setCompoundDrawablesRelative(icon(ic, 18), null, null, null); compoundDrawablePadding = if (text.isEmpty()) 0 else dp(6) }
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x66FFFFFF); setStroke(1, 0x55FFFFFF) }
        setOnClickListener { onClick(); if (!locked) view.showController() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
    }
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private fun hudShow(t: String, ic: androidx.compose.ui.graphics.vector.ImageVector? = null) { hud.text = t; hud.setCompoundDrawablesRelative(ic?.let { icon(it, 26) }, null, null, null); hud.compoundDrawablePadding = if (t.isEmpty()) 0 else dp(8); hud.gravity = Gravity.CENTER_VERTICAL; hud.visibility = View.VISIBLE; ui.removeCallbacks(hideHud); ui.postDelayed(hideHud, 900) }
    private fun toast(t: String) = hudShow(t)
    private fun showMsg(t: String) { msg.text = t; msg.visibility = View.VISIBLE; bar.visibility = View.VISIBLE }
    private fun clock(ms: Long): String { val t = ms / 1000; val h = t / 3600; val m = t % 3600 / 60; val sec = (t % 60).toString().padStart(2, '0'); return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec" }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) }
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); player?.let { out.putLong("pos", it.currentPosition) } }
    override fun onPause() { super.onPause(); if (resume) savePos(); if (!isInPictureInPictureMode && !bgAudio) player?.let { startAt = it.currentPosition; it.pause() } }
    override fun onStop() { super.onStop(); if (resume) savePos(); if (!bgAudio || isFinishing) player?.pause(); if (wasInPip && !isFinishing) close() }
    override fun onResume() { super.onResume(); wasInPip = false; hideBars() }
    override fun onDestroy() { super.onDestroy(); ui.removeCallbacksAndMessages(null); if (resume) savePos(); try { boost?.release() } catch (_: Throwable) {}; player?.release(); player = null }
}

/** Draws a Compose [ImageVector] into a white bitmap of [px] × [px]. */
internal fun iconBitmap(v: androidx.compose.ui.graphics.vector.ImageVector, px: Int, color: Int = Color.WHITE): android.graphics.Bitmap {
    val n = px.coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); c.scale(n / v.viewportWidth, n / v.viewportHeight)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    fun draw(g: androidx.compose.ui.graphics.vector.VectorGroup) {
        for (node in g) when (node) {
            is androidx.compose.ui.graphics.vector.VectorPath -> {
                val p = androidx.compose.ui.graphics.vector.PathParser().addPathNodes(node.pathData).toPath().let { (it as androidx.compose.ui.graphics.AndroidPath).internalPath }
                p.fillType = if (node.pathFillType == androidx.compose.ui.graphics.PathFillType.EvenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                c.drawPath(p, paint)
            }
            is androidx.compose.ui.graphics.vector.VectorGroup -> draw(node)
        }
    }
    draw(v.root)
    return bmp
}
