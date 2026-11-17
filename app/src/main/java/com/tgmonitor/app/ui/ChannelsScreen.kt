package com.tgmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.engine.ChannelInputParser

@Composable
fun ChannelsScreen(vm: ChannelsViewModel, onOpen: (Long) -> Unit) {
    val channels by vm.channels.collectAsState()
    val adding by vm.adding.collectAsState()
    val message by vm.message.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ChannelEntity?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "title") {
                ScreenTitle("القنوات", if (channels.isEmpty()) "أضف قناة عامة لبدء المراقبة" else "${channels.size} قناة تحت المراقبة")
            }
            if (adding) {
                item(key = "adding") {
                    AppCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("جارٍ إضافة القناة وفحصها...")
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            message?.let { m -> item(key = "message") { MessageBanner(m, vm::dismissMessage) } }
            if (channels.isEmpty() && !adding) {
                item(key = "empty") {
                    EmptyState(
                        Icons.Filled.Menu, "لا توجد قنوات بعد",
                        "أضف قناة عامة بالمعرّف أو الرابط، وسيبدأ التطبيق بمراقبة منشوراتها وتعديلاتها وحذفها.",
                        "إضافة قناة", { showAdd = true },
                    )
                }
            }
            items(channels, key = { it.id }, contentType = { "channel" }) { ch ->
                ChannelCard(
                    ch,
                    onOpen = { onOpen(ch.id) },
                    onToggle = { vm.setMonitoring(ch.id, it) },
                    onScan = { vm.scan(ch.id) },
                    onDelete = { toDelete = ch },
                )
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("إضافة قناة") },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (showAdd) {
        AddChannelDialog(onDismiss = { showAdd = false }, onAdd = { vm.add(it); showAdd = false })
    }
    toDelete?.let { ch ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("حذف القناة؟") },
            text = { Text("سيتم حذف القناة وكل منشوراتها ونسخها وسجل تغييراتها نهائياً من الجهاز.") },
            confirmButton = { TextButton(onClick = { vm.remove(ch.id); toDelete = null }) { Text("حذف", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("إلغاء") } },
        )
    }
}

@Composable
private fun ChannelCard(
    ch: ChannelEntity,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onScan: () -> Unit,
    onDelete: () -> Unit,
) {
    val color = statusColor(ch.status)
    val problem = ch.status != ChannelStatus.OK && ch.status != ChannelStatus.PENDING
    AppCard(
        onClick = onOpen,
        containerColor = if (problem) color.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surface,
        borderColor = if (problem) color.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelAvatar(ch.title)
            Column(Modifier.weight(1f)) {
                Text(ch.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${ch.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(shortStatus(ch.status), color)
        }
        if (problem) Text(statusLabel(ch.status), style = MaterialTheme.typography.bodySmall, color = color)
        Text(
            "آخر فحص ناجح: ${TimeUtil.relative(ch.lastSuccessfulCheck)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = ch.monitoringEnabled, onCheckedChange = onToggle)
            Text(
                if (ch.monitoringEnabled) "المراقبة تعمل" else "المراقبة متوقفة",
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onScan) { Icon(Icons.Filled.Refresh, contentDescription = "فحص يدوي") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "حذف", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AddChannelDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val valid = ChannelInputParser.parse(input) != null
    val showError = input.isNotBlank() && !valid
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة قناة عامة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("@username أو رابط t.me") },
                    isError = showError,
                    supportingText = { Text(if (showError) "صيغة غير صالحة" else "قنوات عامة فقط") },
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                TextButton(onClick = { clipboard.getText()?.text?.let { input = it.trim() } }) { Text("لصق من الحافظة") }
            }
        },
        confirmButton = { Button(onClick = { onAdd(input) }, enabled = valid) { Text("إضافة") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
