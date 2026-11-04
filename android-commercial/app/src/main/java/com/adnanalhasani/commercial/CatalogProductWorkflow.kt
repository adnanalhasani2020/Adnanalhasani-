package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Owns the global product creation RPC while the form state remains screen-scoped. */
internal class CatalogProductWorkflow(
    private val state: CatalogProductFormState,
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val onCreated: (String) -> Unit
) {
    fun createProduct() {
        val active=accessToken() ?: return; val orgId=organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة UUID أولًا."); return }
        if(state.nameAr.isBlank() || state.nameEn.isBlank() || state.productCategory.isBlank() || state.unitCode.isBlank()) { onFeedback("أكمل الاسم العربي والإنجليزي والتصنيف ووحدة القياس."); return }
        request({
            val product=JSONObject().put("name_ar",state.nameAr.trim()).put("name_en",state.nameEn.trim())
                .put("description_ar",state.descriptionAr.ifBlank { JSONObject.NULL }).put("description_en",state.descriptionEn.ifBlank { JSONObject.NULL })
                .put("translations",JSONArray().also { translationRows -> state.extraTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) translationRows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } })
                .put("category_code",state.productCategory.trim()).put("item_type",state.itemType).put("unit_code",state.unitCode.trim())
                .put("barcode_gtin",state.barcode.ifBlank { JSONObject.NULL }).put("brand",state.brand.ifBlank { JSONObject.NULL })
                .put("manufacturer_name",state.manufacturer.ifBlank { JSONObject.NULL }).put("country_of_manufacture",state.origin.ifBlank { JSONObject.NULL })
                .put("model_number",state.model.ifBlank { JSONObject.NULL }).put("part_number",state.partNumber.ifBlank { JSONObject.NULL })
                .put("image_url",state.imageUrl.ifBlank { JSONObject.NULL }).put("image_alt",state.imageAlt.ifBlank { JSONObject.NULL })
                .put("ingredients",JSONArray(state.ingredients.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("material_composition",JSONArray(state.materialComposition.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("recipe",JSONObject().put("instructions",state.recipe.ifBlank { JSONObject.NULL })
                    .put("recipe_name",state.nameAr.trim()).put("locale_code","ar")
                    .put("ingredients",JSONArray().also { rows -> state.recipeIngredientsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                        val p=line.split("|",limit=4)
                        if(p.size<3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw IllegalArgumentException("صيغة مكونات الوصفة: name|quantity|unit_code|note")
                        rows.put(JSONObject().put("ingredient_name",p[0].trim()).put("quantity",p[1].trim().toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("كمية المكون غير صحيحة."))
                            .put("unit_code",p[2].trim().uppercase()).put("preparation_note",p.getOrNull(3)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
                    }})
                    .put("yield_quantity",state.recipeYieldQty.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL)
                    .put("yield_unit_code",state.recipeYieldUnit.ifBlank { JSONObject.NULL })
                    .put("preparation_minutes",state.recipePrepMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("cooking_minutes",state.recipeCookMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("source_reference",state.recipeSource.ifBlank { JSONObject.NULL })
                    .put("allergen_notes",state.recipeAllergenNotes.ifBlank { JSONObject.NULL }))
                .put("specifications",JSONObject().put("notes",state.specifications.ifBlank { JSONObject.NULL }))
                .put("attributes_schema",runCatching { JSONObject(state.customAttributesJson) }.getOrElse { throw IllegalArgumentException("الحقول الإضافية يجب أن تكون JSON صالحًا.") })
                .put("allergens",JSONArray(state.allergens.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("storage_instructions",state.storage.ifBlank { JSONObject.NULL }).put("usage_instructions",state.usage.ifBlank { JSONObject.NULL })
                .put("warnings",state.warnings.ifBlank { JSONObject.NULL }).put("shelf_life_days",state.shelfLife.toIntOrNull() ?: JSONObject.NULL)
                .put("requires_expiry_date",state.expiryRequired).put("is_batch_tracked",state.batchTracked)
                .put("is_serial_tracked",state.serialTracked).put("is_prescription_only",state.prescriptionOnly).put("currency_code",state.currencyCode.ifBlank { "YER" })
            val unitRows=JSONArray().put(JSONObject().put("unit_code",state.unitCode.trim()).put("unit_role","base").put("quantity_in_base",1)
                .put("barcode",state.barcode.ifBlank { JSONObject.NULL }).put("package_name",JSONObject.NULL).put("image_url",state.imageUrl.ifBlank { JSONObject.NULL }).put("is_default",true))
            if(state.extraUnitCode.isNotBlank()) unitRows.put(JSONObject().put("unit_code",state.extraUnitCode.trim()).put("unit_role","sale")
                .put("quantity_in_base",state.extraUnitQty.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل تحويل العبوة غير صحيح."))
                .put("barcode",state.extraUnitBarcode.ifBlank { JSONObject.NULL }).put("sku",state.extraUnitSku.ifBlank { JSONObject.NULL }).put("package_name",state.extraUnitPackage.ifBlank { JSONObject.NULL })
                .put("net_quantity",state.extraUnitNetQuantity.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL).put("net_unit_code",state.extraUnitNetUnitCode.ifBlank { JSONObject.NULL })
                .put("image_url",state.extraUnitImageUrl.ifBlank { state.imageUrl.ifBlank { JSONObject.NULL } }).put("is_default",false))
            state.extraUnitsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=9)
                if(p.size<3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw IllegalArgumentException("صيغة الوحدة الإضافية: unit_code|role|qty_in_base|barcode|sku|package_name|net_quantity|net_unit_code|image_url")
                unitRows.put(JSONObject().put("unit_code",p[0].trim().uppercase()).put("unit_role",p[1].trim().lowercase())
                    .put("quantity_in_base",p[2].trim().toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل تحويل العبوة غير صحيح."))
                    .put("barcode",p.getOrNull(3)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("sku",p.getOrNull(4)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("package_name",p.getOrNull(5)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("net_quantity",p.getOrNull(6)?.trim()?.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL)
                    .put("net_unit_code",p.getOrNull(7)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("image_url",p.getOrNull(8)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL).put("is_default",false))
            }
            val levelRows=JSONArray(); state.levels.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { levelRows.put(JSONObject().put("code",it).put("weight",10)) }
            val regionRows=JSONArray()
            if(state.regionCountry.isNotBlank() || state.regionCode.isNotBlank() || state.regionCity.isNotBlank()) regionRows.put(JSONObject()
                .put("country_code",state.regionCountry.ifBlank { JSONObject.NULL }).put("region_code",state.regionCode.ifBlank { JSONObject.NULL })
                .put("city",state.regionCity.ifBlank { JSONObject.NULL }).put("excluded",state.regionExcluded))
            state.extraRegionRules.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=4)
                if(p.size<3) throw IllegalArgumentException("صيغة نطاق الظهور: country|region|city|include أو exclude")
                regionRows.put(JSONObject().put("country_code",p[0].trim().ifBlank { JSONObject.NULL })
                    .put("region_code",p[1].trim().ifBlank { JSONObject.NULL }).put("city",p[2].trim().ifBlank { JSONObject.NULL })
                    .put("excluded",p.getOrNull(3)?.trim()?.equals("exclude",true) ?: false))
            }
            val rulingRows=JSONArray()
            if(state.authority.isNotBlank()) rulingRows.put(JSONObject().put("ruling_domain",state.rulingDomain).put("authority_code",state.authority.trim())
                .put("religion_code",if(state.rulingDomain=="religious") state.religion.ifBlank { JSONObject.NULL } else JSONObject.NULL)
                .put("jurisdiction_code",state.jurisdiction.ifBlank { JSONObject.NULL }).put("outcome",state.rulingOutcome)
                .put("rationale",state.rulingRationale.ifBlank { JSONObject.NULL }).put("source_title",state.rulingSourceTitle.ifBlank { JSONObject.NULL })
                .put("source_url",state.rulingSourceUrl.ifBlank { JSONObject.NULL }).put("confidence",if(state.rulingConfidence=="reported") "reported" else "unverified"))
            state.extraRulingsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=9)
                if(p.size<8) throw IllegalArgumentException("صيغة الحكم: domain|authority|religion|jurisdiction|outcome|source_title|source_url|confidence|rationale")
                rulingRows.put(JSONObject().put("ruling_domain",p[0].trim()).put("authority_code",p[1].trim())
                    .put("religion_code",p[2].trim().ifBlank { JSONObject.NULL }).put("jurisdiction_code",p[3].trim().ifBlank { JSONObject.NULL })
                    .put("outcome",p[4].trim()).put("source_title",p[5].trim().ifBlank { JSONObject.NULL })
                    .put("source_url",p[6].trim().ifBlank { JSONObject.NULL }).put("confidence",p[7].trim())
                    .put("rationale",p.getOrNull(8)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
            }
            val sectorArray=JSONArray(); state.sectors.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { sectorArray.put(it) }
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_product",JSONObject().put("p_organization_id",orgId).put("p_product",product)
                .put("p_units",unitRows).put("p_sectors",sectorArray).put("p_business_levels",levelRows).put("p_regions",regionRows).put("p_rulings",rulingRows),active)
        }, { onCreated(it) }, null)
    }
}
