package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CustomerPortalOutstandingSale(
    val saleId: String, val invoiceNumber: String, val createdAt: String,
    val totalMinor: Long, val paidMinor: Long, val refundedMinor: Long,
    val creditedMinor: Long, val dueMinor: Long, val currencyCode: String
)

internal data class CustomerPaymentRequest(
    val id: String, val organizationId: String, val organizationName: String,
    val saleId: String, val invoiceNumber: String, val amountMinor: Long,
    val currencyCode: String, val customerNote: String, val status: String,
    val reviewNote: String, val paymentId: String, val receiptNumber: String,
    val version: Int, val createdAt: String
)

internal data class MerchantCustomerPaymentRequest(
    val id: String, val customerId: String, val customerName: String,
    val saleId: String, val invoiceNumber: String, val amountMinor: Long, val dueMinor: Long,
    val currencyCode: String, val customerNote: String, val status: String,
    val reviewNote: String, val version: Int, val createdAt: String
)

internal fun loadCustomerPortalOutstandingSales(
    context: Context, organizationId: String
): List<CustomerPortalOutstandingSale> {
    require(organizationId.isNotBlank()) { "اختر المؤسسة أولًا." }
    val access = customerPortalAccessToken(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_outstanding_sales",
        "POST", JSONObject().put("p_organization_id", organizationId), access
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("sale_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code")
            val due = row.optLong("due_minor", -1L)
            if (id.isBlank() || !currency.matches(Regex("^[A-Z]{3}$")) || due <= 0L) continue
            add(CustomerPortalOutstandingSale(
                id, row.optString("invoice_number"), row.optString("created_at"),
                row.optLong("total_minor"), row.optLong("paid_minor"),
                row.optLong("refunded_minor"), row.optLong("credited_minor"),
                due, currency
            ))
        }
    }
}

internal fun loadCustomerPortalPaymentRequests(
    context: Context, organizationId: String
): List<CustomerPaymentRequest> {
    require(organizationId.isNotBlank()) { "اختر المؤسسة أولًا." }
    val access = customerPortalAccessToken(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_my_payment_requests",
        "POST", JSONObject().put("p_organization_id", organizationId), access
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            add(CustomerPaymentRequest(
                id, row.optString("organization_id"), row.optString("organization_name"),
                row.optString("sale_id"), row.optString("invoice_number"),
                row.optLong("amount_minor"), row.optString("currency_code"),
                row.optString("customer_note"), row.optString("status"),
                row.optString("review_note"), row.optString("payment_id"),
                row.optString("receipt_number"), row.optInt("version", 1),
                row.optString("created_at")
            ))
        }
    }
}

private fun customerPaymentRequestKeyPrefs(context: Context) =
    context.getSharedPreferences("commercial_customer_payment_request_keys_v1", Context.MODE_PRIVATE)

private fun getCustomerPaymentRequestActionKey(context: Context, slot: String, fingerprint: String): String {
    val prefs = customerPaymentRequestKeyPrefs(context)
    val oldKey = prefs.getString("$slot:key", null).orEmpty()
    if (oldKey.isNotBlank()) {
        require(prefs.getString("$slot:fingerprint", null) == fingerprint) {
            "هناك طلب سابق لم يتأكد حفظه. أعد المحاولة بنفس البيانات قبل إنشاء طلب آخر."
        }
        return oldKey
    }
    val key = UUID.randomUUID().toString()
    check(prefs.edit().putString("$slot:fingerprint", fingerprint).putString("$slot:key", key).commit()) {
        "تعذر حفظ مفتاح الطلب قبل الإرسال."
    }
    return key
}

private fun clearCustomerPaymentRequestActionKey(context: Context, slot: String) {
    check(customerPaymentRequestKeyPrefs(context).edit()
        .remove("$slot:fingerprint").remove("$slot:key").commit()) {
        "تم تأكيد الطلب على الخادم لكن تعذر تنظيف مفتاح إعادة المحاولة."
    }
}

internal fun submitCustomerPortalPaymentRequest(
    context: Context, saleId: String, amountMinor: Long, customerNote: String
): JSONObject {
    require(saleId.isNotBlank() && amountMinor > 0L && customerNote.length <= 1000)
    val fingerprint = listOf(saleId, amountMinor.toString(), customerNote).joinToString("|")
    val slot = "create:$saleId"
    val key = getCustomerPaymentRequestActionKey(context, slot, fingerprint)
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_submit_payment_request", "POST",
        JSONObject().put("p_sale_id", saleId).put("p_amount_minor", amountMinor)
            .put("p_customer_note", customerNote).put("p_idempotency_key", key),
        customerPortalAccessToken(context)
    ))
    require(result.optString("request_id").isNotBlank()) { "لم يُرجع الخادم مرجع طلب السند." }
    clearCustomerPaymentRequestActionKey(context, slot)
    return result
}

internal fun updateCustomerPortalPaymentRequest(
    context: Context, requestId: String, amountMinor: Long, customerNote: String
): JSONObject {
    require(requestId.isNotBlank() && amountMinor > 0L && customerNote.length <= 1000)
    val fingerprint = listOf(requestId, amountMinor.toString(), customerNote).joinToString("|")
    val slot = "update:$requestId"
    val key = getCustomerPaymentRequestActionKey(context, slot, fingerprint)
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_update_payment_request", "POST",
        JSONObject().put("p_request_id", requestId).put("p_amount_minor", amountMinor)
            .put("p_customer_note", customerNote).put("p_idempotency_key", key),
        customerPortalAccessToken(context)
    ))
    require(result.optString("request_id").isNotBlank()) { "لم يُرجع الخادم مرجع تعديل الطلب." }
    clearCustomerPaymentRequestActionKey(context, slot)
    return result
}

internal fun loadCommercialCloudCustomerPaymentRequests(
    context: Context, status: String = "pending"
): List<MerchantCustomerPaymentRequest> {
    require(status in setOf("pending", "change_requested", "rejected", "approved", "all"))
    val (organizationId, access) = commercialMasterDataAccess(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_merchant_list_customer_payment_requests", "POST",
        JSONObject().put("p_organization_id", organizationId).put("p_status", status).put("p_limit", 100),
        access
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            add(MerchantCustomerPaymentRequest(
                id, row.optString("customer_id"), row.optString("customer_name"),
                row.optString("sale_id"), row.optString("invoice_number"),
                row.optLong("amount_minor"), row.optLong("due_minor", -1L), row.optString("currency_code"),
                row.optString("customer_note"), row.optString("status"),
                row.optString("review_note"), row.optInt("version", 1),
                row.optString("created_at")
            ))
        }
    }
}

internal fun reviewCommercialCloudCustomerPaymentRequest(
    context: Context,
    requestId: String,
    decision: String,
    confirmedAmountMinor: Long,
    reviewNote: String,
    paymentMethod: String = "cash"
): JSONObject {
    require(requestId.isNotBlank())
    require(decision in setOf("approve", "reject", "request_changes"))
    require(paymentMethod in setOf("cash", "bank_transfer", "card", "other"))
    require(reviewNote.length <= 1000)
    val fingerprint = listOf(requestId, decision, confirmedAmountMinor.toString(), reviewNote, paymentMethod).joinToString("|")
    val slot = "review:$requestId:$decision"
    val key = getCustomerPaymentRequestActionKey(context, slot, fingerprint)
    val (_, access) = commercialMasterDataAccess(context)
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_merchant_review_customer_payment_request", "POST",
        JSONObject().put("p_request_id", requestId).put("p_decision", decision)
            .put("p_confirmed_amount_minor", if (decision == "approve") confirmedAmountMinor else JSONObject.NULL)
            .put("p_review_note", reviewNote).put("p_payment_method_type", paymentMethod)
            .put("p_idempotency_key", key),
        access
    ))
    require(result.optString("request_id").isNotBlank()) { "لم يؤكد الخادم مراجعة طلب السند." }
    clearCustomerPaymentRequestActionKey(context, slot)
    return result
}
