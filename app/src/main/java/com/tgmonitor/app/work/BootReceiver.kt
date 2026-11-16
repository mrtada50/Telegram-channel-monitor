package com.tgmonitor.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tgmonitor.app.TgMonitorApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** After reboot/app update: re-ensure the schedule and run a compensation scan right away. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as TgMonitorApp).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = container.settings.current()
                container.scheduler.ensureScheduled(settings)
                if (settings.monitoringEnabled) container.scheduler.scanNow(null, manual = false)
            } finally {
                pending.finish()
            }
        }
    }
}
