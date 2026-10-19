package com.adnanalhasani.hospital

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AuthScreen(onAuthenticated: () -> Unit, onContinueAsGuest: () -> Unit) {
    val context = LocalContext.current
    val configured = remember { FirebaseAuthGateway.isConfigured(context) }
    var createAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Color(0xFFF4F7F8)).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF263B4D), Color(0xFF548F88))), RoundedCornerShape(26.dp)).padding(24.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LocalHospital, null, tint = Color.White, modifier = Modifier.size(42.dp))
                        Text("نظام إدارة المستشفى", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(if (createAccount) "أنشئ حسابك للبدء" else "سجّل الدخول إلى حسابك", color = Color.White.copy(alpha = .9f))
                    }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                        Text(if (createAccount) "إنشاء حساب جديد" else "تسجيل الدخول", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263B4D))
                        OutlinedTextField(email, { email = it; status = null }, Modifier.fillMaxWidth(), label = { Text("البريد الإلكتروني") }, leadingIcon = { Icon(Icons.Default.Mail, null) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(password, { password = it; status = null }, Modifier.fillMaxWidth(), label = { Text("كلمة المرور") }, leadingIcon = { Icon(Icons.Default.Lock, null) }, singleLine = true, visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "إظهار كلمة المرور") } }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        if (createAccount) {
                            OutlinedTextField(confirmPassword, { confirmPassword = it; status = null }, Modifier.fillMaxWidth(), label = { Text("تأكيد كلمة المرور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        }
                        if (!configured) {
                            Text("الاتصال بـ Firebase غير مهيأ بعد. أضف google-services.json وفعّل Email/Password من Firebase Console لتشغيل الحسابات. يمكنك متابعة التجربة المحلية دون حساب.", color = Color(0xFF8A5A16), fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        status?.let { Text(it, color = if (it.startsWith("تم")) Color(0xFF267B60) else Color(0xFFB44444), fontSize = 12.sp) }
                        Button(
                            onClick = {
                                when {
                                    email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches() -> status = "أدخل بريداً إلكترونياً صحيحاً."
                                    password.length < 6 -> status = "يجب ألا تقل كلمة المرور عن 6 أحرف."
                                    createAccount && password != confirmPassword -> status = "كلمتا المرور غير متطابقتين."
                                    !configured -> status = "أضف إعدادات Firebase أولاً، أو اختر المتابعة دون حساب."
                                    else -> {
                                        busy = true
                                        val callback: (Result<Unit>) -> Unit = { result ->
                                            busy = false
                                            status = result.fold({ "تم تسجيل الدخول بنجاح." }, { it.message ?: "تعذر إكمال الطلب." })
                                            if (result.isSuccess) onAuthenticated()
                                        }
                                        if (createAccount) FirebaseAuthGateway.register(context, email, password, callback)
                                        else FirebaseAuthGateway.signIn(context, email, password, callback)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && configured
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(if (createAccount) Icons.Default.PersonAdd else Icons.Default.Lock, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (createAccount) "إنشاء الحساب" else "تسجيل الدخول")
                        }
                        TextButton(onClick = { createAccount = !createAccount; status = null }) {
                            Text(if (createAccount) "لديك حساب؟ سجّل الدخول" else "ليس لديك حساب؟ أنشئ حساباً")
                        }
                        if (!createAccount && configured) {
                            TextButton(onClick = {
                                if (email.isBlank()) status = "اكتب بريدك الإلكتروني أولاً."
                                else FirebaseAuthGateway.sendPasswordReset(context, email) { result ->
                                    status = result.fold({ "تم إرسال رابط إعادة تعيين كلمة المرور." }, { it.message ?: "تعذر إرسال الرابط." })
                                }
                            }) { Text("نسيت كلمة المرور؟") }
                        }
                    }
                }
                OutlinedButton(onClick = onContinueAsGuest, modifier = Modifier.fillMaxWidth()) { Text("المتابعة للتجربة دون حساب") }
                Text("لا تستخدم بيانات مرضى حقيقية قبل إعداد الأمان والصلاحيات والنسخ الاحتياطي.", color = Color(0xFF788895), fontSize = 11.sp)
            }
        }
    }
}
