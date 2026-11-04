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
