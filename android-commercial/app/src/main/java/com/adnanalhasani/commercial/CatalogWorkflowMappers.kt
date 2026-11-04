package com.adnanalhasani.commercial

import org.json.JSONArray

/** Pure response mapping for catalog-adjacent invoice and transfer workflows. */
internal fun parseInvoicePurchaseRequests(raw: String): List<InvoicePurchaseRequestRow> {
    val rows = JSONArray(raw)
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        InvoicePurchaseRequestRow(
            row.optString("id"),
            row.optString("source_organization_id"),
            row.optString("destination_organization_id"),
            row.optString("source_invoice_number"),
            row.optString("currency_code"),
            row.optLong("total_minor"),
            row.optString("status"),
            row.optString("destination_purchase_order_id").takeUnless { it == "null" }.orEmpty(),
            row.optString("created_at")
        )
    }
}

internal fun parsePendingCatalogTransfers(raw: String): List<PendingTransfer> {
    val rows = JSONArray(raw)
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        PendingTransfer(
            row.optString("id"),
            row.optString("source_organization_id"),
            row.optString("destination_organization_id"),
            row.optInt("item_count"),
            row.optString("created_at")
        )
    }
}
