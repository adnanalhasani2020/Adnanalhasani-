package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Loads shared directories and creates organization-owned directory entries. */
internal class CatalogDirectoryWorkflow(
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val locale: () -> String,
    private val initialActivityCode: () -> String,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onLocalUnitCodes: (Set<String>) -> Unit,
    private val onActivityNodes: (List<CatalogActivityNode>) -> Unit,
    private val onCategories: (List<CatalogCategory>) -> Unit,
    private val onUnits: (List<DirectoryUnit>) -> Unit,
    private val onSector: (String) -> Unit,
    private val onBusinessLevel: (String) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onDirectoriesLoaded: () -> Unit,
    private val onReloadDirectories: () -> Unit
) {
    fun loadDirectories() {
        val active=accessToken() ?: return
        request({
            val catRaw=catalogGet("/rest/v1/commercial_global_catalog_categories?select=code,parent_code,name_ar,name_en,description_ar,description_en&is_active=eq.true&order=parent_code.asc,sort_order.asc,code.asc",active)
            val unitRaw=catalogGet("/rest/v1/commercial_global_units?select=code,name_ar,name_en,dimension,base_unit_code,factor_to_base,owner_organization_id&is_active=eq.true&order=dimension.asc,code.asc",active)
            val localUnitRaw=catalogGet("/rest/v1/commercial_unit_of_measure?select=code&is_active=eq.true&order=code.asc",active)
            val categoryTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.name&locale_code=eq.${locale().trim().ifBlank { "ar" }}",active)
            val categoryDescriptionTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.description&locale_code=eq.${locale().trim().ifBlank { "ar" }}",active)
            val unitTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.unit&field_key=eq.name&locale_code=eq.${locale().trim().ifBlank { "ar" }}",active)
            val activityDirectoryRaw=catalogHttp("/rest/v1/rpc/commercial_list_business_activity_nodes",JSONObject(),active)
            val activityDirectory=JSONObject(activityDirectoryRaw).optJSONArray("nodes") ?: JSONArray()
            JSONObject().put("categories",JSONArray(catRaw)).put("units",JSONArray(unitRaw)).put("localUnits",JSONArray(localUnitRaw))
                .put("categoryTranslations",JSONArray(categoryTranslationRaw)).put("categoryDescriptionTranslations",JSONArray(categoryDescriptionTranslationRaw)).put("unitTranslations",JSONArray(unitTranslationRaw)).put("activityDirectory",activityDirectory).toString()
        }, { raw ->
            val directory = parseCatalogDirectory(raw)
            onLocalUnitCodes(directory.localUnitCodes)
            onActivityNodes(directory.activityNodes)
            onCategories(directory.categories)
            onUnits(directory.units)
            if (initialActivityCode().isNotBlank()) {
                val inferredSector = catalogSectorForNode(initialActivityCode().uppercase(), directory.activityNodes)
                if (inferredSector.isNotBlank()) onSector(inferredSector)
                directory.activityNodes.firstOrNull { it.code.equals(initialActivityCode, true) }?.defaultBusinessLevelCode?.takeIf { it.isNotBlank() }?.let { onBusinessLevel(it) }
            }
            onFeedback("تم تحميل ${directory.categories.size} تصنيفًا و${directory.units.size} وحدة قياس و${directory.activityNodes.count { it.nodeKind.equals("sector", true) }} قطاعًا و${directory.activityNodes.count { it.nodeKind.equals("activity", true) }} نشاطًا و${directory.activityNodes.count { it.nodeKind.equals("specialization", true) }} تخصصًا.")
            onDirectoriesLoaded()
        }, null)
    }
    fun createCategory(categoryCode: String, parentCode: String, nameAr: String, nameEn: String, descriptionAr: String, descriptionEn: String, translations: String) {
        val active=accessToken() ?: return; val orgId=organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة UUID أولًا."); return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_category",JSONObject()
                .put("p_organization_id",orgId).put("p_code",categoryCode).put("p_parent_code",parentCode.ifBlank { JSONObject.NULL })
                .put("p_name_ar",nameAr).put("p_name_en",nameEn)
                .put("p_description_ar",descriptionAr.ifBlank { JSONObject.NULL })
                .put("p_description_en",descriptionEn.ifBlank { JSONObject.NULL }).put("p_sort_order",100)
                .put("p_translations",JSONArray().also { rows -> translations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { onFeedback("تم إنشاء التصنيف: $it"); onReloadDirectories() }, null)
    }
    fun createUnit(code: String, nameAr: String, nameEn: String, dimension: String, factor: String, baseCode: String, decimals: String, translations: String) {
        val active=accessToken() ?: return; val orgId=organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة UUID أولًا."); return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_unit",JSONObject()
                .put("p_organization_id",orgId).put("p_code",code).put("p_name_ar",nameAr).put("p_name_en",nameEn)
                .put("p_dimension",dimension).put("p_factor_to_base",factor.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل التحويل غير صحيح."))
                .put("p_base_unit_code",baseCode).put("p_decimal_places",decimals.toIntOrNull() ?: 0)
                .put("p_translations",JSONArray().also { rows -> translations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { onFeedback("تم إنشاء وحدة الحساب الخاصة بالمؤسسة: $it"); onReloadDirectories() }, null)
    }
}
