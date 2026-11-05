package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

internal fun getOrCreateCommercialCloudSaleKey(context: Context, offeringId: String, quantity: Int): String {
    require(offeringId.isNotBlank() && quantity > 0) { "بيانات البيع غير صحيحة." }
    val cloudContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val organizationId = cloudContext.getString("organization_id", null).orEmpty()
    val activityId = cloudContext.getString("activity_id", null).orEmpty()
    val locationId = cloudContext.getString("location_id", null).orEmpty()
    require(organizationId.isNotBlank() && activityId.isNotBlank() && locationId.isNotBlank()) {
        "بيانات المؤسسة والنشاط والموقع غير مكتملة."
    }

    val prefs = context.getSharedPreferences("commercial_cloud_pending_sale_v1", Context.MODE_PRIVATE)
    val storedOffering = prefs.getString("offering_id", null).orEmpty()
    val storedQuantity = prefs.getInt("quantity", -1)
    val storedOrganization = prefs.getString("organization_id", null).orEmpty()
    val storedActivity = prefs.getString("activity_id", null).orEmpty()
    val storedLocation = prefs.getString("location_id", null).orEmpty()
    val storedKey = prefs.getString("idempotency_key", null).orEmpty()

    // A retry key belongs to one exact operation and tenant/location context.
    // Reusing it after switching organizations or warehouses can collide with
    // a different server-side request or retry an unintended sale.
    if (storedOffering == offeringId && storedQuantity == quantity &&
        storedOrganization == organizationId && storedActivity == activityId &&
        storedLocation == locationId && storedKey.isNotBlank()) return storedKey

    val key = UUID.randomUUID().toString()
    check(prefs.edit()
        .putString("organization_id", organizationId)
        .putString("activity_id", activityId)
        .putString("location_id", locationId)
        .putString("offering_id", offeringId)
        .putInt("quantity", quantity)
        .putString("idempotency_key", key)
        .commit()) { "تعذر حفظ مفتاح عملية البيع قبل الإرسال؛ لم يتم إرسال البيع." }
    return key
}

internal fun clearCommercialCloudSaleKey(context: Context, idempotencyKey: String) {
    val prefs = context.getSharedPreferences("commercial_cloud_pending_sale_v1", Context.MODE_PRIVATE)
    if (prefs.getString("idempotency_key", null) == idempotencyKey) {
        check(prefs.edit().clear().commit()) { "تعذر تنظيف حالة البيع بعد تأكيد الخادم." }
    }
}

internal fun checkoutCommercialCloudSale(
    context: Context,
    offeringId: String,
    quantity: Int,
    idempotencyKey: String
): JSONObject {
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val locationId = savedContext.getString("location_id", null).orEmpty()
    require(locationId.isNotBlank()) { "معرّف موقع المخزون غير محفوظ." }
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
    val result = JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_checkout_sale", "POST",
        JSONObject().put("p_offering_id", offeringId)
            .put("p_location_id", locationId)
            .put("p_quantity", quantity)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
    return result
}

internal fun getOrCreateCommercialCloudMultiSaleKey(
    context: Context,
    lines: List<CommercialCloudCheckoutLine>,
    customerId: String? = null,
    paymentMode: String = "cash",
    initialPaymentMinor: Long = 0L,
    discountMinor: Long = 0L,
    taxMinor: Long = 0L
): String {
    require(discountMinor >= 0L && taxMinor >= 0L) { "قيمة الخصم والضريبة لا يمكن أن تكون سالبة." }
    val normalizedLines = aggregateCommercialCheckoutLines(lines)
    val canonical = JSONArray()
    normalizedLines.forEach { line ->
        canonical.put(JSONObject().put("offering_id", line.offeringId).put("quantity", line.quantity.stripTrailingZeros().toPlainString()).put("unit_code", line.unitCode))
    }
    val payload = JSONObject().put("customer_id", customerId ?: JSONObject.NULL)
        .put("payment_mode", paymentMode)
        .put("initial_payment_minor", if (paymentMode == "cash") JSONObject.NULL else initialPaymentMinor)
        .put("discount_minor", discountMinor)
        .put("tax_minor", taxMinor)
        .put("lines", canonical).toString()
    val prefs = context.getSharedPreferences("commercial_cloud_pending_multi_sale_v1", Context.MODE_PRIVATE)
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val organizationId = saved.getString("organization_id", null).orEmpty()
    val activityId = saved.getString("activity_id", null).orEmpty()
    val locationId = saved.getString("location_id", null).orEmpty()
    require(organizationId.isNotBlank() && activityId.isNotBlank() && locationId.isNotBlank()) {
        "ملف النشاط وموقع المخزون مطلوبان قبل إنشاء الفاتورة."
    }
    val oldKey = prefs.getString("idempotency_key", null).orEmpty()
    if (oldKey.isNotBlank()) {
        require(prefs.getString("payload", null) == payload &&
            prefs.getString("organization_id", null) == organizationId &&
            prefs.getString("activity_id", null) == activityId &&
            prefs.getString("location_id", null) == locationId) {
            "توجد فاتورة سحابية معلقة ببيانات مختلفة؛ أعد المحاولة أو أكمل العملية السابقة أولًا."
        }
        return oldKey
    }
    val key = UUID.randomUUID().toString()
    check(prefs.edit().putString("payload", payload).putString("organization_id", organizationId)
        .putString("activity_id", activityId).putString("location_id", locationId)
        .putString("idempotency_key", key).commit()) {
        "تعذر حفظ مفتاح الفاتورة قبل الإرسال؛ لم يتم إرسال البيع."
    }
    return key
}

internal fun clearCommercialCloudMultiSaleKey(context: Context, idempotencyKey: String) {
    val prefs = context.getSharedPreferences("commercial_cloud_pending_multi_sale_v1", Context.MODE_PRIVATE)
    if (prefs.getString("idempotency_key", null) == idempotencyKey) {
        check(prefs.edit().clear().commit()) { "تعذر تنظيف حالة الفاتورة بعد تأكيد الخادم." }
    }
}

internal fun retryPendingCommercialCloudMultiSale(context: Context): JSONObject {
    val prefs = context.getSharedPreferences("commercial_cloud_pending_multi_sale_v1", Context.MODE_PRIVATE)
    val key = prefs.getString("idempotency_key", null).orEmpty()
    require(key.isNotBlank()) { "لا توجد فاتورة سحابية محفوظة لإعادة المحاولة." }
    val payloadText = prefs.getString("payload", null).orEmpty()
    require(payloadText.isNotBlank()) { "بيانات الفاتورة المعلقة غير مكتملة؛ لم يتم إرسال أي فاتورة." }
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    require(prefs.getString("organization_id", null) == saved.getString("organization_id", null) &&
        prefs.getString("activity_id", null) == saved.getString("activity_id", null) &&
        prefs.getString("location_id", null) == saved.getString("location_id", null)) {
        "الفاتورة المعلقة تخص مؤسسة أو نشاطًا أو موقعًا آخر. بدّل إلى السياق الأصلي أولًا؛ لم يتم إنشاء فاتورة جديدة."
    }
    val payload = JSONObject(payloadText)
    val rawLines = payload.optJSONArray("lines")
        ?: throw IllegalStateException("بنود الفاتورة المعلقة غير موجودة؛ لم يتم إرسال أي فاتورة.")
    require(rawLines.length() > 0) { "الفاتورة المعلقة لا تحتوي بنودًا؛ لم يتم إرسال أي فاتورة." }
    val lines = List(rawLines.length()) { index ->
        val row = rawLines.getJSONObject(index)
        val offeringId = row.optString("offering_id").trim()
        val unitCode = row.optString("unit_code").trim()
        val quantity = row.optString("quantity").toBigDecimalOrNull()
        require(offeringId.isNotBlank() && unitCode.isNotBlank() && quantity != null && quantity.signum() > 0) {
            "بيانات أحد بنود الفاتورة المعلقة غير صالحة؛ لم يتم إرسال أي فاتورة."
        }
        CommercialCloudCheckoutLine(offeringId, quantity, unitCode)
    }
    val paymentMode = payload.optString("payment_mode", "cash")
    require(paymentMode == "cash" || paymentMode == "credit") {
        "طريقة دفع الفاتورة المعلقة غير مدعومة؛ لم يتم إرسال أي فاتورة."
    }
    val customerId = if (payload.isNull("customer_id")) null else payload.optString("customer_id").takeIf { it.isNotBlank() }
    val initialPayment = if (payload.isNull("initial_payment_minor")) 0L else payload.optLong("initial_payment_minor", 0L)
    val discount = payload.optLong("discount_minor", -1L)
    val tax = payload.optLong("tax_minor", -1L)
    require(discount >= 0L && tax >= 0L) { "قيم الخصم أو الضريبة في الفاتورة المعلقة غير صالحة." }

    // Re-submit the exact locally persisted payload with its original idempotency key.
    // The server returns the prior sale if it already committed; otherwise it processes this same request.
    val sale = checkoutCommercialCloudSaleMulti(
        context, lines, key, customerId, paymentMode, initialPayment, discount, tax
    )
    clearCommercialCloudMultiSaleKey(context, key)
    return sale
}

internal fun checkoutCommercialCloudSaleMulti(
    context: Context,
    lines: List<CommercialCloudCheckoutLine>,
    idempotencyKey: String,
    customerId: String? = null,
    paymentMode: String = "cash",
    initialPaymentMinor: Long = 0L,
    discountMinor: Long = 0L,
    taxMinor: Long = 0L
): JSONObject {
    require(discountMinor >= 0L && taxMinor >= 0L) { "قيمة الخصم والضريبة لا يمكن أن تكون سالبة." }
    val normalizedLines = aggregateCommercialCheckoutLines(lines)
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val locationId = savedContext.getString("location_id", null).orEmpty()
    require(locationId.isNotBlank()) { "معرّف موقع المخزون غير محفوظ." }
    val payload = JSONArray()
    normalizedLines.forEach { line ->
        payload.put(JSONObject().put("offering_id", line.offeringId).put("quantity", line.quantity.stripTrailingZeros().toPlainString()).put("unit_code", line.unitCode))
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
    return JSONObject(SupabaseClient.request(
        "/rest/v1/rpc/commercial_checkout_sale_multi", "POST",
        JSONObject().put("p_lines", payload).put("p_location_id", locationId)
            .put("p_idempotency_key", idempotencyKey)
            .put("p_customer_id", customerId ?: JSONObject.NULL)
            .put("p_payment_mode", paymentMode)
            .put("p_initial_payment_minor", if (paymentMode == "cash") JSONObject.NULL else initialPaymentMinor)
            .put("p_discount_minor", discountMinor)
            .put("p_tax_minor", taxMinor),
        access
    ))
}

internal data class CommercialCloudSaleLine(
    val id: String,
    val productName: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val costTotalMinor: Long?,
    val unitCostMinor: Long?
)

internal data class CommercialCloudPaymentSummary(
    val id: String,
    val amountMinor: Long,
    val status: String,
    val methodType: String,
    val createdAt: String,
    val currencyCode: String
)

internal data class CommercialCloudReturnSummary(
    val id: String,
    val status: String,
    val refundStatus: String,
    val restockStatus: String,
    val requestedRefundMinor: Long,
    val approvedRefundMinor: Long?,
    val createdAt: String,
    val updatedAt: String
)

internal data class CommercialCloudSaleDocument(
    val id: String,
    val customerId: String,
    val userId: String,
    val receiptNumber: String,
    val customerName: String,
    val customerPhone: String,
    val customerEmail: String,
    val totalMinor: Long,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val taxMinor: Long,
    val saleStatus: String,
    val paymentStatus: String,
    val createdAt: String,
    val currencyCode: String,
    val lines: List<CommercialCloudSaleLine>,
    val payments: List<CommercialCloudPaymentSummary>,
    val returns: List<CommercialCloudReturnSummary>
)

/** Detailed, read-only invoice projection for the cloud sales history screen. */
internal fun loadCommercialCloudSaleDocuments(context: Context): List<CommercialCloudSaleDocument> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales" +
        "?select=id,customer_id,user_id,receipt_number,subtotal_minor,discount_minor,tax_minor,total_minor,payment_status,status,created_at,currency_code,customer:commercial_customers(display_name,phone,email),items:commercial_sale_items(id,product_name_snapshot,quantity,line_total_minor,unit_price_minor,unit_cost_minor,cost_total_minor,selling_quantity,selling_unit_name,selling_unit_code,quantity_in_stock_units),payments:commercial_payments(id,amount_minor,status,payment_method_type,created_at,currency_code),returns:commercial_sales_returns(id,status,refund_status,restock_status,requested_refund_minor,approved_refund_minor,created_at,updated_at)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val customer = row.optJSONObject("customer")
            val items = row.optJSONArray("items")
            val lines = buildList {
                for (lineIndex in 0 until (items?.length() ?: 0)) {
                    val item = items?.optJSONObject(lineIndex) ?: continue
                    val sellingQuantityText = item.optString("selling_quantity")
                        .takeUnless { it.isBlank() || it == "null" }
                        ?: item.optString("quantity").takeUnless { it == "null" }.orEmpty()
                    val sellingQuantity = runCatching { java.math.BigDecimal(sellingQuantityText) }
                        .getOrNull()?.takeIf { it.signum() > 0 }
                    val stockQuantity = item.optLong("quantity_in_stock_units", item.optLong("quantity", 0L))
                        .coerceAtLeast(0L)
                    val baseUnitPrice = item.optLong("unit_price_minor", 0L).coerceAtLeast(0L)
                    // Stored unit_price_minor is the base-stock-unit price. Convert it using
                    // the immutable quantities captured on this sale line, never today's unit setup.
                    val sellingUnitPrice = if (sellingQuantity != null && stockQuantity > 0L) {
                        runCatching {
                            java.math.BigDecimal.valueOf(baseUnitPrice)
                                .multiply(java.math.BigDecimal.valueOf(stockQuantity))
                                .divide(sellingQuantity, 0, java.math.RoundingMode.HALF_UP)
                                .longValueExact()
                        }.getOrDefault(baseUnitPrice)
                    } else baseUnitPrice
                    add(CommercialCloudSaleLine(
                        id = item.optString("id").takeUnless { it.isBlank() || it == "null" }.orEmpty(),
                        productName = item.optString("product_name_snapshot").ifBlank { "صنف بدون اسم" } +
                            item.optString("selling_unit_name").takeUnless { it.isBlank() || it == "null" }?.let { " ($it)" }.orEmpty(),
                        quantity = sellingQuantityText,
                        unitPriceMinor = sellingUnitPrice,
                        lineTotalMinor = item.optLong("line_total_minor", 0L),
                        costTotalMinor = if (item.isNull("cost_total_minor")) null else item.optLong("cost_total_minor"),
                        unitCostMinor = if (item.isNull("unit_cost_minor")) null else item.optLong("unit_cost_minor")
                    ))
                }
            }
            val paymentRows = row.optJSONArray("payments")
            val payments = buildList {
                for (paymentIndex in 0 until (paymentRows?.length() ?: 0)) {
                    val payment = paymentRows?.optJSONObject(paymentIndex) ?: continue
                    add(CommercialCloudPaymentSummary(
                        id = payment.optString("id").takeUnless { it == "null" }.orEmpty(),
                        amountMinor = payment.optLong("amount_minor", 0L),
                        status = payment.optString("status").ifBlank { "غير محدد" },
                        methodType = payment.optString("payment_method_type").takeUnless { it == "null" }.orEmpty(),
                        createdAt = payment.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                        currencyCode = payment.optString("currency_code").takeUnless { it == "null" }.orEmpty()
                    ))
                }
            }
            val totalMinor = row.optLong("total_minor", 0L).coerceAtLeast(0L)
            val successfulPaymentMinor = payments
                .filter { it.status.equals("succeeded", ignoreCase = true) }
                .sumOf { it.amountMinor.coerceAtLeast(0L) }
            val paymentStatusFromServer = row.optString("payment_status").ifBlank { "غير محدد" }
            val derivedPaymentStatus = when {
                paymentStatusFromServer.equals("refunded", ignoreCase = true) ||
                    payments.any { it.status.equals("refunded", ignoreCase = true) } -> "refunded"
                payments.any { it.status.equals("partially_refunded", ignoreCase = true) } -> "partially_refunded"
                totalMinor == 0L && paymentStatusFromServer.equals("paid", ignoreCase = true) -> "paid"
                totalMinor > 0L && successfulPaymentMinor >= totalMinor -> "paid"
                successfulPaymentMinor > 0L -> "partially_paid"
                paymentStatusFromServer.equals("failed", ignoreCase = true) -> "failed"
                else -> "pending"
            }
            val returnRows = row.optJSONArray("returns")
            val returns = buildList {
                for (returnIndex in 0 until (returnRows?.length() ?: 0)) {
                    val item = returnRows?.optJSONObject(returnIndex) ?: continue
                    add(CommercialCloudReturnSummary(
                        id = item.optString("id").takeUnless { it == "null" }.orEmpty(),
                        status = item.optString("status").ifBlank { "غير محدد" },
                        refundStatus = item.optString("refund_status").ifBlank { "غير محدد" },
                        restockStatus = item.optString("restock_status").ifBlank { "غير محدد" },
                        requestedRefundMinor = item.optLong("requested_refund_minor", 0L),
                        approvedRefundMinor = if (item.isNull("approved_refund_minor")) null else item.optLong("approved_refund_minor"),
                        createdAt = item.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                        updatedAt = item.optString("updated_at").takeUnless { it == "null" }.orEmpty()
                    ))
                }
            }
            add(CommercialCloudSaleDocument(
                id = row.optString("id"),
                customerId = row.optString("customer_id").takeUnless { it == "null" }.orEmpty(),
                userId = row.optString("user_id").takeUnless { it == "null" }.orEmpty(),
                receiptNumber = row.optString("receipt_number").ifBlank { "—" },
                customerName = customer?.optString("display_name").orEmpty().takeUnless { it == "null" }.orEmpty().ifBlank { "عميل نقدي / غير محدد" },
                customerPhone = customer?.optString("phone").orEmpty().takeUnless { it == "null" }.orEmpty(),
                customerEmail = customer?.optString("email").orEmpty().takeUnless { it == "null" }.orEmpty(),
                totalMinor = totalMinor,
                subtotalMinor = row.optLong("subtotal_minor", totalMinor).coerceAtLeast(0L),
                discountMinor = row.optLong("discount_minor", 0L).coerceAtLeast(0L),
                taxMinor = row.optLong("tax_minor", 0L).coerceAtLeast(0L),
                saleStatus = row.optString("status").ifBlank { "غير محدد" },
                paymentStatus = derivedPaymentStatus,
                createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                lines = lines,
                payments = payments,
                returns = returns
            ))
        }
    }
}

internal fun loadCommercialCloudSales(context: Context): List<SaleRecord> {
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = savedContext.getString("organization_id", null).orEmpty()
    val activityId = savedContext.getString("activity_id", null).orEmpty()
    require(orgId.isNotBlank() && activityId.isNotBlank()) { "بيانات المؤسسة والنشاط غير محفوظة." }
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

    val path = "/rest/v1/commercial_sales" +
        "?select=id,receipt_number,total_minor,payment_status,status,created_at,currency_code,customer:commercial_customers(display_name),items:commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor),payments:commercial_payments(amount_minor,status)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&order=created_at.desc,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val items = row.optJSONArray("items")
            val firstItem = items?.optJSONObject(0)
            val total = row.optLong("total_minor", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val paymentStatus = row.optString("payment_status").lowercase(Locale.ROOT)
            val saleStatus = row.optString("status").lowercase(Locale.ROOT)
            val paymentRows = row.optJSONArray("payments")
            val paidMinor = (0 until (paymentRows?.length() ?: 0))
                .mapNotNull { paymentRows?.optJSONObject(it) }
                .filter { it.optString("status").equals("succeeded", ignoreCase = true) }
                .sumOf { it.optLong("amount_minor", 0L).coerceAtLeast(0L) }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val isCredit = saleStatus == "completed" &&
                paymentStatus !in setOf("paid", "refunded", "cancelled", "canceled") &&
                total > paidMinor
            val customerObject = row.optJSONObject("customer")
            val createdAt = runCatching {
                java.time.Instant.parse(row.optString("created_at")).toEpochMilli()
            }.getOrNull() ?: System.currentTimeMillis()
            add(SaleRecord(
                number = row.optLong("receipt_number", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                product = (firstItem?.optString("product_name_snapshot")?.ifBlank { "فاتورة بيع" } ?: "فاتورة بيع") + if ((items?.length() ?: 0) > 1) " + " + ((items?.length() ?: 0) - 1) + " أصناف" else "",
                quantity = items?.let { itemRows -> (0 until itemRows.length()).sumOf { itemRows.optJSONObject(it)?.optInt("quantity", 0) ?: 0 }.coerceAtLeast(1) } ?: 1,
                total = total,
                credit = isCredit,
                customer = customerObject?.optString("display_name").orEmpty(),
                paid = paidMinor.coerceAtMost(total),
                createdAt = createdAt,
                unitCode = "قطعة",
                currencyCode = row.optString("currency_code").ifBlank { "YER" },
                status = saleStatus
            ))
        }
    }
}
