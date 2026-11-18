package com.tgmonitor.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tgmonitor.app.account.AccountState
import com.tgmonitor.app.ui.theme.Semantic

private fun accountLabel(s: AccountState): String = when (s) {
    AccountState.NotAvailable -> "غير متاح"
    AccountState.NotConfigured -> "غير مفعّل"
    AccountState.Starting -> "جارٍ الاتصال"
    AccountState.WaitPhone -> "بانتظار الرقم"
    AccountState.WaitCode -> "بانتظار الرمز"
    AccountState.WaitPassword -> "بانتظار كلمة المرور"
    AccountState.Ready -> "متصل"
    is AccountState.Failed -> "خطأ"
}

@Composable
fun AccountCard(vm: AccountViewModel) {
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    var apiId by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    val color: Color = when (state) {
        AccountState.Ready -> Semantic.success()
        is AccountState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AppCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("تسجيل الدخول (اختياري)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(accountLabel(state), color)
        }
        Text(
            "لقراءة القنوات الموقفة المعاينة فقط. يعمل التطبيق كعميل تيليجرام على حسابك، فيه خطر تقييد الحساب أو سرقة الجلسة " +
                "لو اخترق أحد هاتفك. الأفضل استخدام حساب ثانوي، والانضمام للقنوات يدوياً واحدة تلو الأخرى.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!vm.available) {
            Text(
                "هذه النسخة بُنيت بدون مكتبة TDLib، فميزة الحساب غير متاحة. راجع README لتفعيلها.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            when (state) {
                AccountState.NotConfigured, is AccountState.Failed -> {
                    (state as? AccountState.Failed)?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    Text("أنشئ api_id و api_hash من my.telegram.org (يفضّل بحساب ثانوي). لا يُخزَّنان إلا مشفّرين على هاتفك.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = apiId, onValueChange = { apiId = it; localError = null }, singleLine = true,
                        label = { Text("api_id") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        value = apiHash, onValueChange = { apiHash = it; localError = null }, singleLine = true,
                        label = { Text("api_hash") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { localError = vm.saveCredentials(apiId, apiHash) }) { Text("حفظ ومتابعة") }
                }
                AccountState.Starting, AccountState.NotAvailable -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                AccountState.WaitPhone -> {
                    OutlinedTextField(
                        value = phone, onValueChange = { phone = it }, singleLine = true,
                        label = { Text("رقم الهاتف مع رمز الدولة") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    )
                    Button(onClick = { vm.submitPhone(phone) }, enabled = phone.isNotBlank()) { Text("إرسال الرمز") }
                }
                AccountState.WaitCode -> {
                    OutlinedTextField(
                        value = code, onValueChange = { code = it }, singleLine = true,
                        label = { Text("الرمز الذي وصلك في تيليجرام") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(onClick = { vm.submitCode(code) }, enabled = code.isNotBlank()) { Text("تأكيد") }
                }
                AccountState.WaitPassword -> {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("كلمة مرور التحقق بخطوتين") }, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(onClick = { vm.submitPassword(password) }, enabled = password.isNotBlank()) { Text("تأكيد") }
                }
                AccountState.Ready -> {
                    Text("الحساب متصل. فعّل «قراءة عبر حسابي» من بطاقة القناة المطلوبة في شاشة القنوات.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { vm.logout() }) { Text("تسجيل الخروج") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
