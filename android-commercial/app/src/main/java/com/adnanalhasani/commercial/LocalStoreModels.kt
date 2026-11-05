package com.adnanalhasani.commercial

import org.json.JSONArray

internal data class SettlementRecord(val invoiceNumber: Int, val customer: String, val amount: Int, val timestamp: Long = System.currentTimeMillis())

internal data class ExpenseRecord(val id: Long, val category: String, val amount: Int, val note: String, val timestamp: Long = System.currentTimeMillis())

internal data class LocalSaleUnit(
    val code: String,
    val role: String,
    val label: String,
    val quantityInBase: Long,
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
    val baseUnit = LocalSaleUnit(
        product.unitCode.ifBlank { "EA" },
        "base",
        product.unitCode.ifBlank { "EA" },
        1L,
        "",
        "",
        true
    )
    if (product.unitVariantsJson.isBlank()) return listOf(baseUnit)
    val json = runCatching { JSONArray(product.unitVariantsJson) }
        .getOrElse { return listOf(baseUnit.copy(quantityInBase = 0L)) }
    if (json.length() == 0) return listOf(baseUnit)
    val parsed = runCatching {
        buildList {
            for (index in 0 until json.length()) {
                val item = json.getJSONObject(index)
                val code = item.optString("unitCode", product.unitCode).ifBlank { product.unitCode }
                val role = item.optString("role", "base").ifBlank { "base" }
                if (code.isBlank() || role !in setOf("base", "sale")) continue
                val factor = item.optString("quantityInBase", "1").toBigDecimalOrNull()?.let {
                    runCatching { it.toBigIntegerExact().longValueExact() }.getOrNull()
                }?.takeIf { it > 0L } ?: 0L
                add(LocalSaleUnit(
                    code = code,
                    role = role,
                    label = item.optString("packageName").takeUnless { it.isNullOrBlank() || it == "null" }
                        ?: item.optString("unitName").takeUnless { it.isNullOrBlank() || it == "null" }
                        ?: code,
                    quantityInBase = factor,
                    barcode = item.optString("barcode").takeUnless { it == "null" }.orEmpty(),
                    imageUrl = item.optString("imageUrl").takeUnless { it == "null" }.orEmpty(),
                    isDefault = item.optBoolean("isDefault", false)
                ))
            }
        }.distinctBy { it.code }
    }.getOrElse { return listOf(baseUnit.copy(quantityInBase = 0L)) }
    return if (parsed.any { it.role == "base" }) parsed else parsed + baseUnit
}
