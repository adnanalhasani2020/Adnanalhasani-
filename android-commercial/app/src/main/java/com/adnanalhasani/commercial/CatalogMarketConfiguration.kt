package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Builds the exact configuration payload for selected catalog items.
 *
 * Unit rows are fetched from the authoritative catalog endpoint by the caller.
 * This function validates the base and sale-unit contract before any RPC write.
 */
internal fun buildCatalogConfigurationItems(selectedIds: List<String>, unitRows: JSONArray): JSONArray {
    val grouped = mutableMapOf<String, MutableList<JSONObject>>()
    for (index in 0 until unitRows.length()) {
        val row = unitRows.getJSONObject(index)
        grouped.getOrPut(row.optString("product_id")) { mutableListOf() }.add(row)
    }

    val payloadItems = JSONArray()
    for (id in selectedIds) {
        val units = grouped[id].orEmpty()
        val baseCandidates = units.filter {
            it.optString("unit_role").equals("base", true) &&
                it.optString("quantity_in_base").toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) == 0
        }
        if (baseCandidates.size != 1) {
            throw IllegalStateException("الصنف $id لا يملك وحدة أساس واحدة موثوقة في الدليل؛ راجع بيانات وحداته قبل التهيئة.")
        }

        // The catalog authoring contract marks the base unit as the product's sole default.
        // Therefore, only a sale-role default can be preferred as the sale unit.
        val saleCandidates = units.filter { it.optString("unit_role").equals("sale", true) }
        val defaultSaleCandidates = saleCandidates.filter { it.optBoolean("is_default", false) }
        if (defaultSaleCandidates.size > 1 || (defaultSaleCandidates.isEmpty() && saleCandidates.size > 1)) {
            throw IllegalStateException("وحدات الصنف $id تحتوي أكثر من وحدة بيع افتراضية أو مرشحة؛ يلزم تصحيح الدليل قبل التهيئة.")
        }

        val base = baseCandidates.single()
        val sale = defaultSaleCandidates.singleOrNull() ?: saleCandidates.singleOrNull() ?: base
        val saleFactor = sale.optString("quantity_in_base").toBigDecimalOrNull()
        if (saleFactor == null || saleFactor <= BigDecimal.ZERO || saleFactor.stripTrailingZeros().scale() > 0 || sale.optString("unit_code").isBlank()) {
            throw IllegalStateException("معامل تحويل وحدة البيع للصنف $id غير صالح؛ لم يتم اختلاق معامل بديل.")
        }

        payloadItems.put(
            JSONObject()
                .put("global_item_id", id)
                .put("base_unit_code", base.optString("unit_code"))
                .put("sale_unit_code", sale.optString("unit_code"))
        )
    }
    return payloadItems
}
