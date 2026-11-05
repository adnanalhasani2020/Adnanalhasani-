package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.Executors

internal data class CommercialCloudContext(
    val organizationId: String,
    val activityId: String,
    val locationId: String,
    val currencyCode: String
)

internal data class CommercialCloudInventory(
    val context: CommercialCloudContext,
    val accessToken: String,
    val products: List<Product>
)

private val commercialCloudIo = Executors.newSingleThreadExecutor()
private val commercialCloudMain = Handler(Looper.getMainLooper())

internal fun loadCommercialCloudInventory(context: Context): CommercialCloudInventory {
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = savedContext.getString("organization_id", null).orEmpty()
    val activityId = savedContext.getString("activity_id", null).orEmpty()
    val locationId = savedContext.getString("location_id", null).orEmpty()
    val currency = savedContext.getString("currency_code", "YER").orEmpty()
    require(orgId.isNotBlank() && activityId.isNotBlank() && locationId.isNotBlank()) {
        "أنشئ ملف النشاط من قسم إعداد النشاط أولًا لربط المخزون بالمؤسسة والموقع."
    }

    val sessionPrefs = commercialCloudSessionPreferences(context)
    val savedRefresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(savedRefresh.isNotBlank()) { "سجّل الدخول إلى حساب Supabase من إعداد النشاط أولًا." }

    val session = JSONObject(SupabaseClient.request(
        "/auth/v1/token?grant_type=refresh_token", "POST",
        JSONObject().put("refresh_token", savedRefresh)
    ))
    val access = session.optString("access_token")
    val rotatedRefresh = session.optString("refresh_token").ifBlank { savedRefresh }
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", rotatedRefresh).apply()

    val query = "/rest/v1/commercial_inventory_balances" +
        "?select=offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,offering:commercial_offerings!inner(id,price_minor,currency_code,activity_id,product:commercial_products!inner(name,description,unit_name,unit_code,state),categories:commercial_offering_categories(is_primary,category:commercial_product_categories(name_ar,name_en)),sell_units:commercial_offering_sell_units(unit_code,display_name,quantity_in_stock,is_base,is_active))" +
        "&organization_id=eq.$orgId&location_id=eq.$locationId&offering.activity_id=eq.$activityId&order=updated_at.desc,offering_id.asc"
    val rows = commercialCloudPagedGet(query, access)
    val products = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val offering = row.optJSONObject("offering") ?: continue
            val product = offering.optJSONObject("product") ?: continue
            if (product.optString("state") != "active") continue
            val categoryRows = offering.optJSONArray("categories")
            val categoryName = (0 until (categoryRows?.length() ?: 0))
                .asSequence()
                .mapNotNull { categoryRows?.optJSONObject(it) }
                .sortedByDescending { it.optBoolean("is_primary", false) }
                .mapNotNull { it.optJSONObject("category") }
                .map { category -> category.optString("name_ar").takeUnless { it == "null" }.orEmpty().ifBlank { category.optString("name_en").takeUnless { it == "null" }.orEmpty() } }
                .firstOrNull { it.isNotBlank() && it != "null" }
                ?: "غير مصنف"
            // Parse PostgreSQL NUMERIC as decimal text and reject fractional stock before any legacy integer projection.
            // Cloud sale validation and display use stockPrecise only after the whole-quantity invariant is verified.
            val availableQuantity = runCatching {
                val onHand = BigDecimal(row.optString("quantity_on_hand", "0"))
                val reserved = BigDecimal(row.optString("quantity_reserved", "0"))
                require(onHand.stripTrailingZeros().scale() <= 0 && reserved.stripTrailingZeros().scale() <= 0) {
                    "رصيد المخزون السحابي يحتوي على كمية كسرية؛ أوقفنا العرض والبيع لحماية الرصيد."
                }
                require(onHand.signum() >= 0 && reserved.signum() >= 0 && reserved <= onHand) {
                    "رصيد المخزون السحابي غير متسق؛ أوقفنا العرض والبيع."
                }
                onHand.subtract(reserved)
            }.getOrElse { throw IllegalStateException(it.message ?: "تعذر التحقق من رصيد المخزون السحابي.") }
            val quantity = availableQuantity.min(BigDecimal.valueOf(Int.MAX_VALUE.toLong())).toInt()
            val reorderValue = BigDecimal(row.optString("reorder_point", "0"))
            require(reorderValue.stripTrailingZeros().scale() <= 0 && reorderValue.signum() >= 0) {
                "حد إعادة الطلب السحابي يجب أن يكون عددًا صحيحًا غير سالب."
            }
            val reorderPoint = reorderValue.min(BigDecimal.valueOf(Int.MAX_VALUE.toLong())).toInt()
            val price = offering.optLong("price_minor", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val sellUnits = offering.optJSONArray("sell_units") ?: JSONArray()
            val sellUnitsJson = JSONArray()
            var baseUnitCode = product.optString("unit_code").takeUnless { it.isBlank() || it == "null" } ?: "each"
            for (unitIndex in 0 until sellUnits.length()) {
                val unit = sellUnits.optJSONObject(unitIndex) ?: continue
                if (!unit.optBoolean("is_active", false)) continue
                if (unit.optBoolean("is_base", false)) baseUnitCode = unit.optString("unit_code", baseUnitCode)
                sellUnitsJson.put(JSONObject()
                    .put("unitCode", unit.optString("unit_code"))
                    .put("unitName", unit.optString("display_name"))
                    .put("quantityInBase", unit.optLong("quantity_in_stock", 1L))
                    .put("isDefault", unit.optBoolean("is_base", false)))
            }
            if (sellUnitsJson.length() == 0) {
                sellUnitsJson.put(JSONObject().put("unitCode", baseUnitCode)
                    .put("unitName", product.optString("unit_name", "حبة"))
                    .put("quantityInBase", 1).put("isDefault", true))
            }
            add(Product(
                name = product.optString("name"),
                category = categoryName,
                description = product.optString("description"),
                stock = quantity,
                price = String.format(Locale.US, "%,d ر.ي", price),
                cloudOfferingId = offering.optString("id"),
                cloudPriceMinor = offering.optLong("price_minor", 0L).coerceAtLeast(0L),
                unitCode = baseUnitCode,
                unitVariantsJson = sellUnitsJson.toString(),
                createdBy = orgId,
                reorderPoint = reorderPoint,
                stockPrecise = availableQuantity
            ))
        }
    }
    return CommercialCloudInventory(
        CommercialCloudContext(orgId, activityId, locationId, currency),
        access,
        products
    )
}

internal fun configureCommercialCloudSellUnit(
    context: Context,
    offeringId: String,
    unitCode: String,
    displayName: String,
    quantityInStock: Long
): JSONObject {
    require(offeringId.isNotBlank() && unitCode.isNotBlank() && displayName.isNotBlank() && quantityInStock in 1L..1_000_000L) {
        "أدخل رمز وحدة واسمًا ومعامل تحويل صحيحًا."
    }
    val (_, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_configure_offering_sell_unit", "POST",
        JSONObject().put("p_offering_id", offeringId)
            .put("p_unit_code", unitCode.trim())
            .put("p_display_name", displayName.trim())
            .put("p_quantity_in_stock", quantityInStock)
            .put("p_is_base", false),
        access
    ))
}

internal fun findCommercialCloudCatalogOffering(context: Context, globalItemId: String, locationId: String): String? {
    require(globalItemId.isNotBlank() && locationId.isNotBlank()) { "معرّف الصنف أو الفرع غير مكتمل." }
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = saved.getString("organization_id", null).orEmpty()
    require(orgId.isNotBlank()) { "معرّف المؤسسة غير محفوظ." }
    val prefs = commercialCloudSessionPreferences(context)
    val refresh = prefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى حساب Supabase أولًا." }
    val session = JSONObject(SupabaseClient.request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh)))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    prefs.edit().putString("refresh_token",session.optString("refresh_token").ifBlank { refresh }).apply()
    val rows = JSONArray(SupabaseClient.request("/rest/v1/commercial_catalog_item_offerings?select=offering_id&organization_id=eq.$orgId&global_item_id=eq.$globalItemId&location_id=eq.$locationId&limit=1","GET",token=access))
    return rows.optJSONObject(0)?.optString("offering_id")?.takeIf { it.isNotBlank() && it!="null" }
}

internal fun createCommercialCloudCatalogInventoryItem(
    context: Context, globalItemId: String, activityNodeCode: String, locationId: String, warehouseId: String,
    baseUnitCode: String, saleUnitCode: String, unitCode: String, displayName: String, priceMinor: Long,
    initialQuantity: Int, reorderPoint: Double, categoryId: String, idempotencyKey: String
): String {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = saved.getString("organization_id", null).orEmpty()
    val activityId = saved.getString("activity_id", null).orEmpty()
    val currency = saved.getString("currency_code", "YER").orEmpty().uppercase(Locale.ROOT)
    require(orgId.isNotBlank() && activityId.isNotBlank()) { "بيانات المؤسسة والنشاط غير محفوظة." }
    require(globalItemId.isNotBlank() && activityNodeCode.isNotBlank() && locationId.isNotBlank() && warehouseId.isNotBlank()) { "اختر النشاط والفرع والمستودع." }
    require(baseUnitCode.isNotBlank() && saleUnitCode.isNotBlank() && unitCode.isNotBlank()) { "لا توجد وحدات موثوقة مكتملة لهذا الصنف." }
    require(priceMinor > 0 && initialQuantity >= 0) { "أدخل سعر بيع أكبر من صفر وكمية افتتاحية صحيحة." }
    require(idempotencyKey.length in 8..200) { "مفتاح العملية غير صالح." }
    val prefs = commercialCloudSessionPreferences(context)
    val refresh = prefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى حساب Supabase أولًا." }
    val session = JSONObject(SupabaseClient.request("/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh)))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    prefs.edit().putString("refresh_token",session.optString("refresh_token").ifBlank { refresh }).apply()
    val payload = JSONObject().put("p_organization_id",orgId).put("p_activity_id",activityId).put("p_activity_node_code",activityNodeCode)
        .put("p_location_id",locationId).put("p_warehouse_id",warehouseId).put("p_global_item_id",globalItemId)
        .put("p_base_unit_code",baseUnitCode).put("p_sale_unit_code",saleUnitCode).put("p_unit_code",unitCode)
        .put("p_display_name",displayName.trim()).put("p_price_minor",priceMinor).put("p_currency_code",currency)
        .put("p_initial_quantity",initialQuantity).put("p_reorder_point",reorderPoint.coerceAtLeast(0.0))
        .put("p_category_id",if(categoryId.isNotBlank()) categoryId else JSONObject.NULL).put("p_idempotency_key",idempotencyKey)
    return SupabaseClient.request("/rest/v1/rpc/commercial_catalog_create_inventory_item_from_global","POST",payload,access)
}
internal fun createCommercialCloudInventoryItem(
    context: Context,
    name: String,
    unitName: String,
    priceMinor: Long,
    initialQuantity: Int,
    reorderPoint: Double = 0.0,
    categoryId: String = "",
    unitCode: String = "each"
): String {
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = savedContext.getString("organization_id", null).orEmpty()
    val activityId = savedContext.getString("activity_id", null).orEmpty()
    val locationId = savedContext.getString("location_id", null).orEmpty()
    val currency = savedContext.getString("currency_code", "YER").orEmpty()
    require(orgId.isNotBlank() && activityId.isNotBlank() && locationId.isNotBlank()) {
        "بيانات المؤسسة والنشاط والموقع غير محفوظة."
    }
    val sessionPrefs = commercialCloudSessionPreferences(context)
    val savedRefresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(savedRefresh.isNotBlank()) { "سجّل الدخول إلى حساب Supabase أولًا." }
    val session = JSONObject(SupabaseClient.request(
        "/auth/v1/token?grant_type=refresh_token", "POST",
        JSONObject().put("refresh_token", savedRefresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { savedRefresh }).apply()
    val payload = JSONObject()
        .put("p_organization_id", orgId)
        .put("p_activity_id", activityId)
        .put("p_location_id", locationId)
        .put("p_name", name.trim())
        .put("p_unit_name", unitName.trim())
        .put("p_price_minor", priceMinor)
        .put("p_currency_code", currency)
        .put("p_initial_quantity", initialQuantity)
        .put("p_reorder_point", reorderPoint.coerceAtLeast(0.0))
        .put("p_category_id", if (categoryId.isNotBlank()) categoryId else JSONObject.NULL)
        .put("p_unit_code", unitCode.ifBlank { "each" })
    return SupabaseClient.request("/rest/v1/rpc/commercial_create_inventory_item_with_category_and_unit", "POST", payload, access)
}
