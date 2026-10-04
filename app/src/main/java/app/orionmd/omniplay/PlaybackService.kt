package app.orionmd.omniplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder

/** Foreground service so music keeps playing with the app in the background. */
class PlaybackService : Service() {

    companion object {
        const val CH = "playback"
        const val NID = 42
        const val A_TOGGLE = "toggle"; const val A_NEXT = "next"; const val A_PREV = "prev"; const val A_STOP = "stop"

        fun refresh(ctx: Context) {
            if (Player.current() == null) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            try { nm.notify(NID, build(ctx)) } catch (_: Exception) {}
        }

        private fun pi(ctx: Context, action: String, req: Int): PendingIntent =
            PendingIntent.getService(ctx, req, Intent(ctx, PlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
            val t = Player.current()
            val title = t?.optString("t")?.ifEmpty { null } ?: t?.optString("n") ?: "Media Hub"
            val artist = t?.optString("a") ?: ""
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = Notification.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title)
                .setContentText(if (Player.preparing) "Loading…" else artist)
                .setContentIntent(open)
                .setOngoing(Player.isPlaying() || Player.preparing)
                .setShowWhen(false)
                .addAction(Notification.Action.Builder(null, "Prev", pi(ctx, A_PREV, 1)).build())
                .addAction(Notification.Action.Builder(null, if (Player.isPlaying()) "Pause" else "Play", pi(ctx, A_TOGGLE, 2)).build())
                .addAction(Notification.Action.Builder(null, "Next", pi(ctx, A_NEXT, 3)).build())
                .addAction(Notification.Action.Builder(null, "Stop", pi(ctx, A_STOP, 4)).build())
                .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            t?.optLong("i")?.let { id ->
                val st = Store(ctx)
                val cv = t.optString("cv")
                var f = if (cv.isNotEmpty()) MusicMeta.coverFile(st, cv).takeIf { it.exists() } ?: st.artFile(id) else st.artFile(id)
                if (f.exists()) Vault.decodeBitmap(f)?.let { b.setLargeIcon(it) }
            }
            return b.build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NID, n)
        when (intent?.action) {
            A_TOGGLE -> Player.toggle()
            A_NEXT -> Player.next()
            A_PREV -> Player.prev()
            A_STOP -> { Player.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
}
