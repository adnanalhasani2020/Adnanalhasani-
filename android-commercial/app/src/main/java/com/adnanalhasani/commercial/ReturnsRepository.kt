package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

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
