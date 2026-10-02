package app.orionmd.omniplay.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.orionmd.omniplay.Library
import app.orionmd.omniplay.Net
import app.orionmd.omniplay.Source
import app.orionmd.omniplay.Store
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Background sync: rescans cloud sources that have sync turned on, so new files show up without opening the app. Runs on
 * the schedule set in Settings (default daily), only on Wi-Fi when "Wi-Fi only" is on.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        Cloud.init(store)
        if (store.getPref("syncWifiOnly", "1") == "1" && !Net.wifi(applicationContext)) return Result.retry()
        val sources = try { JSONArray(store.getPref("sources", "[]")).let { a -> (0 until a.length()).map { Source.of(a.getJSONObject(it)) } } } catch (_: Exception) { emptyList() }
        for (src in sources) {
            if (src.kind != "cloud" || !src.sync) continue
            try { Library.scan(applicationContext, store, src) } catch (_: Exception) {}
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "omniplay-sync"
        fun schedule(ctx: Context, hours: Long, wifiOnly: Boolean) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(hours.coerceAtLeast(1), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork(NAME) }
    }
}
