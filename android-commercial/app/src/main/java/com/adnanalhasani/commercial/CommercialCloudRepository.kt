package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

internal data class CommercialCloudCustomer(
    val id: String,
    val displayName: String,
    val phone: String,
    val email: String
) {
    val label: String get() = buildList {
        add(displayName)
        if (phone.isNotBlank()) add(phone)
        if (email.isNotBlank()) add(email)
    }.joinToString(" · ")
}

internal fun loadCommercialCloudCustomers(context: Context): List<CommercialCloudCustomer> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_customers" +
        "?select=id,display_name,phone,email,state&organization_id=eq.$orgId&state=eq.active&order=display_name.asc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val name = row.optString("display_name").takeUnless { it == "null" }.orEmpty().trim()
            if (id.isBlank() || name.isBlank()) continue
            add(CommercialCloudCustomer(id, name,
                row.optString("phone").takeUnless { it == "null" }.orEmpty(),
                row.optString("email").takeUnless { it == "null" }.orEmpty()))
        }
    }
}

internal fun commercialStableIdempotencyKey(prefix: String, parts: List<String>): String {
    val canonical = parts.joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return prefix + "-" + digest
}

internal fun commercialCloudPagedGet(path: String, accessToken: String, pageSize: Int = 500): JSONArray {
    require(pageSize in 1..1000) { "حجم الصفحة غير صالح." }
    require(path.contains("?") && !path.contains("limit=") && !path.contains("offset=")) { "استعلام الصفحات يحتوي على محددات مسبقة." }
    val rows = JSONArray()
    var offset = 0
    while (true) {
        val page = JSONArray(SupabaseClient.request("$path&limit=$pageSize&offset=$offset", "GET", token = accessToken))
        for (index in 0 until page.length()) rows.put(page.getJSONObject(index))
        if (page.length() < pageSize) break
        offset += pageSize
    }
    return rows
}

internal fun commercialMasterDataAccess(context: Context): Pair<String, String> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = saved.getString("organization_id", null).orEmpty()
    require(orgId.isNotBlank()) { "ملف المؤسسة غير محفوظ." }
    val sessionPrefs = commercialCloudSessionPreferences(context)
    val refresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى الحساب أولًا." }
    val session = JSONObject(SupabaseClient.request(
        "/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { refresh }).apply()
    return orgId to access
}

internal data class CommercialCloudRecord(
    val id: String, val title: String, val subtitle: String, val status: String,
    val amountMinor: Long = 0L, val createdAt: String = "",
    val details: List<String> = emptyList(), val currencyCode: String = "",
    val paymentMethodCode: String = ""
)

/** Read-only operational projections. Writes must use an approved RPC, never generic POST. */
internal fun loadCommercialCloudRecords(context: Context, screenKey: String): List<CommercialCloudRecord> {
    val config = when (screenKey) {
        "customers" -> Triple("commercial_customers", "id,display_name,phone,email,state,created_at", "display_name")
        "suppliers" -> Triple("commercial_suppliers", "id,display_name,supplier_code,phone,email,state,payment_terms_days,created_at", "display_name")
        "purchases" -> Triple("commercial_purchase_orders", "id,order_number,status,ordered_at,expected_at,currency_code,supplier_id,created_at", "created_at")
        "payments" -> Triple("commercial_payments", "id,status,sale_id,amount_minor,currency_code,payment_method_type,created_at", "created_at")
        "returns" -> Triple("commercial_sales_returns", "id,status,sale_id,reason_code,refund_status,restock_status,requested_refund_minor,currency_code,created_at", "created_at")
        "journals" -> Triple("commercial_journal_entries", "id,entry_number,status,entry_date,description,source_type,source_id,posted_at,created_at", "entry_date")
        "accounts" -> Triple("commercial_ledger_accounts", "id,account_code,account_name,account_type,normal_balance,currency_code,is_active,parent_account_id", "account_code")
        else -> throw IllegalArgumentException("هذه الشاشة غير مدعومة.")
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/${config.first}?select=${config.second}&organization_id=eq.$orgId&order=${config.third}.desc.nullslast,id.asc", access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val title: String
            val subtitle: String
            val status: String
            val amount: Long
            when (screenKey) {
                "customers" -> {
                    title = row.optString("display_name").ifBlank { "عميل بدون اسم" }
                    subtitle = listOf(row.optString("phone"), row.optString("email")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا توجد بيانات اتصال" }
                    status = row.optString("state").ifBlank { "غير محدد" }; amount = 0L
                }
                "suppliers" -> {
                    title = row.optString("display_name").ifBlank { "مورد بدون اسم" }
                    subtitle = listOf(row.optString("supplier_code"), row.optString("phone"), row.optString("payment_terms_days").takeUnless { it == "null" }?.let { "أجل السداد: $it يوم" }).filterNotNull().filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا توجد بيانات إضافية" }
                    status = row.optString("state").ifBlank { "غير محدد" }; amount = 0L
                }
                "purchases" -> {
                    title = row.optString("order_number").takeUnless { it == "null" || it.isBlank() }?.let { "أمر شراء #$it" } ?: "أمر شراء"
                    subtitle = "المورد: " + row.optString("supplier_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير محدد" } + " • العملة: " + row.optString("currency_code").ifBlank { "—" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = 0L
                }
                "payments" -> {
                    title = "سند دفع/تحصيل"
                    subtitle = "المرجع: " + row.optString("sale_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير مرتبط بفاتورة" } + " • " + row.optString("payment_method_type").ifBlank { "طريقة غير محددة" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = row.optLong("amount_minor", 0L)
                }
                "returns" -> {
                    title = "طلب مرتجع • " + row.optString("reason_code").ifBlank { "بدون سبب محدد" }
                    subtitle = "الفاتورة: " + row.optString("sale_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير محددة" } + " • رد المبلغ: " + row.optString("refund_status").ifBlank { "غير محدد" } + " • إعادة المخزون: " + row.optString("restock_status").ifBlank { "غير محدد" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = row.optLong("requested_refund_minor", 0L)
                }
                "journals" -> {
                    title = row.optString("entry_number").takeUnless { it == "null" || it.isBlank() }?.let { "قيد #$it" } ?: "قيد محاسبي"
                    subtitle = listOf(row.optString("entry_date"), row.optString("source_type"), row.optString("description")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا يوجد وصف" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = 0L
                }
                else -> {
                    title = listOf(row.optString("account_code"), row.optString("account_name")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "حساب دفتر أستاذ" }
                    subtitle = listOf(row.optString("account_type"), row.optString("normal_balance"), row.optString("currency_code")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ")
                    status = if (row.optBoolean("is_active", false)) "نشط" else "غير نشط"; amount = 0L
                }
            }
            val details = when (screenKey) {
                "customers" -> listOfNotNull(
                    row.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { "البريد الإلكتروني: $it" },
                    row.optString("phone").takeIf { it.isNotBlank() && it != "null" }?.let { "رقم الهاتف: $it" },
                    row.optString("state").takeIf { it.isNotBlank() && it != "null" }?.let { "حالة العميل: $it" }
                ).distinct()
                "suppliers" -> listOfNotNull(
                    row.optString("supplier_code").takeIf { it.isNotBlank() && it != "null" }?.let { "رمز المورد: $it" },
                    row.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { "البريد الإلكتروني: $it" },
                    row.optString("phone").takeIf { it.isNotBlank() && it != "null" }?.let { "رقم الهاتف: $it" },
                    row.optString("payment_terms_days").takeIf { it.isNotBlank() && it != "null" }?.let { "شروط السداد: $it يومًا" },
                    row.optString("state").takeIf { it.isNotBlank() && it != "null" }?.let { "حالة المورد: $it" }
                ).distinct()
                else -> emptyList()
            }
            add(CommercialCloudRecord(
                id, title, subtitle, status, amount,
                row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                details,
                row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                if (screenKey == "payments") row.optString("payment_method_type").takeUnless { it == "null" }.orEmpty() else ""
            ))
        }
    }
}
