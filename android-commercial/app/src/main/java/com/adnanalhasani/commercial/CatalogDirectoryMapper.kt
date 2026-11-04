package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Parsed catalog master data; contains no network, Compose, or screen state. */
internal data class CatalogDirectoryData(
    val localUnitCodes: Set<String>,
    val activityNodes: List<CatalogActivityNode>,
    val categories: List<CatalogCategory>,
    val units: List<DirectoryUnit>
)

/** Converts the combined directory response assembled by CatalogScreen into domain rows. */
internal fun parseCatalogDirectory(raw: String): CatalogDirectoryData {
    val result = JSONObject(raw)
    val cats = result.getJSONArray("categories")
    val units = result.getJSONArray("units")
    val localUnits = result.optJSONArray("localUnits") ?: JSONArray()
    val categoryTranslations = result.getJSONArray("categoryTranslations")
    val categoryDescriptionTranslations = result.getJSONArray("categoryDescriptionTranslations")
    val unitTranslations = result.getJSONArray("unitTranslations")
    val activityJson = result.optJSONArray("activityDirectory") ?: JSONArray()

    val localUnitCodes = (0 until localUnits.length())
        .map { localUnits.getJSONObject(it).optString("code").lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

    val activityNodes = List(activityJson.length()) { index ->
        val node = activityJson.getJSONObject(index)
        CatalogActivityNode(
            node.optString("code"),
            node.optString("parent_code").takeUnless { it == "null" }.orEmpty(),
            node.optString("node_kind"),
            node.optString("path_ar", node.optString("name_ar")),
            node.optString("path_en", node.optString("name_en")),
            node.optString("default_business_level_code").takeUnless { it == "null" }.orEmpty(),
            node.optInt("depth", 0),
            node.optBoolean("is_selectable", false)
        )
    }.filter { it.code.isNotBlank() }

    val categoryNames = buildMap {
        for (index in 0 until categoryTranslations.length()) {
            val row = categoryTranslations.getJSONObject(index)
            put(row.optString("entity_key"), row.optString("translated_value"))
        }
    }
    val categoryDescriptions = buildMap {
        for (index in 0 until categoryDescriptionTranslations.length()) {
            val row = categoryDescriptionTranslations.getJSONObject(index)
            put(row.optString("entity_key"), row.optString("translated_value"))
        }
    }
    val unitNames = buildMap {
        for (index in 0 until unitTranslations.length()) {
            val row = unitTranslations.getJSONObject(index)
            put(row.optString("entity_key"), row.optString("translated_value"))
        }
    }

    val categories = List(cats.length()) { index ->
        val row = cats.getJSONObject(index)
        val code = row.optString("code")
        CatalogCategory(
            code,
            row.optString("parent_code").takeUnless { it == "null" }.orEmpty(),
            row.optString("name_ar"),
            row.optString("name_en"),
            categoryNames[code].orEmpty(),
            row.optString("description_ar").takeUnless { it == "null" }.orEmpty(),
            row.optString("description_en").takeUnless { it == "null" }.orEmpty(),
            categoryDescriptions[code].orEmpty()
        )
    }
    val directoryUnits = List(units.length()) { index ->
        val row = units.getJSONObject(index)
        val code = row.optString("code")
        DirectoryUnit(
            code,
            row.optString("name_ar"),
            row.optString("name_en"),
            row.optString("dimension"),
            row.optString("base_unit_code").takeUnless { it == "null" }.orEmpty(),
            row.optString("factor_to_base").takeUnless { it == "null" }.orEmpty(),
            row.optString("owner_organization_id").takeUnless { it == "null" }.orEmpty(),
            unitNames[code].orEmpty()
        )
    }

    return CatalogDirectoryData(localUnitCodes, activityNodes, categories, directoryUnits)
}
