package com.tgmonitor.app.ui

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.ui.theme.Semantic

/**
 * The two most common reasons a scan works with the app open but not in the background:
 * battery optimization and Data Saver. Shows both, with a shortcut to fix each.
 */
@Composable
fun BackgroundHealthCard() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val batteryOk = remember(refresh) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
    val saverBlocks = remember(refresh) {
        val status = context.getSystemService(ConnectivityManager::class.java)?.restrictBackgroundStatus
        status == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    AppCard {
        Text("لماذا قد يعمل الفحص والتطبيق مفتوح فقط؟", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("استثناء البطارية", Modifier.weight(1f))
            if (batteryOk) Pill("مفعّل", Semantic.success()) else Pill("غير مفعّل", Semantic.warning())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("توفير البيانات", Modifier.weight(1f))
            if (saverBlocks) Pill("يمنع الإنترنت بالخلفية", MaterialTheme.colorScheme.error) else Pill("لا يمنع", Semantic.success())
        }
        if (!batteryOk || saverBlocks) {
            Text(
                "عند تفعيل أحدهما يوقف النظام اتصال التطبيق بالإنترنت وهو بالخلفية، فيفشل الفحص المجدول (ومعه البروكسي) ويعمل الفحص اليدوي لأن التطبيق مفتوح.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!batteryOk) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("استثناء البطارية") }
            }
            if (saverBlocks) {
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                            Uri.parse("package:" + context.packageName),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("السماح بالبيانات") }
            }
            OutlinedButton(onClick = { refresh++ }) { Text("تحديث") }
        }
    }
}
