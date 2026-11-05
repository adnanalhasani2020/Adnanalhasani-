package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

internal data class CommercialPurchaseSupplierOption(val id: String, val name: String)
internal data class CommercialPurchaseLocationOption(val id: String, val name: String)
internal data class CommercialPurchaseOfferingOption(
    val id: String,
    val name: String,
    val currencyCode: String,
    val locationRef: String,
    val unitCode: String = "EA",
    val unitName: String = "قطعة",
    val availableQuantity: Long = 0L
)

internal data class CommercialPurchaseOrderOptions(
    val suppliers: List<CommercialPurchaseSupplierOption>,
    val locations: List<CommercialPurchaseLocationOption>,
    val offerings: List<CommercialPurchaseOfferingOption>
)

internal fun loadCommercialCloudPurchaseOrderOptions(context: Context): CommercialPurchaseOrderOptions {
    val (orgId, access) = commercialMasterDataAccess(context)
    val suppliers = commercialCloudPagedGet(
        "/rest/v1/commercial_suppliers?select=id,display_name,state&organization_id=eq.$orgId&state=eq.active&order=display_name.asc",
        access
    ).let { rows -> buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("display_name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank() && name.isNotBlank()) add(CommercialPurchaseSupplierOption(id, name))
        }
    } }
    val locations = commercialCloudPagedGet(
        "/rest/v1/commercial_organization_locations?select=id,name,state&organization_id=eq.$orgId&state=eq.active&order=name.asc",
        access
    ).let { rows -> buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val name = row.optString("name").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            if (id.isNotBlank() && name.isNotBlank()) add(CommercialPurchaseLocationOption(id, name))
        }
    } }
    val offerings = commercialCloudPagedGet(
        "/rest/v1/commercial_offerings?select=id,currency_code,state,location_ref,product:commercial_products(name,state,unit_code,unit_name),activity:commercial_activities(organization_id,state),inventory_balances:commercial_inventory_balances(quantity_on_hand,quantity_reserved,location_id)&state=eq.active&order=created_at.desc",
        access
    ).let { rows -> buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val activity = row.optJSONObject("activity")
            if (activity?.optString("organization_id") != orgId || activity.optString("state") != "active") continue
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val currency = row.optString("currency_code").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val product = row.optJSONObject("product")
            if (product?.optString("state") != "active") continue
            val productName = product.optString("name").orEmpty().ifBlank { "عنصر مخزون" }
            val locationRef = row.optString("location_ref").takeUnless { it.isBlank() || it == "null" }.orEmpty()
            val balances = row.optJSONArray("inventory_balances") ?: JSONArray()
            var available = 0L
            for (balanceIndex in 0 until balances.length()) {
                val balance = balances.optJSONObject(balanceIndex) ?: continue
                if (balance.optString("location_id") != locationRef) continue
                val onHand = balance.optLong("quantity_on_hand", 0L)
                val reserved = balance.optLong("quantity_reserved", 0L)
                require(onHand >= 0L && reserved >= 0L && reserved <= onHand) { "رصيد مخزون غير متسق للصنف $productName." }
                available = Math.addExact(available, onHand - reserved)
            }
            val unitCode = product.optString("unit_code").takeUnless { it.isBlank() || it == "null" } ?: "EA"
            val unitName = product.optString("unit_name").takeUnless { it.isBlank() || it == "null" } ?: "قطعة"
            if (id.isNotBlank() && locationRef.isNotBlank() && currency.matches(Regex("^[A-Z]{3}$"))) {
                add(CommercialPurchaseOfferingOption(id, productName, currency, locationRef, unitCode, unitName, available))
            }
        }
    } }
    return CommercialPurchaseOrderOptions(suppliers, locations, offerings)
}

internal data class CommercialCloudPurchaseLine(
    val id: String,
    val lineNumber: Int,
    val description: String,
    val quantityOrdered: String,
    val quantityReceived: String,
    val unitCostMinor: Long,
    val offeringId: String?,
    val sourceSellingUnitCode: String = "",
    val sourceSellingUnitName: String = "",
    val sourceSellingQuantity: String = "",
    val sourceQuantityInStockUnits: String = ""
)

internal data class CommercialCloudSupplierObligationSummary(
    val id: String,
    val amountDueMinor: Long,
    val amountPaidMinor: Long,
    val state: String,
    val currencyCode: String
)

internal data class CommercialCloudSupplierInvoiceLineSummary(
    val id: String,
    val purchaseOrderItemId: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val purchaseOrderItemDescription: String,
    val purchaseOrderUnitCostMinor: Long?
)

internal data class CommercialCloudSupplierInvoiceSummary(
    val id: String,
    val invoiceNumber: String,
    val totalMinor: Long,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val freightMinor: Long,
    val currencyCode: String,
    val recordedAt: String,
    val invoiceDate: String,
    val dueDate: String,
    val state: String,
    val lines: List<CommercialCloudSupplierInvoiceLineSummary>,
    val obligation: CommercialCloudSupplierObligationSummary?
)

internal data class CommercialCloudPurchaseDocument(
    val id: String,
    val supplierId: String,
    val orderNumber: String,
    val supplierName: String,
    val status: String,
    val orderedAt: String,
    val expectedAt: String,
    val currencyCode: String,
    val notes: String,
    val receiveLocationId: String?,
    val receiveLocationName: String,
    val lines: List<CommercialCloudPurchaseLine>,
    val supplierInvoices: List<CommercialCloudSupplierInvoiceSummary>
)

/** Read-only purchase order detail projection using the existing order/item/supplier tables. */
internal fun loadCommercialCloudPurchaseDocuments(context: Context): List<CommercialCloudPurchaseDocument> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_purchase_orders" +
        "?select=id,supplier_id,order_number,status,ordered_at,expected_at,currency_code,notes,receive_location_id,receive_location:commercial_organization_locations!commercial_purchase_orders_receive_location_id_fkey(name),supplier:commercial_suppliers(display_name),items:commercial_purchase_order_items(id,line_number,item_description,quantity_ordered,quantity_received,unit_cost_minor,offering_id,source_selling_unit_code,source_selling_unit_name,source_selling_quantity,source_quantity_in_stock_units),supplier_invoices:commercial_supplier_invoices(id,invoice_number,subtotal_minor,tax_minor,freight_minor,total_minor,currency_code,recorded_at,invoice_date,due_date,state,items:commercial_supplier_invoice_items(id,purchase_order_item_id,quantity,unit_price_minor,line_total_minor,purchase_order_item:commercial_purchase_order_items!commercial_supplier_invoice_items_purchase_order_item_id_fkey(item_description,unit_cost_minor)),obligation:commercial_supplier_obligations(id,amount_due_minor,amount_paid_minor,state,currency_code))" +
        "&organization_id=eq.$orgId&order=created_at.desc.nullslast,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val supplier = row.optJSONObject("supplier")
            val items = row.optJSONArray("items")
            val invoiceRows = row.optJSONArray("supplier_invoices")
            val supplierInvoices = buildList {
                for (invoiceIndex in 0 until (invoiceRows?.length() ?: 0)) {
                    val invoice = invoiceRows?.optJSONObject(invoiceIndex) ?: continue
                    val invoiceId = invoice.optString("id").takeUnless { it == "null" }.orEmpty()
                    if (invoiceId.isBlank()) continue
                    val invoiceItems = invoice.optJSONArray("items")
                    val invoiceLines = buildList {
                        for (itemIndex in 0 until (invoiceItems?.length() ?: 0)) {
                            val item = invoiceItems?.optJSONObject(itemIndex) ?: continue
                            val purchaseItem = item.optJSONObject("purchase_order_item")
                            val lineId = item.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
                            val purchaseItemId = item.optString("purchase_order_item_id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
                            if (lineId.isBlank() || purchaseItemId.isBlank()) continue
                            val baseline = purchaseItem?.takeIf { it.has("unit_cost_minor") && !it.isNull("unit_cost_minor") }
                                ?.optLong("unit_cost_minor")
                            add(CommercialCloudSupplierInvoiceLineSummary(
                                id = lineId,
                                purchaseOrderItemId = purchaseItemId,
                                quantity = item.optString("quantity").takeUnless { it == "null" }.orEmpty(),
                                unitPriceMinor = item.optLong("unit_price_minor", -1L),
                                lineTotalMinor = item.optLong("line_total_minor", -1L),
                                purchaseOrderItemDescription = purchaseItem?.optString("item_description")
                                    .takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty(),
                                purchaseOrderUnitCostMinor = baseline
                            ))
                        }
                    }
                    add(CommercialCloudSupplierInvoiceSummary(
                        id = invoiceId,
                        invoiceNumber = invoice.optString("invoice_number").takeUnless { it == "null" }.orEmpty(),
                        totalMinor = invoice.optLong("total_minor", 0L),
                        subtotalMinor = invoice.optLong("subtotal_minor", 0L),
                        taxMinor = invoice.optLong("tax_minor", 0L),
                        freightMinor = invoice.optLong("freight_minor", 0L),
                        currencyCode = invoice.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                        recordedAt = invoice.optString("recorded_at").takeUnless { it == "null" }.orEmpty(),
                        invoiceDate = invoice.optString("invoice_date").takeUnless { it == "null" }.orEmpty(),
                        dueDate = invoice.optString("due_date").takeUnless { it == "null" }.orEmpty(),
                        state = invoice.optString("state").takeUnless { it == "null" }.orEmpty(),
                        lines = invoiceLines,
                        obligation = invoice.optJSONObject("obligation")?.let { ob ->
                            val obligationId = ob.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty()
                            if (obligationId.isBlank()) null else CommercialCloudSupplierObligationSummary(
                                id = obligationId,
                                amountDueMinor = ob.optLong("amount_due_minor", 0L),
                                amountPaidMinor = ob.optLong("amount_paid_minor", 0L),
                                state = ob.optString("state").takeUnless { it == "null" }.orEmpty(),
                                currencyCode = ob.optString("currency_code").takeUnless { it == "null" }.orEmpty()
                            )
                        }
                    ))
                }
            }.sortedBy { it.recordedAt }
            val lines = buildList {
                for (lineIndex in 0 until (items?.length() ?: 0)) {
                    val item = items?.optJSONObject(lineIndex) ?: continue
                    add(CommercialCloudPurchaseLine(
                        id = item.optString("id"),
                        lineNumber = item.optInt("line_number", lineIndex + 1),
                        description = item.optString("item_description").ifBlank { "بند بدون وصف" },
                        quantityOrdered = item.optString("quantity_ordered").takeUnless { it == "null" }.orEmpty(),
                        quantityReceived = item.optString("quantity_received").takeUnless { it == "null" }.orEmpty(),
                        unitCostMinor = item.optLong("unit_cost_minor", 0L),
                        offeringId = item.optString("offering_id").takeUnless { it.isBlank() || it == "null" },
                        sourceSellingUnitCode = item.optString("source_selling_unit_code").takeUnless { it == "null" }.orEmpty(),
                        sourceSellingUnitName = item.optString("source_selling_unit_name").takeUnless { it == "null" }.orEmpty(),
                        sourceSellingQuantity = item.optString("source_selling_quantity").takeUnless { it == "null" }.orEmpty(),
                        sourceQuantityInStockUnits = item.optString("source_quantity_in_stock_units").takeUnless { it == "null" }.orEmpty()
                    ))
                }
            }.sortedBy { it.lineNumber }
            add(CommercialCloudPurchaseDocument(
                id = row.optString("id"),
                supplierId = row.optString("supplier_id").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                orderNumber = row.optString("order_number").ifBlank { "—" },
                supplierName = supplier?.optString("display_name").orEmpty().takeUnless { it == "null" }.orEmpty().ifBlank { "مورد غير محدد" },
                status = row.optString("status").ifBlank { "غير محدد" },
                orderedAt = row.optString("ordered_at").takeUnless { it == "null" }.orEmpty(),
                expectedAt = row.optString("expected_at").takeUnless { it == "null" }.orEmpty(),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                notes = row.optString("notes").takeUnless { it == "null" }.orEmpty(),
                receiveLocationId = row.optString("receive_location_id").takeUnless { it.isBlank() || it == "null" },
                receiveLocationName = row.optJSONObject("receive_location")?.optString("name")
                    .takeUnless { it.isNullOrBlank() || it == "null" }.orEmpty(),
                lines = lines,
                supplierInvoices = supplierInvoices
            ))
        }
    }
}


private fun commercialSupplierInvoicePendingPreferences(context: Context) =
    context.getSharedPreferences("commercial_supplier_invoice_pending_v1", Context.MODE_PRIVATE)

/** Reuse one persisted key per purchase order until the server confirms a successful save. */
internal fun getOrCreateCommercialSupplierInvoiceIdempotencyKey(context: Context, purchaseOrderId: String): String {
    require(purchaseOrderId.isNotBlank()) { "مرجع أمر الشراء غير صالح." }
    val preferences = commercialSupplierInvoicePendingPreferences(context)
    val preferenceKey = "purchase_order_$purchaseOrderId"
    val existing = preferences.getString(preferenceKey, null).orEmpty()
    if (existing.isNotBlank()) return existing
    val created = UUID.randomUUID().toString()
    check(preferences.edit().putString(preferenceKey, created).commit()) {
        "تعذر حفظ مفتاح فاتورة المورد قبل الإرسال؛ لم يتم إرسال الفاتورة."
    }
    return preferences.getString(preferenceKey, null).orEmpty().also {
        check(it.isNotBlank()) { "تعذر تأكيد حفظ مفتاح فاتورة المورد؛ لم يتم إرسال الفاتورة." }
    }
}

/** Clear only the confirmed operation key; never erase a newer pending operation. */
internal fun clearCommercialSupplierInvoiceIdempotencyKey(
    context: Context,
    purchaseOrderId: String,
    idempotencyKey: String
): Boolean {
    val preferences = commercialSupplierInvoicePendingPreferences(context)
    val preferenceKey = "purchase_order_$purchaseOrderId"
    if (preferences.getString(preferenceKey, null) != idempotencyKey) return false
    return preferences.edit().remove(preferenceKey).commit()
}

/** Persist a supplier invoice through the authorized atomic server RPC. */
internal fun recordCommercialCloudSupplierInvoice(
    context: Context,
    purchaseOrderId: String,
    invoiceNumber: String,
    currencyCode: String,
    lines: List<CommercialSupplierInvoiceLineInput>,
    taxMinor: Long,
    freightMinor: Long,
    idempotencyKey: String,
    invoiceDate: String,
    dueDate: String
): JSONObject {
    require(purchaseOrderId.isNotBlank() && invoiceNumber.trim().isNotEmpty()) { "أدخل رقم فاتورة المورد." }
    require(lines.isNotEmpty()) { "أدخل كمية مفوترة واحدة على الأقل." }
    require(taxMinor >= 0 && freightMinor >= 0) { "الضريبة والشحن لا يمكن أن يكونا سالبين." }
    require(idempotencyKey.length in 8..200) { "مفتاح عملية الفاتورة غير صالح." }
    val parsedInvoiceDate = java.time.LocalDate.parse(invoiceDate)
    val parsedDueDate = java.time.LocalDate.parse(dueDate)
    require(!parsedInvoiceDate.isAfter(java.time.LocalDate.now()) && !parsedDueDate.isBefore(parsedInvoiceDate)) {
        "تاريخ الفاتورة أو الاستحقاق غير صالح."
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    val lineArray = JSONArray()
    lines.forEach { line ->
        require(line.purchaseOrderItemId.isNotBlank() && line.quantity.isNotBlank()) { "بيانات بند الفاتورة غير مكتملة." }
        val quantity = line.quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("أدخل كمية صحيحة لكل بند.")
        require(quantity > java.math.BigDecimal.ZERO && quantity.stripTrailingZeros().scale() <= 0) { "كمية الفاتورة يجب أن تكون عددًا صحيحًا موجبًا." }
        require(line.unitPriceMinor >= 0) { "سعر الوحدة لا يمكن أن يكون سالبًا." }
        lineArray.put(JSONObject()
            .put("purchase_order_item_id", line.purchaseOrderItemId)
            .put("quantity", quantity.stripTrailingZeros().toPlainString())
            .put("unit_price_minor", line.unitPriceMinor))
    }
    val payload = JSONObject()
        .put("p_organization_id", orgId)
        .put("p_purchase_order_id", purchaseOrderId)
        .put("p_invoice_number", invoiceNumber.trim())
        .put("p_currency_code", currencyCode)
        .put("p_lines", lineArray)
        .put("p_tax_minor", taxMinor)
        .put("p_freight_minor", freightMinor)
        .put("p_idempotency_key", idempotencyKey)
        .put("p_invoice_date", parsedInvoiceDate.toString())
        .put("p_due_date", parsedDueDate.toString())
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_record_supplier_invoice_with_dates", "POST", payload, access
    ))
}

private fun commercialPurchaseReceiptPendingPreferences(context: Context) =
    context.getSharedPreferences("commercial_purchase_receipt_pending_v1", Context.MODE_PRIVATE)

internal fun getOrCreateCommercialPurchaseReceiptIdempotencyKey(context: Context, purchaseOrderId: String): String {
    require(purchaseOrderId.isNotBlank()) { "مرجع أمر الشراء غير صالح." }
    val preferences = commercialPurchaseReceiptPendingPreferences(context)
    val preferenceKey = "purchase_order_" + purchaseOrderId
    val existing = preferences.getString(preferenceKey, null).orEmpty()
    if (existing.isNotBlank()) return existing
    val created = UUID.randomUUID().toString()
    check(preferences.edit().putString(preferenceKey, created).commit()) {
        "تعذر حفظ مفتاح الاستلام قبل الإرسال؛ لم يتم إرسال الاستلام."
    }
    return preferences.getString(preferenceKey, null).orEmpty().also {
        check(it.isNotBlank()) { "تعذر تأكيد حفظ مفتاح الاستلام؛ لم يتم إرسال الاستلام." }
    }
}

internal fun hasPendingCommercialPurchaseReceipt(context: Context, purchaseOrderId: String): Boolean =
    commercialPurchaseReceiptPendingPreferences(context).getString("purchase_order_" + purchaseOrderId, null).orEmpty().isNotBlank()

internal fun clearCommercialPurchaseReceiptIdempotencyKey(context: Context, purchaseOrderId: String, idempotencyKey: String): Boolean {
    val preferences = commercialPurchaseReceiptPendingPreferences(context)
    val preferenceKey = "purchase_order_" + purchaseOrderId
    if (preferences.getString(preferenceKey, null) != idempotencyKey) return false
    return preferences.edit().remove(preferenceKey).commit()
}

/** Resolve an interrupted receipt by asking the server before clearing its persisted key. */
internal fun resolvePendingCommercialPurchaseReceipt(context: Context, purchaseOrderId: String): Boolean {
    val preferences = commercialPurchaseReceiptPendingPreferences(context)
    val preferenceKey = "purchase_order_" + purchaseOrderId
    val pendingKey = preferences.getString(preferenceKey, null).orEmpty()
    if (pendingKey.isBlank()) return false
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_purchase_receipts?select=id&organization_id=eq.$orgId&idempotency_key=eq.$pendingKey&limit=1"
    val rows = JSONArray(SupabaseClient.request(path, "GET", null, access))
    val found = rows.length() > 0
    check(preferences.getString(preferenceKey, null) != pendingKey || preferences.edit().remove(preferenceKey).commit()) {
        "تعذر مسح مفتاح الاستلام بعد التحقق من الخادم."
    }
    return found
}

internal data class CommercialPurchaseReceiptLineInput(val purchaseOrderItemId: String, val quantity: String)
internal data class CommercialPurchaseOrderLineInput(
    val offeringId: String,
    val description: String,
    val quantityOrdered: String,
    val unitCostMinor: Long
)

internal fun linkCommercialCloudPurchaseOrderItemOffering(
    context: Context, purchaseOrderId: String, purchaseOrderItemId: String, offeringId: String
): JSONObject {
    require(purchaseOrderId.isNotBlank() && purchaseOrderItemId.isNotBlank() && offeringId.isNotBlank()) {
        "مرجع أمر الشراء أو البند أو عنصر المخزون غير صالح."
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_link_purchase_order_item_offering", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_purchase_order_id", purchaseOrderId)
            .put("p_purchase_order_item_id", purchaseOrderItemId).put("p_offering_id", offeringId),
        access
    ))
}

internal fun transitionCommercialCloudPurchaseOrder(context: Context, purchaseOrderId: String, action: String): JSONObject {
    require(action in setOf("submit", "approve", "cancel", "close")) { "إجراء أمر الشراء غير صالح." }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_transition_purchase_order", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_purchase_order_id", purchaseOrderId).put("p_action", action),
        access
    ))
}

internal fun receiveCommercialCloudPurchaseOrder(
    context: Context,
    purchaseOrderId: String,
    locationId: String,
    lines: List<CommercialPurchaseReceiptLineInput>,
    idempotencyKey: String
): JSONObject {
    require(purchaseOrderId.isNotBlank() && locationId.isNotBlank()) { "مرجع أمر الشراء أو المستودع غير صالح." }
    require(lines.isNotEmpty()) { "أدخل كمية استلام واحدة على الأقل." }
    require(idempotencyKey.length in 8..200) { "مفتاح الاستلام غير صالح." }
    val lineArray = JSONArray()
    lines.forEach { line ->
        val quantity = line.quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("كمية الاستلام غير صالحة.")
        require(
            quantity > java.math.BigDecimal.ZERO &&
                quantity <= java.math.BigDecimal("999999999999") &&
                quantity.stripTrailingZeros().scale() <= 0
        ) {
            "كمية الاستلام يجب أن تكون موجبة وعدد صحيح موجب فقط."
        }
        lineArray.put(JSONObject().put("purchase_order_item_id", line.purchaseOrderItemId).put("quantity", quantity.stripTrailingZeros().toPlainString()))
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_receive_purchase_order", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_purchase_order_id", purchaseOrderId)
            .put("p_location_id", locationId).put("p_lines", lineArray).put("p_idempotency_key", idempotencyKey),
        access
    ))
}

internal fun createCommercialCloudPurchaseOrder(
    context: Context, supplierId: String, orderNumber: String, currencyCode: String, locationId: String,
    lines: List<CommercialPurchaseOrderLineInput>, idempotencyKey: String, notes: String = ""
): JSONObject {
    require(supplierId.isNotBlank() && orderNumber.isNotBlank() && locationId.isNotBlank()) { "بيانات أمر الشراء غير مكتملة." }
    require(currencyCode.matches(Regex("^[A-Z]{3}$"))) { "عملة أمر الشراء غير صالحة." }
    require(lines.isNotEmpty() && idempotencyKey.length in 8..200) { "بنود الأمر أو مفتاح العملية غير صالح." }
    val lineArray = JSONArray()
    lines.forEach { line ->
        val quantity = line.quantityOrdered.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("كمية الطلب غير صالحة.")
        require(line.offeringId.isNotBlank() && line.description.isNotBlank() && quantity > java.math.BigDecimal.ZERO &&
            quantity.stripTrailingZeros().scale() <= 0 && line.unitCostMinor >= 0L) { "بيانات بند أمر الشراء غير صالحة." }
        lineArray.put(JSONObject().put("offering_id", line.offeringId).put("item_description", line.description.trim())
            .put("quantity_ordered", quantity.stripTrailingZeros().toPlainString()).put("unit_cost_minor", line.unitCostMinor))
    }
    val (orgId, access) = commercialMasterDataAccess(context)
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_create_purchase_order", "POST",
        JSONObject().put("p_organization_id", orgId).put("p_supplier_id", supplierId)
            .put("p_order_number", orderNumber.trim()).put("p_currency_code", currencyCode)
            .put("p_receive_location_id", locationId).put("p_lines", lineArray)
            .put("p_idempotency_key", idempotencyKey).put("p_notes", notes.trim().takeIf { it.isNotEmpty() }),
        access
    ))
}

internal data class CommercialSupplierInvoiceLineInput(
    val purchaseOrderItemId: String,
    val quantity: String,
    val unitPriceMinor: Long
)
