package app.orionmd.omniplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps offline downloads going in the background, with a notification showing the overall percentage. */
class DlService : Service() {
    companion object {
        private const val CH = "downloads"; private const val NID = 43
        private const val A_PAUSE = "pauseAll"; private const val A_RESUME = "resumeAll"
        @Volatile private var running = false
        private var lastNotify = 0L

        fun start(ctx: Context) {
            if (running) { update(ctx); return }
            try { ctx.startForegroundService(Intent(ctx, DlService::class.java)) } catch (_: Exception) { update(ctx) }
        }
        /** Refreshes the notification (at most twice a second); stops the service when nothing is left to do. */
        fun update(ctx: Context) {
            val active = DlQueue.entries.values.any { it.state == DlQueue.State.RUNNING || it.state == DlQueue.State.QUEUED }
            if (!active && running) { ctx.stopService(Intent(ctx, DlService::class.java)); return }
            val now = System.currentTimeMillis(); if (now - lastNotify < 500) return; lastNotify = now
            try { ctx.getSystemService(NotificationManager::class.java).notify(NID, build(ctx)) } catch (_: Exception) {}
        }
        private fun pi(ctx: Context, a: String, r: Int) = PendingIntent.getService(ctx, r, Intent(ctx, DlService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val (n, pct) = DlQueue.overall()
            val paused = DlQueue.entries.values.isNotEmpty() && DlQueue.entries.values.all { it.state == DlQueue.State.PAUSED || it.state == DlQueue.State.FAILED }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "downloads"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(ctx, CH).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (paused) "Downloads paused" else "Saving $n file${if (n == 1) "" else "s"} for offline")
                .setContentText(if (pct >= 0) "$pct%" else "Starting…").setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!paused)
                .setProgress(100, pct.coerceAtLeast(0), pct < 0 && !paused)
                .addAction(Notification.Action.Builder(null, if (paused) "Resume all" else "Pause all", pi(ctx, if (paused) A_RESUME else A_PAUSE, 1)).build())
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        DlQueue.init(this)
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NID, n)
        when (intent?.action) { A_PAUSE -> DlQueue.pauseAll(); A_RESUME -> DlQueue.resumeAll() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { running = false; super.onDestroy() }
}
