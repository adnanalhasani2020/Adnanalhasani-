package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
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
            // Preserve the server's numeric quantity before any legacy integer projection.
            // Cloud sale validation and display must use stockPrecise, never the truncated stock field.
            // Parse PostgreSQL NUMERIC as decimal text so fractional stock is not rounded through Double.
            val availableQuantity = runCatching {
                BigDecimal(row.optString("quantity_on_hand", "0"))
                    .subtract(BigDecimal(row.optString("quantity_reserved", "0")))
                    .max(BigDecimal.ZERO)
            }.getOrDefault(BigDecimal.ZERO)
            val quantity = availableQuantity.toInt().coerceAtLeast(0)
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
                reorderPoint = row.optDouble("reorder_point", 0.0).toInt().coerceAtLeast(0),
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



internal data class CommercialCloudCustomer(
    val id: String,
    val displayName: String,
    val phone: String,
    val email: String
) {
    val label: String get() = buildList {
        add(displayName)
        if (phone.isNotBlank()) add(phone)
        if (email.isNotBlank()) add(email)
    }.joinToString(" · ")
}

internal fun loadCommercialCloudCustomers(context: Context): List<CommercialCloudCustomer> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_customers" +
        "?select=id,display_name,phone,email,state&organization_id=eq.$orgId&state=eq.active&order=display_name.asc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val name = row.optString("display_name").takeUnless { it == "null" }.orEmpty().trim()
            if (id.isBlank() || name.isBlank()) continue
            add(CommercialCloudCustomer(id, name,
                row.optString("phone").takeUnless { it == "null" }.orEmpty(),
                row.optString("email").takeUnless { it == "null" }.orEmpty()))
        }
    }
}

internal fun commercialStableIdempotencyKey(prefix: String, parts: List<String>): String {
    val canonical = parts.joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
    return prefix + "-" + digest
}

internal data class CommercialCloudStockMovement(
    val id: String,
    val offeringId: String,
    val productName: String,
    val unitName: String,
    val movementType: String,
    val quantityDelta: java.math.BigDecimal,
    val sourceReference: String,
    val saleId: String,
    val saleReceiptNumber: String,
    val saleStatus: String,
    val createdBy: String,
    val createdAt: String
)

/** Actual inventory movement ledger, restricted to the saved activity and its organization. */
internal fun loadCommercialCloudStockMovements(context: Context): List<CommercialCloudStockMovement> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_stock_movements" +
        "?select=id,sale_id,created_at,created_by,source_ref,offering_id,movement_type,quantity_delta,offering:commercial_offerings!inner(id,activity_id,product:commercial_products(name,unit_name),activity:commercial_activities!inner(organization_id)),sale:commercial_sales(receipt_number,status)" +
        "&offering.activity_id=eq.$activityId&offering.activity.organization_id=eq.$orgId&order=created_at.asc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val offering = row.optJSONObject("offering") ?: continue
            val activity = offering.optJSONObject("activity") ?: continue
            if (activity.optString("organization_id") != orgId || offering.optString("activity_id") != activityId) continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val offeringId = row.optString("offering_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val quantity = runCatching { java.math.BigDecimal(row.optString("quantity_delta")) }.getOrNull() ?: continue
            if (id.isBlank() || offeringId.isBlank() || quantity.signum() == 0) continue
            val product = offering.optJSONObject("product")
            val sale = row.optJSONObject("sale")
            add(CommercialCloudStockMovement(
                id = id, offeringId = offeringId,
                productName = product?.optString("name").takeUnless { it == null || it == "null" }.orEmpty().ifBlank { "صنف غير معروف" },
                unitName = product?.optString("unit_name").takeUnless { it == null || it == "null" }.orEmpty(),
                movementType = row.optString("movement_type").ifBlank { "غير محدد" }, quantityDelta = quantity,
                sourceReference = row.optString("source_ref").takeUnless { it == "null" }.orEmpty(),
                saleId = row.optString("sale_id").takeUnless { it == "null" }.orEmpty(),
                saleReceiptNumber = sale?.optString("receipt_number").takeUnless { it == null || it == "null" }.orEmpty(),
                saleStatus = sale?.optString("status").takeUnless { it == null || it == "null" }.orEmpty(),
                createdBy = row.optString("created_by").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
}
internal data class CommercialMasterDataRow(
    val id: String,
    val code: String,
    val label: String,
    val kind: String,
    val parentId: String = "",
    val warehouseId: String = "",
    val state: String = ""
)

internal data class CommercialUnitOption(
    val code: String,
    val label: String,
    val dimension: String,
    val decimalPlaces: Int
)

internal fun loadCommercialUnitsOfMeasure(context: Context): List<CommercialUnitOption> {
    val (_, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_unit_of_measure?select=code,display_name,dimension,decimal_places&is_active=eq.true&order=dimension.asc,code.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val code = row.optString("code").trim()
            val label = row.optString("display_name").trim()
            if (code.isNotBlank() && label.isNotBlank()) add(
                CommercialUnitOption(code, label, row.optString("dimension"), row.optInt("decimal_places", 0).coerceIn(0, 6))
            )
        }
    }
}

/** Fetch a complete PostgREST result in bounded pages with stable caller ordering. */
internal fun commercialCloudPagedGet(path: String, accessToken: String, pageSize: Int = 500): JSONArray {
    require(pageSize in 1..1000) { "حجم الصفحة غير صالح." }
    require(path.contains("?") && !path.contains("limit=") && !path.contains("offset=")) { "استعلام الصفحات يحتوي على محددات مسبقة." }
    val rows = JSONArray()
    var offset = 0
    while (true) {
        val page = JSONArray(SupabaseClient.request("$path&limit=$pageSize&offset=$offset", "GET", token = accessToken))
        for (index in 0 until page.length()) rows.put(page.getJSONObject(index))
        if (page.length() < pageSize) break
        offset += pageSize
    }
    return rows
}

internal fun commercialMasterDataAccess(context: Context): Pair<String, String> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = saved.getString("organization_id", null).orEmpty()
    require(orgId.isNotBlank()) { "ملف المؤسسة غير محفوظ." }
    val sessionPrefs = commercialCloudSessionPreferences(context)
    val refresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى الحساب أولًا." }
    val session = JSONObject(SupabaseClient.request(
        "/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { refresh }).apply()
    return orgId to access
}

internal fun loadCommercialMasterData(context: Context, table: String): List<CommercialMasterDataRow> {
    require(table in setOf("commercial_warehouses", "commercial_product_categories", "commercial_departments", "commercial_storage_locations")) {
        "نوع الدليل غير مدعوم."
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    val fields = when (table) {
        "commercial_warehouses" -> "id,code,name,warehouse_type,state"
        "commercial_storage_locations" -> "id,warehouse_id,parent_location_id,code,name,location_type,state"
        else -> "id,code,name_ar,name_en,state,parent_id"
    }
    val rows = commercialCloudPagedGet(
        "/rest/v1/$table?select=$fields&organization_id=eq.$orgId&order=code.asc,id.asc", access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            add(CommercialMasterDataRow(
                id = row.optString("id"),
                code = row.optString("code"),
                label = row.optString("name").takeUnless { it == "null" }.orEmpty().ifBlank { row.optString("name_ar").takeUnless { it == "null" }.orEmpty() }.ifBlank { row.optString("name_en").takeUnless { it == "null" }.orEmpty() },
                kind = row.optString(when (table) { "commercial_storage_locations" -> "location_type"; "commercial_warehouses" -> "warehouse_type"; else -> "state" }).ifBlank { table.removePrefix("commercial_") },
                parentId = row.optString(if (table == "commercial_storage_locations") "parent_location_id" else "parent_id").takeUnless { it == "null" }.orEmpty(),
                warehouseId = row.optString("warehouse_id").takeUnless { it == "null" }.orEmpty(),
                state = row.optString("state").ifBlank { "active" }
            ))
        }
    }
}

internal fun assignCommercialCloudOfferingCategory(
    context: Context,
    offeringId: String,
    categoryId: String
) {
    require(offeringId.isNotBlank() && categoryId.isNotBlank()) { "اختر الصنف والتصنيف." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "النشاط التجاري غير محفوظ." }
    val payload = JSONObject()
        .put("p_organization_id", orgId)
        .put("p_activity_id", activityId)
        .put("p_offering_id", offeringId)
        .put("p_category_id", categoryId)
    SupabaseClient.request("/rest/v1/rpc/commercial_assign_offering_category", "POST", payload, access)
}

internal fun createCommercialMasterData(
    context: Context,
    table: String,
    code: String,
    name: String,
    parentId: String = "",
    warehouseType: String = "main",
    warehouseId: String = "",
    locationType: String = "internal"
) {
    require(table in setOf("commercial_warehouses", "commercial_product_categories", "commercial_departments", "commercial_storage_locations")) {
        "نوع الدليل غير مدعوم."
    }
    require(code.trim().isNotEmpty() && name.trim().isNotEmpty()) { "أدخل الرمز والاسم." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val duplicate = loadCommercialMasterData(context, table).any { row ->
        row.code.trim().equals(code.trim(), ignoreCase = true) &&
            (table != "commercial_storage_locations" || row.warehouseId == warehouseId)
    }
    require(!duplicate) { "هذا الرمز مستخدم بالفعل في هذا الدليل." }
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val payload = JSONObject().put("organization_id", orgId).put("code", code.trim())
    if (table == "commercial_warehouses") {
        val branchId = saved.getString("location_id", null).orEmpty()
        val activityId = saved.getString("activity_id", null).orEmpty()
        require(branchId.isNotBlank()) { "الموقع/الفرع غير محفوظ." }
        payload.put("branch_location_id", branchId)
            .put("activity_id", activityId.ifBlank { JSONObject.NULL })
            .put("name", name.trim()).put("warehouse_type", warehouseType)
            .put("created_by", JSONObject.NULL)
    } else if (table == "commercial_storage_locations") {
        require(warehouseId.isNotBlank()) { "اختر المستودع أولًا." }
        require(locationType in setOf("view", "internal", "receiving", "packing", "quality", "quarantine", "transit", "customer", "supplier", "scrap")) { "نوع موقع التخزين غير صالح." }
        payload.put("warehouse_id", warehouseId).put("name", name.trim())
            .put("location_type", locationType).put("state", "active")
        if (parentId.isNotBlank()) payload.put("parent_location_id", parentId)
    } else {
        payload.put("name_ar", name.trim())
        if (parentId.isNotBlank()) payload.put("parent_id", parentId)
    }
    SupabaseClient.request("/rest/v1/$table", "POST", payload, access)
}


internal data class CommercialDepartmentWarehouseAssignment(
    val departmentId: String,
    val warehouseId: String
)

/** Read the existing organization-scoped department/warehouse links. */
internal fun loadCommercialDepartmentWarehouseAssignments(context: Context): List<CommercialDepartmentWarehouseAssignment> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_department_warehouse_assignments?select=department_id,warehouse_id&organization_id=eq.$orgId&order=department_id.asc,warehouse_id.asc", access
    )
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val departmentId = row.optString("department_id").takeUnless { it == "null" }.orEmpty()
            val warehouseId = row.optString("warehouse_id").takeUnless { it == "null" }.orEmpty()
            if (departmentId.isNotBlank() && warehouseId.isNotBlank()) {
                add(CommercialDepartmentWarehouseAssignment(departmentId, warehouseId))
            }
        }
    }
}

/** Add one validated department/warehouse link; database RLS and scope triggers remain authoritative. */
internal fun assignCommercialDepartmentToWarehouse(context: Context, departmentId: String, warehouseId: String) {
    require(departmentId.isNotBlank() && warehouseId.isNotBlank()) { "اختر القسم والمستودع." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val existingRows = JSONArray(SupabaseClient.request(
        "/rest/v1/commercial_department_warehouse_assignments?select=department_id,warehouse_id&organization_id=eq.$orgId&department_id=eq.$departmentId&warehouse_id=eq.$warehouseId&limit=1",
        "GET", token = access
    ))
    if (existingRows.length() > 0) return
    val payload = JSONObject()
        .put("organization_id", orgId)
        .put("department_id", departmentId)
        .put("warehouse_id", warehouseId)
        .put("created_by", JSONObject.NULL)
    SupabaseClient.request("/rest/v1/commercial_department_warehouse_assignments", "POST", payload, access)
}


internal data class CommercialCloudStockAlert(
    val offeringId: String,
    val productName: String,
    val categoryName: String,
    val unitName: String,
    val availableQuantity: Double,
    val reorderPoint: Double,
    val quantityOnHand: java.math.BigDecimal,
    val unitCostMinor: Long?
)

/** Precise read-only quantities for the stock-alert screen; preserve fractional units. */
internal fun loadCommercialCloudStockAlerts(context: Context): List<CommercialCloudStockAlert> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    val locationId = saved.getString("location_id", null).orEmpty()
    require(activityId.isNotBlank() && locationId.isNotBlank()) {
        "بيانات النشاط والموقع غير محفوظة."
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    val query = "/rest/v1/commercial_inventory_balances" +
        "?select=offering_id,quantity_on_hand,quantity_reserved,reorder_point,unit_cost_minor,offering:commercial_offerings!inner(id,activity_id,product:commercial_products!inner(name,unit_name,state),categories:commercial_offering_categories(is_primary,category:commercial_product_categories(name_ar,name_en)))" +
        "&organization_id=eq.$orgId&location_id=eq.$locationId&offering.activity_id=eq.$activityId&order=updated_at.desc,offering_id.asc"
    val rows = commercialCloudPagedGet(query, access)
    return buildList {
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
                .map { it.optString("name_ar").ifBlank { it.optString("name_en") } }
                .firstOrNull { it.isNotBlank() && it != "null" }
                ?: "غير مصنف"
            val onHand = row.optDouble("quantity_on_hand", 0.0)
            val reserved = row.optDouble("quantity_reserved", 0.0)
            add(CommercialCloudStockAlert(
                offeringId = offering.optString("id"),
                productName = product.optString("name").ifBlank { "صنف بدون اسم" },
                categoryName = categoryName,
                unitName = product.optString("unit_name").ifBlank { "وحدة" },
                availableQuantity = (onHand - reserved).coerceAtLeast(0.0),
                reorderPoint = row.optDouble("reorder_point", 0.0).coerceAtLeast(0.0),
                quantityOnHand = runCatching { java.math.BigDecimal(row.optString("quantity_on_hand", "0")) }.getOrDefault(java.math.BigDecimal.ZERO),
                unitCostMinor = if (row.isNull("unit_cost_minor")) null else row.optLong("unit_cost_minor")
            ))
        }
    }
}

internal data class CommercialInventoryCostingPolicyState(
    val organizationMethod: String?,
    val locationMethod: String?,
    val effectiveMethod: String
)

internal data class CommercialCloudCostLayerSummary(
    val activeLayerCount: Int,
    val offeringCount: Int,
    val remainingQuantity: BigDecimal,
    val inventoryValueMinor: BigDecimal
)

/** Loads the organization default and current-location override without changing either policy. */
internal fun loadCommercialInventoryCostingPolicy(context: Context): CommercialInventoryCostingPolicyState {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val locationId = saved.getString("location_id", null).orEmpty()
    require(locationId.isNotBlank()) { "اختر موقع المؤسسة قبل تحميل طريقة تقييم المخزون." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_costing_policies" +
            "?select=id,location_id,costing_method,effective_at" +
            "&organization_id=eq.$orgId" +
            "&or=(location_id.eq.$locationId,location_id.is.null)" +
            "&order=effective_at.desc,id.asc",
        access
    )
    var organizationMethod: String? = null
    var locationMethod: String? = null
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val method = row.optString("costing_method").takeIf { it in setOf("fifo", "lifo", "weighted_average") } ?: continue
        val policyLocation = row.optString("location_id").takeUnless { it.isBlank() || it == "null" }
        if (policyLocation == null && organizationMethod == null) organizationMethod = method
        if (policyLocation == locationId && locationMethod == null) locationMethod = method
    }
    return CommercialInventoryCostingPolicyState(
        organizationMethod = organizationMethod,
        locationMethod = locationMethod,
        effectiveMethod = locationMethod ?: organizationMethod ?: "weighted_average"
    )
}

/** Saves only the selected scope; existing sale allocations retain their original method snapshot. */
internal fun saveCommercialInventoryCostingPolicy(context: Context, method: String, scope: String) {
    require(method in setOf("fifo", "lifo", "weighted_average")) { "طريقة تقييم المخزون غير صالحة." }
    require(scope in setOf("organization", "location")) { "نطاق سياسة التكلفة غير صالح." }
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val locationId = saved.getString("location_id", null).orEmpty()
    require(scope != "location" || locationId.isNotBlank()) { "اختر موقع المؤسسة أولًا." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val locationFilter = if (scope == "location") "location_id=eq.$locationId" else "location_id=is.null"
    val existing = JSONArray(SupabaseClient.request(
        "/rest/v1/commercial_inventory_costing_policies" +
            "?select=id&organization_id=eq.$orgId&$locationFilter&limit=1",
        "GET", token = access
    ))
    val now = java.time.Instant.now().toString()
    val payload = JSONObject()
        .put("costing_method", method)
        .put("effective_at", now)
        .put("updated_at", now)
    if (existing.length() > 0) {
        val id = existing.optJSONObject(0)?.optString("id").orEmpty()
        require(id.isNotBlank() && id != "null") { "تعذر تحديد سياسة التكلفة الحالية." }
        SupabaseClient.request(
            "/rest/v1/commercial_inventory_costing_policies?id=eq.$id&organization_id=eq.$orgId",
            "PATCH", payload, access
        )
    } else {
        payload.put("organization_id", orgId)
            .put("location_id", if (scope == "location") locationId else JSONObject.NULL)
        SupabaseClient.request("/rest/v1/commercial_inventory_costing_policies", "POST", payload, access)
    }
}

/** Read-only summary of active cost layers for the current activity and location. */
internal fun loadCommercialCloudCostLayerSummary(context: Context): CommercialCloudCostLayerSummary {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    val locationId = saved.getString("location_id", null).orEmpty()
    require(activityId.isNotBlank() && locationId.isNotBlank()) { "بيانات النشاط والموقع غير محفوظة." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_cost_layers" +
            "?select=offering_id,quantity_remaining,unit_cost_minor,offering:commercial_offerings!inner(activity_id)" +
            "&organization_id=eq.$orgId&location_id=eq.$locationId" +
            "&quantity_remaining=gt.0&offering.activity_id=eq.$activityId" +
            "&order=received_at.asc,id.asc",
        access
    )
    var quantity = BigDecimal.ZERO
    var valueMinor = BigDecimal.ZERO
    val offerings = mutableSetOf<String>()
    var activeLayers = 0
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val remaining = runCatching { BigDecimal(row.optString("quantity_remaining", "0")) }.getOrDefault(BigDecimal.ZERO)
        val unitCost = row.optLong("unit_cost_minor", -1L)
        if (remaining.signum() <= 0 || unitCost < 0L) continue
        activeLayers++
        offerings += row.optString("offering_id")
        quantity = quantity.add(remaining)
        valueMinor = valueMinor.add(remaining.multiply(BigDecimal.valueOf(unitCost)))
    }
    return CommercialCloudCostLayerSummary(activeLayers, offerings.size, quantity, valueMinor.setScale(0, java.math.RoundingMode.HALF_UP))
}

internal data class CommercialCloudReturnableSaleItem(
    val id: String,
    val productName: String,
    val soldQuantity: String,
    val remainingQuantity: Int,
    val lineTotalMinor: Long,
    val sellingUnitCode: String = "each",
    val sellingUnitName: String = "حبة",
    val sellingUnitFactor: Long = 1L
)

internal data class CommercialCloudReturnableSale(
    val id: String,
    val receiptNumber: String,
    val customerName: String,
    val currencyCode: String,
    val items: List<CommercialCloudReturnableSaleItem>
)

internal fun loadCommercialCloudReturnableSales(context: Context): List<CommercialCloudReturnableSale> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales?select=id,receipt_number,currency_code,created_at,customer:commercial_customers(display_name),items:commercial_sale_items(id,product_name_snapshot,quantity,quantity_in_stock_units,line_total_minor,selling_unit_code,selling_unit_name,selling_quantity),returns:commercial_sales_returns(status,items:commercial_sale_return_items(sale_item_id,quantity))&organization_id=eq.$orgId&status=eq.completed&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val priorReturns = row.optJSONArray("returns")
            val alreadyRequested = mutableMapOf<String, Int>()
            for (rIndex in 0 until (priorReturns?.length() ?: 0)) {
                val prior = priorReturns?.optJSONObject(rIndex) ?: continue
                if (prior.optString("status") in setOf("rejected", "cancelled")) continue
                val priorItems = prior.optJSONArray("items")
                for (pIndex in 0 until (priorItems?.length() ?: 0)) {
                    val priorItem = priorItems?.optJSONObject(pIndex) ?: continue
                    val itemId = priorItem.optString("sale_item_id").takeUnless { it == "null" }.orEmpty()
                    if (itemId.isNotBlank()) alreadyRequested[itemId] = (alreadyRequested[itemId] ?: 0) + priorItem.optInt("quantity", 0).coerceAtLeast(0)
                }
            }
            val itemRows = row.optJSONArray("items")
            val saleItems = buildList {
                for (j in 0 until (itemRows?.length() ?: 0)) {
                    val item = itemRows?.optJSONObject(j) ?: continue
                    val qty = item.optInt("quantity", 0)
                    if (qty <= 0) continue
                    val itemId = item.optString("id").takeUnless { it == "null" }.orEmpty()
                    val baseQuantitySnapshot = item.optLong("quantity_in_stock_units", -1L)
                    val sellingQuantitySnapshot = runCatching { BigDecimal(item.optString("selling_quantity")) }.getOrNull()
                    val sellingUnitFactor = sellingQuantitySnapshot?.let {
                        commercialSavedSellingUnitFactor(baseQuantitySnapshot, it)
                    } ?: throw IllegalStateException("بيانات وحدة البيع المحفوظة في الفاتورة غير صالحة؛ أوقفنا المرتجع لحماية المخزون.")
                    require(baseQuantitySnapshot == qty.toLong()) {
                        "كمية المخزون المحفوظة لا تطابق بند الفاتورة؛ أوقفنا المرتجع لحماية المخزون."
                    }
                    val remaining = (qty - (alreadyRequested[itemId] ?: 0)).coerceAtLeast(0)
                    if (remaining <= 0) continue
                    add(CommercialCloudReturnableSaleItem(
                        id = itemId,
                        productName = item.optString("product_name_snapshot").takeUnless { it == "null" || it.isBlank() } ?: "صنف غير معروف",
                        soldQuantity = formatCommercialReturnSoldQuantity(
                            item.optString("selling_quantity").takeUnless { it == "null" || it.isBlank() }, qty
                        ),
                        remainingQuantity = remaining,
                        lineTotalMinor = item.optLong("line_total_minor", 0L),
                        sellingUnitCode = item.optString("selling_unit_code").takeUnless { it.isBlank() || it == "null" } ?: "each",
                        sellingUnitName = item.optString("selling_unit_name").takeUnless { it.isBlank() || it == "null" } ?: "حبة",
                        sellingUnitFactor = sellingUnitFactor
                    ))
                }
            }
            if (saleItems.isNotEmpty()) {
                val customer = row.optJSONObject("customer")
                add(CommercialCloudReturnableSale(
                    id = row.optString("id"),
                    receiptNumber = row.optString("receipt_number").takeUnless { it == "null" }.orEmpty(),
                    customerName = customer?.optString("display_name").takeUnless { it == null || it == "null" || it.isBlank() } ?: "عميل نقدي / غير محدد",
                    currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                    items = saleItems
                ))
            }
        }
    }
}

internal fun requestCommercialCloudSaleReturn(
    context: Context,
    saleId: String,
    reasonCode: String,
    reasonNote: String,
    quantities: Map<String, BigDecimal>,
    unitCodes: Map<String, String> = emptyMap()
): String {
    require(saleId.isNotBlank()) { "اختر الفاتورة أولًا." }
    require(reasonCode in setOf("customer_request", "damaged", "wrong_item", "not_as_described", "duplicate_charge", "other")) { "سبب المرتجع غير صالح." }
    val selected = quantities.mapValues { it.value.stripTrailingZeros() }.filterValues { it.signum() > 0 }
    require(selected.isNotEmpty()) { "أدخل كمية مرتجعة لصنف واحد على الأقل." }
    val items = JSONArray()
    selected.forEach { (saleItemId, quantity) ->
        require(saleItemId.isNotBlank() && quantity <= BigDecimal("100000") && quantity.scale() <= 3) { "كمية المرتجع غير صالحة؛ الحد الأقصى ثلاث منازل عشرية." }
        val item = JSONObject().put("sale_item_id", saleItemId)
            .put("quantity", quantity.toPlainString()).put("restock", true)
        unitCodes[saleItemId]?.takeIf { it.isNotBlank() }?.let { item.put("unit_code", it) }
        items.put(item)
    }
    val (organizationId, access) = commercialMasterDataAccess(context)
    val canonicalItems = JSONArray()
    selected.toSortedMap().forEach { (saleItemId, quantity) ->
        canonicalItems.put(JSONObject()
            .put("sale_item_id", saleItemId)
            .put("quantity", quantity.toPlainString())
            .put("unit_code", unitCodes[saleItemId].orEmpty()))
    }
    val fingerprint = JSONObject()
        .put("organization_id", organizationId)
        .put("sale_id", saleId)
        .put("reason_code", reasonCode)
        .put("reason_note", reasonNote.trim())
        .put("items", canonicalItems)
        .toString()
    val pending = context.getSharedPreferences("commercial_cloud_pending_return_v1", Context.MODE_PRIVATE)
    val storedKey = pending.getString("idempotency_key", null).orEmpty()
    if (storedKey.isNotBlank()) {
        require(pending.getString("fingerprint", null) == fingerprint) {
            "يوجد طلب مرتجع سابق لم يُحسم بعد. أعد المحاولة بنفس الفاتورة والكميات والسبب أولًا."
        }
    }
    val idempotencyKey = storedKey.ifBlank { UUID.randomUUID().toString() }
    if (storedKey.isBlank()) {
        check(pending.edit().putString("fingerprint", fingerprint)
            .putString("idempotency_key", idempotencyKey).commit()) {
            "تعذر حفظ مفتاح طلب المرتجع قبل الإرسال؛ لم يتم إرسال الطلب."
        }
    }
    val payload = JSONObject()
        .put("p_sale_id", saleId)
        .put("p_idempotency_key", idempotencyKey)
        .put("p_reason_code", reasonCode)
        .put("p_items", items)
        .put("p_reason_note", reasonNote.trim().ifBlank { JSONObject.NULL })
    val response = SupabaseClient.request("/rest/v1/rpc/commercial_request_sale_return", "POST", payload, access)
    check(pending.edit().clear().commit()) {
        "تم تأكيد طلب المرتجع لكن تعذر تنظيف مفتاح إعادة المحاولة؛ أعد المحاولة بنفس البيانات ولا تنشئ طلبًا جديدًا."
    }
    return response
}

internal data class CommercialCloudReturnItem(
    val id: String,
    val saleItemId: String,
    val productName: String,
    val quantity: Int,
    val refundAmountMinor: Long,
    val refundAmountPresent: Boolean,
    val restock: Boolean,
    val restockFlagPresent: Boolean,
    val restockedQuantity: Int,
    val restockedQuantityPresent: Boolean,
    val conditionCode: String,
    val note: String,
    val saleItemQuantity: Int,
    val saleItemQuantityInStockUnits: Long,
    val saleItemCostTotalMinor: Long?,
    val saleItemUnitCostMinor: Long?
)

internal data class CommercialCloudSaleReturn(
    val id: String,
    val saleId: String,
    val receiptNumber: String,
    val customerName: String,
    val reasonCode: String,
    val reasonNote: String,
    val status: String,
    val refundStatus: String,
    val restockStatus: String,
    val currencyCode: String,
    val requestedRefundMinor: Long,
    val approvedRefundMinor: Long?,
    val createdAt: String,
    val completedAt: String,
    val decisionNote: String,
    val items: List<CommercialCloudReturnItem>
)

internal fun loadCommercialCloudSaleReturns(context: Context): List<CommercialCloudSaleReturn> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales_returns?select=id,sale_id,reason_code,reason_note,status,refund_status,restock_status,currency_code,requested_refund_minor,approved_refund_minor,created_at,completed_at,decision_note,sale:commercial_sales!inner(receipt_number,activity_id,customer:commercial_customers(display_name)),items:commercial_sale_return_items(id,sale_item_id,quantity,refund_amount_minor,restock,restocked_quantity,condition_code,note,sale_item:commercial_sale_items(product_name_snapshot,quantity,quantity_in_stock_units,unit_cost_minor,cost_total_minor))&organization_id=eq.$orgId&sale.activity_id=eq.$activityId&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val sale = row.optJSONObject("sale")
            val customer = sale?.optJSONObject("customer")
            val itemRows = row.optJSONArray("items")
            val items = buildList {
                for (j in 0 until (itemRows?.length() ?: 0)) {
                    val item = itemRows?.optJSONObject(j) ?: continue
                    val saleItem = item.optJSONObject("sale_item")
                    add(CommercialCloudReturnItem(
                        id = item.optString("id").takeUnless { it == "null" }.orEmpty(),
                        saleItemId = item.optString("sale_item_id").takeUnless { it == "null" }.orEmpty(),
                        productName = saleItem?.optString("product_name_snapshot").takeUnless { it == null || it == "null" || it.isBlank() } ?: "صنف غير معروف",
                        quantity = item.optInt("quantity", 0),
                        refundAmountMinor = item.optLong("refund_amount_minor", 0L),
                        refundAmountPresent = item.has("refund_amount_minor") && !item.isNull("refund_amount_minor"),
                        restock = item.optBoolean("restock", false),
                        restockFlagPresent = item.has("restock") && !item.isNull("restock"),
                        restockedQuantity = item.optInt("restocked_quantity", 0),
                        restockedQuantityPresent = item.has("restocked_quantity") && !item.isNull("restocked_quantity"),
                        conditionCode = item.optString("condition_code").takeUnless { it == "null" }.orEmpty(),
                        note = item.optString("note").takeUnless { it == "null" }.orEmpty(),
                        saleItemQuantity = saleItem?.optInt("quantity", 0) ?: 0,
                        saleItemQuantityInStockUnits = saleItem?.optLong("quantity_in_stock_units", 0L) ?: 0L,
                        saleItemCostTotalMinor = if (saleItem == null || saleItem.isNull("cost_total_minor")) null else saleItem.optLong("cost_total_minor"),
                        saleItemUnitCostMinor = if (saleItem == null || saleItem.isNull("unit_cost_minor")) null else saleItem.optLong("unit_cost_minor")
                    ))
                }
            }
            add(CommercialCloudSaleReturn(
                id = row.optString("id").takeUnless { it == "null" }.orEmpty(),
                saleId = row.optString("sale_id").takeUnless { it == "null" }.orEmpty(),
                receiptNumber = sale?.optString("receipt_number").takeUnless { it == null || it == "null" }.orEmpty(),
                customerName = customer?.optString("display_name").takeUnless { it == null || it == "null" || it.isBlank() } ?: "عميل نقدي / غير محدد",
                reasonCode = row.optString("reason_code").takeUnless { it == "null" }.orEmpty(),
                reasonNote = row.optString("reason_note").takeUnless { it == "null" }.orEmpty(),
                status = row.optString("status").takeUnless { it == "null" }.orEmpty(),
                refundStatus = row.optString("refund_status").takeUnless { it == "null" }.orEmpty(),
                restockStatus = row.optString("restock_status").takeUnless { it == "null" }.orEmpty(),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                requestedRefundMinor = row.optLong("requested_refund_minor", 0L),
                approvedRefundMinor = if (row.isNull("approved_refund_minor")) null else row.optLong("approved_refund_minor"),
                createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                completedAt = row.optString("completed_at").takeUnless { it == "null" }.orEmpty(),
                decisionNote = row.optString("decision_note").takeUnless { it == "null" }.orEmpty(),
                items = items
            ))
        }
    }
}

internal fun decideCommercialCloudSaleReturn(context: Context, returnId: String, decision: String, approvedRefundMinor: Long, note: String) {
    require(decision == "approve" || decision == "reject") { "قرار المرتجع غير صالح." }
    val (_, access) = commercialMasterDataAccess(context)
    val payload = JSONObject()
        .put("p_return_id", returnId)
        .put("p_decision", decision)
        .put("p_approved_refund_minor", if (decision == "approve") approvedRefundMinor else JSONObject.NULL)
        .put("p_decision_note", note.trim().ifBlank { JSONObject.NULL })
    SupabaseClient.request("/rest/v1/rpc/commercial_decide_sale_return", "POST", payload, access)
}

internal fun inspectCommercialCloudSaleReturnItem(context: Context, itemId: String, condition: String, restock: Boolean, note: String) {
    require(condition in setOf("resellable", "damaged", "written_off", "uninspected")) { "حالة الصنف غير صالحة." }
    val (_, access) = commercialMasterDataAccess(context)
    val payload = JSONObject()
        .put("p_return_item_id", itemId)
        .put("p_condition_code", condition)
        .put("p_restock", restock)
        .put("p_note", note.trim().ifBlank { JSONObject.NULL })
    SupabaseClient.request("/rest/v1/rpc/commercial_inspect_sale_return_item", "POST", payload, access)
}

internal fun restockCommercialCloudSaleReturn(context: Context, returnId: String): String {
    val (_, access) = commercialMasterDataAccess(context)
    return SupabaseClient.request(
        "/rest/v1/rpc/commercial_restock_approved_sale_return", "POST",
        JSONObject().put("p_return_id", returnId), access
    )
}

internal data class CommercialCloudRecord(
    val id: String, val title: String, val subtitle: String, val status: String,
    val amountMinor: Long = 0L, val createdAt: String = "",
    val details: List<String> = emptyList(), val currencyCode: String = "",
    val paymentMethodCode: String = ""
)

/** Read-only operational projections. Writes must use an approved RPC, never generic POST. */
internal fun loadCommercialCloudRecords(context: Context, screenKey: String): List<CommercialCloudRecord> {
    val config = when (screenKey) {
        "customers" -> Triple("commercial_customers", "id,display_name,phone,email,state,created_at", "display_name")
        "suppliers" -> Triple("commercial_suppliers", "id,display_name,supplier_code,phone,email,state,payment_terms_days,created_at", "display_name")
        "purchases" -> Triple("commercial_purchase_orders", "id,order_number,status,ordered_at,expected_at,currency_code,supplier_id,created_at", "created_at")
        "payments" -> Triple("commercial_payments", "id,status,sale_id,amount_minor,currency_code,payment_method_type,created_at", "created_at")
        "returns" -> Triple("commercial_sales_returns", "id,status,sale_id,reason_code,refund_status,restock_status,requested_refund_minor,currency_code,created_at", "created_at")
        "journals" -> Triple("commercial_journal_entries", "id,entry_number,status,entry_date,description,source_type,source_id,posted_at,created_at", "entry_date")
        "accounts" -> Triple("commercial_ledger_accounts", "id,account_code,account_name,account_type,normal_balance,currency_code,is_active,parent_account_id", "account_code")
        else -> throw IllegalArgumentException("هذه الشاشة غير مدعومة.")
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/${config.first}?select=${config.second}&organization_id=eq.$orgId&order=${config.third}.desc.nullslast,id.asc", access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it == "null" }.orEmpty()
            val title: String
            val subtitle: String
            val status: String
            val amount: Long
            when (screenKey) {
                "customers" -> {
                    title = row.optString("display_name").ifBlank { "عميل بدون اسم" }
                    subtitle = listOf(row.optString("phone"), row.optString("email")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا توجد بيانات اتصال" }
                    status = row.optString("state").ifBlank { "غير محدد" }; amount = 0L
                }
                "suppliers" -> {
                    title = row.optString("display_name").ifBlank { "مورد بدون اسم" }
                    subtitle = listOf(row.optString("supplier_code"), row.optString("phone"), row.optString("payment_terms_days").takeUnless { it == "null" }?.let { "أجل السداد: $it يوم" }).filterNotNull().filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا توجد بيانات إضافية" }
                    status = row.optString("state").ifBlank { "غير محدد" }; amount = 0L
                }
                "purchases" -> {
                    title = row.optString("order_number").takeUnless { it == "null" || it.isBlank() }?.let { "أمر شراء #$it" } ?: "أمر شراء"
                    subtitle = "المورد: " + row.optString("supplier_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير محدد" } + " • العملة: " + row.optString("currency_code").ifBlank { "—" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = 0L
                }
                "payments" -> {
                    title = "سند دفع/تحصيل"
                    subtitle = "المرجع: " + row.optString("sale_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير مرتبط بفاتورة" } + " • " + row.optString("payment_method_type").ifBlank { "طريقة غير محددة" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = row.optLong("amount_minor", 0L)
                }
                "returns" -> {
                    title = "طلب مرتجع • " + row.optString("reason_code").ifBlank { "بدون سبب محدد" }
                    subtitle = "الفاتورة: " + row.optString("sale_id").takeUnless { it == "null" }.orEmpty().ifBlank { "غير محددة" } + " • رد المبلغ: " + row.optString("refund_status").ifBlank { "غير محدد" } + " • إعادة المخزون: " + row.optString("restock_status").ifBlank { "غير محدد" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = row.optLong("requested_refund_minor", 0L)
                }
                "journals" -> {
                    title = row.optString("entry_number").takeUnless { it == "null" || it.isBlank() }?.let { "قيد #$it" } ?: "قيد محاسبي"
                    subtitle = listOf(row.optString("entry_date"), row.optString("source_type"), row.optString("description")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "لا يوجد وصف" }
                    status = row.optString("status").ifBlank { "غير محدد" }; amount = 0L
                }
                else -> {
                    title = listOf(row.optString("account_code"), row.optString("account_name")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ").ifBlank { "حساب دفتر أستاذ" }
                    subtitle = listOf(row.optString("account_type"), row.optString("normal_balance"), row.optString("currency_code")).filter { it.isNotBlank() && it != "null" }.joinToString(" • ")
                    status = if (row.optBoolean("is_active", false)) "نشط" else "غير نشط"; amount = 0L
                }
            }
            val details = when (screenKey) {
                "customers" -> listOfNotNull(
                    row.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { "البريد الإلكتروني: $it" },
                    row.optString("phone").takeIf { it.isNotBlank() && it != "null" }?.let { "رقم الهاتف: $it" },
                    row.optString("state").takeIf { it.isNotBlank() && it != "null" }?.let { "حالة العميل: $it" }
                ).distinct()
                "suppliers" -> listOfNotNull(
                    row.optString("supplier_code").takeIf { it.isNotBlank() && it != "null" }?.let { "رمز المورد: $it" },
                    row.optString("email").takeIf { it.isNotBlank() && it != "null" }?.let { "البريد الإلكتروني: $it" },
                    row.optString("phone").takeIf { it.isNotBlank() && it != "null" }?.let { "رقم الهاتف: $it" },
                    row.optString("payment_terms_days").takeIf { it.isNotBlank() && it != "null" }?.let { "شروط السداد: $it يومًا" },
                    row.optString("state").takeIf { it.isNotBlank() && it != "null" }?.let { "حالة المورد: $it" }
                ).distinct()
                else -> emptyList()
            }
            add(CommercialCloudRecord(
                id, title, subtitle, status, amount,
                row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                details,
                row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                if (screenKey == "payments") row.optString("payment_method_type").takeUnless { it == "null" }.orEmpty() else ""
            ))
        }
    }
}

internal data class CommercialCloudReceivable(
    val saleId: String,
    val receiptNumber: String,
    val customerId: String,
    val customerName: String,
    val totalMinor: Long,
    val paidMinor: Long,
    val refundedMinor: Long,
    val creditedMinor: Long,
    val currencyCode: String,
    val createdAt: String
) {
    // A completed approved return reduces the sale value; an executed refund reduces
    // net cash retained. Keep both adjustments distinct to avoid double counting.
    val netPaidMinor: Long get() = (paidMinor - refundedMinor).coerceAtLeast(0L)
    val dueMinor: Long get() = (totalMinor - netPaidMinor - creditedMinor).coerceAtLeast(0L)
}

internal fun loadCommercialCloudReceivables(context: Context): List<CommercialCloudReceivable> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales" +
        "?select=id,receipt_number,customer_id,total_minor,currency_code,created_at,payment_status,customer:commercial_customers(display_name),payments:commercial_payments(amount_minor,status),returns:commercial_sales_returns(id,status,refund_status,approved_refund_minor)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&status=eq.completed&customer_id=not.is.null&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val customer = row.optJSONObject("customer")
            val customerId = row.optString("customer_id").takeUnless { it == "null" }.orEmpty()
            val customerName = customer?.optString("display_name").orEmpty()
            if (customerId.isBlank() || customerName.isBlank()) continue
            val payments = row.optJSONArray("payments")
            var paid = 0L
            for (j in 0 until (payments?.length() ?: 0)) {
                val payment = payments?.optJSONObject(j) ?: continue
                if (payment.optString("status") in setOf("succeeded", "partially_refunded", "refunded")) {
                    paid = Math.addExact(paid, payment.optLong("amount_minor", 0L).coerceAtLeast(0L))
                }
            }
            val returns = row.optJSONArray("returns")
            var credited = 0L
            var refunded = 0L
            for (j in 0 until (returns?.length() ?: 0)) {
                val saleReturn = returns?.optJSONObject(j) ?: continue
                val approvedRefund = saleReturn.optLong("approved_refund_minor", 0L).coerceAtLeast(0L)
                if (saleReturn.optString("status") == "completed") {
                    credited = Math.addExact(credited, approvedRefund)
                }
                // The refund RPC inserts the internal refund posting and changes this
                // status in the same transaction. Its amount equals approved_refund_minor.
                // Read the status from the client-visible return row instead of embedding
                // commercial_return_refund_postings, whose table access is intentionally denied.
                if (saleReturn.optString("refund_status") == "refunded") {
                    refunded = Math.addExact(refunded, approvedRefund)
                }
            }
            val netPaid = (paid - refunded).coerceAtLeast(0L)
            val total = row.optLong("total_minor", 0L).coerceAtLeast(0L)
            val due = (total - netPaid - credited).coerceAtLeast(0L)
            if (due > 0L) add(CommercialCloudReceivable(
                saleId = row.optString("id"),
                receiptNumber = row.optString("receipt_number"),
                customerId = customerId,
                customerName = customerName,
                totalMinor = total,
                paidMinor = paid,
                refundedMinor = refunded,
                creditedMinor = credited,
                currencyCode = row.optString("currency_code"),
                createdAt = row.optString("created_at")
            ))
        }
    }
}

internal fun recordCommercialCloudCustomerPayment(
    context: Context,
    saleId: String,
    amountMinor: Long,
    method: String
): JSONObject {
    require(saleId.isNotBlank() && amountMinor > 0L)
    require(method in setOf("cash", "bank_transfer", "card", "other"))
    val (orgId, access) = commercialMasterDataAccess(context)
    val fingerprint = listOf(orgId, saleId, amountMinor.toString(), method).joinToString("|")
    val prefs = context.getSharedPreferences("commercial_cloud_pending_collection_v1", Context.MODE_PRIVATE)
    val storedKey = prefs.getString("idempotency_key", null).orEmpty()
    if (storedKey.isNotBlank()) {
        require(prefs.getString("fingerprint", null) == fingerprint) {
            "يوجد تحصيل معلّق لم يُحسم بعد. أعد المحاولة بنفس بيانات التحصيل."
        }
    }
    val idempotencyKey = storedKey.ifBlank { UUID.randomUUID().toString() }
    if (storedKey.isBlank()) {
        check(prefs.edit().clear().putString("fingerprint", fingerprint)
            .putString("idempotency_key", idempotencyKey).commit()) {
            "تعذر حفظ مفتاح التحصيل قبل الإرسال."
        }
    }
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_record_customer_payment", "POST",
        JSONObject().put("p_sale_id", saleId)
            .put("p_amount_minor", amountMinor)
            .put("p_payment_method_type", method)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
    check(prefs.edit().clear().commit()) { "تم تأكيد التحصيل لكن تعذر تنظيف مفتاح إعادة المحاولة." }
    return result
}


/** Fetch the server-issued numbered receipt for a confirmed customer payment. */
internal fun loadCommercialCloudCustomerReceipt(context: Context, paymentId: String): JSONObject {
    require(paymentId.isNotBlank()) { "مرجع الدفعة غير صالح." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_payments" +
        "?select=id,receipt_number,amount_minor,currency_code,payment_method_type,created_at,sale:commercial_sales(receipt_number,customer:commercial_customers(display_name))" +
        "&organization_id=eq.$orgId&id=eq.$paymentId&limit=1"
    val rows = JSONArray(SupabaseClient.request(path, "GET", null, access))
    require(rows.length() == 1) { "لم يُعثر على سند القبض المؤكد لهذه الدفعة." }
    return rows.getJSONObject(0)
}


internal data class CustomerPortalOrganization(
    val id: String, val name: String, val description: String,
    val currencyCode: String, val logoUrl: String, val allowCredit: Boolean, val delegationId: String = "",
    val canPurchase: Boolean = true, val canBook: Boolean = true
)
internal data class CustomerPortalOffering(
    val id: String, val name: String, val description: String, val unitName: String,
    val priceMinor: Long, val currencyCode: String, val itemType: String
)
internal data class CustomerPortalOrderResult(
    val orderId: String, val orderNumber: String, val status: String,
    val paymentStatus: String, val totalMinor: Long, val currencyCode: String, val replayed: Boolean
)

internal fun customerPortalAccessToken(context: Context): String {
    val prefs = commercialCloudSessionPreferences(context)
    val refresh = prefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى حساب الزبون أولًا." }
    val session = JSONObject(SupabaseClient.request(
        "/auth/v1/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", refresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    session.optString("refresh_token").takeIf { it.isNotBlank() }?.let { prefs.edit().putString("refresh_token", it).apply() }
    return access
}

internal fun loadCustomerPortalOrganizations(context: Context): List<CustomerPortalOrganization> {
    val access = customerPortalAccessToken(context)
    val result = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_organizations", "POST", JSONObject(), access
    ))
    val resultRows = buildList {
        for (i in 0 until result.length()) {
            val row = result.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank() || name.isBlank()) continue
            add(CustomerPortalOrganization(id, name,
                row.optString("description").takeUnless { it == "null" }.orEmpty(),
                row.optString("currency_code", "YER").ifBlank { "YER" },
                row.optString("logo_url").takeUnless { it == "null" }.orEmpty(),
                row.optBoolean("allow_credit", false)))
        }
    }.toMutableList()
    val delegated = runCatching {
        JSONArray(SupabaseClient.request(
            "/rest/v1/rpc/commercial_account_list_my_active_delegations", "POST", JSONObject(), access
        ))
    }.getOrElse { error ->
        val detail = error.message.orEmpty()
        // Keep the existing customer portal usable while the backend migration is not deployed yet.
        if (detail.contains("PGRST202", ignoreCase = true) ||
            detail.contains("Could not find the function", ignoreCase = true) ||
            detail.contains("HTTP 404", ignoreCase = true)) JSONArray()
        else throw error
    }
    for (i in 0 until delegated.length()) {
        val row = delegated.optJSONObject(i) ?: continue
        val orgId = row.optString("organization_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
        val delegationId = row.optString("delegation_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
        val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
        if (orgId.isBlank() || delegationId.isBlank() || name.isBlank()) continue
        val parentName = row.optString("parent_display_name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
        val permissions = row.optJSONObject("permissions") ?: JSONObject()
        resultRows.add(CustomerPortalOrganization(
            orgId, if (parentName.isBlank()) "$name — حساب رئيسي مفوض" else "$name — عبر حساب $parentName",
            row.optString("description").takeUnless { it == "null" }.orEmpty(),
            row.optString("currency_code", "YER").ifBlank { "YER" },
            row.optString("logo_url").takeUnless { it == "null" }.orEmpty(),
            row.optBoolean("allow_credit", false), delegationId,
            permissions.optBoolean("purchase", false), permissions.optBoolean("book", false)
        ))
    }
    return resultRows
}

internal fun loadCustomerPortalOfferings(context: Context, organizationId: String, delegationId: String = ""): List<CustomerPortalOffering> {
    require(organizationId.isNotBlank()) { "اختر المؤسسة أولًا." }
    val access = customerPortalAccessToken(context)
    val delegated = delegationId.isNotBlank()
    val result = JSONArray(SupabaseClient.request(
        if (delegated) "/rest/v1/rpc/commercial_account_list_delegated_offerings" else "/rest/v1/rpc/commercial_customer_portal_list_offerings", "POST",
        if (delegated) JSONObject().put("p_delegation_id", delegationId) else JSONObject().put("p_organization_id", organizationId), access
    ))
    return buildList {
        for (i in 0 until result.length()) {
            val row = result.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it == "null" }.orEmpty()
            val price = row.optLong("price_minor", -1L)
            if (id.isBlank() || name.isBlank() || !currency.matches(Regex("^[A-Z]{3}$")) || price < 0) continue
            add(CustomerPortalOffering(id, name, row.optString("description").takeUnless { it == "null" }.orEmpty(),
                row.optString("unit_name").ifBlank { "وحدة" }, price, currency,
                row.optString("item_type").ifBlank { "product" }))
        }
    }
}

private fun customerPortalPendingPrefs(context: Context) =
    context.getSharedPreferences("customer_portal_pending_order_v1", Context.MODE_PRIVATE)

internal fun getOrCreateCustomerPortalOrderKey(context: Context, organizationId: String, lines: List<Pair<String, String>>, paymentMode: String, note: String, extra: List<String> = emptyList()): String {
    require(organizationId.isNotBlank() && lines.isNotEmpty()) { "السلة فارغة أو المؤسسة غير محددة." }
    val canonical = listOf(organizationId, paymentMode, note) + extra + lines.sortedBy { it.first }.flatMap { listOf(it.first, it.second) }
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(canonical.joinToString("\u001f").toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val prefs = customerPortalPendingPrefs(context)
    val oldFingerprint = prefs.getString("fingerprint", null)
    val oldKey = prefs.getString("idempotency_key", null)
    if (!oldKey.isNullOrBlank() && oldFingerprint != fingerprint) {
        throw IllegalStateException("هناك طلب سابق لم يتأكد حفظه. أعد محاولة الطلب السابق أو تحقق من سجل الطلبات قبل تغيير السلة.")
    }
    if (!oldKey.isNullOrBlank()) return oldKey
    val key = "customer-" + UUID.randomUUID().toString()
    prefs.edit().putString("fingerprint", fingerprint).putString("idempotency_key", key).apply()
    return key
}

internal fun clearCustomerPortalOrderKey(context: Context, key: String) {
    val prefs = customerPortalPendingPrefs(context)
    if (prefs.getString("idempotency_key", null) == key) prefs.edit().remove("idempotency_key").remove("fingerprint").apply()
}

internal fun submitCustomerPortalOrder(
    context: Context, organizationId: String, lines: List<Pair<String, String>>,
    paymentMode: String, note: String, idempotencyKey: String, delegationId: String = ""
): CustomerPortalOrderResult {
    require(paymentMode == "cash" || paymentMode == "credit") { "طريقة الفاتورة غير صحيحة." }
    require(lines.isNotEmpty() && lines.size <= 100) { "يجب اختيار صنف واحد على الأقل وبحد أقصى 100 صنف." }
    require(lines.map { it.first }.distinct().size == lines.size) { "يوجد صنف مكرر في السلة." }
    val lineArray = JSONArray()
    lines.forEach { (offeringId, quantity) ->
        require(offeringId.isNotBlank() && quantity.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal("1000000") && it.scale().coerceAtLeast(0) <= 3 } == true) {
            "تحقق من الصنف والكمية المدخلة."
        }
        lineArray.put(JSONObject().put("offering_id", offeringId).put("quantity", quantity))
    }
    val access = customerPortalAccessToken(context)
    val payload = JSONObject()
        .put("p_organization_id", organizationId)
        .put("p_lines", lineArray)
        .put("p_payment_mode", paymentMode)
        .put("p_customer_note", note.take(1000))
        .put("p_idempotency_key", idempotencyKey)
    if (delegationId.isNotBlank()) payload.put("p_delegation_id", delegationId)
    val endpoint = if (delegationId.isNotBlank()) "/rest/v1/rpc/commercial_customer_portal_create_delegated_order"
        else "/rest/v1/rpc/commercial_customer_portal_create_order"
    val row = JSONObject(SupabaseClient.request(endpoint, "POST", payload, access))
    val orderId = row.optString("order_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    require(orderId.isNotBlank()) { "لم يرجع الخادم تأكيدًا صالحًا للطلب؛ لا تنشئ طلبًا جديدًا بمفتاح مختلف." }
    return CustomerPortalOrderResult(orderId, row.optString("order_number"), row.optString("status"),
        row.optString("payment_status"), row.optLong("total_minor", 0L), row.optString("currency_code"), row.optBoolean("replayed"))
}


internal data class CustomerPortalBookingResource(
    val id: String, val name: String, val resourceType: String, val capacity: Int, val timezone: String
)
internal data class CustomerPortalBookingResult(
    val bookingId: String, val bookingNumber: String, val orderId: String,
    val status: String, val priceMinor: Long, val currencyCode: String, val replayed: Boolean
)

internal fun loadCustomerPortalBookingResources(context: Context, organizationId: String, delegationId: String = ""): List<CustomerPortalBookingResource> {
    require(organizationId.isNotBlank()) { "اختر المؤسسة أولًا." }
    val access = customerPortalAccessToken(context)
    val delegated = delegationId.isNotBlank()
    val rows = JSONArray(SupabaseClient.request(
        if (delegated) "/rest/v1/rpc/commercial_account_list_delegated_booking_resources" else "/rest/v1/rpc/commercial_customer_portal_list_booking_resources", "POST",
        if (delegated) JSONObject().put("p_delegation_id", delegationId) else JSONObject().put("p_organization_id", organizationId), access
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank() || name.isBlank()) continue
            add(CustomerPortalBookingResource(id, name, row.optString("resource_type"), row.optInt("capacity", 1), row.optString("timezone", "UTC")))
        }
    }
}

internal fun submitCustomerPortalBooking(
    context: Context, organizationId: String, offeringId: String, resourceId: String,
    startsAt: String, endsAt: String, paymentMode: String, note: String, idempotencyKey: String, delegationId: String = ""
): CustomerPortalBookingResult {
    require(organizationId.isNotBlank() && offeringId.isNotBlank() && resourceId.isNotBlank()) { "اختر الخدمة ومورد الحجز." }
    require(paymentMode == "cash" || paymentMode == "credit") { "طريقة الفاتورة غير صحيحة." }
    val access = customerPortalAccessToken(context)
    val payload = JSONObject()
        .put("p_organization_id", organizationId)
        .put("p_offering_id", offeringId)
        .put("p_resource_id", resourceId)
        .put("p_starts_at", startsAt)
        .put("p_ends_at", endsAt)
        .put("p_payment_mode", paymentMode)
        .put("p_customer_note", note.take(1000))
        .put("p_idempotency_key", idempotencyKey)
    if (delegationId.isNotBlank()) payload.put("p_delegation_id", delegationId)
    val endpoint = if (delegationId.isNotBlank()) "/rest/v1/rpc/commercial_customer_portal_create_delegated_booking"
        else "/rest/v1/rpc/commercial_customer_portal_create_booking"
    val row = JSONObject(SupabaseClient.request(endpoint, "POST", payload, access))
    val bookingId = row.optString("booking_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    require(bookingId.isNotBlank()) { "لم يرجع الخادم تأكيدًا صالحًا للحجز؛ لا تنشئ حجزًا جديدًا بمفتاح مختلف." }
    return CustomerPortalBookingResult(bookingId, row.optString("booking_number"), row.optString("order_id"),
        row.optString("status"), row.optLong("price_minor", 0L), row.optString("currency_code"), row.optBoolean("replayed"))
}


internal fun submitCustomerPortalMultiServiceBooking(
    context: Context,
    organizationId: String,
    lines: List<Pair<String, String>>,
    resourceId: String,
    startsAt: String,
    endsAt: String,
    paymentMode: String,
    note: String,
    idempotencyKey: String,
    delegationId: String = ""
): CustomerPortalBookingResult {
    require(organizationId.isNotBlank() && resourceId.isNotBlank()) { "اختر المؤسسة ومورد الحجز." }
    require(lines.isNotEmpty() && lines.size <= 50) { "اختر خدمة واحدة على الأقل، وبحد أقصى 50 بندًا." }
    require(lines.map { it.first }.distinct().size == lines.size) { "لا تكرر الخدمة نفسها في الفاتورة؛ عدّل كميتها في بند واحد." }
    require(lines.all { (id, quantity) ->
        id.isNotBlank() && quantity.toBigDecimalOrNull()?.let {
            it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal("100000") && it.scale().coerceAtLeast(0) <= 3
        } == true
    }) { "تحقق من كميات الخدمات؛ يجب أن تكون موجبة وبحد أقصى 3 منازل عشرية." }
    require(paymentMode == "cash" || paymentMode == "credit") { "طريقة الفاتورة غير صحيحة." }
    require(idempotencyKey.length in 8..200) { "مفتاح الحجز غير صالح." }

    val payloadLines = JSONArray()
    lines.forEach { (offeringId, quantity) ->
        payloadLines.put(JSONObject().put("offering_id", offeringId).put("quantity", quantity))
    }
    val payload = JSONObject()
        .put("p_organization_id", organizationId)
        .put("p_lines", payloadLines)
        .put("p_resource_id", resourceId)
        .put("p_starts_at", startsAt)
        .put("p_ends_at", endsAt)
        .put("p_payment_mode", paymentMode)
        .put("p_customer_note", note.take(1000))
        .put("p_idempotency_key", idempotencyKey)
    if (delegationId.isNotBlank()) payload.put("p_delegation_id", delegationId)
    val endpoint = if (delegationId.isNotBlank()) "/rest/v1/rpc/commercial_customer_portal_create_delegated_multi_booking"
        else "/rest/v1/rpc/commercial_customer_portal_create_multi_service_booking"
    val row = JSONObject(SupabaseClient.request(endpoint, "POST", payload, customerPortalAccessToken(context)))
    val bookingId = row.optString("booking_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    val orderId = row.optString("order_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    require(bookingId.isNotBlank() && orderId.isNotBlank()) {
        "لم يرجع الخادم تأكيدًا صالحًا للحجز والفاتورة؛ لا تغيّر مفتاح الطلب قبل تحديث سجل الحجوزات."
    }
    return CustomerPortalBookingResult(
        bookingId, row.optString("booking_number"), orderId, row.optString("status"),
        row.optLong("price_minor", 0L), row.optString("currency_code"), row.optBoolean("replayed")
    )
}


internal data class CustomerPortalHistoryItem(
    val id: String,
    val offeringId: String,
    val name: String,
    val itemType: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long
)

internal data class CustomerPortalHistoryOrder(
    val id: String,
    val organizationId: String,
    val organizationName: String,
    val orderNumber: String,
    val createdAt: String,
    val status: String,
    val paymentStatus: String,
    val orderType: String,
    val currencyCode: String,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val taxMinor: Long,
    val totalMinor: Long,
    val note: String,
    val reservedFrom: String,
    val reservedUntil: String,
    val items: List<CustomerPortalHistoryItem>
)

internal data class CustomerPortalHistoryBooking(
    val id: String,
    val organizationId: String,
    val organizationName: String,
    val bookingNumber: String,
    val orderId: String,
    val orderNumber: String,
    val status: String,
    val startsAt: String,
    val endsAt: String,
    val timezone: String,
    val resourceName: String,
    val resourceType: String,
    val priceMinor: Long,
    val paidMinor: Long,
    val currencyCode: String,
    val notes: String,
    val cancellationReason: String
)

internal data class CustomerPortalHistory(
    val orders: List<CustomerPortalHistoryOrder>,
    val bookings: List<CustomerPortalHistoryBooking>,
    val refreshedAt: String
)

internal fun loadCustomerPortalHistory(context: Context, organizationId: String? = null): CustomerPortalHistory {
    val access = customerPortalAccessToken(context)
    val payload = JSONObject()
    if (!organizationId.isNullOrBlank()) payload.put("p_organization_id", organizationId)
    val root = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_history", "POST", payload, access
    ))
    val ordersJson = root.optJSONArray("orders") ?: JSONArray()
    val orders = buildList {
        for (i in 0 until ordersJson.length()) {
            val row = ordersJson.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            val itemsJson = row.optJSONArray("items") ?: JSONArray()
            val items = buildList {
                for (j in 0 until itemsJson.length()) {
                    val item = itemsJson.optJSONObject(j) ?: continue
                    add(CustomerPortalHistoryItem(
                        id = item.optString("id"),
                        offeringId = item.optString("offering_id"),
                        name = item.optString("name").takeUnless { it == "null" }.orEmpty().ifBlank { "صنف غير معروف" },
                        itemType = item.optString("item_type").ifBlank { "product" },
                        quantity = item.optString("quantity").ifBlank { "0" },
                        unitPriceMinor = item.optLong("unit_price_minor", 0L),
                        lineTotalMinor = item.optLong("line_total_minor", 0L)
                    ))
                }
            }
            add(CustomerPortalHistoryOrder(
                id = id,
                organizationId = row.optString("organization_id"),
                organizationName = row.optString("organization_name").takeUnless { it == "null" }.orEmpty(),
                orderNumber = row.optString("order_number").takeUnless { it == "null" }.orEmpty(),
                createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                status = row.optString("status").ifBlank { "unknown" },
                paymentStatus = row.optString("payment_status").ifBlank { "unknown" },
                orderType = row.optString("order_type").ifBlank { "purchase" },
                currencyCode = row.optString("currency_code").ifBlank { "YER" },
                subtotalMinor = row.optLong("subtotal_minor", 0L),
                discountMinor = row.optLong("discount_minor", 0L),
                taxMinor = row.optLong("tax_minor", 0L),
                totalMinor = row.optLong("total_minor", 0L),
                note = row.optString("customer_note").takeUnless { it == "null" }.orEmpty(),
                reservedFrom = row.optString("reserved_from").takeUnless { it == "null" }.orEmpty(),
                reservedUntil = row.optString("reserved_until").takeUnless { it == "null" }.orEmpty(),
                items = items
            ))
        }
    }
    val bookingsJson = root.optJSONArray("bookings") ?: JSONArray()
    val bookings = buildList {
        for (i in 0 until bookingsJson.length()) {
            val row = bookingsJson.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            add(CustomerPortalHistoryBooking(
                id = id,
                organizationId = row.optString("organization_id"),
                organizationName = row.optString("organization_name").takeUnless { it == "null" }.orEmpty(),
                bookingNumber = row.optString("booking_number").takeUnless { it == "null" }.orEmpty(),
                orderId = row.optString("order_id").takeUnless { it == "null" }.orEmpty(),
                orderNumber = row.optString("order_number").takeUnless { it == "null" }.orEmpty(),
                status = row.optString("status").ifBlank { "unknown" },
                startsAt = row.optString("starts_at").takeUnless { it == "null" }.orEmpty(),
                endsAt = row.optString("ends_at").takeUnless { it == "null" }.orEmpty(),
                timezone = row.optString("timezone").ifBlank { "UTC" },
                resourceName = row.optString("resource_name").takeUnless { it == "null" }.orEmpty(),
                resourceType = row.optString("resource_type").takeUnless { it == "null" }.orEmpty(),
                priceMinor = row.optLong("price_minor", 0L),
                paidMinor = row.optLong("paid_minor", 0L),
                currencyCode = row.optString("currency_code").ifBlank { "YER" },
                notes = row.optString("notes").takeUnless { it == "null" }.orEmpty(),
                cancellationReason = row.optString("cancellation_reason").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
    return CustomerPortalHistory(orders, bookings, root.optString("refreshed_at").takeUnless { it == "null" }.orEmpty())
}

internal fun cancelCustomerPortalBooking(context: Context, bookingId: String, reason: String = ""): String {
    require(bookingId.isNotBlank()) { "رقم الحجز غير صالح." }
    val access = customerPortalAccessToken(context)
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_cancel_booking",
        "POST",
        JSONObject().put("p_booking_id", bookingId).put("p_cancellation_reason", reason.take(500)),
        access
    ))
    val id = result.optString("booking_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    require(id.isNotBlank() && result.optString("status") == "cancelled") {
        "لم يؤكد الخادم إلغاء الحجز؛ حدّث سجل الحجوزات قبل إعادة المحاولة."
    }
    return result.optString("status")
}




internal data class CustomerOrderFulfillmentItem(
    val offeringId: String,
    val name: String,
    val itemType: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long
)

internal data class CustomerOrderFulfillmentOrder(
    val id: String,
    val organizationId: String,
    val orderNumber: String,
    val createdAt: String,
    val status: String,
    val paymentStatus: String,
    val orderType: String,
    val currencyCode: String,
    val totalMinor: Long,
    val customerNote: String,
    val customerName: String,
    val fulfillmentSupported: Boolean,
    val items: List<CustomerOrderFulfillmentItem>,
    val hidden: Boolean = false,
    val decisionReason: String = "",
    val reviewedAt: String = "",
    val receiptNumber: String = "",
    val saleId: String = "",
    val fulfilledAt: String = "",
    val paidMinor: Long = 0L,
    val outstandingMinor: Long = 0L,
    val delegatedDisplayName: String = "",
    val delegatedIconKey: String = "",
    val actingAccountNumber: String = ""
)

internal data class CustomerOrderFulfillmentLocation(val id: String, val name: String)

internal fun loadCustomerOrderFulfillmentQueue(context: Context, includeHidden: Boolean = false): List<CustomerOrderFulfillmentOrder> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_organization_orders_v2", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_limit", 200).put("p_include_hidden", includeHidden), access
    ))
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        val itemRows = row.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemRows.length()).map { itemIndex ->
            val item = itemRows.getJSONObject(itemIndex)
            CustomerOrderFulfillmentItem(
                offeringId = item.optString("offering_id"),
                name = item.optString("name"),
                itemType = item.optString("item_type"),
                quantity = item.optString("quantity"),
                unitPriceMinor = item.optLong("unit_price_minor"),
                lineTotalMinor = item.optLong("line_total_minor")
            )
        }
        val fulfillment = row.optJSONObject("fulfillment") ?: JSONObject()
        CustomerOrderFulfillmentOrder(
            id = row.optString("id"),
            organizationId = row.optString("organization_id"),
            orderNumber = row.optString("order_number"),
            createdAt = row.optString("created_at"),
            status = row.optString("status"),
            paymentStatus = row.optString("payment_status"),
            orderType = row.optString("order_type"),
            currencyCode = row.optString("currency_code"),
            totalMinor = row.optLong("total_minor"),
            customerNote = row.optString("customer_note").takeUnless { it == "null" }.orEmpty(),
            customerName = row.optString("customer_name").takeUnless { it == "null" }.orEmpty(),
            delegatedDisplayName = row.optString("delegated_display_name").takeUnless { it == "null" }.orEmpty(),
            delegatedIconKey = row.optString("delegated_icon_key").takeUnless { it == "null" }.orEmpty(),
            actingAccountNumber = row.optString("acting_account_number").takeUnless { it == "null" }.orEmpty(),
            fulfillmentSupported = row.optBoolean("fulfillment_supported"),
            items = items,
            hidden = row.optBoolean("merchant_hidden"),
            decisionReason = row.optString("merchant_decision_reason").takeUnless { it == "null" }.orEmpty(),
            reviewedAt = row.optString("merchant_reviewed_at").takeUnless { it == "null" }.orEmpty(),
            receiptNumber = fulfillment.optString("receipt_number").takeUnless { it == "null" }.orEmpty(),
            saleId = fulfillment.optString("sale_id").takeUnless { it == "null" }.orEmpty(),
            fulfilledAt = fulfillment.optString("fulfilled_at").takeUnless { it == "null" }.orEmpty(),
            paidMinor = fulfillment.optLong("paid_minor"),
            outstandingMinor = fulfillment.optLong("outstanding_minor")
        )
    }
}

internal fun loadCustomerOrderFulfillmentLocations(context: Context): List<CustomerOrderFulfillmentLocation> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_organization_locations?select=id,name&organization_id=eq.$orgId&state=eq.active&order=name.asc",
        access
    )
    return (0 until rows.length()).map { index ->
        val row = rows.getJSONObject(index)
        CustomerOrderFulfillmentLocation(row.optString("id"), row.optString("name"))
    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
}

internal fun fulfillCustomerPortalOrder(
    context: Context,
    orderId: String,
    locationId: String,
    idempotencyKey: String
): JSONObject {
    require(orderId.isNotBlank() && locationId.isNotBlank()) {
        "مرجع الطلب وموقع المخزون مطلوبان."
    }
    require(idempotencyKey.length in 8..200) { "مفتاح إتمام الطلب غير صالح." }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_fulfill_order", "POST",
        JSONObject()
            .put("p_organization_id", orgId)
            .put("p_order_id", orderId)
            .put("p_location_id", locationId)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun cancelCustomerPortalOrder(context: Context, orderId: String, reason: String = ""): String {
    require(orderId.isNotBlank()) { "رقم الطلب غير صالح." }
    val access = customerPortalAccessToken(context)
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_cancel_order",
        "POST",
        JSONObject().put("p_order_id", orderId).put("p_cancellation_reason", reason.take(500)),
        access
    ))
    val id = result.optString("order_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
    require(id.isNotBlank() && result.optString("status") == "cancelled") {
        "لم يؤكد الخادم إلغاء الطلب؛ حدّث سجل الطلبات قبل إعادة المحاولة."
    }
    return if (result.optBoolean("already_cancelled")) "already_cancelled" else "cancelled"
}


internal data class MerchantBookingItem(val name: String, val quantity: String, val unitPriceMinor: Long, val lineTotalMinor: Long, val itemType: String)
internal data class MerchantBooking(
    val id: String, val organizationId: String, val bookingNumber: String, val orderId: String,
    val orderNumber: String, val createdAt: String, val startsAt: String, val endsAt: String,
    val status: String, val currencyCode: String, val totalMinor: Long, val notes: String,
    val customerName: String, val resourceName: String, val timezone: String, val orderStatus: String, val paymentStatus: String,
    val hidden: Boolean, val decisionReason: String, val items: List<MerchantBookingItem>,
    val delegatedDisplayName: String = "", val delegatedIconKey: String = "", val actingAccountNumber: String = ""
)
internal data class MerchantEditableOffering(val id: String, val name: String, val priceMinor: Long, val currencyCode: String, val unitCode: String)

private fun merchantManagementContext(context: Context): Pair<String, String> = commercialMasterDataAccess(context)

internal fun loadMerchantBookings(context: Context, includeHidden: Boolean = false): List<MerchantBooking> {
    val (orgId, access) = merchantManagementContext(context)
    val rows = JSONArray(SupabaseClient.request(
        "/rest/v1/rpc/commercial_customer_portal_list_organization_bookings", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_limit", 200).put("p_include_hidden", includeHidden), access
    ))
    return (0 until rows.length()).map { i ->
        val row = rows.getJSONObject(i)
        val itemRows = row.optJSONArray("items") ?: JSONArray()
        val items = (0 until itemRows.length()).map { n ->
            val item = itemRows.getJSONObject(n)
            MerchantBookingItem(item.optString("name"), item.optString("quantity"), item.optLong("unit_price_minor"), item.optLong("line_total_minor"), item.optString("item_type"))
        }
        MerchantBooking(
            id=row.optString("id"), organizationId=row.optString("organization_id"), bookingNumber=row.optString("booking_number"),
            orderId=row.optString("order_id"), orderNumber=row.optString("order_number"), createdAt=row.optString("created_at"),
            startsAt=row.optString("starts_at"), endsAt=row.optString("ends_at"), status=row.optString("status"),
            currencyCode=row.optString("currency_code"), totalMinor=row.optLong("price_minor"), notes=row.optString("notes").takeUnless { it=="null" }.orEmpty(),
            customerName=row.optString("customer_name"), resourceName=row.optString("resource_name"), timezone=row.optString("timezone","UTC"), orderStatus=row.optString("order_status"),
            paymentStatus=row.optString("payment_status"), hidden=row.optBoolean("merchant_hidden"),
            delegatedDisplayName=row.optString("delegated_display_name").takeUnless { it=="null" }.orEmpty(),
            delegatedIconKey=row.optString("delegated_icon_key").takeUnless { it=="null" }.orEmpty(),
            actingAccountNumber=row.optString("acting_account_number").takeUnless { it=="null" }.orEmpty(),
            decisionReason=row.optString("merchant_decision_reason").takeUnless { it=="null" }.orEmpty(), items=items
        )
    }
}

internal fun decideMerchantCustomerOrder(context: Context, orderId: String, decision: String, reason: String = ""): JSONObject {
    require(orderId.isNotBlank() && decision in setOf("accept","reject")) { "قرار الطلب غير صالح." }
    val (orgId, access)=merchantManagementContext(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_decide_order","POST",
        JSONObject().put("p_organization_id",orgId).put("p_order_id",orderId).put("p_decision",decision).put("p_reason",reason.take(500)),access))
}

internal fun setMerchantCustomerOrderHidden(context: Context, orderId: String, hidden: Boolean): JSONObject {
    require(orderId.isNotBlank()) { "رقم الطلب غير صالح." }
    val (orgId, access)=merchantManagementContext(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_set_order_hidden","POST",
        JSONObject().put("p_organization_id",orgId).put("p_order_id",orderId).put("p_hidden",hidden),access))
}

internal fun decideMerchantCustomerBooking(context: Context, bookingId: String, decision: String, reason: String = ""): JSONObject {
    require(bookingId.isNotBlank() && decision in setOf("accept","reject")) { "قرار الحجز غير صالح." }
    val (orgId, access)=merchantManagementContext(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_decide_booking","POST",
        JSONObject().put("p_organization_id",orgId).put("p_booking_id",bookingId).put("p_decision",decision).put("p_reason",reason.take(500)),access))
}

internal fun setMerchantCustomerBookingHidden(context: Context, bookingId: String, hidden: Boolean): JSONObject {
    require(bookingId.isNotBlank()) { "رقم الحجز غير صالح." }
    val (orgId, access)=merchantManagementContext(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_set_booking_hidden","POST",
        JSONObject().put("p_organization_id",orgId).put("p_booking_id",bookingId).put("p_hidden",hidden),access))
}

internal fun loadMerchantEditableOfferings(context: Context): List<MerchantEditableOffering> {
    val (orgId, access)=merchantManagementContext(context)
    val rows=JSONArray(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_list_editable_offerings","POST",
        JSONObject().put("p_organization_id",orgId),access))
    return (0 until rows.length()).map { i ->
        val row=rows.getJSONObject(i)
        MerchantEditableOffering(row.optString("id"),row.optString("name"),row.optLong("price_minor"),row.optString("currency_code"),row.optString("unit_code","EA"))
    }
}

internal fun saveMerchantCustomerOrderLines(context: Context, orderId: String, lines: List<Pair<String, String>>, reason: String): JSONObject {
    require(orderId.isNotBlank() && lines.isNotEmpty()) { "يجب أن يحتوي الطلب على صنف واحد على الأقل." }
    val payload=JSONArray()
    lines.forEach { (offeringId,quantity) ->
        require(offeringId.isNotBlank() && quantity.toBigDecimalOrNull()?.let { it>java.math.BigDecimal.ZERO && it<=java.math.BigDecimal("100000") && it.stripTrailingZeros().scale()<=0 }==true) {
            "تحقق من الأصناف والكميات؛ يجب أن تكون كميات المنتجات أعدادًا صحيحة موجبة."
        }
        payload.put(JSONObject().put("offering_id",offeringId).put("quantity",quantity))
    }
    val (orgId, access)=merchantManagementContext(context)
    return JSONObject(SupabaseClient.request("/rest/v1/rpc/commercial_customer_portal_edit_order_lines","POST",
        JSONObject().put("p_organization_id",orgId).put("p_order_id",orderId).put("p_lines",payload).put("p_reason",reason.take(500)),access))
}


/** Read-only projection of posted double-entry journal lines, joined to their source entry and ledger account. */
internal data class CommercialCloudSupplierControlAccount(
    val accountRole: String,
    val ledgerAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudSupplierControlAccounts(context: Context): List<CommercialCloudSupplierControlAccount> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_supplier_gl_accounts?select=account_role,ledger_account_id,currency_code&organization_id=eq.$orgId&order=account_role.asc,currency_code.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val role = row.optString("account_role").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (role.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudSupplierControlAccount(role, accountId, currency))
            }
        }
    }
}

internal data class CommercialCloudGlAccountMapping(
    val mappingKey: String,
    val ledgerAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudGlAccountMappings(context: Context): List<CommercialCloudGlAccountMapping> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_gl_account_mappings?select=mapping_key,ledger_account_id,ledger_account:commercial_ledger_accounts!commercial_gl_account_mapping_organization_id_ledger_accou_fkey(currency_code)&organization_id=eq.$orgId&order=mapping_key.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val key = row.optString("mapping_key").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = row.optJSONObject("ledger_account")
            val currency = account?.optString("currency_code").takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty()
            if (key.isNotBlank() && accountId.isNotBlank()) add(CommercialCloudGlAccountMapping(key, accountId, currency))
        }
    }
}

internal data class CommercialCloudPartyAccountLink(
    val partyId: String,
    val partyAccountId: String,
    val currencyCode: String
)

internal fun loadCommercialCloudCustomerPartyAccounts(context: Context): List<CommercialCloudPartyAccountLink> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_customer_party_accounts?select=customer_id,party_account_id,party_account:commercial_party_accounts!commercial_customer_party_accounts_party_account_id_fkey(currency_code)&organization_id=eq.$orgId&order=customer_id.asc,party_account_id.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val partyId = row.optString("customer_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = row.optJSONObject("party_account")
            val currency = account?.optString("currency_code").takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty()
            if (partyId.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudPartyAccountLink(partyId, accountId, currency))
            }
        }
    }
}

internal fun loadCommercialCloudSupplierPartyAccounts(context: Context): List<CommercialCloudPartyAccountLink> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val rows = commercialCloudPagedGet(
        "/rest/v1/commercial_supplier_party_accounts?select=supplier_id,party_account_id,currency_code&organization_id=eq.$orgId&order=supplier_id.asc,currency_code.asc",
        access
    )
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val partyId = row.optString("supplier_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val accountId = row.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (partyId.isNotBlank() && accountId.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialCloudPartyAccountLink(partyId, accountId, currency))
            }
        }
    }
}

internal data class CommercialCloudJournalLine(
    val entryId: String,
    val entryNumber: String,
    val entryDate: String,
    val entryDescription: String,
    val sourceType: String,
    val sourceId: String,
    val lineNumber: Int,
    val accountId: String,
    val accountCode: String,
    val accountName: String,
    val accountType: String,
    val normalBalance: String,
    val partyAccountId: String,
    val debitMinor: Long,
    val creditMinor: Long,
    val currencyCode: String
)

/**
 * Loads posted entries and their lines from the authoritative organization-scoped ledger.
 * Draft/unposted entries are intentionally excluded. This function is read-only and does not post
 * or repair accounting data.
 */
internal fun loadCommercialCloudPostedJournalLines(context: Context): List<CommercialCloudJournalLine> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val entries = commercialCloudPagedGet(
        "/rest/v1/commercial_journal_entries?select=id,entry_number,status,entry_date,description,source_type,source_id" +
            "&organization_id=eq.$orgId&status=eq.posted&order=entry_date.asc,id.asc",
        access
    )
    val entryById = buildMap {
        for (i in 0 until entries.length()) {
            val row = entries.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank() && row.optString("status").equals("posted", true)) put(id, row)
        }
    }
    if (entryById.isEmpty()) return emptyList()

    val lines = commercialCloudPagedGet(
        "/rest/v1/commercial_journal_lines?select=id,journal_entry_id,line_number,ledger_account_id,party_account_id,description,debit_minor,credit_minor,currency_code" +
            "&organization_id=eq.$orgId&order=journal_entry_id.asc,line_number.asc",
        access
    )
    val accounts = commercialCloudPagedGet(
        "/rest/v1/commercial_ledger_accounts?select=id,account_code,account_name,account_type,normal_balance,currency_code,is_active" +
            "&organization_id=eq.$orgId&order=account_code.asc,id.asc",
        access
    )
    val accountById = buildMap {
        for (i in 0 until accounts.length()) {
            val row = accounts.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank()) put(id, row)
        }
    }
    return buildList {
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val entryId = line.optString("journal_entry_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val entry = entryById[entryId] ?: continue
            val accountId = line.optString("ledger_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val account = accountById[accountId]
            add(CommercialCloudJournalLine(
                entryId = entryId,
                entryNumber = entry.optString("entry_number").takeUnless { it == "null" }.orEmpty(),
                entryDate = entry.optString("entry_date").takeUnless { it == "null" }.orEmpty(),
                entryDescription = entry.optString("description").takeUnless { it == "null" }.orEmpty(),
                sourceType = entry.optString("source_type").takeUnless { it == "null" }.orEmpty(),
                sourceId = entry.optString("source_id").takeUnless { it == "null" }.orEmpty(),
                lineNumber = line.optInt("line_number", i + 1),
                accountId = accountId,
                accountCode = account?.optString("account_code").takeUnless { it == null || it == "null" }.orEmpty(),
                accountName = account?.optString("account_name").takeUnless { it == null || it == "null" }.orEmpty(),
                accountType = account?.optString("account_type").takeUnless { it == null || it == "null" }.orEmpty(),
                normalBalance = account?.optString("normal_balance").takeUnless { it == null || it == "null" }.orEmpty(),
                partyAccountId = line.optString("party_account_id").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                debitMinor = line.optLong("debit_minor", 0L),
                creditMinor = line.optLong("credit_minor", 0L),
                currencyCode = line.optString("currency_code").takeUnless { it == "null" }.orEmpty()
                    .ifBlank { account?.optString("currency_code").takeUnless { it == null || it == "null" }.orEmpty() }
            ))
        }
    }
}


internal data class CommercialCloudLocationInventoryBalance(
    val locationId: String,
    val locationName: String,
    val locationType: String,
    val offeringId: String,
    val productName: String,
    val unitName: String,
    val quantityOnHand: java.math.BigDecimal,
    val quantityReserved: java.math.BigDecimal
)

/** Loads current inventory balances for every organization location, without using the selected-location filter. */
internal fun loadCommercialCloudLocationInventoryBalances(context: Context): List<CommercialCloudLocationInventoryBalance> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val locationRows = commercialCloudPagedGet(
        "/rest/v1/commercial_organization_locations" +
            "?select=id,name,location_type&organization_id=eq.$orgId&order=name.asc,id.asc",
        access
    )
    val locations = buildMap {
        for (index in 0 until locationRows.length()) {
            val row = locationRows.optJSONObject(index) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isBlank()) continue
            val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val type = row.optString("location_type").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            require(put(id, (name.ifBlank { "موقع بلا اسم" }) to type) == null) {
                "يوجد معرف موقع مكرر في بيانات المؤسسة؛ تم إيقاف تقرير أرصدة المواقع."
            }
        }
    }
    val balanceRows = commercialCloudPagedGet(
        "/rest/v1/commercial_inventory_balances" +
            "?select=location_id,offering_id,quantity_on_hand,quantity_reserved,offering:commercial_offerings!inner(id,product:commercial_products!inner(name,unit_name))" +
            "&organization_id=eq.$orgId&order=location_id.asc,offering_id.asc",
        access
    )
    return buildList {
        for (index in 0 until balanceRows.length()) {
            val row = balanceRows.optJSONObject(index) ?: continue
            val locationId = row.optString("location_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val offeringId = row.optString("offering_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            require(locationId.isNotBlank() && offeringId.isNotBlank()) {
                "يوجد رصيد مخزون بلا موقع أو صنف مرتبط؛ تم حجب تقرير أرصدة المواقع."
            }
            val location = locations[locationId] ?: throw IllegalStateException(
                "يوجد رصيد مخزون مرتبط بموقع غير موجود ضمن المؤسسة؛ تم حجب التقرير."
            )
            val offering = row.optJSONObject("offering")
                ?: throw IllegalStateException("تعذر قراءة الصنف المرتبط بأحد أرصدة المخزون.")
            val product = offering.optJSONObject("product")
                ?: throw IllegalStateException("تعذر قراءة بيانات الصنف المرتبط بأحد أرصدة المخزون.")
            val onHand = runCatching { java.math.BigDecimal(row.optString("quantity_on_hand")) }
                .getOrElse { throw IllegalStateException("توجد كمية مخزون غير رقمية؛ تم حجب التقرير.") }
            val reserved = runCatching { java.math.BigDecimal(row.optString("quantity_reserved")) }
                .getOrElse { throw IllegalStateException("توجد كمية محجوزة غير رقمية؛ تم حجب التقرير.") }
            require(reserved.signum() >= 0) { "توجد كمية محجوزة سالبة؛ تم حجب تقرير أرصدة المواقع." }
            add(CommercialCloudLocationInventoryBalance(
                locationId = locationId,
                locationName = location.first,
                locationType = location.second,
                offeringId = offeringId,
                productName = product.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty().ifBlank { "صنف بلا اسم" },
                unitName = product.optString("unit_name").takeUnless { it.isBlank() || it == "null" }.orEmpty().ifBlank { "وحدة غير محددة" },
                quantityOnHand = onHand,
                quantityReserved = reserved
            ))
        }
    }
}


internal data class CommercialCloudWarehouseInventoryContext(
    val inventoryBalanceId: String,
    val locationId: String,
    val locationName: String,
    val offeringId: String,
    val productName: String,
    val unitName: String,
    val warehouseId: String?,
    val warehouseCode: String,
    val warehouseName: String,
    val warehouseState: String,
    val mappingStatus: String,
    val mappedWarehouseCount: Int,
    val quantityOnHand: BigDecimal,
    val quantityReserved: BigDecimal,
    val availableQuantity: BigDecimal,
    val reorderPoint: BigDecimal
)

/**
 * Read-only readiness projection. Quantities stay per offering/unit; this function never
 * aggregates unlike units or mutates balances, warehouse assignments, or stock movements.
 */
internal fun loadCommercialCloudWarehouseInventoryContext(context: Context): List<CommercialCloudWarehouseInventoryContext> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_inventory_warehouse_context" +
        "?select=inventory_balance_id,organization_id,location_id,offering_id,warehouse_id,warehouse_mapping_status,mapped_warehouse_count,quantity_on_hand,quantity_reserved,available_quantity,reorder_point,warehouse_code,warehouse_name,warehouse_state,location:commercial_organization_locations!inner(name),offering:commercial_offerings!inner(activity_id,product:commercial_products!inner(name,unit_name))" +
        "&organization_id=eq.$orgId&offering.activity_id=eq.$activityId&order=warehouse_mapping_status.asc,location_id.asc,offering_id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
                ?: throw IllegalStateException("تعذر قراءة أحد صفوف ربط المخزون بالمستودعات.")
            val offering = row.optJSONObject("offering")
                ?: throw IllegalStateException("يوجد رصيد مخزون بلا صنف مرتبط؛ تم حجب تقرير ربط المستودعات.")
            if (offering.optString("activity_id") != activityId) continue
            val location = row.optJSONObject("location")
                ?: throw IllegalStateException("يوجد رصيد مخزون بلا موقع مؤسسي صالح؛ تم حجب التقرير.")
            val product = offering.optJSONObject("product")
                ?: throw IllegalStateException("يوجد رصيد مخزون بلا بيانات صنف صالحة؛ تم حجب التقرير.")
            fun requiredText(field: String): String = row.optString(field)
                .takeUnless { it.isBlank() || it == "null" }
                ?: throw IllegalStateException("حقل $field مفقود في إسقاط ربط المستودعات.")
            fun decimal(field: String): BigDecimal = runCatching { BigDecimal(row.optString(field)) }
                .getOrElse { throw IllegalStateException("قيمة $field غير رقمية؛ تم حجب التقرير.") }
            val balanceId = requiredText("inventory_balance_id")
            val locationId = requiredText("location_id")
            val offeringId = requiredText("offering_id")
            val locationName = location.optString("name").takeUnless { it.isBlank() || it == "null" }
                ?: throw IllegalStateException("يوجد موقع بلا اسم في تقرير ربط المستودعات.")
            val productName = product.optString("name").takeUnless { it.isBlank() || it == "null" }
                ?: throw IllegalStateException("يوجد صنف بلا اسم في تقرير ربط المستودعات.")
            val unitName = product.optString("unit_name").takeUnless { it.isBlank() || it == "null" }
                ?: "وحدة غير محددة"
            val status = requiredText("warehouse_mapping_status")
            require(status in setOf("mapped", "unconfigured", "ambiguous")) {
                "حالة ربط مستودع غير معروفة؛ تم حجب التقرير."
            }
            val mappedCount = row.optInt("mapped_warehouse_count", -1)
            require(
                (status == "mapped" && mappedCount == 1) ||
                    (status == "unconfigured" && mappedCount == 0) ||
                    (status == "ambiguous" && mappedCount > 1)
            ) { "عدد روابط المستودع لا يتطابق مع حالة الربط؛ تم حجب التقرير." }
            val warehouseId = row.optString("warehouse_id").takeUnless { it.isBlank() || it == "null" }
            val warehouseCode = row.optString("warehouse_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val warehouseName = row.optString("warehouse_name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val warehouseState = row.optString("warehouse_state").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            require(if (status == "mapped") !warehouseId.isNullOrBlank() && warehouseName.isNotBlank() else warehouseId == null) {
                "بيانات المستودع لا تتطابق مع حالة الربط؛ تم حجب التقرير."
            }
            val onHand = decimal("quantity_on_hand")
            val reserved = decimal("quantity_reserved")
            val available = decimal("available_quantity")
            val reorder = decimal("reorder_point")
            require(onHand.signum() >= 0 && reserved.signum() >= 0 && reorder.signum() >= 0 &&
                available == onHand.subtract(reserved).max(BigDecimal.ZERO)) {
                "أحد أرصدة المخزون أو كمياته المحسوبة غير متسقة؛ تم حجب التقرير."
            }
            add(CommercialCloudWarehouseInventoryContext(
                inventoryBalanceId = balanceId,
                locationId = locationId,
                locationName = locationName,
                offeringId = offeringId,
                productName = productName,
                unitName = unitName,
                warehouseId = warehouseId,
                warehouseCode = warehouseCode,
                warehouseName = warehouseName,
                warehouseState = warehouseState,
                mappingStatus = status,
                mappedWarehouseCount = mappedCount,
                quantityOnHand = onHand,
                quantityReserved = reserved,
                availableQuantity = available,
                reorderPoint = reorder
            ))
        }
    }
}
