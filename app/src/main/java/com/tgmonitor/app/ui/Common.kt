package com.tgmonitor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tgmonitor.app.data.ChangeListItem
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.data.DeletionStatus
import com.tgmonitor.app.ui.theme.Semantic
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

object TimeUtil {
    fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun format(ms: Long?): String =
        if (ms == null) "—" else SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun timeOnly(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun relative(ms: Long?): String {
        if (ms == null) return "—"
        val d = System.currentTimeMillis() - ms
        return when {
            d < 60_000L -> "الآن"
            d < 3_600_000L -> "قبل ${d / 60_000L} د"
            d < 86_400_000L -> "قبل ${d / 3_600_000L} س"
            else -> format(ms)
        }
    }

    fun dayLabel(ms: Long): String {
        val startToday = startOfToday()
        return when {
            ms >= startToday -> "اليوم"
            ms >= startToday - 86_400_000L -> "أمس"
            else -> SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(ms))
        }
    }
}

fun statusLabel(status: String): String = when (status) {
    ChannelStatus.PENDING -> "بانتظار الفحص الأول"
    ChannelStatus.OK -> "تعمل"
    ChannelStatus.PREVIEW_DISABLED -> "المعاينة العامة موقفة، تتطلب تسجيل دخول ولا يمكن مراقبتها حالياً"
    ChannelStatus.NOT_FOUND -> "غير موجودة أو ليست قناة عامة"
    ChannelStatus.ACCESS_DENIED -> "الوصول مرفوض (403)"
    ChannelStatus.RATE_LIMITED -> "تقييد من الخادم (429)"
    ChannelStatus.SERVER_ERROR -> "خطأ في الخادم"
    ChannelStatus.NETWORK_ERROR -> "خطأ في الشبكة"
    ChannelStatus.PARSE_FAILURE -> "تعذر تحليل الصفحة (تغيّر HTML؟)"
    else -> status
}

fun shortStatus(status: String): String = when (status) {
    ChannelStatus.OK -> "تعمل"
    ChannelStatus.PENDING -> "قيد الفحص"
    ChannelStatus.PREVIEW_DISABLED -> "معاينة موقفة"
    ChannelStatus.NOT_FOUND -> "غير موجودة"
    ChannelStatus.RATE_LIMITED -> "تقييد 429"
    else -> "خطأ"
}

fun changeTypeLabel(type: String): String = when (type) {
    ChangeType.NEW_POST -> "منشور جديد"
    ChangeType.TEXT_EDITED -> "تعديل النص"
    ChangeType.LINKS_EDITED -> "تعديل الروابط"
    ChangeType.TEXT_AND_LINKS_EDITED -> "تعديل النص والروابط"
    ChangeType.DELETION_SUSPECTED -> "حذف مشتبه به"
    ChangeType.DELETION_CONFIRMED -> "حذف مؤكد"
    else -> type
}

fun deletionLabel(status: String): String = when (status) {
    DeletionStatus.ACTIVE -> "نشط"
    DeletionStatus.SUSPECTED_DELETED -> "حذف مشتبه به"
    DeletionStatus.CONFIRMED_DELETED -> "حذف مؤكد"
    DeletionStatus.UNKNOWN -> "غير معروف (اختفى من المعاينة ولم يُتحقق بعد)"
    else -> status
}

fun deletionShort(status: String): String = when (status) {
    DeletionStatus.SUSPECTED_DELETED -> "حذف مشتبه"
    DeletionStatus.CONFIRMED_DELETED -> "محذوف"
    DeletionStatus.UNKNOWN -> "غير معروف"
    else -> "نشط"
}

fun detailLabel(detail: String?): String =
    detail.orEmpty().split(",").filter { it.isNotBlank() }.joinToString("، ") {
        when (it) {
            "TEXT_ADDED" -> "إضافة نص"
            "TEXT_REMOVED" -> "حذف جزء من النص"
            "TEXT_MODIFIED" -> "تغيير النص"
            "LINK_ADDED" -> "إضافة رابط"
            "LINK_REMOVED" -> "حذف رابط"
            "LINK_MODIFIED" -> "تعديل رابط"
            "LINK_REORDERED" -> "إعادة ترتيب الروابط"
            else -> it
        }
    }

@Composable
fun statusColor(status: String): Color = when (status) {
    ChannelStatus.OK -> Semantic.success()
    ChannelStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    ChannelStatus.PREVIEW_DISABLED, ChannelStatus.RATE_LIMITED -> Semantic.warning()
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun changeVisual(type: String): Pair<ImageVector, Color> = when (type) {
    ChangeType.NEW_POST -> Icons.Filled.Add to Semantic.success()
    ChangeType.DELETION_CONFIRMED -> Icons.Filled.Delete to MaterialTheme.colorScheme.error
    ChangeType.DELETION_SUSPECTED -> Icons.Filled.Warning to Semantic.warning()
    else -> Icons.Filled.Edit to MaterialTheme.colorScheme.primary
}

// ---------- building blocks ----------

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.55f))
    }
}

private val AVATAR_COLORS = listOf(
    0xFF1E88E5, 0xFF00897B, 0xFF8E24AA, 0xFFF4511E, 0xFF3949AB, 0xFF6D4C41, 0xFF039BE5, 0xFF7CB342,
)

@Composable
fun ChannelAvatar(title: String, size: Dp = 44.dp) {
    val color = Color(AVATAR_COLORS[(title.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
    val initial = title.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "؟"
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(initial, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun StatTile(icon: ImageVector, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        IconBadge(icon, color, 36.dp)
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
fun NoticeCard(text: String, color: Color, icon: ImageVector = Icons.Filled.Warning) {
    AppCard(containerColor = color.copy(alpha = 0.10f), borderColor = color.copy(alpha = 0.45f)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MessageBanner(text: String, onDismiss: () -> Unit) {
    AppCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, 72.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun DayHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
fun ChangeRow(e: ChangeListItem, channelName: String?, onClick: () -> Unit) {
    val visual = changeVisual(e.changeType)
    AppCard(onClick = onClick) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            IconBadge(visual.first, visual.second)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(changeTypeLabel(e.changeType), style = MaterialTheme.typography.titleSmall, color = visual.second)
                    Text(TimeUtil.timeOnly(e.detectedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(channelName, "#${e.messageId}").joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!e.detail.isNullOrBlank()) Text(detailLabel(e.detail), style = MaterialTheme.typography.bodySmall)
                if (!e.preview.isNullOrBlank()) {
                    Text(e.preview, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
