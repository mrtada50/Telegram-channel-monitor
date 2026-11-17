package com.tgmonitor.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.ui.theme.Semantic

private val HeroStart = Color(0xFF1565C0)
private val HeroEnd = Color(0xFF29A9EB)

@Composable
fun HomeScreen(vm: HomeViewModel, onOpenMessage: (Long, Long) -> Unit) {
    val s by vm.state.collectAsState()
    val scanning by vm.scanning.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "hero") { Hero(s, scanning, vm::scanNow) }
        if (s.previewDisabled > 0) {
            item(key = "warn") {
                NoticeCard(
                    "${s.previewDisabled} قناة معاينتها العامة موقفة، ولا يمكن مراقبتها بدون تسجيل دخول.",
                    Semantic.warning(),
                )
            }
        }
        item(key = "tiles1") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Filled.Menu, s.channels.toString(), "القنوات", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                StatTile(Icons.Filled.Notifications, s.messages.toString(), "المنشورات المسجلة", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
            }
        }
        item(key = "tiles2") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Filled.Edit, s.editsToday.toString(), "تعديلات اليوم", Semantic.warning(), Modifier.weight(1f))
                StatTile(Icons.Filled.Delete, s.deletionsToday.toString(), "حذوفات اليوم", MaterialTheme.colorScheme.error, Modifier.weight(1f))
            }
        }
        item(key = "recent-title") { SectionTitle("آخر النشاط") }
        if (s.recent.isEmpty()) {
            item(key = "recent-empty") {
                AppCard {
                    Text(
                        "لا يوجد نشاط بعد. عند اكتشاف منشور جديد أو تعديل أو حذف سيظهر هنا.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(s.recent, key = { "r${it.id}" }, contentType = { "change" }) { e ->
                ChangeRow(e, s.names[e.channelId]) { onOpenMessage(e.channelId, e.messageId) }
            }
        }
    }
}

@Composable
private fun Hero(s: HomeState, scanning: Boolean, onScan: () -> Unit) {
    val active = s.settings.monitoringEnabled
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(HeroStart, HeroEnd)))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Color(0xFF7CFFB2) else Color(0xFFFFC857)))
                Text(
                    if (active) "المراقبة نشطة" else "المراقبة متوقفة",
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
            }
            Text("مراقب قنوات تيليجرام", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (active) "فحص كل ${s.settings.intervalMinutes} دقيقة تقريباً • آخر فحص ناجح: ${TimeUtil.relative(s.lastScan)}"
                else "فعّل المراقبة من الإعدادات",
                color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onScan,
                enabled = !scanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White, contentColor = HeroStart,
                    disabledContainerColor = Color.White.copy(alpha = 0.55f), disabledContentColor = HeroStart.copy(alpha = 0.7f),
                ),
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HeroStart)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(if (scanning) "جارٍ الفحص..." else "فحص يدوي الآن")
            }
        }
    }
}
