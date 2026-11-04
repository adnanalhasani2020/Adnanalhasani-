package com.adnanalhasani.commercial

import org.json.JSONArray
import org.json.JSONObject

/**
 * Coordinates catalog market operations while keeping UI state owned by the screen.
 *
 * Network contracts and user-facing outcomes intentionally match the former
 * CatalogScreen implementations.
 */
internal class CatalogMarketWorkflow(
    private val state: CatalogMarketUiState,
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val selectedActivityCode: () -> String,
    private val selectedCatalogIds: () -> Set<String>,
    private val currencyCode: () -> String,
    private val request: (
        action: () -> String,
        onSuccess: (String) -> Unit,
        onFailure: ((Throwable) -> Unit)?
    ) -> Unit,
    private val onFeedback: (String) -> Unit
) {
    fun saveBranchCoordinates(latitude: String?, longitude: String?) {
        val active = accessToken()
        val orgId = organizationId()
        if (active == null || orgId == null || state.selectedBranchId.isBlank()) {
            onFeedback("اختر مؤسسة وفرعًا صالحين أولًا.")
            return
        }
        val branchId = state.selectedBranchId
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_update_location_coordinates",
                JSONObject()
                    .put("p_organization_id", orgId)
                    .put("p_location_id", branchId)
                    .put("p_latitude", latitude ?: JSONObject.NULL)
                    .put("p_longitude", longitude ?: JSONObject.NULL),
                active
            )
        }, { raw ->
            val result = JSONObject(raw)
            val savedLat = result.optString("latitude").takeUnless { it == "null" }.orEmpty()
            val savedLon = result.optString("longitude").takeUnless { it == "null" }.orEmpty()
            state.marketBranches = state.marketBranches.map { branch ->
                if (branch.id == branchId) branch.copy(latitude = savedLat, longitude = savedLon) else branch
            }
            state.branchLatitude = savedLat
            state.branchLongitude = savedLon
            onFeedback(
                if (savedLat.isBlank()) "تم مسح إحداثيات الفرع."
                else "تم حفظ إحداثيات الفرع وتسجيل التغيير في سجل التدقيق."
            )
        }, null)
    }

    fun loadMarketContext() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        request({
            val branches = catalogGet(
                "/rest/v1/commercial_organization_locations?select=id,name,latitude,longitude&organization_id=$orgId&state=eq.active&order=name.asc",
                active
            )
            val warehouses = catalogGet(
                "/rest/v1/commercial_warehouses?select=id,name,branch_location_id&organization_id=$orgId&state=eq.active&order=name.asc",
                active
            )
            JSONObject()
                .put("branches", JSONArray(branches))
                .put("warehouses", JSONArray(warehouses))
                .toString()
        }, { raw ->
            val marketContext = parseCatalogMarketContext(raw)
            state.marketBranches = marketContext.branches
            state.marketWarehouses = marketContext.warehouses
            if (state.selectedBranchId.isBlank()) {
                state.selectedBranchId = state.marketBranches.firstOrNull()?.id.orEmpty()
            }
            if (
                state.selectedWarehouseId.isBlank() ||
                state.marketWarehouses.none {
                    it.id == state.selectedWarehouseId && it.branchId == state.selectedBranchId
                }
            ) {
                state.selectedWarehouseId = state.marketWarehouses
                    .firstOrNull { it.branchId == state.selectedBranchId }?.id.orEmpty()
            }
            val selectedBranch = state.marketBranches.firstOrNull { it.id == state.selectedBranchId }
            state.branchLatitude = selectedBranch?.latitude.orEmpty()
            state.branchLongitude = selectedBranch?.longitude.orEmpty()
            onFeedback("تم تحميل الفروع والمستودعات: " + state.marketBranches.size + " / " + state.marketWarehouses.size)
        }, null)
    }

    fun configureSelectedCatalogItems() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        val activityCode = selectedActivityCode()
        val branchId = state.selectedBranchId
        val warehouseId = state.selectedWarehouseId
        val selectedIds = selectedCatalogIds().toList()
        if (activityCode.isBlank() || branchId.isBlank() || warehouseId.isBlank() || selectedIds.isEmpty()) {
            onFeedback("اختر النشاط والفرع والمستودع وحدد الأصناف أولًا.")
            return
        }
        if (selectedIds.size > 100) {
            onFeedback("الحد الأقصى للعملية الواحدة 100 صنف.")
            return
        }
        request({
            // Fetch authoritative unit rows for every selected item, including nearby results.
            // Product-unit RLS inherits visibility from the global catalog item.
            val filter = java.net.URLEncoder.encode("in.(" + selectedIds.joinToString(",") + ")", "UTF-8")
            val unitRaw = catalogGet(
                "/rest/v1/commercial_catalog_product_units?select=product_id,unit_code,unit_role,quantity_in_base,is_default&is_active=eq.true&product_id=$filter&order=product_id.asc,is_default.desc,created_at.asc",
                active
            )
            val payloadItems = buildCatalogConfigurationItems(selectedIds, JSONArray(unitRaw))
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_configure_items",
                JSONObject()
                    .put("p_organization_id", orgId)
                    .put("p_activity_node_code", activityCode)
                    .put("p_branch_location_id", branchId)
                    .put("p_warehouse_id", warehouseId)
                    .put("p_items", payloadItems),
                active
            )
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            onFeedback(
                "تمت تهيئة " + (result?.optInt("configured_count", selectedIds.size) ?: selectedIds.size) +
                    " صنفًا للنشاط والفرع والمستودع، بما فيها نتائج السوق القريب. الأصناف غير المسعرة تبقى غير متاحة للبيع ولا يُنشأ مخزون تلقائيًا."
            )
        }, null)
    }

    fun loadNearbyCatalog(expandRadius: Boolean = false) {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        val branch = state.marketBranches.firstOrNull { it.id == state.selectedBranchId }
        val useDeviceCenter = state.deviceLatitude != null && state.deviceLongitude != null
        val latitude = if (useDeviceCenter) state.deviceLatitude?.toBigDecimalOrNull() else branch?.latitude?.toBigDecimalOrNull()
        val longitude = if (useDeviceCenter) state.deviceLongitude?.toBigDecimalOrNull() else branch?.longitude?.toBigDecimalOrNull()
        if (latitude == null || longitude == null) {
            onFeedback("حدد موقعك الحالي أو اختر فرعًا بإحداثيات محفوظة.")
            return
        }
        val radiusSteps = listOf(5000, 10000, 25000, 50000, 100000)
        val nextRadius = if (expandRadius) radiusSteps.firstOrNull { it > state.marketRadius } ?: state.marketRadius else state.marketRadius
        if (expandRadius && nextRadius == state.marketRadius) {
            onFeedback("تم بلوغ أقصى نطاق بحث (100 كم).")
            return
        }
        val requestedOffset = if (expandRadius) 0 else state.nearbyOffset
        state.nearbyPageLoadFailed = false
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_nearby_popularity",
                JSONObject()
                    .put("p_organization_id", orgId)
                    .put("p_latitude", latitude.toPlainString())
                    .put("p_longitude", longitude.toPlainString())
                    .put("p_radius_m", nextRadius)
                    .put("p_offset", requestedOffset)
                    .put("p_limit", 30),
                active
            )
        }, { raw ->
            val page = parseNearbyCatalogItems(raw)
            if (expandRadius) state.marketRadius = nextRadius
            state.nearbyOffset = requestedOffset + page.size
            state.nearbyHasMore = page.size == 30
            state.nearbyMarketItems = (state.nearbyMarketItems + page).distinctBy { it.id }
            state.nearbyLoadGeneration++
            state.nearbyPageLoadFailed = false
            onFeedback(
                if (page.isEmpty()) "لا توجد نتائج إضافية في هذا النطاق؛ اسحب قائمة السوق لتوسيع البحث."
                else "تم تحميل " + page.size + " نتيجة جديدة. النتائج مجمعة ولا تكشف هوية التجار."
            )
        }, { error ->
            state.nearbyPageLoadFailed = true
            onFeedback(
                "تعذر تحميل نتائج السوق القريب. تحقق من الاتصال ثم أعد المحاولة: " +
                    (error.message ?: "خطأ غير معروف")
            )
        })
    }

    fun startNearbyCatalog() {
        state.nearbyMarketItems = emptyList()
        state.nearbyOffset = 0
        state.nearbyHasMore = true
        state.nearbyPageLoadFailed = false
        state.nearbyLoadGeneration++
        state.marketRadius = 5000
        loadNearbyCatalog(false)
    }

    fun loadSelectedPriceSuggestions() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run {
            onFeedback("أدخل معرّف المؤسسة UUID أولًا.")
            return
        }
        val branch = state.marketBranches.firstOrNull { it.id == state.selectedBranchId }
        val useDeviceCenter = state.deviceLatitude != null && state.deviceLongitude != null
        val latitude = if (useDeviceCenter) state.deviceLatitude?.toBigDecimalOrNull() else branch?.latitude?.toBigDecimalOrNull()
        val longitude = if (useDeviceCenter) state.deviceLongitude?.toBigDecimalOrNull() else branch?.longitude?.toBigDecimalOrNull()
        val selectedIds = selectedCatalogIds().toList()
        if (latitude == null || longitude == null || selectedIds.isEmpty()) {
            onFeedback("حدد موقعك الحالي أو اختر فرعًا بإحداثيات صحيحة، ثم حدد الأصناف.")
            return
        }
        request({
            catalogHttp(
                "/rest/v1/rpc/commercial_catalog_price_suggestions",
                JSONObject()
                    .put("p_organization_id", orgId)
                    .put("p_global_item_ids", JSONArray(selectedIds))
                    .put("p_latitude", latitude.toPlainString())
                    .put("p_longitude", longitude.toPlainString())
                    .put("p_radius_m", state.marketRadius.coerceAtLeast(1000))
                    .put("p_currency_code", currencyCode().uppercase()),
                active
            )
        }, { raw ->
            val hints = parseCatalogMarketPriceHints(raw)
            state.marketPriceHints = hints
            onFeedback(
                if (hints.isEmpty()) "لا توجد أسعار استرشادية مؤهلة؛ لم يتم اختلاق أي سعر."
                else "وصلت أسعار مجمعة لـ " + hints.size + " صنفًا من مجموعات تضم خمسة تجار مستقلين على الأقل."
            )
        }, null)
    }
}
