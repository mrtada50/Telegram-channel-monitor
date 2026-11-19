package com.tgmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.proxy.ProxyMode
import com.tgmonitor.app.ui.theme.Semantic

@Composable
fun ProxyCard(vm: SettingsViewModel) {
    val st by vm.proxyStatus.collectAsState()
    val log by vm.connectionLog.collectAsState()
    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("إذا كان تيليجرام محجوباً", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when {
                st.searching -> Pill("يبحث", Semantic.warning())
                st.current != null -> Pill("عبر بروكسي", Semantic.success())
                else -> Pill("مباشر", MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "في الوضع التلقائي يتصل التطبيق مباشرة. إذا تأكد أن تيليجرام محجوب (والإنترنت نفسه شغال) يبحث في قوائم بروكسيات عامة، " +
                "ويختار بروكسي يعيد فعلاً صفحة قناة حقيقية، ثم يعيد الفحص عبره. يعود للاتصال المباشر تلقائياً عند زوال الحجب.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = st.mode == ProxyMode.AUTO, onClick = { vm.setProxyMode(ProxyMode.AUTO) }, label = { Text("تلقائي") })
            FilterChip(selected = st.mode == ProxyMode.OFF, onClick = { vm.setProxyMode(ProxyMode.OFF) }, label = { Text("إيقاف البروكسي") })
        }
        st.current?.let { InfoLine("البروكسي الحالي", it) }
        if (st.poolSize > 0) InfoLine("بروكسيات جاهزة", st.poolSize.toString())
        st.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.searchProxy() }, enabled = !st.searching && st.mode == ProxyMode.AUTO) {
                if (st.searching) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (st.searching) "جارٍ البحث..." else "ابحث عن بروكسي الآن")
            }
            if (st.current != null) OutlinedButton(onClick = { vm.stopProxy() }) { Text("اتصال مباشر") }
        }
        if (log.isNotEmpty()) {
            Text("سجل الاتصال (الأحدث أولاً)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            log.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text(
            "ملاحظة: البروكسيات العامة غير موثوقة. لا تُرسل عبرها كلمات مرور، والاتصال بتيليجرام مشفّر (HTTPS) فلا يستطيع البروكسي قراءة المحتوى أو تزويره، " +
                "لكنه يعرف أنك تقرأ قنوات تيليجرام. لا يُستخدم البروكسي أبداً مع وضع حساب تيليجرام.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
