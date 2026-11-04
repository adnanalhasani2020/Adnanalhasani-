package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Coordinates invoice-to-purchase requests, matching, and catalog item creation. */
internal class CatalogInvoiceWorkflow(
    private val context: Context,
    private val state: CatalogInvoiceTransferState,
    private val accessToken: () -> String?,
    private val organizationId: () -> String?,
    private val marketBranches: () -> List<CatalogBranchOption>,
    private val selectedBranchId: () -> String,
    private val selectedActivityCode: () -> String,
    private val directoryUnits: () -> List<DirectoryUnit>,
    private val localUnitCodes: () -> Set<String>,
    private val categories: () -> List<CatalogCategory>,
    private val request: (action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)?) -> Unit,
    private val onFeedback: (String) -> Unit
) {
    fun loadInvoiceRequests() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة UUID أولًا."); return }
        request({
            catalogGet("/rest/v1/commercial_invoice_purchase_requests?select=id,source_organization_id,destination_organization_id,source_invoice_number,currency_code,total_minor,status,destination_purchase_order_id,created_at&or=(source_organization_id.eq."+orgId+",destination_organization_id.eq."+orgId+")&order=created_at.desc&limit=50", active)
        }, { raw ->
            state.invoiceRequests = parseInvoicePurchaseRequests(raw)
            onFeedback("طلبات تحويل الفواتير: " + state.invoiceRequests.size)
        }, null)
    }

    fun requestInvoiceAsPurchase() {
        val active = accessToken() ?: return
        val source = organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة البائعة UUID أولًا."); return }
        if (state.invoiceSaleId.isBlank() || state.invoiceDestinationOrg.isBlank()) { onFeedback("أدخل معرّف فاتورة البيع ومعرّف المؤسسة المستقبلة."); return }
        if (source == state.invoiceDestinationOrg.trim()) { onFeedback("لا يمكن تحويل الفاتورة إلى المؤسسة نفسها."); return }
        val key = state.invoiceRequestKey
        request({
            catalogHttp("/rest/v1/rpc/commercial_request_invoice_as_purchase", JSONObject()
                .put("p_source_organization_id", source).put("p_destination_organization_id", state.invoiceDestinationOrg.trim())
                .put("p_sale_id", state.invoiceSaleId.trim()).put("p_request_key", key), active)
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            onFeedback("تم إرسال الطلب: " + (result?.optString("state") ?: raw) + ". لن يتغير مخزون المؤسسة المستقبلة قبل الاستلام.")
            state.invoiceRequestKey = java.util.UUID.randomUUID().toString()
            loadInvoiceRequests()
        }, null)
    }

    fun reviewInvoicePurchaseRequest(id: String, approve: Boolean) {
        val active = accessToken() ?: return
        val receiveLocation = state.invoiceReceiveLocationId.ifBlank { selectedBranchId() }
        if (approve && (receiveLocation.isBlank() || marketBranches().none { it.id == receiveLocation })) {
            onFeedback("اختر فرع استلام نشطًا تابعًا للمؤسسة المستقبلة قبل الموافقة."); return
        }
        request({
            catalogHttp("/rest/v1/rpc/commercial_review_invoice_purchase_request", JSONObject()
                .put("p_request_id", id).put("p_decision", if (approve) "approve" else "reject")
                .put("p_review_note", JSONObject.NULL).put("p_receive_location_id", if (approve) receiveLocation else JSONObject.NULL), active)
        }, {
            onFeedback(if (approve) "تمت الموافقة وإنشاء أمر شراء مسودة في الفرع المحدد. الاستلام منفصل." else "تم رفض طلب تحويل الفاتورة.")
            loadInvoiceRequests()
        }, null)
    }

    fun mapKnownInvoicePurchaseItems(id: String) {
        val active = accessToken() ?: return
        request({ catalogHttp("/rest/v1/rpc/commercial_invoice_purchase_map_known_items", JSONObject().put("p_request_id", id), active) }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            onFeedback("تم ربط " + (result?.optInt("mapped_lines", 0) ?: 0) + " صنفًا مطابقًا بشكل موثوق. الأصناف غير المطابقة: " + (result?.optInt("unmapped_lines", 0) ?: 0) + ". لم يتغير المخزون.")
            loadInvoiceRequests()
        }, null)
    }

    fun loadInvoicePurchaseUnmappedLines(req: InvoicePurchaseRequestRow) {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: run { onFeedback("أدخل معرّف المؤسسة UUID أولًا."); return }
        if (req.destinationOrganizationId != orgId || req.status != "approved" || req.purchaseOrderId.isBlank()) {
            onFeedback("إدارة المطابقة متاحة للمؤسسة المستقبلة بعد الموافقة فقط."); return
        }
        request({
            val poRows = JSONArray(catalogGet("/rest/v1/commercial_purchase_orders?select=id,receive_location_id,currency_code,status&organization_id=eq."+orgId+"&id=eq."+req.purchaseOrderId+"&limit=1", active))
            if (poRows.length() != 1) throw IllegalStateException("لم يتم العثور على أمر الشراء ضمن المؤسسة المستقبلة.")
            val purchaseOrder = poRows.getJSONObject(0)
            val locationId = purchaseOrder.optString("receive_location_id").takeUnless { it == "null" }.orEmpty()
            val currencyCode = purchaseOrder.optString("currency_code").uppercase()
            val purchaseOrderStatus = purchaseOrder.optString("status")
            if (purchaseOrderStatus !in setOf("draft", "submitted", "approved")) throw IllegalStateException("لا يمكن تغيير ربط الأصناف بعد استلام أمر الشراء أو إغلاقه أو إلغائه.")
            if (locationId.isBlank() || !currencyCode.matches(Regex("[A-Z]{3}"))) throw IllegalStateException("أمر الشراء لا يحتوي موقع استلام أو عملة صالحة.")
            val supplierInvoices = JSONArray(catalogGet("/rest/v1/commercial_supplier_invoices?select=id&organization_id=eq."+orgId+"&purchase_order_id=eq."+req.purchaseOrderId+"&limit=1", active))
            if (supplierInvoices.length() > 0) throw IllegalStateException("تم تسجيل فاتورة مورد لهذا الأمر؛ لا يمكن تغيير ربط الأصناف بعد ذلك.")
            val lineRows = JSONArray(catalogGet("/rest/v1/commercial_purchase_order_items?select=id,line_number,item_description,quantity_ordered,quantity_received,offering_id,source_selling_unit_code&organization_id=eq."+orgId+"&purchase_order_id=eq."+req.purchaseOrderId+"&offering_id=is.null&quantity_received=eq.0&order=line_number.asc", active))
            val warehouseRows = JSONArray(catalogGet("/rest/v1/commercial_warehouses?select=id,name,branch_location_id&organization_id=eq."+orgId+"&branch_location_id=eq."+locationId+"&state=eq.active&order=name.asc", active))
            val mapRows = JSONArray(catalogGet("/rest/v1/commercial_catalog_item_offerings?select=offering_id,global_item_id&organization_id=eq."+orgId+"&location_id=eq."+locationId, active))
            val globalIds = (0 until mapRows.length()).map { mapRows.getJSONObject(it).optString("global_item_id") }.filter { it.isNotBlank() }.distinct()
            val offeringIds = (0 until mapRows.length()).map { mapRows.getJSONObject(it).optString("offering_id") }.filter { it.isNotBlank() }.distinct()
            val encodedGlobals = java.net.URLEncoder.encode("in.("+globalIds.joinToString(",")+")", "UTF-8")
            val encodedOfferings = java.net.URLEncoder.encode("in.("+offeringIds.joinToString(",")+")", "UTF-8")
            val globalRows = if (globalIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_global_catalog_items?select=id,name_ar,name_en,unit_code&id="+encodedGlobals+"&is_active=eq.true", active))
            val activeOfferings = if (offeringIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_offerings?select=id,product_id,location_ref,state,currency_code&id="+encodedOfferings+"&state=eq.active&currency_code=eq."+currencyCode+"&location_ref=eq."+locationId, active))
            val productIds = (0 until activeOfferings.length()).map { activeOfferings.getJSONObject(it).optString("product_id") }.filter { it.isNotBlank() }.distinct()
            val encodedProducts = java.net.URLEncoder.encode("in.("+productIds.joinToString(",")+")", "UTF-8")
            val activeProducts = if (productIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_products?select=id,state&id="+encodedProducts+"&state=eq.active", active))
            val names = mutableMapOf<String, Pair<String, String>>()
            for (i in 0 until globalRows.length()) {
                val row = globalRows.getJSONObject(i)
                names[row.optString("id")] = Pair(row.optString("name_ar").ifBlank { row.optString("name_en") }, row.optString("unit_code"))
            }
            val activeProductIds = (0 until activeProducts.length()).map { activeProducts.getJSONObject(it).optString("id") }.toSet()
            val activeIds = (0 until activeOfferings.length()).filter { activeOfferings.getJSONObject(it).optString("product_id") in activeProductIds }.map { activeOfferings.getJSONObject(it).optString("id") }.toSet()
            val candidates = (0 until mapRows.length()).mapNotNull { i ->
                val row = mapRows.getJSONObject(i)
                val offeringId = row.optString("offering_id")
                val globalId = row.optString("global_item_id")
                val name = names[globalId]
                if (offeringId !in activeIds || name == null) null else JSONObject().put("id", offeringId).put("global_item_id", globalId).put("name", name.first.ifBlank { globalId }).put("unit_code", name.second)
            }.distinctBy { it.optString("id") }
            val lines = JSONArray().apply {
                for (i in 0 until lineRows.length()) {
                    val row = lineRows.getJSONObject(i)
                    put(JSONObject().put("id", row.optString("id")).put("line_number", row.optInt("line_number"))
                        .put("description", row.optString("item_description")).put("quantity", row.optString("quantity_ordered"))
                        .put("unit_code", row.optString("source_selling_unit_code").takeUnless { it == "null" }.orEmpty()))
                }
            }
            JSONObject().put("location_id", locationId).put("lines", lines)
                .put("offerings", JSONArray().apply { candidates.forEach { put(it) } }).put("warehouses", warehouseRows).toString()
        }, { raw ->
            val mapping = parseCatalogInvoiceMapping(raw)
            state.mappingLocationId = mapping.locationId
            state.mappingWarehouses = mapping.warehouses
            if (state.mappingWarehouses.none { it.id == state.invoiceMappingWarehouseId }) state.invoiceMappingWarehouseId = state.mappingWarehouses.firstOrNull()?.id.orEmpty()
            state.mappingLines = mapping.lines
            state.mappingOfferings = mapping.offerings
            state.invoiceMappingRequestId = req.id
            state.mappingPurchaseOrderId = req.purchaseOrderId
            state.invoiceMappingLineId = state.mappingLines.firstOrNull()?.id.orEmpty()
            state.invoiceMappingOfferingId = ""
            onFeedback("الأصناف غير المطابقة: "+state.mappingLines.size+". العروض المتاحة في موقع الاستلام: "+state.mappingOfferings.size+". لم يتغير المخزون.")
        }, null)
    }

    fun linkSelectedInvoicePurchaseLine() {
        val active = accessToken() ?: return
        val orgId = organizationId() ?: return
        val requestRow = state.invoiceRequests.firstOrNull { it.id == state.invoiceMappingRequestId }
        if (requestRow == null || requestRow.destinationOrganizationId != orgId || requestRow.status != "approved") { onFeedback("تعذر التحقق من صلاحية طلب المطابقة."); return }
        val line = state.mappingLines.firstOrNull { it.id == state.invoiceMappingLineId } ?: run { onFeedback("اختر بندًا غير مطابق."); return }
        val offering = state.mappingOfferings.firstOrNull { it.id == state.invoiceMappingOfferingId } ?: run { onFeedback("اختر صنفًا محليًا مرتبطًا بموقع الاستلام."); return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_link_purchase_order_item_offering", JSONObject()
                .put("p_organization_id", orgId).put("p_purchase_order_id", state.mappingPurchaseOrderId)
                .put("p_purchase_order_item_id", line.id).put("p_offering_id", offering.id), active)
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            val orderNeedsReapproval = result?.optBoolean("reapproval_required", false) ?: false
            state.mappingLines = state.mappingLines.filterNot { it.id == line.id }
            state.invoiceMappingLineId = state.mappingLines.firstOrNull()?.id.orEmpty()
            state.invoiceMappingOfferingId = ""
            onFeedback(if (orderNeedsReapproval) "تم ربط بند الفاتورة بالصنف المحلي "+offering.name+". أعيد أمر الشراء إلى حالة الإرسال ويتطلب إعادة اعتماد؛ لم يتغير المخزون."
                else "تم ربط بند الفاتورة بالصنف المحلي "+offering.name+". لم يتغير المخزون؛ سيُحدّث عند الاستلام فقط.")
            loadInvoicePurchaseUnmappedLines(requestRow)
        }, null)
    }

    fun openInvoiceItemCreationDialog() {
        val line = state.mappingLines.firstOrNull { it.id == state.invoiceMappingLineId } ?: run { onFeedback("اختر بندًا غير مطابق أولًا."); return }
        val orgId = organizationId() ?: return
        if (state.mappingWarehouses.isEmpty() || state.invoiceMappingWarehouseId.isBlank()) { onFeedback("لا يوجد مستودع نشط مرتبط بفرع استلام أمر الشراء."); return }
        if (selectedActivityCode().isBlank()) { onFeedback("اختر النشاط التجاري من الدليل أولًا."); return }
        val available = directoryUnits().filter { it.owner.isBlank() || it.owner == orgId }
        val baseOptions = available.filter { it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes() }
        val sourceUnit = available.firstOrNull { it.code.equals(line.unitCode, true) }
        if (line.unitCode.isNotBlank() && sourceUnit == null) { onFeedback("وحدة الفاتورة "+line.unitCode+" غير موجودة في دليل الوحدات النشطة؛ لم نفترض تحويلًا بديلًا."); return }
        val base = if (sourceUnit == null) baseOptions.firstOrNull() else if (sourceUnit.baseCode.isBlank()) baseOptions.firstOrNull { it.code == sourceUnit.code } else baseOptions.firstOrNull { it.code == sourceUnit.baseCode }
        if (base == null) { onFeedback("لا توجد وحدة أساس نشطة مشتركة بين دليل الوحدات ووحدات المخزون."); return }
        val sale = sourceUnit?.takeIf { it.baseCode.isBlank() || it.baseCode == base.code } ?: base
        state.newNameAr = line.description.trim().take(180)
        state.newNameEn = ""
        state.newCategoryCode = categories().firstOrNull()?.code.orEmpty()
        state.newBaseUnitCode = base.code
        state.newSaleUnitCode = sale.code
        state.newIdempotencyKey = java.util.UUID.randomUUID().toString()
        state.showItemCreation = true
    }

    fun createInvoicePurchaseItem() {
        if (!COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY) { onFeedback("إنشاء صنف جديد من بند فاتورة الشراء متوقف مؤقتًا؛ ترحيل الخادم المطلوب غير منشور في قاعدة الإنتاج بعد. لم يتم إرسال أي بيانات."); return }
        val active = accessToken() ?: return
        val orgId = organizationId() ?: return
        val requestRow = state.invoiceRequests.firstOrNull { it.id == state.invoiceMappingRequestId }
        if (requestRow == null || requestRow.destinationOrganizationId != orgId || requestRow.status != "approved") { onFeedback("إنشاء الصنف متاح للمؤسسة المستقبلة بعد اعتماد طلب الشراء فقط."); return }
        val line = state.mappingLines.firstOrNull { it.id == state.invoiceMappingLineId } ?: run { onFeedback("اختر بند الفاتورة."); return }
        val base = directoryUnits().firstOrNull { it.code == state.newBaseUnitCode && it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes() }
            ?: run { onFeedback("اختر وحدة أساس نشطة ومعتمدة للمخزون."); return }
        val sale = directoryUnits().firstOrNull { it.code == state.newSaleUnitCode && (it.code == base.code || it.baseCode == base.code) && (it.owner.isBlank() || it.owner == orgId) }
            ?: run { onFeedback("اختر وحدة بيع متوافقة مع وحدة الأساس."); return }
        if (line.unitCode.isNotBlank() && !line.unitCode.equals(sale.code, true)) { onFeedback("وحدة بند الفاتورة لا تطابق وحدة البيع المختارة. لا يمكن تغيير التحويل تلقائيًا."); return }
        if (state.newNameAr.isBlank() || state.newNameEn.isBlank() || state.newCategoryCode.isBlank()) { onFeedback("أدخل الاسم العربي والإنجليزي واختر تصنيف الدليل."); return }
        val contextActivityId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE).getString("activity_id", null).orEmpty()
        if (contextActivityId.isBlank()) { onFeedback("لم يتم العثور على النشاط الحالي في سياق المؤسسة. افتح ملف النشاط ثم أعد المحاولة."); return }
        if (state.invoiceMappingWarehouseId.isBlank() || state.mappingLocationId.isBlank()) { onFeedback("اختر مستودعًا نشطًا تابعًا لفرع استلام أمر الشراء."); return }
        val key = state.newIdempotencyKey.ifBlank { java.util.UUID.randomUUID().toString().also { state.newIdempotencyKey = it } }
        request({
            catalogHttp("/rest/v1/rpc/commercial_create_and_link_invoice_purchase_item", JSONObject()
                .put("p_organization_id", orgId).put("p_purchase_order_id", state.mappingPurchaseOrderId)
                .put("p_purchase_order_item_id", line.id).put("p_activity_id", contextActivityId)
                .put("p_activity_node_code", selectedActivityCode().uppercase()).put("p_warehouse_id", state.invoiceMappingWarehouseId)
                .put("p_name_ar", state.newNameAr.trim()).put("p_name_en", state.newNameEn.trim())
                .put("p_category_code", state.newCategoryCode.uppercase()).put("p_base_unit_code", base.code)
                .put("p_sale_unit_code", sale.code).put("p_item_type", "product")
                .put("p_local_category_id", JSONObject.NULL).put("p_idempotency_key", key), active)
        }, { raw ->
            val result = runCatching { JSONObject(raw) }.getOrNull()
            val reapprovalRequired = result?.optBoolean("reapproval_required", false) ?: false
            state.showItemCreation = false
            state.newIdempotencyKey = java.util.UUID.randomUUID().toString()
            onFeedback(if (reapprovalRequired) "تم إنشاء الصنف وربطه. أُعيد أمر الشراء إلى حالة الإرسال ويتطلب إعادة اعتماده. السعر غير محدد والمخزون لم يتغير."
                else "تم إنشاء الصنف وربطه دون سعر أو كمية افتتاحية. لن يتغير المخزون حتى تسجيل الاستلام.")
            loadInvoicePurchaseUnmappedLines(requestRow)
        }, null)
    }
}
