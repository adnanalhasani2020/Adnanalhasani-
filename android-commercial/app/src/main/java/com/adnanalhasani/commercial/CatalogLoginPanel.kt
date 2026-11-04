package com.adnanalhasani.commercial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun CatalogLoginPanel(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    busy: Boolean,
    onLogin: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 18.dp)
    ) {
        item { Text("تسجيل الدخول إلى حساب Supabase", style = MaterialTheme.typography.titleMedium) }
        item {
            OutlinedTextField(
                value = email,
                onValueChange = onEmailChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("البريد الإلكتروني") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("كلمة المرور") },
                singleLine = true
            )
        }
        item {
            Button(
                onClick = onLogin,
                enabled = !busy && email.contains("@") && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "جارٍ تسجيل الدخول..." else "تسجيل الدخول")
            }
        }
        item {
            Text(
                "تُحفظ بيانات تجديد الجلسة مشفرة على الجهاز، ولا تُحفظ كلمة المرور. لا تستخدم مفتاح service_role في التطبيق.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
