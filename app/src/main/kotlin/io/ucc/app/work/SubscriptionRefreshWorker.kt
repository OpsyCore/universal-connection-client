package io.ucc.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.ucc.app.UccApplication
import io.ucc.applogic.SubscriptionRefresher
import java.util.concurrent.TimeUnit

/**
 * Periodic subscription refresh. Runs on WorkManager so it survives process
 * death and respects Doze; requires a connected network. Each subscription is
 * refreshed at most once per [MIN_INTERVAL_HOURS] (or the server-suggested
 * `profile-update-interval` if longer). Failures never touch stored profiles.
 *
 * Logging is limited to counts and redacted error labels — never URLs or bodies.
 */
class SubscriptionRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = UccApplication.graph(applicationContext)
        val hours = graph.settingsStore.settings.value.subscriptionUpdateIntervalHours
        if (hours <= 0) { Log.i(TAG, "periodic refresh disabled in settings"); return Result.success() }
        val outcomes = graph.subscriptionRefresher.refreshAllDue(minIntervalMs = TimeUnit.HOURS.toMillis(hours.toLong()))
        val failed = outcomes.count { it !is SubscriptionRefresher.Outcome.Updated }
        Log.i(TAG, "subscription refresh: ${outcomes.size} due, $failed failed")
        // Only network failures across the board are worth WorkManager's backoff retry; everything else waits for the next period.
        val allNetwork = outcomes.isNotEmpty() && outcomes.all { it is SubscriptionRefresher.Outcome.Failed }
        return if (allNetwork) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "SubRefresh"
        private const val UNIQUE_NAME = "subscription-refresh"
        /** Pre-v1.0.2 fixed period; now the default of `ConnectionSettings.subscriptionUpdateIntervalHours` (24) applies. */
        const val MIN_INTERVAL_HOURS = 12L

        /**
         * (Re)schedules the periodic job for [intervalHours] (0 = cancel). `UPDATE` keeps the existing
         * job's next-run time when only the period changed, so toggling settings does not trigger an
         * immediate refresh. WorkManager's minimum period is 15 min; we never go below 6 h.
         */
        fun schedule(context: Context, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalHours <= 0) { wm.cancelUniqueWork(UNIQUE_NAME); return }
            val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
