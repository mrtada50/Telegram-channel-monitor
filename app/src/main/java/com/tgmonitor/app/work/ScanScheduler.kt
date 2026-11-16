package com.tgmonitor.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.tgmonitor.app.data.AppSettings
import com.tgmonitor.app.engine.IntervalPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

class ScanScheduler(context: Context) {
    private val wm = WorkManager.getInstance(context.applicationContext)

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * One unique periodic work: no duplicate workers. [replace] = true applies a new interval
     * (UPDATE swaps the spec without cancelling a running scan); false keeps the existing schedule.
     */
    fun schedule(intervalMinutes: Int, replace: Boolean) {
        val minutes = intervalMinutes.coerceAtLeast(IntervalPolicy.MIN).toLong()
        val request = PeriodicWorkRequestBuilder<ScanWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodic() {
        wm.cancelUniqueWork(PERIODIC_NAME)
    }

    fun ensureScheduled(settings: AppSettings) {
        if (settings.monitoringEnabled) schedule(settings.intervalMinutes, replace = false) else cancelPeriodic()
    }

    /** Manual or compensation scan. [channelId] null = all enabled channels. */
    fun scanNow(channelId: Long?, manual: Boolean) {
        val request = OneTimeWorkRequestBuilder<ScanWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(ScanWorker.KEY_CHANNEL_ID to (channelId ?: -1L), ScanWorker.KEY_MANUAL to manual))
            .build()
        wm.enqueueUniqueWork(
            if (channelId == null) MANUAL_NAME else "$MANUAL_NAME-$channelId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun manualScanRunning(): Flow<Boolean> =
        wm.getWorkInfosForUniqueWorkFlow(MANUAL_NAME).map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        }

    companion object {
        const val PERIODIC_NAME = "channel_scan_periodic"
        const val MANUAL_NAME = "channel_scan_manual"
    }
}
