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

    // subtitles are drawn by the player itself (own layer), so the delay works both ways and any encoding can be read
    private lateinit var subs: SubtitleView
    private lateinit var subsBtn: TextView
    private lateinit var audioBtn: TextView
    private data class ExtSub(val label: String, val uri: Uri, val name: String)
    private val extSubs = java.util.concurrent.ConcurrentHashMap<Long, List<ExtSub>>()
    private val loadedSubs = java.util.concurrent.ConcurrentHashMap<Long, MutableList<ExtSub>>()   // picked with "Load subtitle file"
    private var extCues: List<SubCue>? = null
    private var extSel = -1                     // index into the current video's external subtitles, -1 = none
    private var subDelay = 0L                   // ms, + = later
    private var lastSubKey: Any? = null
    private var vDecoder = "auto"
    private var errId = 0L; private var errTries = 0
    private var videoDecoder = ""; private var audioDecoder = ""
    private var base = 0                        // queue index the player's first item came from
    private var shuffle = false
    private var zoom = 1f; private var panX = 0f; private var panY = 0f

    // the music player's skin, carried over to the video player (Now Playing → palette, or More → Skin here)
    private var skin = app.orionmd.omniplay.ui.SKINS[0]
    private val allPills = mutableListOf<TextView>()
    private lateinit var skinBg: SkinBgView
    private lateinit var logoView: android.widget.ImageView
    private var accent = 0xFF9E86FF.toInt()

    private val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private val sizes = listOf("fit" to "Best fit", "zoom" to "Fill screen (crop)", "fill" to "Fit width", "stretch" to "Stretch",
        "r16x9" to "16:9", "r4x3" to "4:3", "r21x9" to "21:9 (2.39:1)", "r185" to "1.85:1", "r16x10" to "16:10", "r5x4" to "5:4", "r1x1" to "1:1")
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
        vDecoder = store.getPref("vdecoder", "auto")
        Dsp.avDelayMs = 0
        applyOrientation(store.getPref("vorient", "sensor"))

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        skin = app.orionmd.omniplay.ui.skinOf(store.getPref("playerSkin", "midnight"))
        skinBg = SkinBgView(this)
        root.addView(skinBg, FrameLayout.LayoutParams(-1, -1))
        view = PlayerView(this).apply {
            setShowSubtitleButton(false); setShowNextButton(true); setShowPreviousButton(true)
            setShowFastForwardButton(true); setShowRewindButton(true); keepScreenOn = true
            controllerShowTimeoutMs = (store.getPref("vhide", "3").toIntOrNull() ?: 3) * 1000
        }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.subtitleView?.visibility = View.GONE
        subs = SubtitleView(this)
        root.addView(subs, FrameLayout.LayoutParams(-1, -1))

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
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            logoView = android.widget.ImageView(this@VideoActivity).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
            addView(logoView, LinearLayout.LayoutParams(dp(72), dp(30)).apply { marginStart = dp(8) })
            addView(clockTv)
        })
        val pills = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        bar.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(pills) })
        speedBtn = pill("1×", Icons.Rounded.Speed) { speedSheet() }
        sizeBtn = pill("Fit", Icons.Rounded.AspectRatio) { sizeSheet() }
        subsBtn = pill("Subtitles", Icons.Rounded.ClosedCaption) { subsSheet() }
        audioBtn = pill("Audio", Icons.Rounded.Audiotrack) { audioSheet() }
        rotBtn = pill(rots.first { it.first == store.getPref("vorient", "sensor") }.second, Icons.Rounded.ScreenRotation) { cycleRotation() }
        sleepBtn = pill("Sleep", Icons.Rounded.Bedtime) { cycleSleep() }
        abBtn = pill("A-B", Icons.Rounded.Loop) { cycleAb() }
        repBtn = pill("Off", Icons.Rounded.RepeatOne) { cycleRepeat() }
        boostBtn = pill(boostLabel(), Icons.AutoMirrored.Rounded.VolumeUp) { cycleBoost() }
        val extra = mutableListOf<TextView>()
        if (store.getPref("vintro", "1") == "1") extra += pill("Skip intro", Icons.Rounded.FastForward) { player?.let { it.seekTo(it.currentPosition + (store.getPref("vintrolen", "85").toLongOrNull() ?: 85L) * 1000); toast("Skipped intro") } }
        fun on(k: String) = store.getPref("vbtn_$k", "1") == "1"   // Settings → Playback → Player buttons
        if (on("sleep")) extra += sleepBtn; if (on("ab")) extra += abBtn; if (on("repeat")) extra += repBtn; if (on("boost")) extra += boostBtn
        if (store.getPref("vshot", "1") == "1") extra += pill("", Icons.Rounded.PhotoCamera) { screenshot() }
        val main = listOfNotNull(pill("Close", Icons.Rounded.Close) { close() }, pill("Options", Icons.Rounded.Tune) { optionsSheet() }, subsBtn.takeIf { on("subs") }, audioBtn.takeIf { on("audio") },
            speedBtn.takeIf { on("speed") }, sizeBtn.takeIf { on("size") }, rotBtn.takeIf { on("rotate") })
        (main + extra + (if (pipSupported && on("pip")) listOf(pill("Picture-in-picture", Icons.Rounded.PictureInPictureAlt) { enterPip() }) else emptyList()) +
            listOf(pill("More", Icons.Rounded.MoreHoriz) { moreSheet() }, pill("Lock", Icons.Rounded.Lock) { setLocked(true) })).forEach { pills.addView(it) }
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
        view.setBackgroundColor(Color.TRANSPARENT)
        applySkin()
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> if (!locked) bar.visibility = if (msg.visibility == View.VISIBLE) View.VISIBLE else v })

        styleSubtitles()
        sizeBtn.text = applySize(store.getPref("vresize", "fit")).substringBefore(" (")
        ui.post(ticker); ui.post(subTicker)
        if (!gateOk()) return
        load(savedInstanceState?.getLong("pos"))
    }

    override fun onNewIntent(i: android.content.Intent) {
        super.onNewIntent(i)
        if (resume) savePos()
        player?.release(); player = null; view.player = null
        setIntent(i); msg.visibility = View.GONE
        if (!gateOk()) return
        load(null)
    }

    /** Opened from another app (file manager, browser…): the same licence rules as opening Media Hub itself. */
    private fun gateOk(): Boolean {
        if (intent?.action != android.content.Intent.ACTION_VIEW) return true
        val ok = !(Guard.tampered(this) && store.secret("ownerUnlocked") != "1") && Activation.state(store) != Activation.State.LOCKED
        if (!ok) { startActivity(android.content.Intent(this, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }
        return ok
    }

    /** A queue for a video handed over by another app ("Open with Media Hub") or a network stream URL. */
    private fun externalQueue(): JSONArray? {
        val d = intent?.data ?: return null
        if (intent?.action != android.content.Intent.ACTION_VIEW) return null
        try { contentResolver.takePersistableUriPermission(d, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = try { contentResolver.query(d, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (_: Exception) { null }
            ?: d.lastPathSegment?.substringAfterLast('/') ?: d.toString()
        return JSONArray().put(JSONObject().put("i", idFor(d.toString())).put("t", name).put("u", d.toString()))
    }

    private fun load(pos: Long?) {
        queue = externalQueue() ?: try { JSONArray(intent.getStringExtra("queue") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (queue.length() == 0) { finish(); return }
        title.text = queue.getJSONObject(0).optString("t")
        startAt = pos ?: if (resume) savedPos(queue.getJSONObject(0).optLong("i")).let { if (it > 0) maxOf(0L, it - (store.getPref("vback", "0").toLongOrNull() ?: 0L) * 1000) else it } else 0L
        resolved = 0; resolving = false; gen++; base = 0
        thread {
            try { val first = buildItem(queue.getJSONObject(0)); runOnUiThread { if (!isDestroyed) start(first) } }
            catch (e: Exception) { runOnUiThread { showMsg("Can't play this video (" + (e.message ?: "unknown") + ").") } }
        }
    }

    /** Resolve the video (and its subtitles) to URIs ExoPlayer can play: offline copy, device URI, or cloud proxy URL. */
    private fun buildItem(q: JSONObject): MediaItem {
        val id = q.optLong("i")
        val src = resolve(q.optString("u"), id)
        val subsJ = q.optJSONArray("subs") ?: JSONArray()
        val ext = (0 until subsJ.length()).mapNotNull { k ->
            val o = subsJ.getJSONObject(k); val name = o.optString("n")
            val uri = subUri(o) ?: return@mapNotNull null
            ExtSub(o.optString("label").ifEmpty { name }, uri, name)
        }.toMutableList()
        // subtitles downloaded by "Sync all" (Wyzie Subs) — saved encrypted, decrypted to a temp copy for the player
        val lang = store.getPref("subLang", "en")
        val online = try { Subs.playable(store, cacheDir, id, lang) } catch (_: Exception) { null }
        if (online != null) ext += ExtSub("Online ($lang)", Uri.fromFile(online), "online.srt")
        // a device video: matching subtitle files in the same folder (Movie.srt, Movie.en.srt…)
        if (ext.isEmpty()) ext += siblingSubs(src)
        extSubs[id] = ext
        return MediaItem.Builder().setUri(src).setMediaId(id.toString()).build()
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
        player?.let { old -> try { boost?.release() } catch (_: Throwable) {}; old.release() }
        val p = ExoPlayer.Builder(this, OmniRenderers(this, video = true, decoder = vDecoder)).setSeekBackIncrementMs(seekStep).setSeekForwardIncrementMs(seekStep).build()
        player = p; view.player = p
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
            override fun onAudioDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
        })
        try { val sid = androidx.media3.common.util.Util.generateAudioSessionIdV21(this); p.audioSessionId = sid; boost = android.media.audiofx.LoudnessEnhancer(sid); applyBoost() } catch (_: Throwable) { boost = null }
        store.getPref("vspeed", "1").toFloatOrNull()?.takeIf { abs(it - 1f) > .01f }?.let { p.playbackParameters = PlaybackParameters(it); speedBtn.text = fmtSpeed(it) }
        val subsOn = store.getPref("subauto", "1") == "1"
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(if (subsOn) java.util.Locale.getDefault().language else null)
            .setSelectUndeterminedTextLanguage(subsOn).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
        p.pauseAtEndOfMediaItems = !autoNext
        p.addListener(object : M3Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // recover by itself where it can: a decoder the device chokes on → software decoders (FFmpeg) for the rest
                // of this session; a dropped connection or expired cloud link → a fresh link, from the same spot
                val id = currentId(); if (id != errId) { errId = id; errTries = 0 }
                val c = error.errorCode
                val decode = c in 4000..4999
                val net = c in 2000..2999 || c == PlaybackException.ERROR_CODE_TIMEOUT
                if (decode && vDecoder != "sw" && errTries < 2) { errTries++; vDecoder = "sw"; toast("Switching to software decoding…"); restartHere(); return }
                if (net && errTries < 3) { errTries++; toast("Reconnecting…"); ui.postDelayed({ restartHere() }, 1500L * errTries); return }
                showMsg(when {
                    net -> if (Net.online(this@VideoActivity)) "Lost the connection. Tap play to try again." else "Can't stream — you appear to be offline."
                    decode -> "This device can't decode this video."
                    else -> "Can't play this video."
                } + " (" + error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') + ")")
            }
            // embedded subtitles (inside MKV/MP4): shown on our layer, delayed if a positive delay is set
            override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                if (extCues != null) return
                val cs = cueGroup.cues
                if (subDelay > 0) ui.postDelayed({ if (extCues == null) subs.setCues(cs) }, subDelay) else subs.setCues(cs)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == M3Player.STATE_ENDED && p.repeatMode == M3Player.REPEAT_MODE_OFF) { forget(currentId()); finish() }
                if (state == M3Player.STATE_READY) { msg.visibility = View.GONE; prefetchNext() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                abA = -1L; abB = -1L; abBtn.text = "A-B"
                val prevIdx = p.previousMediaItemIndex
                if (reason == M3Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prevIdx >= 0) p.getMediaItemAt(prevIdx).mediaId.toLongOrNull()?.let { forget(it) }
                val q = queueEntry(mediaItem?.mediaId?.toLongOrNull() ?: 0L)
                title.text = q?.optString("t") ?: ""
                if (reason != M3Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT && q != null) toast(q.optString("t"))
                if (reason != M3Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) autoSubs()
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
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { updatePipParams(); ui.post { applyRatio(); setZoom(zoom, panX, panY) } }
        })
        p.setMediaItem(item, startAt); resolved = base + 1
        autoSubs()
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


    // ================================================================= subtitles
    /** Subtitle files next to a video file (Movie.srt, Movie.en.srt, Movie.forced.vtt…). */
    private fun siblingSubs(src: Uri): List<ExtSub> {
        if (src.scheme != "file") return emptyList()
        val f = File(src.path ?: return emptyList()); val stem = f.nameWithoutExtension
        return f.parentFile?.listFiles()?.filter { it.name.startsWith(stem) && it.extension.lowercase() in setOf("srt", "vtt", "ass", "ssa") }
            ?.sortedBy { it.name.length }?.map { ExtSub(it.name.removePrefix(stem).trim('.', ' ').ifEmpty { it.name }, Uri.fromFile(it), it.name) } ?: emptyList()
    }
    private fun currentExt(): List<ExtSub> { val id = currentId(); return (extSubs[id] ?: emptyList()) + (loadedSubs[id] ?: emptyList()) }

    /** On a new video: its own subtitle files first (when "turn on automatically" is on), otherwise embedded ones. */
    private fun autoSubs() {
        extCues = null; extSel = -1; subs.setCues(emptyList()); lastSubKey = null
        if (store.getPref("subauto", "1") == "1" && currentExt().isNotEmpty()) selectExt(0, quiet = true) else updateSubsBtn()
    }
    private fun selectExt(i: Int, quiet: Boolean = false) {
        val e = currentExt().getOrNull(i) ?: return
        val id = currentId(); val enc = store.getPref("subenc", "auto")
        thread {
            val cues = try { contentResolver.openInputStream(e.uri)?.use { SubParser.parse(SubParser.decode(it.readBytes(), enc), e.name) } } catch (_: Exception) { null }
            runOnUiThread {
                if (currentId() != id) return@runOnUiThread
                if (cues.isNullOrEmpty()) { if (!quiet) toast("Couldn't read \"${e.label}\""); return@runOnUiThread }
                extCues = cues; extSel = i; lastSubKey = null
                player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                updateSubsBtn(); if (!quiet) toast("Subtitles: ${e.label}")
            }
        }
    }
    private fun selectEmbedded(g: androidx.media3.common.Tracks.Group, t: Int) {
        val p = player ?: return
        extCues = null; extSel = -1; subs.setCues(emptyList())
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t)).build()
        updateSubsBtn()
    }
    private fun subsOff() {
        val p = player ?: return
        extCues = null; extSel = -1; subs.setCues(emptyList())
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        updateSubsBtn()
    }
    private fun embeddedOn() = player?.let { p -> C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes && p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected } } ?: false
    private fun updateSubsBtn() { subsBtn.text = if (extCues != null || embeddedOn()) "Subtitles" + (if (subDelay != 0L) " " + fmtDelay(subDelay) else "") else "Subtitles off" }
    private fun fmtDelay(ms: Long) = (if (ms > 0) "+" else "−") + "%.2fs".format(abs(ms) / 1000f)

    /** Draws the chosen subtitle file in step with the video (and the delay). */
    private val subTicker = object : Runnable {
        override fun run() {
            val c = extCues; val p = player
            if (c != null && p != null) {
                val now = SubParser.at(c, p.currentPosition - subDelay)
                if (now != lastSubKey) {
                    lastSubKey = now
                    subs.setCues(now.map { cue ->
                        androidx.media3.common.text.Cue.Builder().setText(android.text.Html.fromHtml(cue.text.replace("\n", "<br>"), android.text.Html.FROM_HTML_MODE_LEGACY)).build()
                    })
                }
            }
            ui.postDelayed(this, 50)
        }
    }

    private fun subsSheet() = sheet("Subtitles") { col, done ->
        val p = player
        col.header("Track")
        col.option("Off", extCues == null && !embeddedOn()) { subsOff(); done() }
        currentExt().forEachIndexed { i, e -> col.option(e.label, extSel == i && extCues != null, "file") { selectExt(i); done() } }
        p?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_TEXT }?.forEach { g ->
            for (t in 0 until g.length) { val f = g.getTrackFormat(t); col.option(trackName(f, "Subtitle"), extCues == null && g.isTrackSelected(t), "in the video") { selectEmbedded(g, t); done() } }
        }
        col.action("Load subtitle file…") { done(); pickSubFile() }
        col.header("Delay (+ shows later)")
        val lbl = col.label(fmtDelay(subDelay).let { if (subDelay == 0L) "0.00s" else it })
        fun shift(d: Long) { subDelay = if (d == 0L) 0L else subDelay + d; lastSubKey = null; lbl.text = if (subDelay == 0L) "0.00s" else fmtDelay(subDelay); updateSubsBtn() }
        col.buttons("−1 s" to { shift(-1000) }, "−0.1 s" to { shift(-100) }, "0" to { shift(0) }, "+0.1 s" to { shift(100) }, "+1 s" to { shift(1000) })
        if (extCues == null && embeddedOn()) col.note("Subtitles inside the video can only be delayed (shown later). Load a subtitle file to move them earlier too.")
        col.header("Text encoding")
        val cur = store.getPref("subenc", "auto")
        col.action("Encoding: " + (SubParser.ENCODINGS.firstOrNull { it.first == cur }?.second ?: cur)) {
            done()
            sheet("Subtitle text encoding") { c2, d2 -> SubParser.ENCODINGS.forEach { (k, n) -> c2.option(n, k == cur) { store.setPref("subenc", k); if (extSel >= 0) selectExt(extSel); d2() } } }
        }
        col.header("Look")
        val sizesS = listOf("tiny" to "Tiny", "small" to "Small", "normal" to "Normal", "large" to "Large", "xlarge" to "XL", "huge" to "Huge")
        col.buttons(*sizesS.map { (k, n) -> n to { store.setPref("subsize", k); styleSubtitles() } }.toTypedArray())
        col.note("Colour, outline, background and position: Settings → Playback & subtitles.")
    }

    private val PICK_SUB = 4711
    private fun pickSubFile() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/vtt", "text/plain", "text/x-ssa", "application/octet-stream", "*/*")), PICK_SUB)
        } catch (_: Exception) { toast("No file picker on this device") }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val u = data?.data
        if (requestCode != PICK_SUB || resultCode != RESULT_OK || u == null) return
        try { contentResolver.takePersistableUriPermission(u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = try { contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } } catch (_: Exception) { null } ?: "subtitles.srt"
        val id = currentId()
        val list = loadedSubs.getOrPut(id) { mutableListOf() }; list += ExtSub(name, u, name)
        selectExt(currentExt().size - 1)
    }

    // ================================================================= audio
    private fun trackName(f: androidx.media3.common.Format, kind: String): String {
        val lang = f.language?.let { java.util.Locale(it).displayLanguage.ifEmpty { it } }
        val bits = listOfNotNull(f.label, lang?.takeIf { f.label == null || !f.label!!.contains(it, true) },
            f.channelCount.takeIf { it > 0 }?.let { when (it) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$it ch" } },
            f.sampleMimeType?.substringAfter('/')?.uppercase()?.takeIf { it.length < 12 },
            if (f.width > 0) "${f.width}×${f.height}" else null, f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kb/s" })
        return bits.joinToString(" · ").ifEmpty { kind }
    }

    private fun saveDsp(c: DspCfg) { Dsp.set(c); store.setPref("dsp", c.json().toString()) }

    private fun audioSheet() = sheet("Audio") { col, done ->
        val p = player
        val groups = p?.currentTracks?.groups?.filter { it.type == C.TRACK_TYPE_AUDIO } ?: emptyList()
        col.header("Audio track")
        if (groups.isEmpty()) col.note("No audio tracks.")
        groups.forEach { g -> for (t in 0 until g.length) { val f = g.getTrackFormat(t); col.option(trackName(f, "Audio"), g.isTrackSelected(t)) {
            p?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t)).build() }; done()
        } } }
        col.header("Audio delay (+ plays later)")
        if (HiResSink.active && HiResSink.mode < 3) {
            val lbl = col.label("${Dsp.avDelayMs} ms")
            fun shift(d: Int) { Dsp.avDelayMs = if (d == 0) 0 else (Dsp.avDelayMs + d).coerceIn(-5000, 5000); lbl.text = "${Dsp.avDelayMs} ms" }
            col.buttons("−500" to { shift(-500) }, "−50" to { shift(-50) }, "0" to { shift(0) }, "+50" to { shift(50) }, "+500" to { shift(500) })
        } else col.note("Needs the hi-res output (Settings → Sound).")
        // quick sound settings (the full engine is in Sound & profiles)
        val c = Dsp.cfg
        col.header("Sound")
        col.option("Effects on", c.on && c.video) { saveDsp(c.copy(on = true, video = true)); done() }
        col.option("Effects off for videos", !(c.on && c.video)) { saveDsp(c.copy(video = false)); done() }
        col.note("Equaliser preset")
        col.buttons(*DspCfg.PRESETS.take(9).map { (n, l) -> n to { saveDsp(Dsp.cfg.copy(on = true, video = true, bands = 10, eq = l)); toast("Equaliser: $n") } }.toTypedArray())
        col.buttons(*DspCfg.PRESETS.drop(9).map { (n, l) -> n to { saveDsp(Dsp.cfg.copy(on = true, video = true, bands = 10, eq = l)); toast("Equaliser: $n") } }.toTypedArray())
        col.note("Loudness (preamp, with a limiter so it doesn't clip)")
        col.buttons(*listOf(0f, 3f, 6f, 9f, 12f).map { v -> (if (v == 0f) "0 dB" else "+${v.toInt()} dB") to { saveDsp(Dsp.cfg.copy(on = true, video = true, preamp = v)); toast("Preamp " + if (v == 0f) "0 dB" else "+${v.toInt()} dB") } }.toTypedArray())
        col.note("Dialogue boost")
        col.buttons("Off" to { saveDsp(Dsp.cfg.copy(vocal = 0f)) }, "+3 dB" to { saveDsp(Dsp.cfg.copy(on = true, video = true, vocal = 3f)) }, "+6 dB" to { saveDsp(Dsp.cfg.copy(on = true, video = true, vocal = 6f)) })
        col.note("Night mode (evens out loud and quiet scenes)")
        col.buttons("Off" to { saveDsp(Dsp.cfg.copy(leveler = 0f)) }, "Medium" to { saveDsp(Dsp.cfg.copy(on = true, video = true, leveler = .5f)) }, "Strong" to { saveDsp(Dsp.cfg.copy(on = true, video = true, leveler = 1f)) })
    }

    // ================================================================= picture
    private val contentFrame get() = view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    private fun ratioOf(k: String): Float? = when (k) { "r16x9" -> 16f / 9; "r4x3" -> 4f / 3; "r21x9" -> 2.39f; "r185" -> 1.85f; "r16x10" -> 1.6f; "r5x4" -> 1.25f; "r1x1" -> 1f; else -> null }
    /** Forced aspect ratios (VLC-style) — reapplied whenever the video size changes. */
    private fun applyRatio() {
        val vs = player?.videoSize ?: return
        val forced = ratioOf(sizeKey)
        val natural = if (vs.width > 0 && vs.height > 0) vs.width * vs.pixelWidthHeightRatio / vs.height else 0f
        val r = forced ?: natural
        if (r > 0) contentFrame?.setAspectRatio(r)
    }
    private fun applySize(k: String): String {
        sizeKey = k
        view.resizeMode = when (k) { "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH; "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
        applyRatio()
        return sizes.firstOrNull { it.first == k }?.second ?: "Best fit"
    }
    private var sizeKey = "fit"
    private fun sizeSheet() = sheet("Picture size") { col, done ->
        sizes.forEach { (k, n) -> col.option(n, sizeKey == k && zoom == 1f) { setZoom(1f, 0f, 0f); sizeBtn.text = applySize(k).substringBefore(" ("); store.setPref("vresize", k); toast(n); done() } }
        if (zoom != 1f) col.action("Back to normal size (now ${(zoom * 100).toInt()}%)") { setZoom(1f, 0f, 0f); done() }
        col.note("Tip: pinch out to make the picture bigger, pinch in to make it smaller (down to 25%), and drag with two fingers to move it. Let go near 100% and it snaps back.")
    }
    /** Pinch size: 25 % (a small picture in the middle) up to 400 % (zoomed in, drag to move). */
    private fun setZoom(z: Float, x: Float, y: Float) {
        zoom = z.coerceIn(.25f, 4f); val f = contentFrame ?: return
        if (zoom > 1f) {
            val maxX = f.width * (zoom - 1) / 2; val maxY = f.height * (zoom - 1) / 2
            panX = x.coerceIn(-maxX, maxX); panY = y.coerceIn(-maxY, maxY)
        } else {
            // smaller than the screen: it can be moved around inside the screen
            val maxX = f.width * (1 - zoom) / 2; val maxY = f.height * (1 - zoom) / 2
            panX = x.coerceIn(-maxX, maxX); panY = y.coerceIn(-maxY, maxY)
        }
        f.scaleX = zoom; f.scaleY = zoom; f.translationX = panX; f.translationY = panY
    }
    private fun zoomLabel() = if (kotlin.math.abs(zoom - 1f) < .01f) "100% (${sizes.firstOrNull { it.first == sizeKey }?.second?.substringBefore(" (") ?: "Best fit"})" else "${(zoom * 100).toInt()}%"
    private val scaler by lazy { android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val before = zoom; setZoom(zoom * d.scaleFactor, panX, panY)
            hudShow(zoomLabel(), if (zoom >= before) Icons.Rounded.ZoomIn else Icons.Rounded.ZoomOut); return true
        }
    }) }
    private var pinching = false; private var lastFx = 0f; private var lastFy = 0f

    // ================================================================= speed
    private fun speedSheet() = sheet("Playback speed") { col, _ ->
        val p = player ?: return@sheet
        val lbl = col.label(fmtSpeed(p.playbackParameters.speed))
        val bar = android.widget.SeekBar(this).apply { max = 375; progress = ((p.playbackParameters.speed - .25f) * 100).toInt().coerceIn(0, 375) }
        fun set(v: Float) { val sp = (Math.round(v * 20) / 20f).coerceIn(.25f, 4f); p.playbackParameters = PlaybackParameters(sp); lbl.text = fmtSpeed(sp); speedBtn.text = fmtSpeed(sp); store.setPref("vspeed", sp.toString()); bar.progress = ((sp - .25f) * 100).toInt() }
        bar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: android.widget.SeekBar, v: Int, fromUser: Boolean) { if (fromUser) set(.25f + v / 100f) }
            override fun onStartTrackingTouch(b: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(b: android.widget.SeekBar) {}
        })
        col.addView(bar)
        col.buttons("−0.05" to { set(p.playbackParameters.speed - .05f) }, "1×" to { set(1f) }, "+0.05" to { set(p.playbackParameters.speed + .05f) })
        col.buttons(*listOf(.5f, .75f, 1.25f, 1.5f, 2f, 3f).map { v -> fmtSpeed(v) to { set(v) } }.toTypedArray())
    }
    private fun fmtSpeed(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else "%.2f".format(s).trimEnd('0').trimEnd('.')) + "×"

    // ================================================================= more
    private fun moreSheet() = sheet("More") { col, done ->
        val p = player
        col.action("Jump to time…") { done(); jumpDialog() }
        col.action("Bookmarks…") { done(); bookmarksSheet() }
        col.action("Playlist…  (${queue.length()})") { done(); playlistSheet() }
        col.action("Video track / quality…") { done(); videoSheet() }
        col.action("Media information") { done(); infoDialog() }
        col.action("Skin: ${skin.name}" + (if (skin.theme.isNotEmpty()) " (${skin.theme})" else "")) { done(); skinSheet() }
        col.header("Frame by frame")
        col.buttons("◀ Previous frame" to { frameStep(-1) }, "Next frame ▶" to { frameStep(1) })
        col.header("Playback")
        col.option("Keep playing sound in the background", bgAudio) { bgAudio = !bgAudio; store.setPref("vbgaudio", if (bgAudio) "1" else "0"); toast(if (bgAudio) "Sound keeps playing when you leave" else "Pauses when you leave"); done() }
        col.option("Shuffle the rest of the list", shuffle) { toggleShuffle(); done() }
        col.header("Decoder (hardware acceleration)")
        listOf("auto" to "Automatic", "hw" to "Hardware first (fast, saves battery)", "sw" to "Software first (plays more files, uses more battery)").forEach { (k, n) ->
            col.option(n, vDecoder == k) { if (vDecoder != k) { vDecoder = k; store.setPref("vdecoder", k); restartHere(); toast(n.substringBefore(" (")) }; done() }
        }
        if (p == null) col.note("Nothing playing.")
    }

    private fun frameStep(dir: Int) {
        val p = player ?: return
        p.pause()
        val fps = p.videoFormat?.frameRate?.takeIf { it > 1f } ?: 30f
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        p.seekTo((p.currentPosition + dir * (1000f / fps)).toLong().coerceAtLeast(0))
        hudShow(clock(p.currentPosition), if (dir > 0) Icons.Rounded.SkipNext else Icons.Rounded.SkipPrevious)
    }

    private fun jumpDialog() {
        val p = player ?: return
        val et = android.widget.EditText(this).apply { hint = "h:mm:ss  or  mm:ss"; setText(clock(p.currentPosition)); setSelectAllOnFocus(true); inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Jump to time").setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Go") { _, _ ->
                val parts = et.text.toString().trim().split(':').mapNotNull { it.trim().toLongOrNull() }
                if (parts.isEmpty()) return@setPositiveButton
                val ms = parts.fold(0L) { acc, v -> acc * 60 + v } * 1000
                p.seekTo(ms.coerceIn(0, if (p.duration > 0) p.duration else Long.MAX_VALUE)); toast("Jumped to " + clock(ms))
            }.setNegativeButton("Cancel", null).setOnDismissListener { hideBars() }.show()
    }

    private fun bookmarksSheet(): Unit = sheet("Bookmarks") { col, done ->
        val p = player ?: return@sheet; val id = currentId()
        col.action("Add bookmark at ${clock(p.currentPosition)}") { Bookmarks.add(store, id, p.currentPosition); toast("Bookmark added"); done(); bookmarksSheet() }
        val l = Bookmarks.of(store, id)
        if (l.isEmpty()) col.note("No bookmarks in this video yet.") else col.note("Tap to jump · press and hold to delete")
        l.forEach { ms -> col.action(clock(ms), onLong = { Bookmarks.remove(store, id, ms); toast("Bookmark deleted"); done(); bookmarksSheet() }) { p.seekTo(ms); done() } }
    }

    private fun playlistSheet() = sheet("Playlist") { col, done ->
        val cur = currentId()
        for (k in 0 until queue.length()) {
            val q = queue.getJSONObject(k)
            col.option("${k + 1}. " + q.optString("t"), q.optLong("i") == cur) { if (q.optLong("i") != cur) jumpTo(k, 0L); done() }
        }
    }
    private fun toggleShuffle() {
        shuffle = !shuffle
        if (shuffle) {
            // shuffle what hasn't been queued in the player yet
            val head = (0 until minOf(resolved, queue.length())).map { queue.getJSONObject(it) }
            val tail = (resolved until queue.length()).map { queue.getJSONObject(it) }.shuffled()
            queue = JSONArray((head + tail))
            toast("Shuffle on")
        } else toast("Shuffle off (keeps the current order)")
    }
    /** Starts list item [k] (from the playlist, or to restart the current video with another decoder). */
    private fun jumpTo(k: Int, pos: Long) {
        if (resume) savePos()
        gen++; base = k; resolved = k; resolving = false; startAt = pos
        val q = queue.getJSONObject(k); title.text = q.optString("t")
        thread {
            try { val item = buildItem(q); runOnUiThread { if (!isDestroyed) start(item) } }
            catch (e: Exception) { runOnUiThread { showMsg("Can't play this video (" + (e.message ?: "unknown") + ").") } }
        }
    }
    private fun restartHere() {
        val p = player ?: return
        val cur = currentId(); val k = (0 until queue.length()).firstOrNull { queue.getJSONObject(it).optLong("i") == cur } ?: return
        jumpTo(k, p.currentPosition)
    }

    private fun videoSheet() = sheet("Video track / quality") { col, done ->
        val p = player ?: return@sheet
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        col.option("Automatic", p.trackSelectionParameters.overrides.values.none { it.type == C.TRACK_TYPE_VIDEO }) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build(); done()
        }
        groups.forEach { g -> for (t in 0 until g.length) { val f = g.getTrackFormat(t); col.option(trackName(f, "Video"), g.isTrackSelected(t)) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, t)).build(); done()
        } } }
    }

    private fun infoDialog() {
        val p = player ?: return
        val v = p.videoFormat; val a = p.audioFormat; val q = queueEntry(currentId())
        val lines = mutableListOf<String>()
        lines += "Title: " + (q?.optString("t") ?: title.text)
        p.currentMediaItem?.localConfiguration?.uri?.let { lines += "Source: " + if (it.host == "127.0.0.1") "cloud (streamed)" else (it.lastPathSegment ?: it.toString()) }
        if (p.duration > 0) lines += "Length: " + clock(p.duration)
        if (v != null) {
            lines += ""; lines += "VIDEO"
            lines += "Codec: " + (v.codecs ?: v.sampleMimeType ?: "?")
            lines += "Resolution: ${v.width}×${v.height}" + (if (v.frameRate > 0) " · %.3g fps".format(v.frameRate) else "")
            if (v.bitrate > 0) lines += "Bitrate: ${v.bitrate / 1000} kb/s"
            v.colorInfo?.let { if (it.colorTransfer == C.COLOR_TRANSFER_ST2084 || it.colorTransfer == C.COLOR_TRANSFER_HLG) lines += "HDR: " + if (it.colorTransfer == C.COLOR_TRANSFER_HLG) "HLG" else "HDR10 / PQ" }
            if (videoDecoder.isNotEmpty()) lines += "Decoder: $videoDecoder"
        }
        if (a != null) {
            lines += ""; lines += "AUDIO"
            lines += "Codec: " + (a.codecs ?: a.sampleMimeType ?: "?")
            lines += "Channels: ${a.channelCount} · ${a.sampleRate} Hz" + (a.language?.let { " · " + java.util.Locale(it).displayLanguage } ?: "")
            if (a.bitrate > 0) lines += "Bitrate: ${a.bitrate / 1000} kb/s"
            if (audioDecoder.isNotEmpty()) lines += "Decoder: $audioDecoder"
            if (Dsp.format.isNotEmpty()) lines += "Output: " + Dsp.format
        }
        val tc = p.currentTracks.groups.count { it.type == C.TRACK_TYPE_TEXT }
        lines += ""; lines += "Subtitles: $tc in the video, ${currentExt().size} files"
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Media information")
            .setMessage(lines.joinToString("\n")).setPositiveButton("Close", null).setOnDismissListener { hideBars() }.show()
    }



    // ================================================================= options (sound presets + shortcuts)
    private fun optionsSheet(): Unit = sheet("Options") { col, done ->
        col.header("Sound presets")
        val list = SoundPresets.all(store); val last = SoundPresets.last(store)
        if (list.isEmpty()) col.note("No saved presets yet — tap \"Save current sound\" to keep the sound you're hearing (equaliser, effects, volume boost).")
        else col.note("Tap to use · press and hold to update, rename or delete")
        list.forEach { p ->
            col.addView(rowView((if (p.id == last) "●  " else "○  ") + p.name + "\n     " + SoundPresets.describe(p.cfg), { done(); presetMenu(p) }) {
                SoundPresets.apply(store, p); toast("Sound: ${p.name}"); done()
            }.apply { if (p.id == last) setTextColor(accentOnDark()) })
        }
        col.action("Save current sound…") { done(); presetName("Save sound preset", "") { n -> val p = SoundPresets.saveCurrent(store, n); store.setPref("soundPresetLast", p.id); toast("Saved \"${p.name}\"") } }
        col.header("Sound")
        val c = Dsp.cfg
        col.option("Effects on for videos", c.on && c.video) { saveDsp(Dsp.cfg.copy(on = true, video = true)); done() }
        col.option("Original sound (effects off for videos)", !(c.on && c.video)) { saveDsp(Dsp.cfg.copy(video = false)); done() }
        col.header("Player")
        col.action("Subtitles…") { done(); subsSheet() }
        col.action("Audio: tracks, delay, equaliser…") { done(); audioSheet() }
        col.action("Speed…") { done(); speedSheet() }
        col.action("Picture size…") { done(); sizeSheet() }
        col.action("Skin: ${skin.name}") { done(); skinSheet() }
        col.action("More: jump, bookmarks, playlist, info, decoder…") { done(); moreSheet() }
    }
    private fun presetMenu(p: SoundPresets.Preset) = sheet(p.name) { col, done ->
        col.action("Use this preset") { SoundPresets.apply(store, p); toast("Sound: ${p.name}"); done() }
        col.action("Update with the current sound") { SoundPresets.saveCurrent(store, p.name); toast("Updated \"${p.name}\""); done() }
        col.action("Rename…") { done(); presetName("Rename preset", p.name) { n -> SoundPresets.rename(store, p.id, n) } }
        col.action("Delete") { SoundPresets.delete(store, p.id); toast("Deleted \"${p.name}\""); done() }
    }
    private fun presetName(title: String, initial: String, ok: (String) -> Unit) {
        val et = android.widget.EditText(this).apply { hint = "e.g. Movie night, Dialogue, Headphones"; setText(initial); setSelectAllOnFocus(true); isSingleLine = true }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("Save") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(ok) }
            .setNegativeButton("Cancel", null).setOnDismissListener { hideBars() }.show()
    }
    // ================================================================= skin
    private fun argb(c: androidx.compose.ui.graphics.Color): Int { return with(c) { androidx.compose.ui.graphics.Color(red, green, blue, alpha) }.let { (it.alpha * 255).toInt() shl 24 or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt() } }
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
    /** The accent, kept readable on the dark controls (very dark accents fall back to the skin's text colour or white). */
    private fun accentOnDark(): Int {
        fun lum(c: Int) = (0.299 * ((c shr 16) and 255) + 0.587 * ((c shr 8) and 255) + 0.114 * (c and 255)) / 255
        val a = argb(skin.accent); if (lum(a) > .3) return a
        val t = argb(skin.text); return if (lum(t) > .3) t else Color.WHITE
    }
    private fun applySkin() {
        val s = skin; accent = accentOnDark()
        val base = argb(s.bg.first()); val dark = !s.light
        val fg = if (dark) argb(s.text) else Color.WHITE
        val pillBg = if (dark) withAlpha(base, 0xB0) else 0x99000000.toInt()
        bar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(withAlpha(if (dark) base else Color.BLACK, 0xCC), 0x00000000))
        title.setTextColor(fg); clockTv.setTextColor(fg)
        allPills.forEach { p ->
            p.setTextColor(fg); p.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(fg)
            p.background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(pillBg); setStroke(dp(1), withAlpha(accent, 0xAA)) }
        }
        hud.background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(withAlpha(if (dark) base else Color.BLACK, 0xD8)); setStroke(dp(1), withAlpha(accent, 0x88)) }
        hud.setTextColor(fg); hud.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(fg)
        view.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.apply {
            setPlayedColor(accent); setScrubberColor(accent); setBufferedColor(withAlpha(accent, 0x66)); setUnplayedColor(0x44FFFFFF)
        }
        listOf(androidx.media3.ui.R.id.exo_play_pause, androidx.media3.ui.R.id.exo_prev, androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_rew_with_amount, androidx.media3.ui.R.id.exo_ffwd_with_amount).forEach { id ->
            (view.findViewById<View>(id) as? android.widget.ImageView)?.imageTintList = android.content.res.ColorStateList.valueOf(if (id == androidx.media3.ui.R.id.exo_play_pause) accent else fg)
        }
        val logo = app.orionmd.omniplay.ui.SkinLogos.load(this, s.id)
        logoView.setImageBitmap(logo); logoView.visibility = if (logo != null) View.VISIBLE else View.GONE
        skinBg.show(if (store.getPref("vskinbg", "1") == "1") s else null, logo.takeIf { store.getPref("skinLogoWm", "0") == "1" })
    }
    private fun skinSheet() = sheet("Skin") { col, done ->
        col.note("The same skins as the music player. The pattern shows in the space around the picture (turn that off in Settings → Playback).")
        app.orionmd.omniplay.ui.SKINS.groupBy { it.theme }.forEach { (theme, list) ->
            col.header(theme.ifEmpty { "Classic" })
            list.forEach { sk -> col.option(sk.name, sk.id == skin.id) { skin = sk; store.setPref("playerSkin", sk.id); applySkin(); toast("Skin: ${sk.name}"); done() } }
        }
    }

    /** Draws the skin's background (gradient + pattern, dimmed) behind the video — visible around the picture. */
    private class SkinBgView(ctx: android.content.Context) : View(ctx) {
        private var skin: app.orionmd.omniplay.ui.PlayerSkin? = null
        private var bmp: android.graphics.Bitmap? = null
        private var logo: android.graphics.Bitmap? = null
        fun show(s: app.orionmd.omniplay.ui.PlayerSkin?, wm: android.graphics.Bitmap? = null) { skin = s; logo = wm; render(); invalidate() }
        private fun render() {
            bmp?.recycle(); bmp = null
            val s = skin ?: return; if (width <= 0 || height <= 0) return
            // drawn at half size and scaled up: the patterns are soft, and it keeps memory low on big screens
            bmp = try { app.orionmd.omniplay.ui.skinBitmap(s, width / 2, height / 2, resources.displayMetrics.density / 2, dim = .45f) } catch (_: Throwable) { null }
        }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { render() }
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(c: android.graphics.Canvas) {
            val b = bmp
            if (b == null) { c.drawColor(Color.BLACK); return }
            c.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), paint)
            logo?.let { l ->   // your skin logo, large and faint
                val side = minOf(width, height) * .6f; val sc = side / maxOf(l.width, l.height)
                val w = l.width * sc; val h = l.height * sc; val left = (width - w) / 2; val top = (height - h) / 2
                c.drawBitmap(l, null, android.graphics.RectF(left, top, left + w, top + h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = 30 })
            }
        }
    }

    // ================================================================= small dialog toolkit
    private fun sheet(title: String, build: (LinearLayout, () -> Unit) -> Unit) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(12)) }
        val d = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
            .setView(android.widget.ScrollView(this).apply { addView(col) }).setPositiveButton("Close", null).create()
        build(col) { d.dismiss() }
        d.setOnDismissListener { hideBars() }
        d.show()
    }
    private fun LinearLayout.header(t: String) = addView(TextView(context).apply { text = t.uppercase(); setTextColor(0xFF9FA4B8.toInt()); textSize = 12f; letterSpacing = .06f; setPadding(dp(8), dp(14), dp(8), dp(4)) })
    private fun LinearLayout.note(t: String) = addView(TextView(context).apply { text = t; setTextColor(0xFFB5B9C9.toInt()); textSize = 12.5f; setPadding(dp(8), dp(4), dp(8), dp(4)) })
    private fun LinearLayout.label(t: String): TextView = TextView(context).apply { text = t; setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }.also { addView(it) }
    private fun rowView(t: String, onLong: (() -> Unit)?, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(10), dp(11), dp(10), dp(11))
        val tv = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }; if (onLong != null) setOnLongClickListener { onLong(); true }
    }
    private fun LinearLayout.option(t: String, selected: Boolean, sub: String? = null, onClick: () -> Unit) =
        addView(rowView((if (selected) "●  " else "○  ") + t + (sub?.let { "   ($it)" } ?: ""), null, onClick).apply { if (selected) setTextColor(accentOnDark()) })
    private fun LinearLayout.action(t: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) = addView(rowView(t, onLong, onClick))
    private fun LinearLayout.buttons(vararg b: Pair<String, () -> Unit>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        b.forEach { (t, f) -> row.addView(TextView(context).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(0x33FFFFFF) }
            setOnClickListener { f() }
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        }) }
        addView(android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    private var rotKey = "sensor"
    private fun applyOrientation(k: String) { rotKey = k; requestedOrientation = when (k) { "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR } }
    private fun cycleRotation() { val next = rots[(rots.indexOfFirst { it.first == rotKey } + 1) % rots.size]; applyOrientation(next.first); rotBtn.text = next.second; store.setPref("vorient", next.first); toast(next.second) }

    private fun setLocked(on: Boolean) {
        locked = on; view.useController = !on
        if (on) { view.hideController(); bar.visibility = View.GONE; unlockBtn.visibility = View.VISIBLE; ui.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500); toast("Screen locked") }
        else { unlockBtn.visibility = View.GONE; view.showController() }
    }

    private fun styleSubtitles() { styleSubtitlesOn(subs) }
    private fun styleSubtitlesOn(sv: SubtitleView) {
        val scale = when (store.getPref("subsize", "normal")) { "tiny" -> .65f; "small" -> .8f; "large" -> 1.3f; "xlarge" -> 1.6f; "huge" -> 2f; else -> 1f }
        val fg = when (store.getPref("subcolor", "white")) { "yellow" -> 0xFFFFE14D.toInt(); "cyan" -> 0xFF7FE7FF.toInt(); "green" -> 0xFF8CFF8C.toInt(); "pink" -> 0xFFFF9AD5.toInt(); "orange" -> 0xFFFFB347.toInt(); else -> Color.WHITE }
        val bgKind = store.getPref("subbg", "shadow")
        val alpha = (store.getPref("subbgop", "80").toIntOrNull() ?: 80).coerceIn(0, 100) * 255 / 100
        val bg = if (bgKind == "box") (alpha shl 24) else 0x00000000
        val win = if (bgKind == "window") (alpha shl 24) else Color.TRANSPARENT
        val edge = when (store.getPref("subedge", "outline")) { "none" -> CaptionStyleCompat.EDGE_TYPE_NONE; "shadow" -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW; else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE }
        sv.apply {
            setApplyEmbeddedStyles(store.getPref("subembed", "1") == "1" && bgKind == "shadow")
            // sized from the screen's short side, so subtitles are the same size upright as sideways (a fraction of the
            // height made them ~1.7× too big in portrait)
            val dm = resources.displayMetrics
            setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, minOf(dm.widthPixels, dm.heightPixels) * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
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
    private fun cycleRepeat() {
        val p = player ?: return
        p.repeatMode = when (p.repeatMode) { M3Player.REPEAT_MODE_OFF -> M3Player.REPEAT_MODE_ONE; M3Player.REPEAT_MODE_ONE -> M3Player.REPEAT_MODE_ALL; else -> M3Player.REPEAT_MODE_OFF }
        repBtn.text = when (p.repeatMode) { M3Player.REPEAT_MODE_ONE -> "One"; M3Player.REPEAT_MODE_ALL -> "All"; else -> "Off" }
        toast(when (p.repeatMode) { M3Player.REPEAT_MODE_ONE -> "Repeat this video"; M3Player.REPEAT_MODE_ALL -> "Repeat the whole list"; else -> "Repeat off" })
        if (p.repeatMode == M3Player.REPEAT_MODE_ALL) p.pauseAtEndOfMediaItems = false
    }

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
        val name = "MediaHub_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".jpg"
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Media Hub")
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
    /** Direct Volume Control: volume keys change Media Hub's own volume while it's playing. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (Dvc.engaged && (k == android.view.KeyEvent.KEYCODE_VOLUME_UP || k == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) { Dvc.step(k == android.view.KeyEvent.KEYCODE_VOLUME_UP); hudShow("${Dvc.level}%", if (Dvc.level == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp) }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isInPictureInPictureMode) return super.dispatchTouchEvent(ev)
        if (locked) {
            if (ev.actionMasked == MotionEvent.ACTION_UP && unlockBtn.visibility != View.VISIBLE) { unlockBtn.visibility = View.VISIBLE; ui.postDelayed({ if (locked) unlockBtn.visibility = View.INVISIBLE }, 2500); return true }
            return if (unlockBtn.visibility == View.VISIBLE) super.dispatchTouchEvent(ev) else true
        }
        if (player == null) return super.dispatchTouchEvent(ev)
        // two fingers: pinch in / out to resize, drag to move the picture (like VLC)
        scaler.onTouchEvent(ev)   // sees every touch, so a pinch is recognised from the moment the second finger lands
        if (ev.pointerCount >= 2 || pinching) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { if (!pinching) { pinching = true; drag = Drag.NONE; cancelChild(ev) }; lastFx = (ev.getX(0) + ev.getX(1)) / 2; lastFy = (ev.getY(0) + ev.getY(1)) / 2 }
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2) { val fx = (ev.getX(0) + ev.getX(1)) / 2; val fy = (ev.getY(0) + ev.getY(1)) / 2; if (zoom > 1f) setZoom(zoom, panX + fx - lastFx, panY + fy - lastFy); lastFx = fx; lastFy = fy }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pinching = false
                    if (kotlin.math.abs(zoom - 1f) < .07f) { setZoom(1f, 0f, 0f); hudShow(zoomLabel(), Icons.Rounded.FitScreen) }   // snaps back to the normal size
                }
            }
            return true
        }
        if (!gestures) return super.dispatchTouchEvent(ev)
        detector.onTouchEvent(ev)
        val h = view.height.toFloat(); val w = view.width.toFloat()
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { drag = if (ev.y < h * .15f || ev.y > h * .80f) Drag.PASS else Drag.NONE; dragStart = ev.y; startX = ev.x; if (eatUntilUp) return true }
            MotionEvent.ACTION_MOVE -> {
                if (eatUntilUp) return true
                if (drag == Drag.NONE) {
                    val dx = ev.x - startX; val dy = ev.y - dragStart; val slop = dp(18)
                    if (abs(dy) > slop && abs(dy) > abs(dx) * 1.5f) { drag = if (startX < w / 2) Drag.BRIGHT else Drag.VOLUME; startVal = if (drag == Drag.BRIGHT) currentBrightness() else if (Dvc.engaged) Dvc.level.toFloat() else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat(); dragStart = ev.y; cancelChild(ev) }
                    else if (abs(dx) > slop && abs(dx) > abs(dy) * 1.5f) { drag = Drag.SEEK; startX = ev.x; seekTarget = player?.currentPosition ?: 0; startVal = seekTarget.toFloat(); cancelChild(ev) }
                }
                when (drag) {
                    Drag.BRIGHT -> { val v = (startVal + (dragStart - ev.y) / (h * .7f) * sens).coerceIn(.01f, 1f); setBrightness(v); hudShow("${(v * 100).toInt()}%", Icons.Rounded.BrightnessMedium); return true }
                    Drag.VOLUME -> if (Dvc.engaged) { val v = (startVal + (dragStart - ev.y) / (h * .7f) * Dvc.MAX * sens).toInt().coerceIn(0, Dvc.MAX); Dvc.setLevel(v); hudShow("$v%", if (v == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp); return true } else { val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC); val v = (startVal + (dragStart - ev.y) / (h * .7f) * max * sens).toInt().coerceIn(0, max); audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0); hudShow("${v * 100 / max}%", if (v == 0) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp); return true }
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
        allPills += this
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
