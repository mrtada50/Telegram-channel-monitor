package com.tgmonitor.app.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tgmonitor.app.MainActivity
import com.tgmonitor.app.data.AppSettings
import com.tgmonitor.app.engine.NotificationKind
import com.tgmonitor.app.engine.ScanNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Notifier(context: Context) {
    private val context = context.applicationContext

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CHANGES, "تغييرات القنوات", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "تنبيهات الأعطال", NotificationManager.IMPORTANCE_DEFAULT))
    }

    @SuppressLint("MissingPermission")
    fun notify(events: List<ScanNotification>, settings: AppSettings) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        events.filter { settings.isEnabled(it.kind) }
            .groupBy { it.channelId to it.kind }
            .forEach { (_, group) ->
                if (group.size > SUMMARY_THRESHOLD) {
                    val first = group.first()
                    post(
                        nm, first, "${first.channelTitle} — ${first.kind.label}",
                        "${group.size} حدثاً • ${formatTime(first.detectedAt)}", group.size,
                    )
                } else {
                    group.forEach { e ->
                        val number = e.messageId?.let { "المنشور #$it • " } ?: ""
                        val detail = e.detail?.takeIf { it.isNotBlank() && e.kind != NotificationKind.EDIT }?.let { " • $it" } ?: ""
                        post(nm, e, "${e.channelTitle} — ${e.kind.label}", number + formatTime(e.detectedAt) + detail, 0)
                    }
                }
            }
    }

    @SuppressLint("MissingPermission")
    private fun post(nm: NotificationManagerCompat, e: ScanNotification, title: String, text: String, salt: Int) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val channel = when (e.kind) {
            NotificationKind.NEW_POST, NotificationKind.EDIT,
            NotificationKind.DELETION_CONFIRMED, NotificationKind.DELETION_SUSPECTED -> CH_CHANGES
            else -> CH_ALERTS
        }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val id = (e.channelId * 1_000_003L + (e.messageId ?: 0L) * 31L + e.kind.ordinal * 7L + salt).toInt()
        nm.notify(id, n)
    }

    private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

    companion object {
        const val CH_CHANGES = "changes"
        const val CH_ALERTS = "alerts"
        private const val SUMMARY_THRESHOLD = 3
    }
}
