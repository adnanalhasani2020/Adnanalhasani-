package com.adnanalhasani.commercial

import org.json.JSONArray

internal data class SettlementRecord(val invoiceNumber: Int, val customer: String, val amount: Int, val timestamp: Long = System.currentTimeMillis())

internal data class ExpenseRecord(val id: Long, val category: String, val amount: Int, val note: String, val timestamp: Long = System.currentTimeMillis())

internal data class LocalSaleUnit(
    val code: String,
    val role: String,
    val label: String,
    val quantityInBase: Double,
    val barcode: String,
    val imageUrl: String,
    val isDefault: Boolean
)

internal fun productMatchesUnitQuery(product: Product, query: String): Boolean {
    if (query.isBlank()) return false
    return runCatching {
        val units = JSONArray(product.unitVariantsJson)
        (0 until units.length()).any { index ->
            val unit = units.getJSONObject(index)
            listOf("barcode","unitCode","sku").any { key -> unit.optString(key).contains(query,true) }
        }
    }.getOrDefault(false)
}

internal fun localSaleUnits(product: Product): List<LocalSaleUnit> {
    val parsed = runCatching {
        val json = JSONArray(product.unitVariantsJson)
        List(json.length()) { index ->
            val item = json.getJSONObject(index)
            LocalSaleUnit(
                code = item.optString("unitCode", product.unitCode).ifBlank { product.unitCode },
                role = item.optString("role", "base").ifBlank { "base" },
                label = item.optString("packageName").takeUnless { it.isNullOrBlank() || it == "null" }
                    ?: item.optString("unitName").takeUnless { it.isNullOrBlank() || it == "null" }
                    ?: item.optString("unitCode", product.unitCode).ifBlank { product.unitCode },
                quantityInBase = item.optString("quantityInBase", "1").toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                barcode = item.optString("barcode").takeUnless { it == "null" }.orEmpty(),
                imageUrl = item.optString("imageUrl").takeUnless { it == "null" }.orEmpty(),
                isDefault = item.optBoolean("isDefault", false)
            )
        }.filter { it.code.isNotBlank() && it.role in setOf("base", "sale") }
    }.getOrDefault(emptyList())
    return parsed.ifEmpty {
        listOf(LocalSaleUnit(product.unitCode.ifBlank { "EA" }, "base", product.unitCode.ifBlank { "EA" }, 1.0, product.barcode, product.imageUrl, true))
    }
}
