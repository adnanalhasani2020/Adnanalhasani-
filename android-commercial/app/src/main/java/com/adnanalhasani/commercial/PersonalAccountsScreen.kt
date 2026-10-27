package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

private val personalAccountIo = Executors.newSingleThreadExecutor()
private val personalAccountMain = Handler(Looper.getMainLooper())

private fun personalAccountPrefs(context: Context) = EncryptedSharedPreferences.create(
    context, "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private fun personalAccountHttp(context: Context, path: String, payload: JSONObject, token: String? = null): String {
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
            throw IllegalStateException(detail.ifBlank { "تعذر تنفيذ الطلب (HTTP $status)." })
        }
        raw
    } finally { connection.disconnect() }
}

private fun personalAccountAccessToken(context: Context): String {
    val prefs = personalAccountPrefs(context)
    val saved = prefs.getString("refresh_token", null).orEmpty()
    require(saved.isNotBlank()) { "سجّل الدخول أولًا من إعدادات المتجر، ثم افتح الحسابات الفرعية." }
    val result = JSONObject(personalAccountHttp(context, "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", saved)))
    val access = result.optString("access_token")
    val refresh = result.optString("refresh_token").ifBlank { saved }
    require(access.isNotBlank()) { "انتهت جلسة الدخول؛ سجّل الدخول مجددًا." }
    prefs.edit().putString("refresh_token", refresh).apply()
    return access
}

private fun personalAccountRpc(context: Context, token: String, name: String, args: JSONObject = JSONObject()) =
    personalAccountHttp(context, "/rest/v1/rpc/$name", args, token)

private fun personalAccountValue(raw: String): Any? = JSONTokener(raw).nextValue()

private fun personalAccountSnapshot(context: Context, token: String): JSONObject {
    val account = personalAccountValue(personalAccountRpc(context, token, "commercial_account_get_my_personal_account"))
    val orgs = personalAccountValue(personalAccountRpc(context, token, "commercial_account_list_available_organizations"))
    val delegations = personalAccountValue(personalAccountRpc(context, token, "commercial_account_list_delegations"))
    return JSONObject().put("account", account ?: JSONObject())
        .put("organizations", orgs as? JSONArray ?: JSONArray())
        .put("delegations", delegations as? JSONArray ?: JSONArray())
}

private data class DelegationOrg(val id: String, val name: String, val currency: String)
private data class DelegationEntry(
    val id: String, val childNumber: String, val childName: String, val organizationId: String,
    val organizationName: String, val permissions: String, val status: String, val expiry: String,
    val limit: String, val spent: String, val merchantName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PersonalAccountsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var account by remember { mutableStateOf<JSONObject?>(null) }
    val orgs = remember { mutableStateListOf<DelegationOrg>() }
    val entries = remember { mutableStateListOf<DelegationEntry>() }
    var displayName by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("YER") }
    var childNumber by remember { mutableStateOf("") }
    var childPreview by remember { mutableStateOf<JSONObject?>(null) }
    var chosenOrg by remember { mutableStateOf<DelegationOrg?>(null) }
    var orgMenu by remember { mutableStateOf(false) }
    var viewReports by remember { mutableStateOf(false) }
    var canBook by remember { mutableStateOf(false) }
    var canPurchase by remember { mutableStateOf(true) }
    var expiryDays by remember { mutableStateOf("30") }
    var limitMinor by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    var approveEntry by remember { mutableStateOf<DelegationEntry?>(null) }
    var merchantName by remember { mutableStateOf("") }
    var merchantIcon by remember { mutableStateOf("person") }

    fun applySnapshot(snapshot: JSONObject) {
        val a = snapshot.optJSONObject("account")
        account = if (a != null && a.optString("account_id").isNotBlank()) a else null
        orgs.clear()
        val orgArray = snapshot.optJSONArray("organizations") ?: JSONArray()
        for (i in 0 until orgArray.length()) {
            val o = orgArray.optJSONObject(i) ?: continue
            orgs.add(DelegationOrg(o.optString("organization_id"), o.optString("name"), o.optString("currency_code", "YER")))
        }
        if (chosenOrg == null) chosenOrg = orgs.firstOrNull()
        entries.clear()
        val ds = snapshot.optJSONArray("delegations") ?: JSONArray()
        for (i in 0 until ds.length()) {
            val d = ds.optJSONObject(i) ?: continue
            entries.add(DelegationEntry(
                d.optString("id"), d.optString("child_account_number"), d.optString("child_display_name"),
                d.optString("organization_id"), d.optString("organization_name"),
                d.optJSONObject("permissions")?.toString() ?: "{}", d.optString("status"),
                d.optString("expires_at").takeUnless { it == "null" }.orEmpty(),
                d.optString("spend_limit_minor").takeUnless { it == "null" }.orEmpty(),
                d.optString("spent_minor", "0"),
                d.optString("merchant_display_name").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }

    fun request(action: (String) -> String, onSuccess: (String) -> Unit = {}) {
        if (busy) return
        busy = true; message = ""; isError = false
        personalAccountIo.execute {
            val result = runCatching { action(personalAccountAccessToken(context)) }
            personalAccountMain.post {
                busy = false
                result.onSuccess(onSuccess).onFailure {
                    message = it.message ?: "تعذر إكمال العملية."
                    isError = true
                }
            }
        }
    }

    fun refresh() {
        request({ personalAccountSnapshot(context, it).toString() }) {
            applySnapshot(JSONObject(it))
            message = "تم تحديث البيانات."
        }
    }

    LaunchedEffect(Unit) {
        request({ personalAccountSnapshot(context, it).toString() }) {
            applySnapshot(JSONObject(it))
            message = ""
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("الحسابات الفرعية", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("حسابك الرئيسي وصلاحياته داخل كل مؤسسة", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(enabled = !busy, onClick = { refresh() }) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") }
            }
        }
        item {
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حساب المنصة الرئيسي", fontWeight = FontWeight.Bold)
                    if (account == null) {
                        Text("أنشئ حسابًا شخصيًا للحصول على رقم حساب فريد تشاركه مع الشخص الذي تريد ربطه.")
                        OutlinedTextField(displayName, { displayName = it }, Modifier.fillMaxWidth(), label = { Text("الاسم الذي تختاره") }, singleLine = true)
                        OutlinedTextField(currency, { currency = it.uppercase().take(3) }, Modifier.fillMaxWidth(), label = { Text("رمز العملة (مثل YER)") }, singleLine = true)
                        Button(enabled = !busy && displayName.trim().length >= 2 && currency.matches(Regex("[A-Z]{3}")), onClick = {
                            request({ token ->
                                personalAccountRpc(context, token, "commercial_create_personal_account",
                                    JSONObject().put("p_display_name", displayName.trim()).put("p_currency_code", currency))
                            }) {
                                request({ token -> personalAccountSnapshot(context, token).toString() }) {
                                    applySnapshot(JSONObject(it)); message = "تم إنشاء الحساب الرئيسي."
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("إنشاء حساب المنصة") }
                    } else {
                        Text(account!!.optString("display_name"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text("رقم الحساب: ${account!!.optString("account_number")}") }
                        Text("العملة: ${account!!.optString("currency_code", "YER")}")
                        Text("شارك رقم الحساب فقط مع الشخص الذي تريد إضافته.")
                    }
                }
            }
        }
        if (account != null) item {
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("إضافة حساب فرعي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(childNumber, { childNumber = it.trim(); childPreview = null }, Modifier.fillMaxWidth(),
                        label = { Text("رقم حساب الشخص الآخر") }, singleLine = true)
                    OutlinedButton(enabled = !busy && childNumber.length >= 3, onClick = {
                        request({ token -> personalAccountRpc(context, token, "commercial_account_lookup_personal_account",
                            JSONObject().put("p_account_number", childNumber)) }) {
                            childPreview = runCatching { JSONObject(it) }.getOrNull()
                            message = "راجع بيانات الحساب قبل إرسال طلب الربط."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("التحقق من رقم الحساب") }
                    childPreview?.let { Text("تم العثور على: ${it.optString("display_name")} — ${it.optString("account_number")}", fontWeight = FontWeight.SemiBold) }
                    ExposedDropdownMenuBox(expanded = orgMenu, onExpandedChange = { orgMenu = it }) {
                        OutlinedTextField(chosenOrg?.name.orEmpty(), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true,
                            label = { Text("المؤسسة / النشاط التجاري") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(orgMenu) })
                        ExposedDropdownMenu(expanded = orgMenu, onDismissRequest = { orgMenu = false }) {
                            orgs.forEach { o -> DropdownMenuItem(text = { Text("${o.name} (${o.currency})") }, onClick = { chosenOrg = o; orgMenu = false }) }
                        }
                    }
                    if (orgs.isEmpty()) Text("لا توجد مؤسسات منشورة للاختيار حاليًا.", color = MaterialTheme.colorScheme.error)
                    Text("الصلاحيات داخل هذه المؤسسة فقط", fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(viewReports, { viewReports = it }); Text("عرض تقارير مشترياته") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(canBook, { canBook = it }); Text("حجز الخدمات والمواعيد") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(canPurchase, { canPurchase = it }); Text("شراء الأصناف والخدمات") }
                    OutlinedTextField(expiryDays, { expiryDays = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                        label = { Text("مدة الصلاحية بالأيام (فارغ = بلا انتهاء)") }, singleLine = true)
                    OutlinedTextField(limitMinor, { limitMinor = it.filter(Char::isDigit).take(15) }, Modifier.fillMaxWidth(),
                        label = { Text("سقف الشراء بوحدة العملة الصغرى (اختياري)") }, singleLine = true)
                    Button(enabled = !busy && childPreview != null && chosenOrg != null && (viewReports || canBook || canPurchase),
                        onClick = {
                            val org = chosenOrg ?: return@Button
                            request({ token ->
                                val args = JSONObject()
                                    .put("p_parent_account_id", account!!.optString("account_id"))
                                    .put("p_child_account_number", childNumber)
                                    .put("p_organization_id", org.id)
                                    .put("p_permissions", JSONObject().put("view_reports", viewReports).put("book", canBook).put("purchase", canPurchase))
                                if (expiryDays.isNotBlank()) args.put("p_expires_at", Instant.now().plusSeconds(expiryDays.toLong().coerceAtMost(3650) * 86400L).toString())
                                if (limitMinor.isNotBlank()) args.put("p_spend_limit_minor", limitMinor.toLong())
                                personalAccountRpc(context, token, "commercial_account_create_delegation", args)
                            }) {
                                childNumber = ""; childPreview = null
                                request({ token -> personalAccountSnapshot(context, token).toString() }) {
                                    applySnapshot(JSONObject(it)); message = "أُرسل الطلب إلى إدارة المؤسسة للموافقة."
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("إرسال طلب الربط") }
                    Text("لن يستطيع الحساب الفرعي الشراء أو الحجز قبل موافقة إدارة المؤسسة. التحقق من الصلاحيات وسقف الشراء يتم على الخادم.")
                }
            }
        }
        item { Text("الحسابات الفرعية وطلبات الموافقة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (entries.isEmpty()) item { Text("لا توجد حسابات فرعية أو طلبات ربط بعد.") }
        items(entries, key = { it.id }) { e ->
            Card(border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.merchantName.ifBlank { e.childName.ifBlank { e.childNumber } }, fontWeight = FontWeight.Bold)
                    Text("المؤسسة: ${e.organizationName}")
                    Text("رقم الحساب الفرعي: ${e.childNumber}")
                    Text("الحالة: ${when (e.status) {
                        "pending_merchant_approval" -> "بانتظار موافقة المؤسسة"
                        "active" -> "فعال"
                        "rejected" -> "مرفوض"
                        "revoked" -> "ملغى"
                        "expired" -> "منتهي"
                        else -> e.status
                    }}")
                    Text("الصلاحيات: ${e.permissions}")
                    if (e.limit.isNotBlank()) Text("السقف: ${e.limit} | المنفق: ${e.spent}")
                    if (e.expiry.isNotBlank()) Text("انتهاء الصلاحية: ${e.expiry}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (e.status == "pending_merchant_approval") {
                            OutlinedButton(onClick = { approveEntry = e; merchantName = e.childName.ifBlank { e.childNumber }; merchantIcon = "person" }) { Text("موافقة التاجر") }
                        }
                        if (e.status == "active") {
                            OutlinedButton(enabled = !busy, onClick = {
                                request({ token -> personalAccountRpc(context, token, "commercial_account_revoke_delegation",
                                    JSONObject().put("p_delegation_id", e.id)); "ok" }) { refresh() }
                            }) { Text("إلغاء الربط") }
                            OutlinedButton(enabled = !busy, onClick = {
                                request({ token -> personalAccountRpc(context, token, "commercial_account_delegated_spend_report",
                                    JSONObject().put("p_delegation_id", e.id)) }) { report = it }
                            }) { Text("تقرير الإنفاق") }
                        }
                    }
                }
            }
        }
        if (report.isNotBlank()) item { Card { Column(Modifier.padding(12.dp)) { Text("تقرير الإنفاق", fontWeight = FontWeight.Bold); Text(report) } } }
        if (message.isNotBlank()) item { Text(message, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }

    approveEntry?.let { target ->
        AlertDialog(
            onDismissRequest = { approveEntry = null },
            title = { Text("اعتماد الحساب الفرعي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر الاسم والأيقونة اللذين سيظهر بهما الحساب داخل مؤسستك.")
                    OutlinedTextField(merchantName, { merchantName = it }, label = { Text("اسم الحساب داخل المؤسسة") }, singleLine = true)
                    OutlinedTextField(merchantIcon, { merchantIcon = it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }.take(48) },
                        label = { Text("مفتاح الأيقونة مثل person أو family") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(enabled = !busy && merchantName.trim().isNotEmpty(), onClick = {
                    request({ token -> personalAccountRpc(context, token, "commercial_account_review_delegation",
                        JSONObject().put("p_delegation_id", target.id).put("p_approve", true)
                            .put("p_merchant_display_name", merchantName.trim()).put("p_merchant_icon_key", merchantIcon.ifBlank { "person" })) }) {
                        approveEntry = null; refresh()
                    }
                }) { Text("اعتماد") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    request({ token -> personalAccountRpc(context, token, "commercial_account_review_delegation",
                        JSONObject().put("p_delegation_id", target.id).put("p_approve", false)); "ok" }) {
                        approveEntry = null; refresh()
                    }
                }) { Text("رفض") }
            }
        )
    }
}
