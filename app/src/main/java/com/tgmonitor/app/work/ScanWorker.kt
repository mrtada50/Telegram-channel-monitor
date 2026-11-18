package com.tgmonitor.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tgmonitor.app.TgMonitorApp
import com.tgmonitor.app.engine.ScanNotification
import com.tgmonitor.app.engine.ScanOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as TgMonitorApp).container
        val settings = container.settings.current()
        val single = inputData.getLong(KEY_CHANNEL_ID, -1L)
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        if (!manual && !settings.monitoringEnabled) return Result.success()

        // Queue: stalest channel first, strictly one at a time, with a random 4-12 s pause between channels
        // (a person opening channels one after another, never a burst).
        val channels = if (single > 0) listOfNotNull(container.store.getChannel(single))
        else container.store.getEnabledChannelsStalestFirst()

        val notifications = mutableListOf<ScanNotification>()
        var rateLimited = false
        for ((index, channel) in channels.withIndex()) {
            if (isStopped) break
            if (index > 0) delay(Random.nextLong(MIN_GAP_MS, MAX_GAP_MS + 1))
            val outcome = try {
                container.engine.scanChannel(channel.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScanOutcome(status = "FAILED", error = e.message)
            }
            notifications += outcome.notifications
            if (outcome.rateLimited) {
                rateLimited = true
                break
            }
        }
        container.notifier.notify(notifications, settings)
        return if (rateLimited) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_CHANNEL_ID = "channel_id"
        const val KEY_MANUAL = "manual"
        private const val MIN_GAP_MS = 4_000L
        private const val MAX_GAP_MS = 12_000L
    }
}
