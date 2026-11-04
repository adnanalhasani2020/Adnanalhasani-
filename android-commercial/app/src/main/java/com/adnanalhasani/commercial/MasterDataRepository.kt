package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
