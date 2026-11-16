package com.tgmonitor.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus

@Composable
fun HomeScreen(vm: HomeViewModel) {
    val s by vm.state.collectAsState()
    val scanning by vm.scanning.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("مراقب قنوات تيليجرام") }
        if (s.previewDisabled > 0) {
            item { WarningCard("${s.previewDisabled} قناة معاينتها العامة موقفة ولا يمكن مراقبتها بدون تسجيل دخول.") }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("القنوات", s.channels.toString(), Modifier.weight(1f))
                StatCard("المنشورات المسجلة", s.messages.toString(), Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("تعديلات اليوم", s.editsToday.toString(), Modifier.weight(1f))
                StatCard("حذوفات اليوم", s.deletionsToday.toString(), Modifier.weight(1f))
            }
        }
        item { InfoCard("آخر فحص ناجح", TimeUtil.format(s.lastScan)) }
        item {
            InfoCard(
                "حالة المراقبة",
                if (s.settings.monitoringEnabled) "تعمل تقريباً كل ${s.settings.intervalMinutes} دقيقة (التوقيت غير مضمون بالدقيقة)" else "متوقفة",
            )
        }
        item {
            Button(onClick = vm::scanNow, enabled = !scanning, modifier = Modifier.fillMaxWidth()) {
                Text(if (scanning) "جارٍ الفحص..." else "فحص يدوي الآن")
            }
        }
    }
}

@Composable
fun ChannelsScreen(vm: ChannelsViewModel, onOpen: (Long) -> Unit) {
    val channels by vm.channels.collectAsState()
    val adding by vm.adding.collectAsState()
    val message by vm.message.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ChannelEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ScreenHeader("القنوات")
                Button(onClick = { showAdd = true }, enabled = !adding) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(if (adding) "جارٍ الإضافة..." else "إضافة قناة", Modifier.padding(start = 4.dp))
                }
            }
        }
        message?.let { m ->
            item {
                Card(Modifier.fillMaxWidth().clickable { vm.dismissMessage() }) {
                    Text(m, Modifier.padding(12.dp))
                }
            }
        }
        if (channels.any { it.status == ChannelStatus.PREVIEW_DISABLED }) {
            item { WarningCard("قنوات معاينتها العامة موقفة: لا يمكن قراءتها بالـ Scraping، وهي موسومة أدناه.") }
        }
        if (channels.isEmpty()) item { Text("لا توجد قنوات بعد.") }
        items(channels, key = { it.id }) { ch ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(ch.id) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ch.title, style = MaterialTheme.typography.titleMedium)
                    Text("@${ch.username}", style = MaterialTheme.typography.bodySmall)
                    Text(statusLabel(ch.status), color = statusColor(ch.status))
                    Text("آخر فحص ناجح: ${TimeUtil.format(ch.lastSuccessfulCheck)}", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        SwitchRow(if (ch.monitoringEnabled) "المراقبة تعمل" else "المراقبة متوقفة", ch.monitoringEnabled) {
                            vm.setMonitoring(ch.id, it)
                        }
                    }
                    Row {
                        IconButton(onClick = { vm.scan(ch.id) }) { Icon(Icons.Default.Refresh, contentDescription = "فحص يدوي") }
                        IconButton(onClick = { toDelete = ch }) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("إضافة قناة عامة") },
            text = {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    label = { Text("@username أو رابط t.me") },
                )
            },
            confirmButton = { TextButton(onClick = { vm.add(input); showAdd = false }) { Text("إضافة") } },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("إلغاء") } },
        )
    }
    toDelete?.let { ch ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف القناة؟") },
            text = { Text("سيتم حذف القناة وكل منشوراتها ونسخها وسجل تغييراتها نهائياً من الجهاز.") },
            confirmButton = { TextButton(onClick = { vm.remove(ch.id); toDelete = null }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("إلغاء") } },
        )
    }
}
