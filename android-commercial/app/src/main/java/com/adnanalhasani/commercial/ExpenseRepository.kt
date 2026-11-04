package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal fun loadCommercialCloudExpenseCurrencyCode(context: Context): String {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_organizations?select=default_currency_code&id=eq.$orgId",
        access
    )
    require(rows.length() == 1) { "تعذر تحديد عملة المؤسسة من الخادم." }
    val code = rows.getJSONObject(0).optString("default_currency_code").uppercase(Locale.US)
    require(code.matches(Regex("^[A-Z]{3}$"))) { "عملة المؤسسة غير صالحة." }
    return code
}

internal fun loadCommercialCloudExpenseReportingTimeZone(context: Context): java.time.ZoneId {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_organizations?select=default_timezone&id=eq.$orgId",
        access
    )
    require(rows.length() == 1) { "تعذر تحديد المنطقة الزمنية للتقارير من الخادم." }
    val zoneName = rows.getJSONObject(0).optString("default_timezone").takeUnless { it.isBlank() || it == "null" }
        ?: throw IllegalStateException("لم تُضبط المنطقة الزمنية المعتمدة للتقارير للمؤسسة.")
    return try {
        java.time.ZoneId.of(zoneName)
    } catch (e: java.time.DateTimeException) {
        throw IllegalStateException("المنطقة الزمنية المعتمدة للتقارير غير صالحة: $zoneName", e)
    }
}

internal data class CommercialCloudExpenseVoucher(
    val id: String,
    val category: String,
    val amountMinor: Long,
    val currencyCode: String,
    val paymentMethod: String,
    val evidenceReference: String,
    val note: String,
    val journalEntryId: String,
    val recordedAt: String,
    val canceledAt: String,
    val cancellationReason: String,
    val reversalJournalEntryId: String
)

internal fun cancelCommercialCloudExpenseVoucher(context: Context, voucherId: String, reason: String): JSONObject {
    require(voucherId.isNotBlank()) { "سند الصرف غير محدد." }
    require(reason.trim().length in 3..300) { "أدخل سبب إلغاء واضحًا (3 أحرف على الأقل)." }
    val (_, access) = commercialMasterDataAccess(context)
    val payload = JSONObject().put("p_voucher_id", voucherId).put("p_reason", reason.trim())
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_cancel_expense_voucher", "POST", payload, access
    ))
}

internal fun loadCommercialCloudExpenses(context: Context): List<CommercialCloudExpenseVoucher> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_expense_vouchers" +
        "?select=id,category,amount_minor,currency_code,payment_method,evidence_reference,note,journal_entry_id,recorded_at,canceled_at,cancellation_reason,reversal_journal_entry_id" +
        "&organization_id=eq.$orgId&order=recorded_at.desc,id.desc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            add(CommercialCloudExpenseVoucher(
                id = row.optString("id"),
                category = row.optString("category").ifBlank { "مصروف" },
                amountMinor = row.optLong("amount_minor", 0L),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                paymentMethod = row.optString("payment_method").ifBlank { "cash" },
                evidenceReference = row.optString("evidence_reference").takeUnless { it == "null" }.orEmpty(),
                note = row.optString("note").takeUnless { it == "null" }.orEmpty(),
                journalEntryId = row.optString("journal_entry_id").takeUnless { it == "null" }.orEmpty(),
                recordedAt = row.optString("recorded_at").takeUnless { it == "null" }.orEmpty(),
                canceledAt = row.optString("canceled_at").takeUnless { it == "null" }.orEmpty(),
                cancellationReason = row.optString("cancellation_reason").takeUnless { it == "null" }.orEmpty(),
                reversalJournalEntryId = row.optString("reversal_journal_entry_id").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
}

private fun commercialExpensePendingPreferences(context: Context) =
    context.getSharedPreferences("commercial_expense_pending_v1", Context.MODE_PRIVATE)

internal fun getOrCreateCommercialExpenseIdempotencyKey(
    context: Context, category: String, amountMinor: Long, currencyCode: String,
    paymentMethod: String, evidenceReference: String, note: String
): String {
    val canonical = JSONObject()
        .put("category", category.trim())
        .put("amount_minor", amountMinor)
        .put("currency_code", currencyCode)
        .put("payment_method", paymentMethod)
        .put("evidence_reference", evidenceReference.trim())
        .put("note", note.trim()).toString()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val preferences = commercialExpensePendingPreferences(context)
    val preferenceKey = "request_$digest"
    val existing = preferences.getString(preferenceKey, null).orEmpty()
    if (existing.isNotBlank()) return existing
    val created = UUID.randomUUID().toString()
    check(preferences.edit().putString(preferenceKey, created).commit()) {
        "تعذر حفظ مفتاح سند الصرف قبل الإرسال؛ لم يتم إرسال العملية."
    }
    return preferences.getString(preferenceKey, null).orEmpty().also {
        check(it.isNotBlank()) { "تعذر تأكيد حفظ مفتاح سند الصرف؛ لم يتم إرسال العملية." }
    }
}

internal fun clearCommercialExpenseIdempotencyKey(
    context: Context, category: String, amountMinor: Long, currencyCode: String,
    paymentMethod: String, evidenceReference: String, note: String, idempotencyKey: String
): Boolean {
    val canonical = JSONObject()
        .put("category", category.trim())
        .put("amount_minor", amountMinor)
        .put("currency_code", currencyCode)
        .put("payment_method", paymentMethod)
        .put("evidence_reference", evidenceReference.trim())
        .put("note", note.trim()).toString()
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val preferences = commercialExpensePendingPreferences(context)
    val preferenceKey = "request_$digest"
    if (preferences.getString(preferenceKey, null) != idempotencyKey) return false
    return preferences.edit().remove(preferenceKey).commit()
}

internal fun recordCommercialCloudExpense(
    context: Context, category: String, amountMinor: Long, currencyCode: String,
    paymentMethod: String, evidenceReference: String, note: String, idempotencyKey: String
): JSONObject {
    require(category.trim().length in 1..120) { "أدخل تصنيف المصروف." }
    require(amountMinor > 0L) { "يجب أن يكون مبلغ المصروف أكبر من صفر." }
    require(currencyCode.matches(Regex("^[A-Z]{3}$"))) { "عملة المصروف غير صالحة." }
    require(paymentMethod in setOf("cash", "bank_transfer")) { "طريقة الصرف غير مدعومة." }
    require(evidenceReference.trim().length in 3..200) { "أدخل مرجع إثبات الصرف (3 أحرف على الأقل)." }
    require(note.length <= 1000) { "الملاحظات أطول من الحد المسموح." }
    require(idempotencyKey.length in 8..200) { "مفتاح سند الصرف غير صالح." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val payload = JSONObject()
        .put("p_organization_id", orgId)
        .put("p_category", category.trim())
        .put("p_amount_minor", amountMinor)
        .put("p_currency_code", currencyCode)
        .put("p_payment_method", paymentMethod)
        .put("p_evidence_reference", evidenceReference.trim())
        .put("p_note", note.trim())
        .put("p_idempotency_key", idempotencyKey)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_record_expense", "POST", payload, access
    ))
}
