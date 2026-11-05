package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors

private val workNotesIo = Executors.newSingleThreadExecutor()
private val workNotesMain = Handler(Looper.getMainLooper())

private fun workNotesHttp(context: Context, path: String, payload: JSONObject, token: String? = null): String =
    SupabaseClient.request(
        path = path,
        method = "POST",
        payload = payload,
        token = token,
        readTimeoutMs = 20000,
        wrapNetworkErrors = false
    )

private fun workNotesAccessToken(context: Context): String {
    val prefs = commercialCloudSessionPreferences(context)
    val saved = prefs.getString("refresh_token", null).orEmpty()
    require(saved.isNotBlank()) { "سجّل الدخول إلى حساب المنصة أولًا." }
    val session = runCatching {
        JSONObject(SupabaseClient.request(
            path = "/auth/v1/token?grant_type=refresh_token",
            method = "POST",
            payload = JSONObject().put("refresh_token", saved),
            wrapNetworkErrors = false
        ))
    }.getOrElse { throw IllegalStateException("انتهت جلسة الدخول أو تعذر تجديدها؛ سجّل الدخول مجددًا.") }
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الدخول؛ سجّل الدخول مجددًا." }
    session.optString("refresh_token").takeIf { it.isNotBlank() }?.let {
        prefs.edit().putString("refresh_token", it).apply()
    }
    return access
}

private data class PersonalWorkNote(
    val id: String, val title: String, val body: String, val reminderAt: String,
    val status: String, val createdAt: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalWorkNotesScreen(context: Context, onBack: () -> Unit) {
    var rows by remember { mutableStateOf<List<PersonalWorkNote>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PersonalWorkNote?>(null) }

    fun run(action: (String) -> String, success: (String) -> Unit = {}) {
        if (busy) return
        busy = true; error = ""; notice = ""
        workNotesIo.execute {
            val result = runCatching { action(workNotesAccessToken(context)) }
            workNotesMain.post {
                busy = false
                result.onSuccess(success).onFailure { error = it.message ?: "تعذر إكمال العملية." }
            }
        }
    }
    fun refresh() {
        run({ token -> workNotesHttp(context, "/rest/v1/rpc/commercial_user_work_notes_list_my",
            JSONObject().put("p_limit", 100), token) }) { raw ->
            val array = JSONArray(raw)
            rows = buildList {
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    add(PersonalWorkNote(row.optString("id"), row.optString("title"), row.optString("body"),
                        row.optString("reminder_at").takeUnless { it == "null" }.orEmpty(),
                        row.optString("status"), row.optString("created_at")))
                }
            }
            notice = if (rows.isEmpty()) "لا توجد ملاحظات بعد. أضف أول مهمة أو تذكير." else ""
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("ملاحظاتي وتنبيهاتي", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
            actions = { IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } }
        )
    }, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = { editing = null; editorOpen = true }, icon = { Icon(Icons.Default.StickyNote2, null) }, text = { Text("ملاحظة جديدة") })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(error); TextButton(onClick = { refresh() }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (notice.isNotBlank() && error.isBlank()) item { Text(notice, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                Text("الملاحظات خاصة بحسابك. عند حلول موعد التذكير، ينشئ النظام إشعارًا مرة واحدة عند فتح هذه الشاشة أو تحديثها.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows, key = { it.id }) { row ->
                Card(border = BorderStroke(1.dp, if (row.status == "open") MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.secondary.copy(alpha = .35f))) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Text(if (row.status == "completed") "مكتملة" else "مفتوحة", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                        if (row.body.isNotBlank()) Text(row.body)
                        if (row.reminderAt.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Text("موعد التذكير: " + row.reminderAt, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(enabled = !busy, onClick = { editing = row; editorOpen = true }) {
                                Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("تعديل")
                            }
                            if (row.status == "open") {
                                TextButton(enabled = !busy, onClick = {
                                    run({ token -> workNotesHttp(context, "/rest/v1/rpc/commercial_user_work_notes_update_my",
                                        JSONObject().put("p_note_id", row.id).put("p_title", row.title).put("p_body", row.body)
                                            .put("p_reminder_at", row.reminderAt.ifBlank { JSONObject.NULL }).put("p_status", "completed"), token) }) {
                                        notice = "تم تعليم الملاحظة كمكتملة."; refresh()
                                    }
                                }) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(4.dp)); Text("إنجاز") }
                            } else {
                                TextButton(enabled = !busy, onClick = {
                                    run({ token -> workNotesHttp(context, "/rest/v1/rpc/commercial_user_work_notes_update_my",
                                        JSONObject().put("p_note_id", row.id).put("p_title", row.title).put("p_body", row.body)
                                            .put("p_reminder_at", row.reminderAt.ifBlank { JSONObject.NULL }).put("p_status", "archived"), token) }) { refresh() }
                                }) { Icon(Icons.Default.Archive, null); Spacer(Modifier.width(4.dp)); Text("أرشفة") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) {
        var title by remember(editing?.id) { mutableStateOf(editing?.title.orEmpty()) }
        var body by remember(editing?.id) { mutableStateOf(editing?.body.orEmpty()) }
        var reminder by remember(editing?.id) { mutableStateOf(editing?.reminderAt.orEmpty()) }
        var validation by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text(if (editing == null) "إضافة ملاحظة أو تذكير" else "تعديل الملاحظة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it.take(160); validation = "" },
                        label = { Text("عنوان المهمة") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = body, onValueChange = { body = it.take(4000) },
                        label = { Text("التفاصيل والملاحظات") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = reminder, onValueChange = { reminder = it.take(40); validation = "" },
                        label = { Text("موعد التذكير (اختياري)") }, placeholder = { Text("2026-11-06T09:00:00Z") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("اكتب الموعد بصيغة ISO-8601 مع المنطقة الزمنية، أو اتركه فارغًا.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (validation.isNotBlank()) Text(validation, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    val cleanTitle = title.trim()
                    val cleanReminder = reminder.trim()
                    if (cleanTitle.isBlank()) validation = "اكتب عنوانًا للملاحظة."
                    else if (cleanReminder.isNotBlank() && runCatching { Instant.parse(cleanReminder) }.isFailure)
                        validation = "صيغة الموعد غير صحيحة. مثال: 2026-11-06T09:00:00Z"
                    else {
                        val orgId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
                            .getString("organization_id", null)
                        val reminderValue: Any = cleanReminder.ifBlank { JSONObject.NULL as Any }
                        run({ token ->
                            val payload = if (editing == null) JSONObject()
                                .put("p_title", cleanTitle).put("p_body", body.trim())
                                .put("p_organization_id", orgId ?: JSONObject.NULL).put("p_reminder_at", reminderValue)
                            else JSONObject().put("p_note_id", editing!!.id).put("p_title", cleanTitle)
                                .put("p_body", body.trim()).put("p_reminder_at", reminderValue).put("p_status", editing!!.status)
                            workNotesHttp(context, "/rest/v1/rpc/" + if (editing == null)
                                "commercial_user_work_notes_create" else "commercial_user_work_notes_update_my", payload, token)
                        }) {
                            editorOpen = false; notice = "تم حفظ الملاحظة."; refresh()
                        }
                    }
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("إلغاء") } }
        )
    }
}
