package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

internal data class CatalogMarketContext(
    val branches: List<CatalogBranchOption>,
    val warehouses: List<CatalogWarehouseOption>
)

/** Converts the two market-context REST responses into screen-ready options. */
internal fun parseCatalogMarketContext(raw: String): CatalogMarketContext {
    val result = JSONObject(raw)
    val branches = result.getJSONArray("branches")
    val warehouses = result.getJSONArray("warehouses")
    return CatalogMarketContext(
        branches = List(branches.length()) { index ->
            val row = branches.getJSONObject(index)
            CatalogBranchOption(
                id = row.optString("id"),
                name = row.optString("name"),
                latitude = row.optString("latitude").takeUnless { it == "null" }.orEmpty(),
                longitude = row.optString("longitude").takeUnless { it == "null" }.orEmpty()
            )
        },
        warehouses = List(warehouses.length()) { index ->
            val row = warehouses.getJSONObject(index)
            CatalogWarehouseOption(
                id = row.optString("id"),
                name = row.optString("name"),
                branchId = row.optString("branch_location_id")
            )
        }
    )
}

/** Maps aggregated nearby-market rows without exposing merchant identities. */
internal fun parseNearbyCatalogItems(raw: String): List<NearbyCatalogItem> {
    val rows = JSONArray(raw)
    return List(rows.length()) { index ->
        val row = rows.getJSONObject(index)
        NearbyCatalogItem(
            id = row.optString("global_item_id"),
            name = row.optString("product_name"),
            popularityBand = row.optString("popularity_band"),
            merchantCountBand = row.optString("merchant_count_band")
        )
    }
}

/** Formats aggregated price guidance exactly as displayed by the catalog screen. */
internal fun parseCatalogMarketPriceHints(raw: String): Map<String, String> {
    val rows = JSONArray(raw)
    val hints = linkedMapOf<String, String>()
    for (index in 0 until rows.length()) {
        val row = rows.getJSONObject(index)
        hints[row.optString("global_item_id")] =
            row.optString("currency_code") + " • وسيط " + row.optLong("suggested_median_minor") +
                " • نطاق ربعي " + row.optLong("lower_quartile_minor") + "–" + row.optLong("upper_quartile_minor") +
                " • تجار " + row.optString("merchant_count_band")
    }
    return hints
}
