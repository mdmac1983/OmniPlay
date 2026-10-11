package app.orionmd.omniplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import app.orionmd.omniplay.cloud.Cloud
import app.orionmd.omniplay.cloud.CloudProxy
import androidx.media3.common.Player as M3

/**
 * Music player (ExoPlayer). The queue lives here (not in the UI) so playback keeps advancing with the screen off.
 * Plays device files, saved offline copies, or cloud files (through the local proxy). Its audio
 * passes through [Visual] for the visualizer. Handles audio focus (pauses for calls and other players) and pauses when
 * headphones are unplugged. Everything here runs on the main thread.
 */
@OptIn(UnstableApi::class)
object Player {
    private var exo: ExoPlayer? = null
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    var queue = JSONArray(); private set
    var index = -1; private set
    var sourceId = ""; private set
    @Volatile var preparing = false; private set
    var error = ""; private set
    private var token = 0
    var repeat = "off" // off | all | one

    fun current(): JSONObject? = if (index in 0 until queue.length()) queue.getJSONObject(index) else null

    private val listener = object : M3.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val p = exo ?: return; val ctx = app ?: return
            if (p.currentMediaItem?.mediaId != token.toString()) return
            when (state) {
                M3.STATE_READY -> { preparing = false; PlaybackService.refresh(ctx) }
                M3.STATE_ENDED -> onComplete()
                else -> {}
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { app?.let { PlaybackService.refresh(it) } }
        // ReplayGain: read the new song's loudness tags for the sound engine
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val f = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
            Dsp.readReplayGain(f?.metadata)
        }
        override fun onPlayerError(e: PlaybackException) {
            preparing = false
            error = if (e.errorCode in 2000..2999) "Can't reach the file — check your connection" else "Can't play this file (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})"
            app?.let { PlaybackService.refresh(it) }
        }
    }

    private fun exo(ctx: Context): ExoPlayer = exo ?: ExoPlayer.Builder(ctx, Visual.renderers(ctx))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { p ->
            p.addListener(listener); exo = p
            // our own audio session so the equaliser and volume boost can attach to it
            try { val sid = androidx.media3.common.util.Util.generateAudioSessionIdV21(ctx); p.audioSessionId = sid; Fx.attach(ctx, sid) } catch (_: Throwable) {}
        }

    fun playQueue(ctx: Context, srcId: String, q: JSONArray, start: Int) {
        app = ctx.applicationContext
        sourceId = srcId
        queue = q; index = start
        playIndex()
    }

    /** Turns an item's URI into something ExoPlayer can play: a saved offline copy, a device/SAF URI, or a cloud file
     *  served by the local [CloudProxy] (handles auth + seeking + on-the-fly decryption). */
    private fun resolveUri(ctx: Context, item: JSONObject): Uri {
        Downloads.localUri(ctx, item.optLong("i"))?.let { return it }
        val u = item.optString("u")
        if (u.startsWith("omni://")) {
            val r = Cloud.resolve(u) ?: throw Exception("cloud account missing")
            return Uri.parse(CloudProxy.url(r.first.id, r.second))
        }
        return Uri.parse(u)
    }

    private fun playIndex() {
        val ctx = app ?: return
        val item = current() ?: return
        val my = ++token
        preparing = true; error = ""
        try { exo?.stop() } catch (_: Exception) {}
        startService(ctx)
        thread {
            try {
                val uri = resolveUri(ctx, item)
                main.post {
                    if (my != token) return@post
                    val p = exo(ctx)
                    Visual.basePosMs = 0
                    p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(my.toString()).build())
                    p.prepare(); p.playWhenReady = true
                }
            } catch (e: Exception) {
                main.post {
                    if (my == token) {
                        preparing = false
                        error = "Can't stream — you appear to be offline"
                        PlaybackService.refresh(ctx)
                    }
                }
            }
        }
    }

    private fun onComplete() {
        if (sleepEndOfSong) { sleepEndOfSong = false; exo?.playWhenReady = false; app?.let { PlaybackService.refresh(it) }; return }
        when {
            repeat == "one" -> playIndex()
            index < queue.length() - 1 -> { index++; playIndex() }
            repeat == "all" && queue.length() > 0 -> { index = 0; playIndex() }
            else -> { app?.let { PlaybackService.refresh(it) } }
        }
    }

    fun toggle() {
        val p = exo
        if (p == null || p.playbackState == M3.STATE_IDLE) { if (current() != null) playIndex(); return }
        if (p.playbackState == M3.STATE_ENDED) { p.seekTo(0); Visual.basePosMs = 0; p.playWhenReady = true }
        else p.playWhenReady = !p.playWhenReady
        app?.let { PlaybackService.refresh(it) }
    }

    fun next() { if (index < queue.length() - 1) { index++; playIndex() } else if (repeat == "all" && queue.length() > 0) { index = 0; playIndex() } }

    fun prev() {
        val pos = positionMs()
        if (pos > 3000 || index <= 0) seek(0) else { index--; playIndex() }
    }

    /** Swipe back: the previous song (the start of this one when it's the first). */
    fun back() { when { index > 0 -> { index--; playIndex() }; repeat == "all" && queue.length() > 1 -> { index = queue.length() - 1; playIndex() }; else -> seek(0) } }

    fun jump(i: Int) { if (i in 0 until queue.length()) { index = i; playIndex() } }

    fun seek(ms: Int) { try { exo?.let { it.seekTo(ms.toLong()); Visual.basePosMs = ms.toLong() } } catch (_: Exception) {} }

    fun isPlaying(): Boolean = try { exo?.let { it.playWhenReady && (it.playbackState == M3.STATE_READY || it.playbackState == M3.STATE_BUFFERING) } == true } catch (_: Exception) { false }

    /** Where the song is now (ms) — read every frame by the visualizer. */
    fun positionMs(): Long = try { exo?.currentPosition ?: 0L } catch (_: Exception) { 0L }
    fun durationMs(): Long = try { exo?.duration?.takeIf { it > 0 } ?: 0L } catch (_: Exception) { 0L }

    fun stop() {
        token++
        releasePlayer()
        queue = JSONArray(); index = -1; preparing = false
        app?.let { it.stopService(Intent(it, PlaybackService::class.java)) }
    }

    private fun releasePlayer() {
        Fx.release()
        try { exo?.release() } catch (_: Exception) {}
        exo = null
    }

    // ---------- sleep timer ----------
    /** When the music stops (ms since epoch), or 0. */
    var sleepAt = 0L; private set
    /** Stop at the end of the current song. */
    var sleepEndOfSong = false; private set
    private val sleepRun = Runnable { fadeAndPause() }
    fun setSleep(minutes: Int) {
        main.removeCallbacks(sleepRun); sleepEndOfSong = false; sleepAt = 0L
        if (minutes > 0) { sleepAt = System.currentTimeMillis() + minutes * 60_000L; main.postDelayed(sleepRun, minutes * 60_000L) }
    }
    fun sleepAtEndOfSong() { main.removeCallbacks(sleepRun); sleepAt = 0L; sleepEndOfSong = true }
    /** Fades out over 8 seconds, then pauses (volume comes back for next time). */
    private fun fadeAndPause() {
        sleepAt = 0L
        val p = exo ?: return
        var step = 0
        val fade = object : Runnable { override fun run() {
            step++
            val v = 1f - step / 40f
            if (v <= 0f || exo == null) { exo?.let { it.playWhenReady = false; it.volume = 1f }; app?.let { PlaybackService.refresh(it) }; return }
            p.volume = v; main.postDelayed(this, 200)
        } }
        main.post(fade)
    }

    fun state(): JSONObject {
        val o = JSONObject()
        o.put("playing", isPlaying())
        o.put("preparing", preparing)
        o.put("error", error)
        o.put("index", index)
        o.put("size", queue.length())
        o.put("repeat", repeat)
        o.put("sourceId", sourceId)
        val p = exo
        var pos = 0L; var dur = 0L
        if (p != null && !preparing) try { pos = p.currentPosition; dur = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L } catch (_: Exception) {}
        o.put("pos", pos); o.put("dur", if (dur < 0) 0 else dur)
        current()?.let { o.put("track", it) }
        return o
    }

    private fun startService(ctx: Context) {
        val i = Intent(ctx, PlaybackService::class.java)
        try { ctx.startForegroundService(i) } catch (_: Exception) {}
    }
}
