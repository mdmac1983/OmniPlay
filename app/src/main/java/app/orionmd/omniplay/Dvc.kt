package app.orionmd.omniplay

import android.content.Context
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import kotlin.math.pow

/**
 * Direct Volume Control (DVC), like Poweramp's: Android's media volume is set to maximum and the volume is applied
 * inside Media Hub's 64-bit engine instead ([HiResSink]), so quiet listening keeps full resolution and the effects get
 * the whole range. Volume keys (and the video player's volume swipe) then change Media Hub's own volume — in the
 * background too, through a remote-volume media session.
 *
 * Safety: when DVC takes over it starts from the loudness you had (via Android's standard media volume curve, a little
 * quieter), and whenever nothing is playing in Media Hub the system volume is put back to the matching level, so other
 * apps don't play at full volume.
 */
object Dvc {
    const val MAX = 100
    private const val FLOOR_DB = -60.0
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val am get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var session: MediaSession? = null

    @Volatile var enabled = false; private set
    /** 0..[MAX]; 0 = silent. */
    @Volatile var level = 50; private set
    /** Linear gain the hi-res output applies (1.0 when DVC is off). */
    @Volatile var gain = 1.0; private set
    @Volatile var engaged = false; private set
    private var playing = 0
    /** Called with the new level after a key press (for the on-screen volume). */
    var onChange: ((Int) -> Unit)? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val st = Store(app)
        enabled = st.getPref("dvc", "0") == "1" && HiResSink.active
        level = st.getPref("dvcLevel", "50").toIntOrNull()?.coerceIn(0, MAX) ?: 50
        updateGain()
        // the app was closed (or killed) while DVC had the system volume at maximum: put it back first
        if (st.getPref("dvcEngaged", "0") == "1") { try { am.setStreamVolume(AudioManager.STREAM_MUSIC, systemFromLevel(), 0) } catch (_: Exception) {}; st.setPref("dvcEngaged", "0") }
    }

    private fun db(l: Int) = FLOOR_DB * (1 - l.toDouble() / MAX)
    private fun updateGain() { gain = if (!enabled) 1.0 else if (level <= 0) 0.0 else 10.0.pow(db(level) / 20) }
    private fun save() { val st = Store(app); st.setPref("dvc", if (enabled) "1" else "0"); st.setPref("dvcLevel", level.toString()) }

    // Android's default media volume curve (index % → dB), used to move between system volume and DVC level.
    private val CURVE = listOf(1.0 to -58.0, 20.0 to -40.0, 60.0 to -17.0, 100.0 to 0.0)
    private fun pctToDb(p: Double): Double {
        if (p <= 0) return Double.NEGATIVE_INFINITY
        if (p <= CURVE[0].first) return CURVE[0].second
        for (i in 1 until CURVE.size) { val (x1, y1) = CURVE[i]; val (x0, y0) = CURVE[i - 1]; if (p <= x1) return y0 + (y1 - y0) * (p - x0) / (x1 - x0) }
        return 0.0
    }
    private fun dbToPct(d: Double): Double {
        if (d <= CURVE[0].second) return CURVE[0].first
        for (i in 1 until CURVE.size) { val (x1, y1) = CURVE[i]; val (x0, y0) = CURVE[i - 1]; if (d <= y1) return x0 + (x1 - x0) * (d - y0) / (y1 - y0) }
        return 100.0
    }
    private fun levelFromSystem(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1); val idx = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (idx <= 0) return 0
        val d = pctToDb(idx * 100.0 / max) - 3   // start a little quieter than before, never louder
        return ((d - FLOOR_DB) / -FLOOR_DB * MAX).toInt().coerceIn(1, MAX)
    }
    private fun systemFromLevel(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (level <= 0) return 0
        return Math.round(dbToPct(db(level)) * max / 100).toInt().coerceIn(1, max)
    }

    /** Turns DVC on/off (needs the hi-res output). Turning it off puts the system volume back to the same loudness. */
    /** DVC only works through the hi-res output that's running now (otherwise full system volume would be heard as is). */
    val available get() = HiResSink.active

    fun setEnabled(on: Boolean) {
        if (on == enabled || (on && !available)) return
        if (on) { level = levelFromSystem(); enabled = true; updateGain(); if (playing > 0) engage() }
        else { if (engaged) disengage(); enabled = false; updateGain() }
        save()
    }

    fun setLevel(l: Int) { level = l.coerceIn(0, MAX); updateGain(); session?.let { s -> provider?.currentVolume = level }; save() }
    fun step(up: Boolean) { setLevel(level + if (up) 3 else -3); onChange?.invoke(level) }

    // ---------- playing / not playing (from the hi-res output) ----------
    private val release = Runnable { if (playing == 0 && engaged) disengage() }
    fun sinkPlaying() = main.post { playing++; main.removeCallbacks(release); if (enabled && !engaged) engage() }
    fun sinkStopped() = main.post { playing = (playing - 1).coerceAtLeast(0); if (playing == 0) { main.removeCallbacks(release); main.postDelayed(release, 1500) } }

    private var provider: VolumeProvider? = null
    private fun engage() {
        engaged = true; Store(app).setPref("dvcEngaged", "1")
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) } catch (_: Exception) {}
        // volume keys in the background → Media Hub's volume
        try {
            val p = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX, level) {
                override fun onSetVolumeTo(volume: Int) { setLevel(volume) }
                override fun onAdjustVolume(direction: Int) { if (direction != 0) step(direction > 0) }
            }
            provider = p
            val s = session ?: MediaSession(app, "MediaHubDVC").also { session = it }
            s.setPlaybackToRemote(p)
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build())
            s.isActive = true
        } catch (_: Exception) {}
    }
    private fun disengage() {
        engaged = false; Store(app).setPref("dvcEngaged", "0")
        try { am.setStreamVolume(AudioManager.STREAM_MUSIC, systemFromLevel(), 0) } catch (_: Exception) {}
        try { session?.isActive = false } catch (_: Exception) {}
    }
}
