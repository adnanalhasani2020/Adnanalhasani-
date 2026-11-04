package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/** Maps a catalog discovery page into the screen's existing product model. */
internal fun parseCatalogProducts(raw: String, importedBy: String): List<CatalogProduct> {
    val rows = JSONArray(raw)
    return List(rows.length()) { i ->
                    val row=rows.getJSONObject(i)
                    val localized=row.optJSONObject("localized_details") ?: JSONObject()
                    CatalogProduct(
                        id=row.optString("product_id"),catalogCode=row.optString("catalog_code"),name=row.optString("product_name"),
                        description=row.optString("short_description"),categoryCode=row.optString("category_code"),category=row.optString("category_name"),
                        brand=localized.optString("brand").takeUnless { it.isBlank() || it == "null" } ?: row.optString("brand"),
                        manufacturer=localized.optString("manufacturer_name").takeUnless { it.isBlank() || it == "null" } ?: row.optString("manufacturer_name"),
                        countryOfManufacture=localized.optString("country_of_manufacture").takeUnless { it.isBlank() || it == "null" } ?: row.optString("country_of_manufacture"),
                        imageUrl=row.optString("image_url"),prescriptionOnly=row.optBoolean("is_prescription_only",false),
                        recommendationScore=row.optInt("recommendation_score"),barcode=row.optString("barcode"),unitCode=row.optString("unit_code").ifBlank { "EA" },
                        availableUnits=row.optJSONArray("available_units")?.let { arr -> List(arr.length()) { j ->
                            val u=arr.getJSONObject(j); CatalogUnit(u.optString("unit_code"),u.optString("unit_role"),u.optString("quantity_in_base","1"),u.optString("barcode"),u.optString("package_name").takeUnless { it == "null" }.orEmpty(),u.optString("image_url").takeUnless { it == "null" }.orEmpty(),u.optBoolean("is_default"),u.optString("unit_name").takeUnless { it == "null" }.orEmpty(),u.optString("sku").takeUnless { it == "null" }.orEmpty(),u.optString("net_quantity").takeUnless { it == "null" }.orEmpty(),u.optString("net_unit_code").takeUnless { it == "null" }.orEmpty())
                        } } ?: emptyList(),createdBy=row.optString("created_by"),createdAt=row.optString("created_at"),importedBy=importedBy,
                        attributesSchema=localized.optString("attributes_schema").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONObject("attributes_schema")?.toString().orEmpty(),modelNumber=localized.optString("model_number").takeUnless { it.isBlank() || it == "null" } ?: row.optString("model_number").takeUnless { it == "null" }.orEmpty(),partNumber=localized.optString("part_number").takeUnless { it.isBlank() || it == "null" } ?: row.optString("part_number").takeUnless { it == "null" }.orEmpty(),imageAlt=localized.optString("image_alt").takeUnless { it.isBlank() || it == "null" } ?: row.optString("image_alt").takeUnless { it == "null" }.orEmpty(),
                        materialComposition=localized.optString("material_composition").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONArray("material_composition")?.let { arr -> List(arr.length()) { arr.get(it).toString() }.joinToString(", ") }.orEmpty(),
                        ingredients=localized.optString("ingredients").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONArray("ingredients")?.let { arr -> List(arr.length()) { arr.get(it).toString() }.joinToString(", ") }.orEmpty(),
                        recipe=localized.optString("recipe").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONObject("recipe")?.let { rec ->
                            listOf(rec.optString("instructions").takeUnless { it == "null" || it.isBlank() },
                                rec.optJSONArray("ingredients")?.let { ing -> List(ing.length()) { ingredientIndex -> val ingredient=ing.getJSONObject(ingredientIndex); "${ingredient.optString("ingredient_name")} ${ingredient.optString("quantity")} ${ingredient.optString("unit_code")}" }.joinToString(", ") })
                                .filterNotNull().joinToString(" • ")
                        }.orEmpty(),
                        specifications=localized.optString("specifications").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONObject("specifications")?.toString().orEmpty(),
                        allergens=localized.optString("allergens").takeUnless { it.isBlank() || it == "null" } ?: row.optJSONArray("allergens")?.let { arr -> List(arr.length()) { arr.get(it).toString() }.joinToString(", ") }.orEmpty(),
                        storageInstructions=localized.optString("storage_instructions").takeUnless { it.isBlank() || it == "null" } ?: row.optString("storage_instructions").takeUnless { it == "null" }.orEmpty(),
                        usageInstructions=localized.optString("usage_instructions").takeUnless { it.isBlank() || it == "null" } ?: row.optString("usage_instructions").takeUnless { it == "null" }.orEmpty(),
                        warnings=localized.optString("warnings").takeUnless { it.isBlank() || it == "null" } ?: row.optString("warnings").takeUnless { it == "null" }.orEmpty(),
                        shelfLifeDays=if(row.isNull("shelf_life_days")) null else row.optInt("shelf_life_days"),
                        requiresExpiryDate=row.optBoolean("requires_expiry_date",false),isBatchTracked=row.optBoolean("is_batch_tracked",false),
                        isSerialTracked=row.optBoolean("is_serial_tracked",false),
                        ownerOrganizationId=row.optString("owner_organization_id").takeUnless { it == "null" }.orEmpty(),
                        nameAr=row.optString("name_ar"),nameEn=row.optString("name_en"),
                        descriptionAr=row.optString("description_ar").takeUnless { it == "null" }.orEmpty(),
                        descriptionEn=row.optString("description_en").takeUnless { it == "null" }.orEmpty(),
                        itemType=row.optString("item_type","product"),barcodeGtin=row.optString("barcode_gtin").takeUnless { it == "null" }.orEmpty(),
                        rawMaterialComposition=row.optJSONArray("material_composition")?.toString() ?: "[]",
                        rawIngredients=row.optJSONArray("ingredients")?.toString() ?: "[]",
                        rawRecipe=row.optJSONObject("recipe")?.toString() ?: "{}",
                        rawSpecifications=row.optJSONObject("specifications")?.toString() ?: "{}",
                        rawAllergens=row.optJSONArray("allergens")?.toString() ?: "[]",
                        rawAttributesSchema=row.optJSONObject("attributes_schema")?.toString() ?: "{}",
                        rawBrand=row.optString("brand").takeUnless { it == "null" }.orEmpty(),
                        rawManufacturer=row.optString("manufacturer_name").takeUnless { it == "null" }.orEmpty(),
                        rawCountryOfManufacture=row.optString("country_of_manufacture").takeUnless { it == "null" }.orEmpty(),
                        rawModelNumber=row.optString("model_number").takeUnless { it == "null" }.orEmpty(),
                        rawPartNumber=row.optString("part_number").takeUnless { it == "null" }.orEmpty(),
                        rawImageAlt=row.optString("image_alt").takeUnless { it == "null" }.orEmpty(),
                        rawStorageInstructions=row.optString("storage_instructions").takeUnless { it == "null" }.orEmpty(),
                        rawUsageInstructions=row.optString("usage_instructions").takeUnless { it == "null" }.orEmpty(),
                        rawWarnings=row.optString("warnings").takeUnless { it == "null" }.orEmpty(),
                        rulings=row.optJSONArray("rulings")?.let { arr -> List(arr.length()) { i -> val ruling=arr.getJSONObject(i); CatalogRuling(
                            ruling.optString("ruling_id"),ruling.optString("ruling_domain"),ruling.optString("authority_code"),
                            ruling.optString("religion_code").takeUnless { it == "null" }.orEmpty(),ruling.optString("jurisdiction_code").takeUnless { it == "null" }.orEmpty(),
                            ruling.optString("outcome"),ruling.optString("rationale").takeUnless { it == "null" }.orEmpty(),
                            ruling.optString("source_title").takeUnless { it == "null" }.orEmpty(),ruling.optString("source_url").takeUnless { it == "null" }.orEmpty(),
                            ruling.optString("confidence"),ruling.optString("effective_from").takeUnless { it == "null" }.orEmpty(),ruling.optString("effective_to").takeUnless { it == "null" }.orEmpty(),ruling.optString("reviewed_at").takeUnless { it == "null" }.orEmpty()
                        ) } } ?: emptyList()
                    )
                }
}
