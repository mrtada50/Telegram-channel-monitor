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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.data.ChangeEventEntity
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.DeletionStatus
import com.tgmonitor.app.engine.ContentFormat
import com.tgmonitor.app.engine.DiffType
import com.tgmonitor.app.engine.LinkCodec
import com.tgmonitor.app.engine.WordDiff

@Composable
private fun EventCard(e: ChangeEventEntity, channelName: String?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(changeTypeLabel(e.changeType), style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(channelName, "المنشور #${e.messageId}").joinToString(" • "), style = MaterialTheme.typography.bodySmall)
            if (e.detail?.isNotBlank() == true) Text(detailLabel(e.detail), style = MaterialTheme.typography.bodySmall)
            Text(TimeUtil.format(e.detectedAt), style = MaterialTheme.typography.labelSmall)
            (e.newContent ?: e.oldContent)?.let { Text(it.take(140), maxLines = 3, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun ChannelDetailsScreen(vm: ChannelDetailsViewModel, onBack: () -> Unit, onOpenMessage: (Long, Long) -> Unit) {
    val channel by vm.channel.collectAsState()
    val messages by vm.messages.collectAsState()
    val events by vm.events.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val ch = channel
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader(ch?.title ?: "القناة", onBack) }
        if (ch != null) {
            item { InfoCard("المعرّف", "@${ch.username}") }
            item { InfoCard("الحالة", statusLabel(ch.status)) }
            item { InfoCard("آخر فحص ناجح", TimeUtil.format(ch.lastSuccessfulCheck)) }
            item { InfoCard("تاريخ الإضافة", TimeUtil.format(ch.dateAdded)) }
            if (ch.lastError != null && ch.status != "OK") item { WarningCard("آخر خطأ: ${ch.lastError}") }
        }
        item { Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) { Text("فحص هذه القناة الآن") } }
        item {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("المنشورات (${messages.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("سجل التغييرات (${events.size})") })
            }
        }
        if (tab == 0) {
            if (messages.isEmpty()) item { Text("لا توجد منشورات مسجلة.") }
            items(messages, key = { it.id }) { m ->
                Card(Modifier.fillMaxWidth().clickable { onOpenMessage(m.channelId, m.messageId) }) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("#${m.messageId} • ${TimeUtil.format(m.publishedAt ?: m.firstSeenAt)}", style = MaterialTheme.typography.labelMedium)
                        Text(m.currentText.take(140), maxLines = 3)
                        if (m.versionCount > 1) Text("معدّل — ${m.versionCount} نسخ", color = MaterialTheme.colorScheme.tertiary)
                        if (m.deletionStatus != DeletionStatus.ACTIVE) Text(deletionLabel(m.deletionStatus), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            if (events.isEmpty()) item { Text("لا توجد تغييرات.") }
            items(events, key = { it.id }) { e -> EventCard(e, null) { onOpenMessage(e.channelId, e.messageId) } }
        }
    }
}

@Composable
fun ChangesScreen(vm: ChangesViewModel, onOpenMessage: (Long, Long) -> Unit) {
    val events by vm.events.collectAsState()
    val channels by vm.channels.collectAsState()
    val filter by vm.filter.collectAsState()
    val names = channels.associate { it.id to it.title }
    var menu by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("التغييرات") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("القناة", style = MaterialTheme.typography.labelMedium)
                Column {
                    OutlinedButton(onClick = { menu = true }) { Text(filter.channelId?.let { names[it] } ?: "كل القنوات") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("كل القنوات") }, onClick = { vm.filter.value = filter.copy(channelId = null); menu = false })
                        channels.forEach { c ->
                            DropdownMenuItem(text = { Text(c.title) }, onClick = { vm.filter.value = filter.copy(channelId = c.id); menu = false })
                        }
                    }
                }
                Text("نوع التغيير", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TypeFilter.values().toList()) { t ->
                        FilterChip(selected = filter.type == t, onClick = { vm.filter.value = filter.copy(type = t) }, label = { Text(t.label) })
                    }
                }
                Text("التاريخ", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DateRange.values().toList()) { r ->
                        FilterChip(selected = filter.range == r, onClick = { vm.filter.value = filter.copy(range = r) }, label = { Text(r.label) })
                    }
                }
            }
        }
        if (events.isEmpty()) item { Text("لا توجد تغييرات مطابقة.") }
        items(events, key = { it.id }) { e -> EventCard(e, names[e.channelId]) { onOpenMessage(e.channelId, e.messageId) } }
    }
}

@Composable
private fun DiffView(old: String, new: String) {
    val green = Color(0xFF2E9E4F)
    val red = Color(0xFFE05252)
    val text = buildAnnotatedString {
        WordDiff.diff(old, new).forEach { span ->
            when (span.type) {
                DiffType.SAME -> append(span.text)
                DiffType.ADDED -> withStyle(SpanStyle(color = green, background = green.copy(alpha = 0.15f))) { append(span.text) }
                DiffType.REMOVED -> withStyle(SpanStyle(color = red, background = red.copy(alpha = 0.15f), textDecoration = TextDecoration.LineThrough)) { append(span.text) }
            }
        }
    }
    SelectionContainer { Text(text) }
}

@Composable
fun MessageHistoryScreen(vm: MessageHistoryViewModel, onBack: () -> Unit) {
    val message by vm.message.collectAsState()
    val channel by vm.channel.collectAsState()
    val versions by vm.versions.collectAsState()
    val uri = LocalUriHandler.current
    var colored by remember { mutableStateOf(true) }
    var fromIdx by remember(versions.size) { mutableIntStateOf((versions.size - 2).coerceAtLeast(0)) }
    var toIdx by remember(versions.size) { mutableIntStateOf((versions.size - 1).coerceAtLeast(0)) }
    val m = message

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenHeader("سجل المنشور", onBack) }
        if (m == null) {
            item { Text("المنشور غير موجود.") }
            return@LazyColumn
        }
        item { InfoCard("القناة / المنشور", "${channel?.title ?: ""} • #${m.messageId}") }
        item { InfoCard("وقت النشر / أول رصد", "${TimeUtil.format(m.publishedAt)} / ${TimeUtil.format(m.firstSeenAt)}") }
        item {
            InfoCard(
                "الحالة",
                deletionLabel(m.deletionStatus) + (m.deletionDetectedAt?.let { " — اكتُشف: ${TimeUtil.format(it)}" } ?: ""),
            )
        }
        if (m.url.isNotEmpty()) item { OutlinedButton(onClick = { uri.openUri(m.url) }) { Text("فتح المنشور الأصلي") } }

        item { SectionTitle("النص الأصلي") }
        item { Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(ContentFormat.display(m.originalText, LinkCodec.decode(m.originalLinks)), Modifier.padding(12.dp)) } } }
        item { SectionTitle("النص الحالي") }
        item { Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(ContentFormat.display(m.currentText, LinkCodec.decode(m.currentLinks)), Modifier.padding(12.dp)) } } }

        item { SectionTitle("المقارنة بين النسخ") }
        if (versions.size < 2) {
            item { Text("لا توجد تعديلات مسجلة على هذا المنشور.") }
        } else {
            val old = versions[fromIdx.coerceIn(versions.indices)]
            val new = versions[toIdx.coerceIn(versions.indices)]
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("من النسخة", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(versions.indices.toList()) { i ->
                            FilterChip(selected = fromIdx == i, onClick = { fromIdx = i }, label = { Text("${versions[i].versionNumber}") })
                        }
                    }
                    Text("إلى النسخة", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(versions.indices.toList()) { i ->
                            FilterChip(selected = toIdx == i, onClick = { toIdx = i }, label = { Text("${versions[i].versionNumber}") })
                        }
                    }
                    SwitchRow("عرض ملوّن (أخضر = مضاف، أحمر = محذوف)", colored) { colored = it }
                }
            }
            val oldText = ContentFormat.display(old.content, LinkCodec.decode(old.links))
            val newText = ContentFormat.display(new.content, LinkCodec.decode(new.links))
            if (colored) {
                item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { DiffView(oldText, newText) } } }
            } else {
                item { Text("النسخة ${old.versionNumber} (كاملة)", style = MaterialTheme.typography.labelMedium) }
                item { Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(oldText, Modifier.padding(12.dp)) } } }
                item { Text("النسخة ${new.versionNumber} (كاملة)", style = MaterialTheme.typography.labelMedium) }
                item { Card(Modifier.fillMaxWidth()) { SelectionContainer { Text(newText, Modifier.padding(12.dp)) } } }
            }
        }

        item { SectionTitle("كل النسخ (${versions.size})") }
        items(versions, key = { it.id }) { v ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("النسخة ${v.versionNumber} • ${TimeUtil.format(v.detectedAt)}", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(ContentFormat.display(v.content, LinkCodec.decode(v.links))) }
                }
            }
        }
    }
}
