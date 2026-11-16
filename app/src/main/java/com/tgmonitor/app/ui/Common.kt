package com.tgmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tgmonitor.app.data.ChangeType
import com.tgmonitor.app.data.ChannelStatus
import com.tgmonitor.app.data.DeletionStatus
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
}

fun statusLabel(status: String): String = when (status) {
    ChannelStatus.PENDING -> "بانتظار الفحص الأول"
    ChannelStatus.OK -> "تعمل"
    ChannelStatus.PREVIEW_DISABLED -> "المعاينة العامة موقفة (تتطلب تسجيل دخول)"
    ChannelStatus.NOT_FOUND -> "غير موجودة أو ليست قناة عامة"
    ChannelStatus.ACCESS_DENIED -> "الوصول مرفوض (403)"
    ChannelStatus.RATE_LIMITED -> "تقييد من الخادم (429)"
    ChannelStatus.SERVER_ERROR -> "خطأ في الخادم"
    ChannelStatus.NETWORK_ERROR -> "خطأ في الشبكة"
    ChannelStatus.PARSE_FAILURE -> "تعذر تحليل الصفحة (تغير HTML؟)"
    else -> status
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
    DeletionStatus.UNKNOWN -> "غير معروف (اختفى من المعاينة، لم يُتحقق)"
    else -> status
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
    ChannelStatus.OK -> MaterialTheme.colorScheme.primary
    ChannelStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun InfoCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun WarningCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
