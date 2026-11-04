package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class CommercialCloudSupplierPaymentMovement(
    val id: String,
    val obligationId: String,
    val supplierInvoiceId: String,
    val supplierId: String,
    val supplierName: String,
    val invoiceNumber: String,
    val amountMinor: Long,
    val currencyCode: String,
    val paymentMethod: String,
    val evidenceReference: String,
    val journalEntryId: String,
    val recordedAt: String
)

/** Posted supplier disbursements only; the supplier-payment row is the source of truth, not its journal copy. */
internal fun loadCommercialCloudSupplierPaymentMovements(context: Context): List<CommercialCloudSupplierPaymentMovement> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_supplier_payments" +
        "?select=id,obligation_id,supplier_invoice_id,amount_minor,currency_code,payment_method,evidence_reference,journal_entry_id,recorded_at,obligation:commercial_supplier_obligations(supplier_id,supplier:commercial_suppliers(display_name)),supplier_invoice:commercial_supplier_invoices(invoice_number),journal:commercial_journal_entries!inner(status)" +
        "&organization_id=eq.$orgId&journal.status=eq.posted&order=recorded_at.asc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val journal = row.optJSONObject("journal") ?: continue
            if (!journal.optString("status").equals("posted", true)) continue
            val obligation = row.optJSONObject("obligation") ?: continue
            val supplier = obligation.optJSONObject("supplier")
            val invoice = row.optJSONObject("supplier_invoice")
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val obligationId = row.optString("obligation_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val invoiceId = row.optString("supplier_invoice_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val supplierId = obligation.optString("supplier_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val journalId = row.optString("journal_entry_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val amount = row.optLong("amount_minor", 0L)
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank() || obligationId.isBlank() || invoiceId.isBlank() || supplierId.isBlank() || journalId.isBlank() || amount <= 0L || !currency.matches(Regex("^[A-Z]{3}$"))) continue
            add(CommercialCloudSupplierPaymentMovement(
                id = id, obligationId = obligationId, supplierInvoiceId = invoiceId, supplierId = supplierId,
                supplierName = supplier?.optString("display_name").takeUnless { it == null || it == "null" }.orEmpty().ifBlank { "مورد غير محدد" },
                invoiceNumber = invoice?.optString("invoice_number").takeUnless { it == null || it == "null" }.orEmpty(),
                amountMinor = amount, currencyCode = currency, paymentMethod = row.optString("payment_method"),
                evidenceReference = row.optString("evidence_reference"), journalEntryId = journalId,
                recordedAt = row.optString("recorded_at").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
}
internal fun recordCommercialCloudSupplierPayment(
    context: Context, obligationId: String, amountMinor: Long, currencyCode: String,
    paymentMethod: String, evidenceReference: String, idempotencyKey: String
): JSONObject {
    require(obligationId.isNotBlank() && amountMinor > 0L && evidenceReference.trim().length in 3..200) {
        "بيانات دفعة المورد غير صالحة."
    }
    require(paymentMethod in setOf("cash", "bank_transfer", "check", "other") && idempotencyKey.length in 8..200) {
        "طريقة الدفع أو مفتاح العملية غير صالح."
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_record_supplier_payment", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_obligation_id", obligationId)
            .put("p_amount_minor", amountMinor).put("p_currency_code", currencyCode)
            .put("p_payment_method", paymentMethod).put("p_evidence_reference", evidenceReference.trim())
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}
