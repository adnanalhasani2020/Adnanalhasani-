package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class CommercialCloudReceivable(
    val saleId: String,
    val receiptNumber: String,
    val customerId: String,
    val customerName: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val refundedMinor: Long,
    val creditedMinor: Long,
    val currencyCode: String,
    val createdAt: String
) {
    // A completed approved return reduces the sale value; an executed refund reduces
    // net cash retained. Keep both adjustments distinct to avoid double counting.
    val netPaidMinor: Long get() = (paidMinor - refundedMinor).coerceAtLeast(0L)
    val dueMinor: Long get() = (totalMinor - netPaidMinor - creditedMinor).coerceAtLeast(0L)
}

internal fun loadCommercialCloudReceivables(context: Context): List<CommercialCloudReceivable> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales" +
        "?select=id,receipt_number,customer_id,total_minor,currency_code,created_at,payment_status,customer:commercial_customers(display_name),payments:commercial_payments(amount_minor,status),returns:commercial_sales_returns(id,status,refund_status,approved_refund_minor)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&status=eq.completed&customer_id=not.is.null&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val customer = row.optJSONObject("customer")
            val customerId = row.optString("customer_id").takeUnless { it == "null" }.orEmpty()
            val customerName = customer?.optString("display_name").orEmpty()
            if (customerId.isBlank() || customerName.isBlank()) continue
            val payments = row.optJSONArray("payments")
            var paid = 0L
            for (j in 0 until (payments?.length() ?: 0)) {
                val payment = payments?.optJSONObject(j) ?: continue
                if (payment.optString("status") in setOf("succeeded", "partially_refunded", "refunded")) {
                    paid = Math.addExact(paid, payment.optLong("amount_minor", 0L).coerceAtLeast(0L))
                }
            }
            val returns = row.optJSONArray("returns")
            var credited = 0L
            var refunded = 0L
            for (j in 0 until (returns?.length() ?: 0)) {
                val saleReturn = returns?.optJSONObject(j) ?: continue
                val approvedRefund = saleReturn.optLong("approved_refund_minor", 0L).coerceAtLeast(0L)
                if (saleReturn.optString("status") == "completed") {
                    credited = Math.addExact(credited, approvedRefund)
                }
                // The refund RPC inserts the internal refund posting and changes this
                // status in the same transaction. Its amount equals approved_refund_minor.
                // Read the status from the client-visible return row instead of embedding
                // commercial_return_refund_postings, whose table access is intentionally denied.
                if (saleReturn.optString("refund_status") == "refunded") {
                    refunded = Math.addExact(refunded, approvedRefund)
                }
            }
            val netPaid = (paid - refunded).coerceAtLeast(0L)
            val total = row.optLong("total_minor", 0L).coerceAtLeast(0L)
            val due = (total - netPaid - credited).coerceAtLeast(0L)
            if (due > 0L) add(CommercialCloudReceivable(
                saleId = row.optString("id"),
                receiptNumber = row.optString("receipt_number"),
                customerId = customerId,
                customerName = customerName,
                totalMinor = total,
                paidMinor = paid,
                refundedMinor = refunded,
                creditedMinor = credited,
                currencyCode = row.optString("currency_code"),
                createdAt = row.optString("created_at")
            ))
        }
    }
}

internal fun recordCommercialCloudCustomerPayment(
    context: Context,
    saleId: String,
    amountMinor: Long,
    method: String
): JSONObject {
    require(saleId.isNotBlank() && amountMinor > 0L)
    require(method in setOf("cash", "bank_transfer", "card", "other"))
    val (orgId, access) = commercialMasterDataAccess(context)
    val fingerprint = listOf(orgId, saleId, amountMinor.toString(), method).joinToString("|")
    val prefs = context.getSharedPreferences("commercial_cloud_pending_collection_v1", Context.MODE_PRIVATE)
    val storedKey = prefs.getString("idempotency_key", null).orEmpty()
    if (storedKey.isNotBlank()) {
        require(prefs.getString("fingerprint", null) == fingerprint) {
            "يوجد تحصيل معلّق لم يُحسم بعد. أعد المحاولة بنفس بيانات التحصيل."
        }
    }
    val idempotencyKey = storedKey.ifBlank { UUID.randomUUID().toString() }
    if (storedKey.isBlank()) {
        check(prefs.edit().clear().putString("fingerprint", fingerprint)
            .putString("idempotency_key", idempotencyKey).commit()) {
            "تعذر حفظ مفتاح التحصيل قبل الإرسال."
        }
    }
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_record_customer_payment", "POST",
        JSONObject().put("p_sale_id", saleId)
            .put("p_amount_minor", amountMinor)
            .put("p_payment_method_type", method)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
    check(prefs.edit().clear().commit()) { "تم تأكيد التحصيل لكن تعذر تنظيف مفتاح إعادة المحاولة." }
    return result
}

/** Fetch the server-issued numbered receipt for a confirmed customer payment. */
internal fun loadCommercialCloudCustomerReceipt(context: Context, paymentId: String): JSONObject {
    require(paymentId.isNotBlank()) { "مرجع الدفعة غير صالح." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_payments" +
        "?select=id,receipt_number,amount_minor,currency_code,payment_method_type,created_at,sale:commercial_sales(receipt_number,customer:commercial_customers(display_name))" +
        "&organization_id=eq.$orgId&id=eq.$paymentId&limit=1"
    val rows = JSONArray(SupabaseClient.request(path, "GET", null, access))
    require(rows.length() == 1) { "لم يُعثر على سند القبض المؤكد لهذه الدفعة." }
    return rows.getJSONObject(0)
}
