package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

internal data class CatalogInvoiceMappingData(
    val locationId: String,
    val warehouses: List<CatalogWarehouseOption>,
    val lines: List<InvoicePurchaseUnmappedLine>,
    val offerings: List<InvoicePurchaseOffering>
)

/** Parses the prepared invoice-mapping payload; network orchestration remains in CatalogScreen. */
internal fun parseCatalogInvoiceMapping(raw: String): CatalogInvoiceMappingData {
    val result = JSONObject(raw)
    val warehouses = result.optJSONArray("warehouses") ?: JSONArray()
    val lines = result.getJSONArray("lines")
    val offerings = result.getJSONArray("offerings")
    return CatalogInvoiceMappingData(
        locationId = result.optString("location_id"),
        warehouses = List(warehouses.length()) { index ->
            val row = warehouses.getJSONObject(index)
            CatalogWarehouseOption(row.optString("id"), row.optString("name"), row.optString("branch_location_id"))
        },
        lines = List(lines.length()) { index ->
            val row = lines.getJSONObject(index)
            InvoicePurchaseUnmappedLine(
                row.optString("id"),
                row.optInt("line_number"),
                row.optString("description"),
                row.optString("quantity"),
                row.optString("unit_code")
            )
        },
        offerings = List(offerings.length()) { index ->
            val row = offerings.getJSONObject(index)
            InvoicePurchaseOffering(
                row.optString("id"),
                row.optString("global_item_id"),
                row.optString("name"),
                row.optString("unit_code")
            )
        }
    )
}
