package com.tgmonitor.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.data.ExportFormat
import com.tgmonitor.app.engine.IntervalPolicy
import com.tgmonitor.app.engine.NotificationKind

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val stats by vm.stats.collectAsState()
    val exportMessage by vm.exportMessage.collectAsState()
    val context = LocalContext.current
    var custom by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    var pendingFormat by remember { mutableStateOf(ExportFormat.JSON) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.export(uri, pendingFormat)
    }
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("الإعدادات") }

        item { SectionTitle("المراقبة وفترة الفحص") }
        item { SwitchRow("تشغيل المراقبة الدورية", settings.monitoringEnabled) { vm.setMonitoring(it) } }
        item { Text("الفترة الحالية: ${settings.intervalMinutes} دقيقة") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(IntervalPolicy.PRESETS) { m ->
                    FilterChip(selected = settings.intervalMinutes == m, onClick = { vm.setInterval(m) }, label = { Text("$m") })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it; customError = null }, singleLine = true,
                    label = { Text("فترة مخصصة (دقائق)") }, isError = customError != null, modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    when (val v = IntervalPolicy.validate(custom)) {
                        is IntervalPolicy.Validation.Valid -> { vm.setInterval(v.minutes); customError = null }
                        is IntervalPolicy.Validation.Invalid -> customError = v.reason
                    }
                }) { Text("تطبيق") }
            }
        }
        customError?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item {
            Text(
                "Android لا يسمح بفترة دورية أقل من 15 دقيقة، والتنفيذ غير مضمون بالدقيقة بسبب توفير البطارية. " +
                    "التطبيق لا يعمل والهاتف مطفأ بالكامل، وعند عودته يُجري فحصاً تعويضياً لما ما زال متاحاً على صفحات تيليجرام العامة.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("إعدادات استثناء البطارية") }
        }

        item { SectionTitle("الإشعارات") }
        items(NotificationKind.values().toList()) { kind ->
            SwitchRow(kind.label, settings.isEnabled(kind)) { vm.setNotification(kind, it) }
        }

        item { SectionTitle("التخزين") }
        item {
            Text(
                "قنوات: ${stats.channels} • منشورات: ${stats.messages} • نسخ: ${stats.versions} • حجم القاعدة: ${stats.dbBytes / 1024} KB\n" +
                    "لا يُحذف أي منشور أو نسخة تلقائياً؛ يتم الحذف فقط عند حذفك قناة يدوياً.",
            )
        }

        item { SectionTitle("تصدير البيانات") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pendingFormat = ExportFormat.JSON; launcher.launch("tg-monitor-export-json.zip") }) { Text("JSON") }
                Button(onClick = { pendingFormat = ExportFormat.CSV; launcher.launch("tg-monitor-export-csv.zip") }) { Text("CSV") }
            }
        }
        item { Text("يشمل: القنوات، الرسائل، النسخ السابقة، سجل التعديلات، سجل الحذف، سجل الفحص. تبقى البيانات محلية.", style = MaterialTheme.typography.bodySmall) }
        exportMessage?.let { item { Text(it) } }

        item { SectionTitle("معلومات التطبيق") }
        item { Text("مراقب قنوات تيليجرام • الإصدار $version\nالمصدر: صفحات t.me/s العامة فقط، بلا تسجيل دخول، ولا يُرسل أي شيء لخوادم خارجية.") }
    }
}
