package com.adnanalhasani.commercial

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal data class CatalogUnit(val unitCode: String, val role: String, val quantityInBase: String, val barcode: String, val packageName: String, val imageUrl: String, val isDefault: Boolean, val unitName: String = "", val sku: String = "", val netQuantity: String = "", val netUnitCode: String = "")
internal data class CatalogRuling(val id: String, val domain: String, val authority: String, val religion: String, val jurisdiction: String, val outcome: String, val rationale: String, val sourceTitle: String, val sourceUrl: String, val confidence: String, val effectiveFrom: String, val effectiveTo: String, val reviewedAt: String)
internal data class CatalogProduct(
    val id: String,
    val catalogCode: String,
    val name: String,
    val description: String,
    val categoryCode: String,
    val category: String,
    val brand: String,
    val manufacturer: String,
    val countryOfManufacture: String,
    val imageUrl: String,
    val prescriptionOnly: Boolean,
    val recommendationScore: Int,
    val barcode: String = "",
    val unitCode: String = "EA",
    val availableUnits: List<CatalogUnit> = emptyList(),
    val createdBy: String = "",
    val createdAt: String = "",
    val importedBy: String = "",
    val attributesSchema: String = "",
    val modelNumber: String = "",
    val partNumber: String = "",
    val imageAlt: String = "",
    val materialComposition: String = "",
    val ingredients: String = "",
    val recipe: String = "",
    val specifications: String = "",
    val allergens: String = "",
    val storageInstructions: String = "",
    val usageInstructions: String = "",
    val warnings: String = "",
    val shelfLifeDays: Int? = null,
    val requiresExpiryDate: Boolean = false,
    val isBatchTracked: Boolean = false,
    val isSerialTracked: Boolean = false,
    val rulings: List<CatalogRuling> = emptyList(),
    val ownerOrganizationId: String = "",
    val nameAr: String = "",
    val nameEn: String = "",
    val descriptionAr: String = "",
    val descriptionEn: String = "",
    val itemType: String = "product",
    val barcodeGtin: String = "",
    val rawMaterialComposition: String = "[]",
    val rawIngredients: String = "[]",
    val rawRecipe: String = "{}",
    val rawSpecifications: String = "{}",
    val rawAllergens: String = "[]",
    val rawAttributesSchema: String = "{}",
    val rawBrand: String = "",
    val rawManufacturer: String = "",
    val rawCountryOfManufacture: String = "",
    val rawModelNumber: String = "",
    val rawPartNumber: String = "",
    val rawImageAlt: String = "",
    val rawStorageInstructions: String = "",
    val rawUsageInstructions: String = "",
    val rawWarnings: String = ""
)
private data class CatalogCategory(val code: String, val parentCode: String, val nameAr: String, val nameEn: String, val localizedName: String = "", val descriptionAr: String = "", val descriptionEn: String = "", val localizedDescription: String = "")
private data class DirectoryUnit(val code: String, val nameAr: String, val nameEn: String, val dimension: String, val baseCode: String, val factor: String, val owner: String, val localizedName: String = "")
private data class PendingTransfer(val id: String, val source: String, val destination: String, val itemCount: Int, val createdAt: String)

private val catalogIo = Executors.newSingleThreadExecutor()
private val catalogMain = Handler(Looper.getMainLooper())

private fun catalogSessionPreferences(context: android.content.Context) = EncryptedSharedPreferences.create(
    context,
    "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private fun catalogHttp(path: String, payload: JSONObject, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "تحقق من إعداد SUPABASE_URL وSUPABASE_ANON_KEY." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"; connectTimeout = 15000; readTimeout = 25000; doOutput = true
        setRequestProperty("apikey", anon); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json")
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val error = runCatching { JSONObject(raw) }.getOrNull()
            throw IllegalStateException(listOf(error?.optString("message"), error?.optString("hint")).filter { !it.isNullOrBlank() && it != "null" }.joinToString(" — ").ifBlank { "فشل الطلب إلى Supabase (HTTP $code)." })
        }
        raw
    } finally { connection.disconnect() }
}

private fun catalogGet(path: String, token: String): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 15000; readTimeout = 25000
        setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
        setRequestProperty("Authorization", "Bearer $token"); setRequestProperty("Accept", "application/json")
    }
    return try {
        val code = connection.responseCode
        val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException(runCatching { JSONObject(raw).optString("message") }.getOrDefault("").ifBlank { "تعذر تحميل الدليل (HTTP $code)." })
        raw
    } finally { connection.disconnect() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogScreen(onBack: () -> Unit, onAddToInventory: (CatalogProduct) -> Unit) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }; var signedInUserId by remember { mutableStateOf("") }; var organizationId by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("browse") }
    var sector by remember { mutableStateOf("retail") }; var businessLevel by remember { mutableStateOf("shop") }
    var country by remember { mutableStateOf("YE") }; var region by remember { mutableStateOf("") }; var city by remember { mutableStateOf("") }
    var locale by remember { mutableStateOf("ar") }; var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var catalogOffset by remember { mutableIntStateOf(0) }
    var catalogHasMore by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<CatalogCategory>>(emptyList()) }
    var directoryUnits by remember { mutableStateOf<List<DirectoryUnit>>(emptyList()) }; var pendingTransfers by remember { mutableStateOf<List<PendingTransfer>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var restoringSession by remember { mutableStateOf(true) }; var feedback by remember { mutableStateOf("") }; var editingProduct by remember { mutableStateOf<CatalogProduct?>(null) }

    var categoryCode by remember { mutableStateOf("") }; var parentCode by remember { mutableStateOf("") }
    var categoryNameAr by remember { mutableStateOf("") }; var categoryNameEn by remember { mutableStateOf("") }
    var categoryDescriptionAr by remember { mutableStateOf("") }; var categoryDescriptionEn by remember { mutableStateOf("") }; var categoryTranslations by remember { mutableStateOf("") }

    var nameAr by remember { mutableStateOf("") }; var nameEn by remember { mutableStateOf("") }
    var descriptionAr by remember { mutableStateOf("") }; var descriptionEn by remember { mutableStateOf("") }; var extraTranslations by remember { mutableStateOf("") }
    var productCategory by remember { mutableStateOf("") }; var itemType by remember { mutableStateOf("product") }
    var unitCode by remember { mutableStateOf("EA") }; var barcode by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }; var manufacturer by remember { mutableStateOf("") }
    var origin by remember { mutableStateOf("") }; var model by remember { mutableStateOf("") }; var partNumber by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }; var imageAlt by remember { mutableStateOf("") }
    var ingredients by remember { mutableStateOf("") }; var materialComposition by remember { mutableStateOf("") }
    var recipe by remember { mutableStateOf("") }; var recipeIngredientsText by remember { mutableStateOf("") }; var recipeYieldQty by remember { mutableStateOf("") }; var recipeYieldUnit by remember { mutableStateOf("EA") }; var recipePrepMinutes by remember { mutableStateOf("") }; var recipeCookMinutes by remember { mutableStateOf("") }; var recipeSource by remember { mutableStateOf("") }; var recipeAllergenNotes by remember { mutableStateOf("") }; var specifications by remember { mutableStateOf("") }; var customAttributesJson by remember { mutableStateOf("{}") }
    var allergens by remember { mutableStateOf("") }; var storage by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf("") }; var warnings by remember { mutableStateOf("") }
    var shelfLife by remember { mutableStateOf("") }; var expiryRequired by remember { mutableStateOf(false) }
    var batchTracked by remember { mutableStateOf(false) }; var serialTracked by remember { mutableStateOf(false) }
    var prescriptionOnly by remember { mutableStateOf(false) }
    var extraUnitCode by remember { mutableStateOf("") }; var extraUnitQty by remember { mutableStateOf("1") }
    var extraUnitBarcode by remember { mutableStateOf("") }; var extraUnitSku by remember { mutableStateOf("") }; var extraUnitPackage by remember { mutableStateOf("") }; var extraUnitNetQuantity by remember { mutableStateOf("") }; var extraUnitNetUnitCode by remember { mutableStateOf("") }; var extraUnitImageUrl by remember { mutableStateOf("") }; var extraUnitsText by remember { mutableStateOf("") }
    var currencyCode by remember { mutableStateOf("YER") }; var sectors by remember { mutableStateOf("retail") }
    var levels by remember { mutableStateOf("shop") }; var regionCountry by remember { mutableStateOf("") }
    var regionCode by remember { mutableStateOf("") }; var regionCity by remember { mutableStateOf("") }
    var regionExcluded by remember { mutableStateOf(false) }; var extraRegionRules by remember { mutableStateOf("") }
    var rulingDomain by remember { mutableStateOf("religious") }; var authority by remember { mutableStateOf("") }
    var religion by remember { mutableStateOf("") }; var jurisdiction by remember { mutableStateOf("") }
    var rulingOutcome by remember { mutableStateOf("unknown") }; var rulingRationale by remember { mutableStateOf("") }
    var rulingSourceTitle by remember { mutableStateOf("") }; var rulingSourceUrl by remember { mutableStateOf("") }
    var rulingConfidence by remember { mutableStateOf("unverified") }; var extraRulingsText by remember { mutableStateOf("") }

    var unitShortCode by remember { mutableStateOf("") }; var unitNameAr by remember { mutableStateOf("") }
    var unitNameEn by remember { mutableStateOf("") }; var unitDimension by remember { mutableStateOf("count") }
    var unitFactor by remember { mutableStateOf("1") }; var unitBaseCode by remember { mutableStateOf("EA") }
    var unitDecimals by remember { mutableStateOf("0") }; var unitTranslations by remember { mutableStateOf("") }
    var transferSource by remember { mutableStateOf("") }; var transferDestination by remember { mutableStateOf("") }
    var transferIds by remember { mutableStateOf("") }

    fun request(action: () -> String, onSuccess: (String) -> Unit) {
        if (busy) return
        busy = true; feedback = ""
        catalogIo.execute {
            val result = runCatching(action)
            catalogMain.post { busy = false; result.onSuccess(onSuccess).onFailure { feedback = it.message ?: "تعذر إكمال العملية."; restoringSession = false } }
        }
    }
    LaunchedEffect(Unit) {
        val savedRefresh = runCatching { catalogSessionPreferences(context).getString("refresh_token", null) }.getOrNull()
        if (!savedRefresh.isNullOrBlank()) {
            request({
                val raw = catalogHttp("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", savedRefresh))
                val session = JSONObject(raw)
                val access = session.optString("access_token")
                val refresh = session.optString("refresh_token").ifBlank { savedRefresh }
                if (access.isBlank()) throw IllegalStateException("انتهت الجلسة؛ سجّل الدخول مجددًا.")
                "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
            }, { sessionValue ->
                val parts = sessionValue.split("|", limit = 3)
                token = parts[0]
                catalogSessionPreferences(context).edit().putString("refresh_token", parts.getOrNull(1).orEmpty()).apply()
                signedInUserId = parts.getOrNull(2).orEmpty()
                feedback = "تم استعادة جلستك السابقة."
                restoringSession = false
            })
        } else {
            restoringSession = false
        }
    }

    fun org(): String? = organizationId.trim().takeIf { it.isNotBlank() }
    fun loadCatalog(reset: Boolean = true) {
        val active = token ?: return
        val orgId = org() ?: run { feedback = "أدخل معرّف المؤسسة UUID أولًا."; return }
        if (!reset && (!catalogHasMore || busy)) return
        val requestedOffset = if (reset) 0 else catalogOffset
        if (reset) { catalogOffset = 0; catalogHasMore = true; items = emptyList() }
        request({
            val payload = JSONObject().put("p_organization_id",orgId)
                .put("p_sector_code",sector.trim().ifBlank { JSONObject.NULL })
                .put("p_business_level_code",businessLevel.trim().ifBlank { JSONObject.NULL })
                .put("p_country_code",country.trim().ifBlank { JSONObject.NULL })
                .put("p_region_code",region.trim().ifBlank { JSONObject.NULL }).put("p_city",city.trim().ifBlank { JSONObject.NULL })
                 .put("p_locale_code",locale.trim().lowercase().ifBlank { "ar" }).put("p_query",query.trim().ifBlank { JSONObject.NULL }).put("p_limit",100).put("p_offset",requestedOffset)
            catalogHttp("/rest/v1/rpc/commercial_catalog_discover_products_page",payload,active)
        }, { raw ->
            val json=JSONArray(raw)
            val pageItems=List(json.length()) { i ->
                val row=json.getJSONObject(i)
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
                    } } ?: emptyList(),createdBy=row.optString("created_by"),createdAt=row.optString("created_at"),importedBy=signedInUserId,
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
            items = if (reset) pageItems.distinctBy { it.id } else (items + pageItems).distinctBy { it.id }
            catalogOffset = requestedOffset + json.length()
            catalogHasMore = json.length() == 100
            feedback = if (catalogHasMore) "تم تحميل ${items.size} صنفًا؛ اسحب إلى الأسفل لتحميل المزيد." else "اكتمل تحميل ${items.size} صنفًا مطابقًا."
        })
    }
    fun loadDirectories() {
        val active=token ?: return
        request({
            val catRaw=catalogGet("/rest/v1/commercial_global_catalog_categories?select=code,parent_code,name_ar,name_en,description_ar,description_en&is_active=eq.true&order=parent_code.asc,sort_order.asc,code.asc",active)
            val unitRaw=catalogGet("/rest/v1/commercial_global_units?select=code,name_ar,name_en,dimension,base_unit_code,factor_to_base,owner_organization_id&is_active=eq.true&order=dimension.asc,code.asc",active)
            val categoryTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.name&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            val categoryDescriptionTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.description&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            val unitTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.unit&field_key=eq.name&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            JSONObject().put("categories",JSONArray(catRaw)).put("units",JSONArray(unitRaw))
                .put("categoryTranslations",JSONArray(categoryTranslationRaw)).put("categoryDescriptionTranslations",JSONArray(categoryDescriptionTranslationRaw)).put("unitTranslations",JSONArray(unitTranslationRaw)).toString()
        }, { raw ->
            val result=JSONObject(raw); val cats=result.getJSONArray("categories"); val units=result.getJSONArray("units")
            val catTr=result.getJSONArray("categoryTranslations"); val catDescTr=result.getJSONArray("categoryDescriptionTranslations"); val unitTr=result.getJSONArray("unitTranslations")
            val categoryNames=buildMap { for(i in 0 until catTr.length()) { val v=catTr.getJSONObject(i); put(v.optString("entity_key"),v.optString("translated_value")) } }
            val categoryDescriptions=buildMap { for(i in 0 until catDescTr.length()) { val v=catDescTr.getJSONObject(i); put(v.optString("entity_key"),v.optString("translated_value")) } }
            val unitNames=buildMap { for(i in 0 until unitTr.length()) { val v=unitTr.getJSONObject(i); put(v.optString("entity_key"),v.optString("translated_value")) } }
            categories=List(cats.length()) { i -> val v=cats.getJSONObject(i); val code=v.optString("code"); CatalogCategory(code,v.optString("parent_code").takeUnless { it == "null" }.orEmpty(),v.optString("name_ar"),v.optString("name_en"),categoryNames[code].orEmpty(),v.optString("description_ar").takeUnless { it == "null" }.orEmpty(),v.optString("description_en").takeUnless { it == "null" }.orEmpty(),categoryDescriptions[code].orEmpty()) }
            directoryUnits=List(units.length()) { i -> val v=units.getJSONObject(i); val code=v.optString("code"); DirectoryUnit(code,v.optString("name_ar"),v.optString("name_en"),v.optString("dimension"),v.optString("base_unit_code").takeUnless { it == "null" }.orEmpty(),v.optString("factor_to_base").takeUnless { it == "null" }.orEmpty(),v.optString("owner_organization_id").takeUnless { it == "null" }.orEmpty(),unitNames[code].orEmpty()) }
            feedback="تم تحميل ${categories.size} تصنيفًا و${directoryUnits.size} وحدة قياس."
        })
    }
    fun createCategory() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_category",JSONObject()
                .put("p_organization_id",orgId).put("p_code",categoryCode).put("p_parent_code",parentCode.ifBlank { JSONObject.NULL })
                .put("p_name_ar",categoryNameAr).put("p_name_en",categoryNameEn)
                .put("p_description_ar",categoryDescriptionAr.ifBlank { JSONObject.NULL })
                .put("p_description_en",categoryDescriptionEn.ifBlank { JSONObject.NULL }).put("p_sort_order",100)
                .put("p_translations",JSONArray().also { rows -> categoryTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { feedback="تم إنشاء التصنيف: $it"; loadDirectories() })
    }
    fun createUnit() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_unit",JSONObject()
                .put("p_organization_id",orgId).put("p_code",unitShortCode).put("p_name_ar",unitNameAr).put("p_name_en",unitNameEn)
                .put("p_dimension",unitDimension).put("p_factor_to_base",unitFactor.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل التحويل غير صحيح."))
                .put("p_base_unit_code",unitBaseCode).put("p_decimal_places",unitDecimals.toIntOrNull() ?: 0)
                .put("p_translations",JSONArray().also { rows -> unitTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { feedback="تم إنشاء وحدة الحساب الخاصة بالمؤسسة: $it"; loadDirectories() })
    }
    fun createProduct() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        if(nameAr.isBlank() || nameEn.isBlank() || productCategory.isBlank() || unitCode.isBlank()) { feedback="أكمل الاسم العربي والإنجليزي والتصنيف ووحدة القياس."; return }
        request({
            val product=JSONObject().put("name_ar",nameAr.trim()).put("name_en",nameEn.trim())
                .put("description_ar",descriptionAr.ifBlank { JSONObject.NULL }).put("description_en",descriptionEn.ifBlank { JSONObject.NULL })
                .put("translations",JSONArray().also { translationRows -> extraTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) translationRows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } })
                .put("category_code",productCategory.trim()).put("item_type",itemType).put("unit_code",unitCode.trim())
                .put("barcode_gtin",barcode.ifBlank { JSONObject.NULL }).put("brand",brand.ifBlank { JSONObject.NULL })
                .put("manufacturer_name",manufacturer.ifBlank { JSONObject.NULL }).put("country_of_manufacture",origin.ifBlank { JSONObject.NULL })
                .put("model_number",model.ifBlank { JSONObject.NULL }).put("part_number",partNumber.ifBlank { JSONObject.NULL })
                .put("image_url",imageUrl.ifBlank { JSONObject.NULL }).put("image_alt",imageAlt.ifBlank { JSONObject.NULL })
                .put("ingredients",JSONArray(ingredients.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("material_composition",JSONArray(materialComposition.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("recipe",JSONObject().put("instructions",recipe.ifBlank { JSONObject.NULL })
                    .put("recipe_name",nameAr.trim()).put("locale_code","ar")
                    .put("ingredients",JSONArray().also { rows -> recipeIngredientsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                        val p=line.split("|",limit=4)
                        if(p.size<3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw IllegalArgumentException("صيغة مكونات الوصفة: name|quantity|unit_code|note")
                        rows.put(JSONObject().put("ingredient_name",p[0].trim()).put("quantity",p[1].trim().toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("كمية المكون غير صحيحة."))
                            .put("unit_code",p[2].trim().uppercase()).put("preparation_note",p.getOrNull(3)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
                    }})
                    .put("yield_quantity",recipeYieldQty.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL)
                    .put("yield_unit_code",recipeYieldUnit.ifBlank { JSONObject.NULL })
                    .put("preparation_minutes",recipePrepMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("cooking_minutes",recipeCookMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("source_reference",recipeSource.ifBlank { JSONObject.NULL })
                    .put("allergen_notes",recipeAllergenNotes.ifBlank { JSONObject.NULL }))
                .put("specifications",JSONObject().put("notes",specifications.ifBlank { JSONObject.NULL }))
                .put("attributes_schema",runCatching { JSONObject(customAttributesJson) }.getOrElse { throw IllegalArgumentException("الحقول الإضافية يجب أن تكون JSON صالحًا.") })
                .put("allergens",JSONArray(allergens.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("storage_instructions",storage.ifBlank { JSONObject.NULL }).put("usage_instructions",usage.ifBlank { JSONObject.NULL })
                .put("warnings",warnings.ifBlank { JSONObject.NULL }).put("shelf_life_days",shelfLife.toIntOrNull() ?: JSONObject.NULL)
                .put("requires_expiry_date",expiryRequired).put("is_batch_tracked",batchTracked)
                .put("is_serial_tracked",serialTracked).put("is_prescription_only",prescriptionOnly).put("currency_code",currencyCode.ifBlank { "YER" })
            val unitRows=JSONArray().put(JSONObject().put("unit_code",unitCode.trim()).put("unit_role","base").put("quantity_in_base",1)
                .put("barcode",barcode.ifBlank { JSONObject.NULL }).put("package_name",JSONObject.NULL).put("image_url",imageUrl.ifBlank { JSONObject.NULL }).put("is_default",true))
            if(extraUnitCode.isNotBlank()) unitRows.put(JSONObject().put("unit_code",extraUnitCode.trim()).put("unit_role","sale")
                .put("quantity_in_base",extraUnitQty.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل تحويل العبوة غير صحيح."))
                .put("barcode",extraUnitBarcode.ifBlank { JSONObject.NULL }).put("sku",extraUnitSku.ifBlank { JSONObject.NULL }).put("package_name",extraUnitPackage.ifBlank { JSONObject.NULL })
                .put("net_quantity",extraUnitNetQuantity.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL).put("net_unit_code",extraUnitNetUnitCode.ifBlank { JSONObject.NULL })
                .put("image_url",extraUnitImageUrl.ifBlank { imageUrl.ifBlank { JSONObject.NULL } }).put("is_default",false))
            extraUnitsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
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
            val levelRows=JSONArray(); levels.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { levelRows.put(JSONObject().put("code",it).put("weight",10)) }
            val regionRows=JSONArray()
            if(regionCountry.isNotBlank() || regionCode.isNotBlank() || regionCity.isNotBlank()) regionRows.put(JSONObject()
                .put("country_code",regionCountry.ifBlank { JSONObject.NULL }).put("region_code",regionCode.ifBlank { JSONObject.NULL })
                .put("city",regionCity.ifBlank { JSONObject.NULL }).put("excluded",regionExcluded))
            extraRegionRules.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=4)
                if(p.size<3) throw IllegalArgumentException("صيغة نطاق الظهور: country|region|city|include أو exclude")
                regionRows.put(JSONObject().put("country_code",p[0].trim().ifBlank { JSONObject.NULL })
                    .put("region_code",p[1].trim().ifBlank { JSONObject.NULL }).put("city",p[2].trim().ifBlank { JSONObject.NULL })
                    .put("excluded",p.getOrNull(3)?.trim()?.equals("exclude",true) ?: false))
            }
            val rulingRows=JSONArray()
            if(authority.isNotBlank()) rulingRows.put(JSONObject().put("ruling_domain",rulingDomain).put("authority_code",authority.trim())
                .put("religion_code",if(rulingDomain=="religious") religion.ifBlank { JSONObject.NULL } else JSONObject.NULL)
                .put("jurisdiction_code",jurisdiction.ifBlank { JSONObject.NULL }).put("outcome",rulingOutcome)
                .put("rationale",rulingRationale.ifBlank { JSONObject.NULL }).put("source_title",rulingSourceTitle.ifBlank { JSONObject.NULL })
                .put("source_url",rulingSourceUrl.ifBlank { JSONObject.NULL }).put("confidence",if(rulingConfidence=="reported") "reported" else "unverified"))
            extraRulingsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=9)
                if(p.size<8) throw IllegalArgumentException("صيغة الحكم: domain|authority|religion|jurisdiction|outcome|source_title|source_url|confidence|rationale")
                rulingRows.put(JSONObject().put("ruling_domain",p[0].trim()).put("authority_code",p[1].trim())
                    .put("religion_code",p[2].trim().ifBlank { JSONObject.NULL }).put("jurisdiction_code",p[3].trim().ifBlank { JSONObject.NULL })
                    .put("outcome",p[4].trim()).put("source_title",p[5].trim().ifBlank { JSONObject.NULL })
                    .put("source_url",p[6].trim().ifBlank { JSONObject.NULL }).put("confidence",p[7].trim())
                    .put("rationale",p.getOrNull(8)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
            }
            val sectorArray=JSONArray(); sectors.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { sectorArray.put(it) }
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_product",JSONObject().put("p_organization_id",orgId).put("p_product",product)
                .put("p_units",unitRows).put("p_sectors",sectorArray).put("p_business_levels",levelRows).put("p_regions",regionRows).put("p_rulings",rulingRows),active)
        }, { feedback="تم حفظ الصنف وتفاصيل وحداته ونطاق ظهوره ومعلومات الحكم المرتبطة به: $it"; section="browse"; loadCatalog() })
    }
    fun transferCatalog() {
        val active=token ?: return
        if(transferSource.isBlank() || transferDestination.isBlank()) { feedback="أدخل معرّف الحساب المصدر والوجهة."; return }
        request({
            val ids=JSONArray(); transferIds.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { ids.put(it) }
            catalogHttp("/rest/v1/rpc/commercial_catalog_send_transfer",JSONObject().put("p_source_organization_id",transferSource.trim())
                .put("p_destination_organization_id",transferDestination.trim()).put("p_global_item_ids",if(ids.length()==0) JSONObject.NULL else ids),active)
        }, { feedback="اكتملت عملية نقل روابط الدليل: $it" })
    }
    fun loadPendingTransfers() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة المستقبلة في الحقل العلوي أولًا."; return }
        request({
            catalogGet("/rest/v1/commercial_catalog_transfers?select=id,source_organization_id,destination_organization_id,item_count,status,created_at&destination_organization_id=eq.$orgId&status=eq.pending&order=created_at.desc",active)
        }, { raw ->
            val json=JSONArray(raw)
            pendingTransfers=List(json.length()) { i -> val v=json.getJSONObject(i); PendingTransfer(v.optString("id"),v.optString("source_organization_id"),v.optString("destination_organization_id"),v.optInt("item_count"),v.optString("created_at")) }
            feedback="طلبات النقل الواردة: ${pendingTransfers.size}"
        })
    }
    fun respondToTransfer(id: String, accept: Boolean) {
        val active=token ?: return
        request({
            catalogHttp("/rest/v1/rpc/${if(accept) "commercial_catalog_accept_transfer" else "commercial_catalog_reject_transfer"}",
                JSONObject().put("p_transfer_id",id),active)
        }, { feedback=if(accept) "تم قبول طلب النقل: $it" else "تم رفض طلب النقل: $it"; loadPendingTransfers() })
    }
    fun reviewRuling(rulingId: String) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_review_ruling",JSONObject()
                .put("p_organization_id",orgId).put("p_ruling_id",rulingId),active)
        }, { feedback="تم تسجيل مراجعة المصدر: $it"; loadCatalog() })
    }
    fun updateProductDetails(item: CatalogProduct, payload: JSONObject) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_update_product_details",JSONObject()
                .put("p_organization_id",orgId).put("p_product_id",item.id).put("p_product",payload),active)
        }, { feedback="تم تحديث تفاصيل الصنف ${item.catalogCode} مع الحفاظ على وحداته واستهدافه وأحكامه."; editingProduct=null; loadCatalog() })
    }

    Column(Modifier.fillMaxSize().padding(horizontal=14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,contentDescription="رجوع") }
            Column(Modifier.weight(1f)) {
                Text("دليل الأصناف والوحدات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text("دليل متعدد الأنشطة والمستويات والمناطق",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            }
            if (token != null) TextButton(onClick = {
                runCatching { catalogSessionPreferences(context).edit().clear().apply() }
                token = null; signedInUserId = ""; organizationId = ""; feedback = "تم تسجيل الخروج."
            }) { Text("تسجيل الخروج") }
        }
        if (restoringSession) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if(token==null) {
            LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=18.dp)) {
                item { Text("تسجيل الدخول إلى حساب Supabase",style=MaterialTheme.typography.titleMedium) }
                item { OutlinedTextField(email,{email=it.trim()},Modifier.fillMaxWidth(),label={Text("البريد الإلكتروني")},singleLine=true) }
                item { OutlinedTextField(password,{password=it},Modifier.fillMaxWidth(),label={Text("كلمة المرور")},singleLine=true) }
                item { Button(onClick={request({
                    val raw=catalogHttp("/auth/v1/token?grant_type=password",JSONObject().put("email",email).put("password",password))
                    val session=JSONObject(raw); val access=session.optString("access_token"); val refresh=session.optString("refresh_token"); if(access.isBlank() || refresh.isBlank()) throw IllegalStateException("لم تُرجع خدمة المصادقة جلسة صالحة.")
                    "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
                },{sessionValue -> val parts=sessionValue.split("|",limit=3); token=parts[0]; catalogSessionPreferences(context).edit().putString("refresh_token",parts.getOrNull(1).orEmpty()).apply(); signedInUserId=parts.getOrNull(2).orEmpty(); password=""; feedback="تم تسجيل الدخول. ستُستعاد الجلسة تلقائيًا عند فتح الدليل لاحقًا."})},enabled=!busy && email.contains("@") && password.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text(if(busy) "جارٍ تسجيل الدخول..." else "تسجيل الدخول") } }
                item { Text("تُحفظ بيانات تجديد الجلسة مشفرة على الجهاز، ولا تُحفظ كلمة المرور. لا تستخدم مفتاح service_role في التطبيق.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall) }
            }
        } else {
            OutlinedTextField(organizationId,{organizationId=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف المؤسسة UUID")},singleLine=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("browse" to "بحث", "product" to "صنف جديد", "category" to "تصنيف", "unit" to "وحدة", "transfer" to "نقل").forEach { (key,label) ->
                    FilterChip(selected=section==key,onClick={section=key},label={Text(label,style=MaterialTheme.typography.labelSmall)})
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(feedback.isNotBlank()) Text(feedback,color=if(feedback.startsWith("تم")||feedback.startsWith("اكتملت")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            when(section) {
                "browse" -> {
                    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                        item { OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("بحث بالاسم أو الوصف أو الباركود أو الكود")},singleLine=true) }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(sector,{sector=it},Modifier.weight(1f),label={Text("النشاط")},singleLine=true)
                            OutlinedTextField(businessLevel,{businessLevel=it},Modifier.weight(1f),label={Text("مستوى النشاط")},singleLine=true)
                        } }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(country,{country=it.uppercase()},Modifier.weight(1f),label={Text("الدولة")},singleLine=true)
                            OutlinedTextField(region,{region=it},Modifier.weight(1f),label={Text("المنطقة")},singleLine=true)
                            OutlinedTextField(city,{city=it},Modifier.weight(1f),label={Text("المدينة")},singleLine=true)
                        } }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            OutlinedTextField(locale,{locale=it},Modifier.weight(1f),label={Text("اللغة")},singleLine=true)
                            Button(onClick={loadCatalog()},enabled=!busy) { Icon(Icons.Default.Search,null); Text("بحث") }
                            OutlinedButton(onClick={loadDirectories()},enabled=!busy) { Text("تحديث الأدلة") }
                        } }
                        items(items,key={it.id}) { item ->
                            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    if(item.imageUrl.startsWith("https://",true)) AsyncImage(model=item.imageUrl,contentDescription=item.name,modifier=Modifier.fillMaxWidth().heightIn(max=180.dp),contentScale=ContentScale.Fit)
                                    Row(verticalAlignment=Alignment.Top) {
                                        Column(Modifier.weight(1f)) {
                                            Text(item.name.ifBlank { item.catalogCode },fontWeight=FontWeight.SemiBold)
                                            Text("${item.category} • ${item.catalogCode}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                        }
                                        if(item.recommendationScore>0) AssistChip(onClick={},label={Text("مقترح +${item.recommendationScore}")})
                                    }
                                    if(item.description.isNotBlank()) Text(item.description,style=MaterialTheme.typography.bodySmall)
                                    val facts=listOf(
                                        item.brand.takeIf{it.isNotBlank()}?.let{"العلامة: $it"},
                                        item.manufacturer.takeIf{it.isNotBlank()}?.let{"المصنّع: $it"},
                                        item.countryOfManufacture.takeIf{it.isNotBlank()}?.let{"بلد الصناعة: $it"},
                                        item.modelNumber.takeIf{it.isNotBlank()}?.let{"رقم الطراز: $it"},
                                        item.partNumber.takeIf{it.isNotBlank()}?.let{"رقم القطعة: $it"},
                                        item.imageAlt.takeIf{it.isNotBlank()}?.let{"وصف الصورة: $it"},
                                        item.barcode.takeIf{it.isNotBlank()}?.let{"الباركود الأساسي: $it"},
                                        "وحدة الأساس/البيع: ${item.unitCode}",
                                        item.availableUnits.takeIf{it.isNotEmpty()}?.joinToString("، "){u->"${u.packageName.ifBlank{u.unitName.ifBlank{u.unitCode}}} × ${u.quantityInBase}${if(u.barcode.isNotBlank()) " (باركود ${u.barcode})" else ""}"}?.let{"العبوات: $it"},
                                        item.createdAt.takeIf{it.isNotBlank()}?.let{"تاريخ إدخال سجل الدليل: $it"},
                                        item.createdBy.takeIf{it.isNotBlank()}?.let{"معرّف مدخل سجل الدليل: $it"},
                                        item.imageUrl.takeIf{it.isNotBlank()}?.let{"رابط الصورة الخارجية: $it"},
                                        item.materialComposition.takeIf{it.isNotBlank()}?.let{"تركيب المواد: $it"},
                                        item.ingredients.takeIf{it.isNotBlank()}?.let{"المكونات: $it"},
                                        item.recipe.takeIf{it.isNotBlank()}?.let{"الوصفة: $it"},
                                        item.specifications.takeIf{it.isNotBlank()}?.let{"المواصفات: $it"},
                                        item.allergens.takeIf{it.isNotBlank()}?.let{"مسببات الحساسية: $it"},
                                        item.storageInstructions.takeIf{it.isNotBlank()}?.let{"التخزين: $it"},
                                        item.usageInstructions.takeIf{it.isNotBlank()}?.let{"الاستخدام: $it"},
                                        item.warnings.takeIf{it.isNotBlank()}?.let{"تحذيرات: $it"},
                                        item.shelfLifeDays?.let{"مدة الصلاحية بالأيام: $it"},
                                        if(item.requiresExpiryDate) "يتطلب تاريخ صلاحية" else null,
                                        if(item.isBatchTracked) "يتطلب تتبع رقم التشغيلة" else null,
                                        if(item.isSerialTracked) "يتطلب تتبع الرقم التسلسلي" else null,
                                        item.attributesSchema.takeIf{it.isNotBlank() && it!="{}"}?.let{"حقول إضافية: $it"},
                                        if(item.prescriptionOnly) "صنف يتطلب وصفة طبية — تحقق من التصنيف المحلي" else null
                                    ).filterNotNull()
                                    facts.forEach{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                                    item.rulings.forEach { ruling ->
                                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                        Text("الحكم ${ruling.domain}: ${ruling.outcome} • الجهة: ${ruling.authority} • الحالة: ${ruling.confidence}",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodySmall)
                                        if(ruling.religion.isNotBlank()) Text("الدين/المذهب: ${ruling.religion}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.jurisdiction.isNotBlank()) Text("الاختصاص: ${ruling.jurisdiction}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.rationale.isNotBlank()) Text(ruling.rationale,style=MaterialTheme.typography.bodySmall)
                                        if(ruling.sourceTitle.isNotBlank()) Text("المصدر: ${ruling.sourceTitle}",style=MaterialTheme.typography.bodySmall)
                                        if(ruling.sourceUrl.isNotBlank()) Text("رابط المصدر: ${ruling.sourceUrl}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                                        if(ruling.sourceUrl.startsWith("https://",true)) TextButton(onClick={
                                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ruling.sourceUrl))) }
                                                .onFailure { feedback="تعذر فتح رابط المصدر: ${it.message.orEmpty()}" }
                                        }) { Text("فتح المصدر") }
                                        if(ruling.effectiveFrom.isNotBlank() || ruling.effectiveTo.isNotBlank()) Text("فترة السريان: ${ruling.effectiveFrom.ifBlank { "غير محدد" }} — ${ruling.effectiveTo.ifBlank { "مفتوح" }}",style=MaterialTheme.typography.labelSmall)
                                        if(ruling.reviewedAt.isNotBlank()) Text("تاريخ المراجعة: ${ruling.reviewedAt}",style=MaterialTheme.typography.labelSmall)
                                        if((ruling.confidence=="unverified" || ruling.confidence=="reported") && ruling.sourceTitle.isNotBlank() && ruling.sourceUrl.startsWith("https://",true)) {
                                            OutlinedButton(onClick={reviewRuling(ruling.id)},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تسجيل مراجعة المصدر (مالك/مدير)") }
                                        }
                                    }
                                    item.availableUnits.filter { it.imageUrl.startsWith("https://",true) }.forEach { unit ->
                                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            AsyncImage(model=unit.imageUrl,contentDescription=unit.packageName.ifBlank { unit.unitCode },modifier=Modifier.size(64.dp),contentScale=ContentScale.Crop)
                                            Column(Modifier.weight(1f)) {
                                                Text("صورة العبوة: ${unit.packageName.ifBlank { unit.unitCode }}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold)
                                                Text(unit.imageUrl,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                    Text("معرّف الصنف: ${item.id}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TextButton(onClick={onAddToInventory(item)}) { Icon(Icons.Default.Add,null); Text("إضافة إلى المخزون المحلي") } }
                    if(item.ownerOrganizationId.isNotBlank() && item.ownerOrganizationId==organizationId.trim()) {
                        OutlinedButton(onClick={editingProduct=item},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تعديل بيانات الصنف") }
                    }
                                }
                            }
                        }
                        if(items.isEmpty()) item { Text("ابحث عن أصناف أو اضغط تحديث الأدلة لعرض التصنيفات والوحدات المتاحة.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        if(categories.isNotEmpty()) item { Text("دليل التصنيفات: ${categories.size} تصنيفًا محمّلًا",style=MaterialTheme.typography.bodySmall) }
                        if(directoryUnits.isNotEmpty()) item { Text("دليل الوحدات: ${directoryUnits.size} وحدة محمّلة",style=MaterialTheme.typography.bodySmall) }
                    }
                }
                "category" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    item { Text("إضافة مستوى تصنيف جديد — اترك التصنيف الأب فارغًا لإنشاء جذر.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(categoryCode,{categoryCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف")},singleLine=true) }
                    item { OutlinedTextField(parentCode,{parentCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف الأب (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(categoryNameAr,{categoryNameAr=it},Modifier.fillMaxWidth(),label={Text("الاسم بالعربية")},singleLine=true) }
                    item { OutlinedTextField(categoryNameEn,{categoryNameEn=it},Modifier.fillMaxWidth(),label={Text("Name in English")},singleLine=true) }
                    item { OutlinedTextField(categoryDescriptionAr,{categoryDescriptionAr=it},Modifier.fillMaxWidth(),label={Text("الوصف العربي")}) }
                    item { OutlinedTextField(categoryDescriptionEn,{categoryDescriptionEn=it},Modifier.fillMaxWidth(),label={Text("English description")}) }
                    item { OutlinedTextField(categoryTranslations,{categoryTranslations=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("ترجمات إضافية: locale|field|value")}) }
                    item { Button(onClick={createCategory()},enabled=!busy && categoryCode.isNotBlank() && categoryNameAr.isNotBlank() && categoryNameEn.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ التصنيف") } }
                    item { OutlinedButton(onClick={loadDirectories()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحميل التصنيفات والوحدات") } }
                    items(categories,key={it.code}) { cat -> Column(verticalArrangement=Arrangement.spacedBy(2.dp)) { Text("${cat.code}  •  ${cat.localizedName.ifBlank { cat.nameAr }} / ${cat.nameEn}${if(cat.parentCode.isNotBlank()) "  ←  ${cat.parentCode}" else "  •  جذر"}",style=MaterialTheme.typography.bodySmall); val desc=cat.localizedDescription.ifBlank { if(locale.startsWith("en")) cat.descriptionEn else cat.descriptionAr }; if(desc.isNotBlank()) Text(desc,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) } }
                }
                "unit" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    item { Text("إنشاء وحدة خاصة بالمؤسسة. رمز الوحدة سيُسبق تلقائيًا برمز المؤسسة لمنع تعارض الرموز عالميًا.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(unitShortCode,{unitShortCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("الرمز المختصر")},singleLine=true) }
                    item { OutlinedTextField(unitNameAr,{unitNameAr=it},Modifier.fillMaxWidth(),label={Text("اسم الوحدة بالعربية")},singleLine=true) }
                    item { OutlinedTextField(unitNameEn,{unitNameEn=it},Modifier.fillMaxWidth(),label={Text("Unit name in English")},singleLine=true) }
                    item { OutlinedTextField(unitDimension,{unitDimension=it.lowercase()},Modifier.fillMaxWidth(),label={Text("البعد (مثل count, mass, length, volume, area)")},singleLine=true) }
                    item {
                        val canonicalUnits=directoryUnits.filter { it.baseCode.isBlank() }
                        if(canonicalUnits.isEmpty()) OutlinedTextField(unitBaseCode,{unitBaseCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("رمز وحدة الأساس (حمّل الأدلة أولًا)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=canonicalUnits.firstOrNull{it.code==unitBaseCode}?.let{"${it.code} — ${it.localizedName.ifBlank { it.nameAr }}"} ?: unitBaseCode,
                                    onValueChange={},readOnly=true,label={Text("وحدة الأساس القياسية")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    canonicalUnits.forEach { unit -> DropdownMenuItem(text={Text("${unit.code} — ${unit.localizedName.ifBlank { unit.nameAr }} (${unit.dimension})")},onClick={unitBaseCode=unit.code;unitDimension=unit.dimension;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(unitFactor,{unitFactor=it},Modifier.fillMaxWidth(),label={Text("كم تساوي وحدة الأساس؟")},singleLine=true) }
                    item { OutlinedTextField(unitDecimals,{unitDecimals=it.filter(Char::isDigit)},Modifier.fillMaxWidth(),label={Text("عدد المنازل العشرية (0–6)")},singleLine=true) }
                    item { OutlinedTextField(unitTranslations,{unitTranslations=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("ترجمات إضافية: locale|field|value")}) }
                    item { Button(onClick={createUnit()},enabled=!busy && unitShortCode.isNotBlank() && unitNameAr.isNotBlank() && unitNameEn.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ وحدة القياس") } }
                    item { OutlinedButton(onClick={loadDirectories()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحديث دليل الوحدات") } }
                    items(directoryUnits,key={it.code}) { u -> Text("${u.code} • ${u.nameAr} / ${u.nameEn} • ${u.dimension} • ${u.factor} ${u.baseCode}${if(u.owner.isNotBlank()) " • وحدة مؤسسة" else ""}",style=MaterialTheme.typography.bodySmall) }
                }
                "product" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    item { Text("بيانات الصنف الأساسية",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(nameAr,{nameAr=it},Modifier.fillMaxWidth(),label={Text("اسم الصنف بالعربية *")},singleLine=true) }
                    item { OutlinedTextField(nameEn,{nameEn=it},Modifier.fillMaxWidth(),label={Text("Product name in English *")},singleLine=true) }
                    item {
                        if(categories.isEmpty()) OutlinedTextField(productCategory,{productCategory=it.uppercase()},Modifier.fillMaxWidth(),label={Text("كود التصنيف * (حدّث الأدلة أولًا للاختيار)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=categories.firstOrNull{it.code==productCategory}?.let{"${it.code} — ${it.nameAr}"} ?: productCategory,
                                    onValueChange={},readOnly=true,label={Text("التصنيف *")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    categories.forEach { cat -> DropdownMenuItem(text={Text("${cat.code} — ${cat.localizedName.ifBlank { cat.nameAr }}")},onClick={productCategory=cat.code;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(itemType,{itemType=it},Modifier.fillMaxWidth(),label={Text("نوع العنصر: product/service/digital/material/asset")},singleLine=true) }
                    item { OutlinedTextField(descriptionAr,{descriptionAr=it},Modifier.fillMaxWidth(),label={Text("الوصف العربي")}) }
                    item { OutlinedTextField(descriptionEn,{descriptionEn=it},Modifier.fillMaxWidth(),label={Text("English description")}) }
                    item { OutlinedTextField(extraTranslations,{extraTranslations=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("ترجمات إضافية لكل لغة — سطر لكل ترجمة: locale|field|value")},placeholder={Text("fr|name|Nom du produit\nfr|description|Description du produit")}) }
                    item { Text("الباركود والوحدات والعبوات",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item {
                        if(directoryUnits.isEmpty()) OutlinedTextField(unitCode,{unitCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة الأساس * (حدّث الأدلة أولًا للاختيار)")},singleLine=true)
                        else {
                            var expanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded=expanded,onExpandedChange={expanded=it}) {
                                OutlinedTextField(value=directoryUnits.firstOrNull{it.code==unitCode}?.let{"${it.code} — ${it.nameAr}"} ?: unitCode,
                                    onValueChange={},readOnly=true,label={Text("وحدة الأساس *")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(expanded=expanded)},
                                    modifier=Modifier.fillMaxWidth().menuAnchor())
                                ExposedDropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                                    directoryUnits.forEach { unit -> DropdownMenuItem(text={Text("${unit.code} — ${unit.nameAr} (${unit.dimension})")},onClick={unitCode=unit.code;expanded=false}) }
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(barcode,{barcode=it.filter(Char::isDigit).take(14)},Modifier.fillMaxWidth(),label={Text("GTIN / الباركود الأساسي (8–14 رقمًا)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitCode,{extraUnitCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة عبوة إضافية (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitQty,{extraUnitQty=it},Modifier.fillMaxWidth(),label={Text("كم وحدة أساس داخل العبوة")},singleLine=true) }
                    item { OutlinedTextField(extraUnitPackage,{extraUnitPackage=it},Modifier.fillMaxWidth(),label={Text("اسم العبوة (كرتون/علبة/شريط)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitBarcode,{extraUnitBarcode=it},Modifier.fillMaxWidth(),label={Text("باركود العبوة الإضافية")},singleLine=true) }
                    item { OutlinedTextField(extraUnitSku,{extraUnitSku=it},Modifier.fillMaxWidth(),label={Text("SKU للعبوة الإضافية")},singleLine=true) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(extraUnitNetQuantity,{extraUnitNetQuantity=it},Modifier.weight(1f),label={Text("الكمية الصافية")},singleLine=true); OutlinedTextField(extraUnitNetUnitCode,{extraUnitNetUnitCode=it.uppercase()},Modifier.weight(1f),label={Text("وحدة الصافي")},singleLine=true) } }
                    item { OutlinedTextField(extraUnitImageUrl,{extraUnitImageUrl=it},Modifier.fillMaxWidth(),label={Text("رابط صورة العبوة HTTPS (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(extraUnitsText,{extraUnitsText=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("عبوات إضافية متعددة — سطر: unit_code|role|qty_in_base|barcode|sku|package_name|net_quantity|net_unit_code|image_url")}) }
                    item { Text("العلامة والمصنع والمواصفات",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(brand,{brand=it},Modifier.fillMaxWidth(),label={Text("العلامة التجارية")},singleLine=true) }
                    item { OutlinedTextField(manufacturer,{manufacturer=it},Modifier.fillMaxWidth(),label={Text("الشركة المصنعة")},singleLine=true) }
                    item { OutlinedTextField(origin,{origin=it},Modifier.fillMaxWidth(),label={Text("بلد الصناعة")},singleLine=true) }
                    item { OutlinedTextField(model,{model=it},Modifier.fillMaxWidth(),label={Text("رقم الطراز")},singleLine=true) }
                    item { OutlinedTextField(partNumber,{partNumber=it},Modifier.fillMaxWidth(),label={Text("رقم القطعة")},singleLine=true) }
                    item { OutlinedTextField(imageUrl,{imageUrl=it},Modifier.fillMaxWidth(),label={Text("رابط صورة HTTPS خارجي")},singleLine=true) }
                    item { OutlinedTextField(imageAlt,{imageAlt=it},Modifier.fillMaxWidth(),label={Text("وصف الصورة")},singleLine=true) }
                    item { OutlinedTextField(ingredients,{ingredients=it},Modifier.fillMaxWidth(),label={Text("المكونات مفصولة بفواصل")}) }
                    item { OutlinedTextField(materialComposition,{materialComposition=it},Modifier.fillMaxWidth(),label={Text("تركيب المواد مفصول بفواصل")}) }
                    item { OutlinedTextField(recipe,{recipe=it},Modifier.fillMaxWidth(),label={Text("الوصفة / طريقة التحضير")}) }
                    item { OutlinedTextField(recipeIngredientsText,{recipeIngredientsText=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("مكونات الوصفة — سطر: name|quantity|unit_code|note")}) }
                    item { OutlinedTextField(recipeYieldQty,{recipeYieldQty=it},Modifier.fillMaxWidth(),label={Text("كمية الناتج من الوصفة")},singleLine=true) }
                    item { OutlinedTextField(recipeYieldUnit,{recipeYieldUnit=it.uppercase()},Modifier.fillMaxWidth(),label={Text("وحدة الناتج")},singleLine=true) }
                    item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedTextField(recipePrepMinutes,{recipePrepMinutes=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("دقائق التحضير")},singleLine=true); OutlinedTextField(recipeCookMinutes,{recipeCookMinutes=it.filter(Char::isDigit)},Modifier.weight(1f),label={Text("دقائق الطبخ")},singleLine=true) } }
                    item { OutlinedTextField(recipeAllergenNotes,{recipeAllergenNotes=it},Modifier.fillMaxWidth(),label={Text("ملاحظات الحساسية للوصفة")}) }
                    item { OutlinedTextField(recipeSource,{recipeSource=it},Modifier.fillMaxWidth(),label={Text("مصدر الوصفة / المرجع")},singleLine=true) }
                    item { OutlinedTextField(specifications,{specifications=it},Modifier.fillMaxWidth(),label={Text("المواصفات الإضافية")}) }
                    item { OutlinedTextField(customAttributesJson,{customAttributesJson=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("حقول مخصصة إضافية (JSON object)")},placeholder={Text("{\"color\":\"black\",\"model\":\"X1\"}")}) }
                    item { OutlinedTextField(allergens,{allergens=it},Modifier.fillMaxWidth(),label={Text("مسببات الحساسية مفصولة بفواصل")}) }
                    item { OutlinedTextField(storage,{storage=it},Modifier.fillMaxWidth(),label={Text("تعليمات التخزين")}) }
                    item { OutlinedTextField(usage,{usage=it},Modifier.fillMaxWidth(),label={Text("تعليمات الاستخدام")}) }
                    item { OutlinedTextField(warnings,{warnings=it},Modifier.fillMaxWidth(),label={Text("التحذيرات")}) }
                    item { OutlinedTextField(shelfLife,{shelfLife=it.filter(Char::isDigit).take(6)},Modifier.fillMaxWidth(),label={Text("مدة الصلاحية بالأيام")},singleLine=true) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(expiryRequired,{expiryRequired=it}); Text("يتطلب تاريخ صلاحية") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(batchTracked,{batchTracked=it}); Text("تتبع رقم التشغيلة") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(serialTracked,{serialTracked=it}); Text("تتبع الرقم التسلسلي") } }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(prescriptionOnly,{prescriptionOnly=it}); Text("يتطلب وصفة طبية (بعد التحقق)") } }
                    item { OutlinedTextField(currencyCode,{currencyCode=it.uppercase()},Modifier.fillMaxWidth(),label={Text("رمز العملة لحساب المؤسسة")},singleLine=true) }
                    item { Text("القطاعات ومستويات النشاط والمناطق",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(sectors,{sectors=it},Modifier.fillMaxWidth(),label={Text("القطاعات مفصولة بفواصل (فارغ = عام)")}) }
                    item { OutlinedTextField(levels,{levels=it},Modifier.fillMaxWidth(),label={Text("مستويات النشاط مفصولة بفواصل (فارغ = عام)")}) }
                    item { OutlinedTextField(regionCountry,{regionCountry=it.uppercase()},Modifier.fillMaxWidth(),label={Text("دولة الإظهار (اختياري)")},singleLine=true) }
                    item { OutlinedTextField(regionCode,{regionCode=it},Modifier.fillMaxWidth(),label={Text("رمز المنطقة / المحافظة")},singleLine=true) }
                    item { OutlinedTextField(regionCity,{regionCity=it},Modifier.fillMaxWidth(),label={Text("المدينة")},singleLine=true) }
                    item { Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(regionExcluded,{regionExcluded=it}); Text("استثناء هذه المنطقة بدل تضمينها") } }
                    item { OutlinedTextField(extraRegionRules,{extraRegionRules=it},Modifier.fillMaxWidth().heightIn(min=80.dp),label={Text("مناطق إضافية متعددة — سطر: country|region|city|include أو exclude")}) }
                    item { Text("الحكم الديني / الطبي / التنظيمي (اختياري، لا يُعد فتوى أو تشخيصًا)",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold) }
                    item { OutlinedTextField(rulingDomain,{rulingDomain=it},Modifier.fillMaxWidth(),label={Text("النطاق: religious / medical / regulatory / dietary")},singleLine=true) }
                    item { OutlinedTextField(authority,{authority=it},Modifier.fillMaxWidth(),label={Text("رمز الجهة أو المرجع (مطلوب لتسجيل الحكم)")},singleLine=true) }
                    item { OutlinedTextField(religion,{religion=it},Modifier.fillMaxWidth(),label={Text("رمز الدين عند الحكم الديني")},singleLine=true) }
                    item { OutlinedTextField(jurisdiction,{jurisdiction=it},Modifier.fillMaxWidth(),label={Text("الدولة / الاختصاص القضائي")},singleLine=true) }
                    item { OutlinedTextField(rulingOutcome,{rulingOutcome=it},Modifier.fillMaxWidth(),label={Text("النتيجة: permitted/prohibited/conditional/unknown/...")},singleLine=true) }
                    item { OutlinedTextField(rulingRationale,{rulingRationale=it},Modifier.fillMaxWidth(),label={Text("التعليل / الشروط")}) }
                    item { OutlinedTextField(rulingSourceTitle,{rulingSourceTitle=it},Modifier.fillMaxWidth(),label={Text("عنوان المصدر")},singleLine=true) }
                    item { OutlinedTextField(rulingSourceUrl,{rulingSourceUrl=it},Modifier.fillMaxWidth(),label={Text("رابط المصدر HTTPS")},singleLine=true) }
                    item { OutlinedTextField(rulingConfidence,{rulingConfidence=it},Modifier.fillMaxWidth(),label={Text("الحالة الأولية: unverified أو reported؛ المراجعة لها إجراء منفصل")},singleLine=true) }
                    item { OutlinedTextField(extraRulingsText,{extraRulingsText=it},Modifier.fillMaxWidth().heightIn(min=90.dp),label={Text("أحكام إضافية متعددة — domain|authority|religion|jurisdiction|outcome|source_title|source_url|confidence|rationale")}) }
                    item { Button(onClick={createProduct()},enabled=!busy && nameAr.isNotBlank() && nameEn.isNotBlank() && productCategory.isNotBlank() && unitCode.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("حفظ الصنف وكل تفاصيله") } }
                    item { Text("يُحفظ سجل الصنف والوحدات والاستهداف والأحكام في معاملة واحدة. لا يُعتمد أي حكم طبي أو ديني تلقائيًا؛ ارفع الثقة بعد مراجعة مصدر موثوق.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "transfer" -> LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                    item { Text("النقل متاح فقط إذا كنت مالكًا أو مديرًا في الحساب المصدر والوجهة. اترك قائمة UUID فارغة لنقل كل روابط الدليل.",style=MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(transferSource,{transferSource=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف الحساب المصدر UUID")},singleLine=true) }
                    item { OutlinedTextField(transferDestination,{transferDestination=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف الحساب الوجهة UUID")},singleLine=true) }
                    item { OutlinedTextField(transferIds,{transferIds=it},Modifier.fillMaxWidth(),label={Text("معرّفات الأصناف UUID مفصولة بفواصل (اختياري)")}) }
                    item { Button(onClick={transferCatalog()},enabled=!busy && transferSource.isNotBlank() && transferDestination.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("إرسال طلب نقل الدليل") } }
                    item { OutlinedButton(onClick={loadPendingTransfers()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("تحميل طلبات النقل الواردة لهذا الحساب") } }
                    items(pendingTransfers,key={it.id}) { transfer ->
                        Card(border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                Text("طلب نقل • ${transfer.itemCount} صنف",fontWeight=FontWeight.SemiBold)
                                Text("المصدر: ${transfer.source}",style=MaterialTheme.typography.bodySmall)
                                Text("رقم الطلب: ${transfer.id}",style=MaterialTheme.typography.bodySmall)
                                Text("تاريخ الطلب: ${transfer.createdAt}",style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={respondToTransfer(transfer.id,true)},enabled=!busy,modifier=Modifier.weight(1f)) { Text("قبول النقل") }
                                    OutlinedButton(onClick={respondToTransfer(transfer.id,false)},enabled=!busy,modifier=Modifier.weight(1f)) { Text("رفض") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editingProduct?.let { item ->
        CatalogProductEditDialog(item=item,busy=busy,onDismiss={editingProduct=null},onSave={payload -> updateProductDetails(item,payload)})
    }
}
