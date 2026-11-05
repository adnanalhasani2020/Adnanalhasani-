package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

private fun parseWholeInventoryQuantity(raw: String, field: String): BigDecimal {
    val value = runCatching { BigDecimal(raw) }
        .getOrElse { throw IllegalStateException("قيمة $field غير رقمية؛ تم إيقاف قراءة المخزون.") }
    require(value.stripTrailingZeros().scale() <= 0) {
        "قيمة $field كسرية؛ تم إيقاف قراءة المخزون دون تقريبها."
    }
    return value
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
            val quantity = parseWholeInventoryQuantity(row.optString("quantity_delta"), "quantity_delta")
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

/** Read-only stock alerts with whole quantities; fractional server values fail closed. */
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
            val onHand = parseWholeInventoryQuantity(row.optString("quantity_on_hand", "0"), "quantity_on_hand")
            val reserved = parseWholeInventoryQuantity(row.optString("quantity_reserved", "0"), "quantity_reserved")
            val reorder = parseWholeInventoryQuantity(row.optString("reorder_point", "0"), "reorder_point")
            require(onHand.signum() >= 0 && reserved.signum() >= 0 && reserved <= onHand && reorder.signum() >= 0) {
                "رصيد المخزون أو الحد الأدنى غير متسق؛ تم إيقاف التنبيه دون تعديل البيانات."
            }
            add(CommercialCloudStockAlert(
                offeringId = offering.optString("id"),
                productName = product.optString("name").ifBlank { "صنف بدون اسم" },
                categoryName = categoryName,
                unitName = product.optString("unit_name").ifBlank { "وحدة" },
                availableQuantity = onHand.subtract(reserved).toDouble(),
                reorderPoint = reorder.toDouble(),
                quantityOnHand = onHand,
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
        val remaining = parseWholeInventoryQuantity(row.optString("quantity_remaining", "0"), "quantity_remaining")
        val unitCost = row.optLong("unit_cost_minor", -1L)
        if (remaining.signum() <= 0 || unitCost < 0L) continue
        activeLayers++
        offerings += row.optString("offering_id")
        quantity = quantity.add(remaining)
        valueMinor = valueMinor.add(remaining.multiply(BigDecimal.valueOf(unitCost)))
    }
    return CommercialCloudCostLayerSummary(activeLayers, offerings.size, quantity, valueMinor.setScale(0, java.math.RoundingMode.HALF_UP))
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
            val onHand = parseWholeInventoryQuantity(row.optString("quantity_on_hand"), "quantity_on_hand")
            val reserved = parseWholeInventoryQuantity(row.optString("quantity_reserved"), "quantity_reserved")
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
            fun decimal(field: String): BigDecimal = parseWholeInventoryQuantity(row.optString(field), field)
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
