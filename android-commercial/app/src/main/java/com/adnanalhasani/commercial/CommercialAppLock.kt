package com.adnanalhasani.commercial

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private const val APP_LOCK_PREFS = "commercial_app_lock_v1"
private fun appLockPrefs(context: Context) = EncryptedSharedPreferences.create(
    context, APP_LOCK_PREFS,
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
private fun appLockHash(pin: CharArray, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin, salt, 180_000, 256)
    return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
    finally { spec.clearPassword() }
}
private fun appLockEnabled(context: Context): Boolean = runCatching {
    appLockPrefs(context).getString("pin_hash", null)?.isNotBlank() == true &&
        appLockPrefs(context).getString("pin_salt", null)?.isNotBlank() == true
}.getOrDefault(true)

@Composable
internal fun CommercialAppLock(context: Context, content: @Composable () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember { mutableStateOf(appLockEnabled(context)) }
    var locked by remember { mutableStateOf(enabled) }
    var backgrounded by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var storageError by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (appLockEnabled(context)) { locked = true; pin = ""; backgrounded = true }
            } else if (event == Lifecycle.Event.ON_START && backgrounded && appLockEnabled(context)) {
                locked = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        storageError = runCatching { appLockPrefs(context).all }.isFailure
        enabled = appLockEnabled(context)
        locked = enabled || storageError
    }
    if (!locked && !storageError) content()
    else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("التطبيق مقفل", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(if (storageError) "تعذر فتح مخزن القفل الآمن. لا يمكن تجاوز القفل." else "أدخل رمز PIN للمتابعة.")
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); error = "" }, label = { Text("رمز PIN") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true, enabled = !storageError)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(enabled = pin.length >= 6 && !storageError, onClick = {
                val result = runCatching {
                    val prefs = appLockPrefs(context)
                    val salt = android.util.Base64.decode(prefs.getString("pin_salt", null), android.util.Base64.NO_WRAP)
                    val expected = android.util.Base64.decode(prefs.getString("pin_hash", null), android.util.Base64.NO_WRAP)
                    val actual = appLockHash(pin.toCharArray(), salt)
                    MessageDigest.isEqual(expected, actual)
                }
                if (result.getOrDefault(false)) { locked = false; backgrounded = false; pin = ""; error = "" }
                else error = "رمز PIN غير صحيح."
            }) { Text("فتح التطبيق") }
        }
    }
}

@Composable
internal fun CommercialAppLockSettingsScreen(context: Context, onBack: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(appLockEnabled(context)) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("رجوع") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("قفل التطبيق", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("يحمي بيانات المتجر برمز PIN مشفر محليًا، ويطلبه عند فتح التطبيق بعد مغادرته.")
        if (enabled) {
            OutlinedTextField(current, { current = it.filter(Char::isDigit).take(8) }, label = { Text("رمز PIN الحالي") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(next, { next = it.filter(Char::isDigit).take(8) }, label = { Text(if (enabled) "رمز PIN الجديد" else "إنشاء رمز PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirm, { confirm = it.filter(Char::isDigit).take(8) }, label = { Text("تأكيد رمز PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && next.length >= 6 && next == confirm && (!enabled || verifyPin(context, current)), onClick = {
            busy = true
            runCatching {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val hash = appLockHash(next.toCharArray(), salt)
                appLockPrefs(context).edit()
                    .putString("pin_salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
                    .putString("pin_hash", android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)).apply()
            }.onSuccess { enabled = true; current = ""; next = ""; confirm = ""; message = "تم تفعيل القفل وحفظ الرمز بأمان." }
                .onFailure { message = "تعذر حفظ إعداد القفل: " + (it.message ?: "خطأ غير معروف") }
            busy = false
        }, modifier = Modifier.fillMaxWidth()) { Text(if (enabled) "تغيير رمز PIN" else "تفعيل القفل") }
        if (enabled) OutlinedButton(enabled = !busy && verifyPin(context, current), onClick = {
            runCatching { appLockPrefs(context).edit().remove("pin_hash").remove("pin_salt").apply() }
                .onSuccess { enabled = false; current = ""; next = ""; confirm = ""; message = "تم إيقاف قفل PIN." }
                .onFailure { message = "تعذر إيقاف القفل الآمن." }
        }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.LockOpen, null); Spacer(Modifier.width(6.dp)); Text("إيقاف القفل") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        Text("الرمز من 6 إلى 8 أرقام. لا تحفظ نسخة نصية منه؛ يخزن التطبيق مشتقًا مشفرًا مع ملح عشوائي.", style = MaterialTheme.typography.bodySmall)
    }
}
private fun verifyPin(context: Context, candidate: String): Boolean = runCatching {
    val prefs = appLockPrefs(context)
    val salt = android.util.Base64.decode(prefs.getString("pin_salt", null), android.util.Base64.NO_WRAP)
    val expected = android.util.Base64.decode(prefs.getString("pin_hash", null), android.util.Base64.NO_WRAP)
    MessageDigest.isEqual(expected, appLockHash(candidate.toCharArray(), salt))
}.getOrDefault(false)
