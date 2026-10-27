package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private val notificationsIo = Executors.newSingleThreadExecutor()
private val notificationsMain = Handler(Looper.getMainLooper())

private fun notificationSessionPrefs(context: Context) = EncryptedSharedPreferences.create(
    context, "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private fun notificationHttp(context: Context, path: String, payload: JSONObject, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات Supabase غير مكتملة في هذا البناء." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", anon)
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { "تعذر تحميل الإشعارات (HTTP $status)." })
        }
        raw
    } finally { connection.disconnect() }
}

private fun notificationAccessToken(context: Context): String {
    val prefs = notificationSessionPrefs(context)
    val saved = prefs.getString("refresh_token", null).orEmpty()
    require(saved.isNotBlank()) { "سجّل الدخول إلى حساب المنصة أولًا من قائمة الحسابات الشخصية." }
    val session = runCatching {
        JSONObject(notificationHttp(context, "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", saved)))
    }.getOrElse { throw IllegalStateException("انتهت جلسة الدخول أو تعذر تجديدها؛ سجّل الدخول مجددًا.") }
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الدخول؛ سجّل الدخول مجددًا." }
    session.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
        prefs.edit().putString("refresh_token", it).apply()
    }
    return access
}

private data class CommercialNotificationRow(
    val id: String, val title: String, val body: String, val type: String,
    val priority: String, val readAt: String, val createdAt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialNotificationsScreen(context: Context, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<CommercialNotificationRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun run(action: (String) -> String, onSuccess: (String) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; notice = ""
        notificationsIo.execute {
            val result = runCatching { action(notificationAccessToken(context)) }
            notificationsMain.post {
                busy = false
                result.onSuccess(onSuccess).onFailure { error = it.message ?: "تعذر إكمال العملية." }
            }
        }
    }

    fun refresh() {
        run({ token -> notificationHttp(context, "/rest/v1/rpc/commercial_user_notifications_list_my",
            JSONObject().put("p_limit", 50), token) }) { raw ->
            val array = JSONArray(raw)
            rows = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    rows.add(CommercialNotificationRow(
                        row.optString("id"), row.optString("title"), row.optString("body"),
                        row.optString("notification_type"), row.optString("priority"),
                        row.optString("read_at").takeUnless { it == "null" }.orEmpty(),
                        row.optString("created_at")
                    ))
                }
            }
            notice = if (rows.isEmpty()) "لا توجد إشعارات في حسابك حتى الآن." else ""
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("الإشعارات", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } }
        )
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(error)
                        TextButton(onClick = { refresh() }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (notice.isNotBlank() && error.isBlank()) item { Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(rows, key = { it.id }) { row ->
                Card(border = BorderStroke(1.dp, if (row.readAt.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Notifications, contentDescription = null, tint = if (row.readAt.isBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(Modifier.weight(1f)) {
                                Text(row.title, fontWeight = FontWeight.Bold)
                                Text(row.createdAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (row.readAt.isBlank()) Text("جديد", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(row.body)
                        if (row.type == "approval" && row.readAt.isBlank()) {
                            Text("افتح الحسابات الفرعية لإتمام مراجعة الطلب.", style = MaterialTheme.typography.bodySmall)
                        }
                        if (row.readAt.isBlank()) {
                            OutlinedButton(enabled = !busy, onClick = {
                                run({ token -> notificationHttp(context, "/rest/v1/rpc/commercial_user_notifications_mark_read",
                                    JSONObject().put("p_notification_id", row.id), token) }) {
                                    refresh()
                                }
                            }) {
                                Icon(Icons.Default.MarkEmailRead, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("تحديد كمقروء")
                            }
                        }
                    }
                }
            }
        }
    }
}
