package com.tgmonitor.app.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.data.ChangeListItem
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelEntity
import com.tgmonitor.app.data.DeletionStatus
import com.tgmonitor.app.engine.ContentFormat
import com.tgmonitor.app.engine.DiffSpan
import com.tgmonitor.app.engine.DiffType
import com.tgmonitor.app.engine.LinkCodec
import com.tgmonitor.app.engine.WordDiff
import com.tgmonitor.app.ui.theme.Semantic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed class FeedRow(val key: String) {
    class Header(val text: String) : FeedRow("h$text")
    class Event(val item: ChangeListItem) : FeedRow("e${item.id}")
}

private fun buildRows(events: List<ChangeListItem>): List<FeedRow> {
    val out = ArrayList<FeedRow>(events.size + 8)
    var last = ""
    for (e in events) {
        val d = TimeUtil.dayLabel(e.detectedAt)
        if (d != last) {
            out += FeedRow.Header(d)
            last = d
        }
        out += FeedRow.Event(e)
    }
    return out
}

@Composable
fun ChannelDetailsScreen(vm: ChannelDetailsViewModel, onBack: () -> Unit, onOpenMessage: (Long, Long) -> Unit) {
    val channel by vm.channel.collectAsState()
    val messages by vm.messages.collectAsState()
    val events by vm.events.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val ch = channel
    val rows = remember(events) { buildRows(events) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { ScreenHeader(ch?.title ?: "القناة", onBack) }
        if (ch != null) {
            item(key = "info") {
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChannelAvatar(ch.title, 52.dp)
                        Column(Modifier.weight(1f)) {
                            Text(ch.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${ch.username}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Pill(shortStatus(ch.status), statusColor(ch.status))
                    }
                    InfoLine("آخر فحص ناجح", TimeUtil.format(ch.lastSuccessfulCheck))
                    InfoLine("تاريخ الإضافة", TimeUtil.format(ch.dateAdded))
                    InfoLine("المنشورات المعروضة", messages.size.toString())
                    if (ch.status != "OK" && ch.status != "PENDING") {
                        Text(statusLabel(ch.status), style = MaterialTheme.typography.bodySmall, color = statusColor(ch.status))
                    }
                }
            }
        }
        item(key = "scan") { Button(onClick = vm::scan, modifier = Modifier.fillMaxWidth()) { Text("فحص هذه القناة الآن") } }
        item(key = "tabs") {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("المنشورات") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("سجل التغييرات") })
            }
        }
        if (tab == 0) {
            if (messages.isEmpty()) {
                item(key = "m-empty") {
                    EmptyState(Icons.Filled.Notifications, "لا توجد منشورات مسجلة", "ستظهر هنا بعد أول فحص ناجح للقناة.")
                }
            }
            items(messages, key = { "m${it.id}" }, contentType = { "message" }) { m ->
                AppCard(onClick = { onOpenMessage(m.channelId, m.messageId) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${m.messageId}", style = MaterialTheme.typography.titleSmall)
                        Text(TimeUtil.relative(m.publishedAt ?: m.firstSeenAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(m.preview, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (m.versionCount > 1 || m.deletionStatus != DeletionStatus.ACTIVE) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (m.versionCount > 1) Pill("معدّل ×${m.versionCount}", Semantic.warning())
                            if (m.deletionStatus != DeletionStatus.ACTIVE) Pill(deletionShort(m.deletionStatus), MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            if (rows.isEmpty()) {
                item(key = "e-empty") { EmptyState(Icons.Filled.Notifications, "لا توجد تغييرات", "لم يُسجل أي تغيير لهذه القناة بعد.") }
            }
            items(rows, key = { it.key }, contentType = { if (it is FeedRow.Header) 0 else 1 }) { row ->
                when (row) {
                    is FeedRow.Header -> DayHeader(row.text)
                    is FeedRow.Event -> ChangeRow(row.item, null) { onOpenMessage(row.item.channelId, row.item.messageId) }
                }
            }
        }
    }
}

@Composable
fun ChangesScreen(vm: ChangesViewModel, onOpenMessage: (Long, Long) -> Unit) {
    val events by vm.events.collectAsState()
    val channels by vm.channels.collectAsState()
    val filter by vm.filter.collectAsState()
    val names = remember(channels) { channels.associate { it.id to it.title } }
    val rows = remember(events) { buildRows(events) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "title") { ScreenTitle("التغييرات", "${events.size} حدث") }
        item(key = "filters") { Filters(filter, channels) { vm.filter.value = it } }
        if (rows.isEmpty()) {
            item(key = "empty") {
                EmptyState(Icons.Filled.Notifications, "لا توجد تغييرات مطابقة", "جرّب تغيير الفلاتر، أو انتظر الفحص القادم.")
            }
        }
        items(rows, key = { it.key }, contentType = { if (it is FeedRow.Header) 0 else 1 }) { row ->
            when (row) {
                is FeedRow.Header -> DayHeader(row.text)
                is FeedRow.Event -> ChangeRow(row.item, names[row.item.channelId]) {
                    onOpenMessage(row.item.channelId, row.item.messageId)
                }
            }
        }
    }
}

@Composable
private fun Filters(filter: ChangeFilter, channels: List<ChannelEntity>, onChange: (ChangeFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (channels.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip(selected = filter.channelId == null, onClick = { onChange(filter.copy(channelId = null)) }, label = { Text("كل القنوات") })
                }
                items(channels, key = { it.id }) { c ->
                    FilterChip(
                        selected = filter.channelId == c.id,
                        onClick = { onChange(filter.copy(channelId = c.id)) },
                        label = { Text(c.title, maxLines = 1) },
                    )
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(TypeFilter.values().toList()) { t ->
                FilterChip(selected = filter.type == t, onClick = { onChange(filter.copy(type = t)) }, label = { Text(t.label) })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(DateRange.values().toList()) { r ->
                FilterChip(selected = filter.range == r, onClick = { onChange(filter.copy(range = r)) }, label = { Text(r.label) })
            }
        }
    }
}

/** The diff is computed off the main thread, so opening a long post never freezes the UI. */
@Composable
private fun DiffView(old: String, new: String) {
    val spans by produceState(initialValue = emptyList<DiffSpan>(), old, new) {
        value = withContext(Dispatchers.Default) { WordDiff.diff(old, new) }
    }
    val green = Semantic.success()
    val red = MaterialTheme.colorScheme.error
    val text = remember(spans, green, red) {
        buildAnnotatedString {
            spans.forEach { span ->
                when (span.type) {
                    DiffType.SAME -> append(span.text)
                    DiffType.ADDED -> withStyle(SpanStyle(color = green, background = green.copy(alpha = 0.15f))) { append(span.text) }
                    DiffType.REMOVED -> withStyle(
                        SpanStyle(color = red, background = red.copy(alpha = 0.15f), textDecoration = TextDecoration.LineThrough)
                    ) { append(span.text) }
                }
            }
        }
    }
    if (spans.isEmpty() && old != new) Text("جارٍ المقارنة...", style = MaterialTheme.typography.bodySmall)
    else SelectionContainer { Text(text) }
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

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") { ScreenHeader(if (m != null) "المنشور #${m.messageId}" else "سجل المنشور", onBack) }
        if (m == null) {
            item(key = "none") { EmptyState(Icons.Filled.Notifications, "المنشور غير موجود", "ربما حُذفت القناة.") }
            return@LazyColumn
        }
        item(key = "info") {
            AppCard {
                InfoLine("القناة", channel?.title ?: "—")
                InfoLine("وقت النشر", TimeUtil.format(m.publishedAt))
                InfoLine("أول رصد", TimeUtil.format(m.firstSeenAt))
                val statusColor = if (m.deletionStatus == DeletionStatus.ACTIVE) Semantic.success() else MaterialTheme.colorScheme.error
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("الحالة", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Pill(deletionShort(m.deletionStatus), statusColor)
                }
                if (m.deletionStatus != DeletionStatus.ACTIVE) {
                    Text(
                        deletionLabel(m.deletionStatus) + (m.deletionDetectedAt?.let { " — اكتُشف: ${TimeUtil.format(it)}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = statusColor,
                    )
                }
                if (m.url.isNotEmpty()) OutlinedButton(onClick = { uri.openUri(m.url) }) { Text("فتح المنشور الأصلي") }
            }
        }

        item(key = "orig-t") { SectionTitle("النص الأصلي") }
        item(key = "orig") { AppCard { SelectionContainer { Text(ContentFormat.display(m.originalText, LinkCodec.decode(m.originalLinks))) } } }
        item(key = "cur-t") { SectionTitle("النص الحالي") }
        item(key = "cur") { AppCard { SelectionContainer { Text(ContentFormat.display(m.currentText, LinkCodec.decode(m.currentLinks))) } } }

        item(key = "cmp-t") { SectionTitle("المقارنة بين النسخ") }
        if (versions.size < 2) {
            item(key = "cmp-none") {
                AppCard { Text("لا توجد تعديلات مسجلة على هذا المنشور.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            val old = versions[fromIdx.coerceIn(versions.indices)]
            val new = versions[toIdx.coerceIn(versions.indices)]
            item(key = "cmp-pick") {
                AppCard {
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
                    SwitchRow("عرض ملوّن (أخضر مضاف، أحمر محذوف)", colored) { colored = it }
                }
            }
            val oldText = ContentFormat.display(old.content, LinkCodec.decode(old.links))
            val newText = ContentFormat.display(new.content, LinkCodec.decode(new.links))
            if (colored) {
                item(key = "cmp-diff") { AppCard { DiffView(oldText, newText) } }
            } else {
                item(key = "cmp-old") {
                    AppCard {
                        Text("النسخة ${old.versionNumber} (كاملة)", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(oldText) }
                    }
                }
                item(key = "cmp-new") {
                    AppCard {
                        Text("النسخة ${new.versionNumber} (كاملة)", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(newText) }
                    }
                }
            }
        }

        item(key = "all-t") { SectionTitle("كل النسخ (${versions.size})") }
        items(versions, key = { "v${it.id}" }) { v ->
            AppCard {
                Text("النسخة ${v.versionNumber} • ${TimeUtil.format(v.detectedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SelectionContainer { Text(ContentFormat.display(v.content, LinkCodec.decode(v.links))) }
            }
        }
    }
}
