package com.adnanalhasani.hospital

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BusinessProfileScreen(profile: BusinessProfileEntity?, onSave: (BusinessProfileEntity) -> Unit) {
    var name by remember(profile) { mutableStateOf(profile?.name.orEmpty()) }
    var legalName by remember(profile) { mutableStateOf(profile?.legalName.orEmpty()) }
    var businessType by remember(profile) { mutableStateOf(profile?.businessType.orEmpty()) }
    var registrationNumber by remember(profile) { mutableStateOf(profile?.registrationNumber.orEmpty()) }
    var taxNumber by remember(profile) { mutableStateOf(profile?.taxNumber.orEmpty()) }
    var phone by remember(profile) { mutableStateOf(profile?.phone.orEmpty()) }
    var email by remember(profile) { mutableStateOf(profile?.email.orEmpty()) }
    var address by remember(profile) { mutableStateOf(profile?.address.orEmpty()) }
    var website by remember(profile) { mutableStateOf(profile?.website.orEmpty()) }
    var currency by remember(profile) { mutableStateOf(profile?.currency ?: "YER") }
    var status by remember { mutableStateOf<String?>(null) }

    LazyColumnCompat(
        content = {
            item {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Business, null, tint = Color(0xFF548F88), modifier = Modifier.size(36.dp))
                        Text("بيانات المنشأة الصحية", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                        Text("تُحفظ هذه البيانات محلياً على هذا الجهاز ضمن قاعدة Room.", color = Color(0xFF788895), fontSize = 12.sp)
                        ProfileField("اسم المنشأة *", name) { name = it }
                        ProfileField("الاسم القانوني", legalName) { legalName = it }
                        ProfileField("نوع النشاط", businessType) { businessType = it }
                        ProfileField("رقم السجل التجاري", registrationNumber) { registrationNumber = it }
                        ProfileField("الرقم الضريبي", taxNumber) { taxNumber = it }
                        ProfileField("رقم الهاتف", phone) { phone = it }
                        ProfileField("البريد الإلكتروني", email) { email = it }
                        ProfileField("العنوان", address) { address = it }
                        ProfileField("الموقع الإلكتروني", website) { website = it }
                        ProfileField("العملة الافتراضية (مثل YER أو USD)", currency) { currency = it.uppercase().take(3) }
                        status?.let { Text(it, color = if (it.startsWith("تم")) Color(0xFF267B60) else Color(0xFFB44444)) }
                        Button(onClick = {
                            if (name.isBlank()) status = "اسم المنشأة مطلوب."
                            else if (email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) status = "صيغة البريد الإلكتروني غير صحيحة."
                            else if (currency.length != 3 || !currency.all { it in 'A'..'Z' }) status = "اكتب رمز عملة من 3 أحرف مثل YER."
                            else {
                                onSave(BusinessProfileEntity(name = name.trim(), legalName = legalName.trim(), businessType = businessType.trim(), registrationNumber = registrationNumber.trim(), taxNumber = taxNumber.trim(), phone = phone.trim(), email = email.trim(), address = address.trim(), website = website.trim(), currency = currency))
                                status = "تم حفظ بيانات المنشأة."
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Save, null); Spacer(Modifier.width(8.dp)); Text("حفظ بيانات المنشأة")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ProfileField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
}

@Composable
private fun LazyColumnCompat(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().verticalScrollDisabledMarker(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

private fun Modifier.verticalScrollDisabledMarker() = this
