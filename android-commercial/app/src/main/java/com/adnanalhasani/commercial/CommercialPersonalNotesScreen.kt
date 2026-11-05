package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val personalNotesIo = Executors.newSingleThreadExecutor()
private val personalNotesMain = Handler(Looper.getMainLooper())

private fun personalNotesHttp(context: Context, path: String, payload: JSONObject, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات Supabase غير مكتملة." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 20000; doOutput = true
        setRequestProperty("apikey", anon); setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val detail = runCatching { JSONObject(raw).optString("message") }.getOrDefault("")
            throw IllegalStateException(detail.ifBlank { "تعذر تنفيذ العملية (HTTP $status)." })
        }
        raw
    } finally { connection.disconnect() }
}

private fun personalNotesToken(context: Context): String {
    val prefs = EncryptedSharedPreferences.create(context, "ruh_catalog_auth_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    val refresh = prefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى حساب المنصة أولًا." }
    val session = JSONObject(personalNotesHttp(context, "/auth/v1/token?grant_type=refresh_token",
        JSONObject().put("refresh_token", refresh)))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الدخول؛ سجّل الدخول مجددًا." }
    session.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.edit().putString("refresh_token", it).apply() }
    return access
}

private data class PersonalNoteRow(
    val id: String, val title: String, val body: String, val dueAt: String,
    val reminderAt: String, val completedAt: String, val overdue: Boolean, val reminderDue: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialPersonalNotesScreen(context: Context, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<PersonalNoteRow>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var includeCompleted by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var dueAt by remember { mutableStateOf("") }
    var reminderAt by remember { mutableStateOf("") }

    fun request(payload: JSONObject, onSuccess: (String) -> Unit) {
        if (busy) return
        busy = true; error = ""
        personalNotesIo.execute {
            val result = runCatching { personalNotesHttp(context, "/rest/v1/rpc/" + payload.optString("_rpc"),
                JSONObject(payload.toString()).also { it.remove("_rpc") }, personalNotesToken(context)) }
            personalNotesMain.post {
                busy = false
                result.onSuccess(onSuccess).onFailure { error = it.message ?: "تعذر تنفيذ العملية." }
            }
        }
    }
    fun refresh() {
        request(JSONObject().put("_rpc", "commercial_personal_notes_list_my")
            .put("p_include_completed", includeCompleted).put("p_limit", 150)) { raw ->
            val array = JSONArray(raw)
            rows = buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(PersonalNoteRow(o.optString("id"), o.optString("title"), o.optString("body"),
                        o.optString("due_at").takeUnless { it == "null" }.orEmpty(),
                        o.optString("reminder_at").takeUnless { it == "null" }.orEmpty(),
                        o.optString("completed_at").takeUnless { it == "null" }.orEmpty(),
                        o.optBoolean("is_overdue"), o.optBoolean("reminder_due")))
                }
            }
        }
    }
    LaunchedEffect(includeCompleted) { refresh() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("ملاحظاتي وتنبيهاتي", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") } },
            actions = { IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Default.Refresh, "تحديث") } })
    }, floatingActionButton = {
        FloatingActionButton(onClick = {
            editingId = ""; title = ""; body = ""; dueAt = ""; reminderAt = ""; editor = true
        }) { Icon(Icons.Default.Add, "ملاحظة جديدة") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) { Text(error); TextButton(onClick = { refresh() }) { Text("إعادة المحاولة") } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ملاحظاتك خاصة بحسابك ولا تظهر لبقية مستخدمي المتجر.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { includeCompleted = !includeCompleted }) {
                        Text(if (includeCompleted) "إخفاء المكتمل" else "عرض المكتمل")
                    }
                }
            }
            if (rows.isEmpty() && !busy) item { Text("لا توجد ملاحظات. أضف أول تذكير لك.") }
            items(rows, key = { it.id }) { row ->
                Card(border = BorderStroke(1.dp, if (row.overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (row.completedAt.isNotBlank()) Text("مكتملة", color = MaterialTheme.colorScheme.tertiary)
                            else if (row.overdue) Text("متأخرة", color = MaterialTheme.colorScheme.error)
                            else if (row.reminderDue) Text("حان التذكير", color = MaterialTheme.colorScheme.primary)
                        }
                        if (row.body.isNotBlank()) Text(row.body)
                        if (row.dueAt.isNotBlank()) Text("الموعد: " + row.dueAt, style = MaterialTheme.typography.bodySmall)
                        if (row.reminderAt.isNotBlank()) Text("التذكير: " + row.reminderAt, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (row.completedAt.isBlank()) OutlinedButton(enabled = !busy, onClick = {
                                request(JSONObject().put("_rpc", "commercial_personal_notes_complete_my")
                                    .put("p_note_id", row.id).put("p_completed", true)) { refresh() }
                            }) { Text("تمت") }
                            TextButton(enabled = !busy, onClick = {
                                editingId = row.id; title = row.title; body = row.body
                                dueAt = row.dueAt; reminderAt = row.reminderAt; editor = true
                            }) { Text("تعديل") }
                            TextButton(enabled = !busy, onClick = {
                                request(JSONObject().put("_rpc", "commercial_personal_notes_archive_my")
                                    .put("p_note_id", row.id)) { refresh() }
                            }) { Text("أرشفة") }
                        }
                    }
                }
            }
        }
    }
    if (editor) AlertDialog(onDismissRequest = { editor = false },
        title = { Text(if (editingId.isBlank()) "ملاحظة جديدة" else "تعديل الملاحظة") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(160) }, label = { Text("العنوان") }, singleLine = true)
                OutlinedTextField(body, { body = it.take(4000) }, label = { Text("التفاصيل") }, minLines = 2)
                OutlinedTextField(dueAt, { dueAt = it }, label = { Text("موعد الإنجاز ISO مثل 2026-11-06T15:00:00Z") }, singleLine = true)
                OutlinedTextField(reminderAt, { reminderAt = it }, label = { Text("موعد التنبيه ISO (اختياري)") }, singleLine = true)
                Text("يظهر التنبيه عند فتح هذه الشاشة بعد حلول موعده. التنبيهات الخلفية حتى لو كان التطبيق مغلقًا غير مفعّلة في هذه النسخة.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(enabled = !busy && title.isNotBlank(), onClick = {
            val payload = JSONObject().put("_rpc", "commercial_personal_notes_save_my")
                .put("p_note_id", editingId.ifBlank { JSONObject.NULL }).put("p_title", title.trim())
                .put("p_body", body).put("p_due_at", dueAt.trim().ifBlank { JSONObject.NULL })
                .put("p_reminder_at", reminderAt.trim().ifBlank { JSONObject.NULL })
            request(payload) { editor = false; refresh() }
        }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = { editor = false }) { Text("إلغاء") } })
}
