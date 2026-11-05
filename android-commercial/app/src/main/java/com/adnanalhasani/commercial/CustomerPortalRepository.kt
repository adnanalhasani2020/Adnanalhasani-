package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

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
        require(offeringId.isNotBlank() && quantity.toBigDecimalOrNull()?.let { it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal("1000000") && it.stripTrailingZeros().scale() <= 0 } == true) {
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
            it > java.math.BigDecimal.ZERO && it <= java.math.BigDecimal("100000") && it.stripTrailingZeros().scale() <= 0
        } == true
    }) { "تحقق من كميات الخدمات؛ يجب أن تكون موجبة وأعداد صحيحة فقط." }
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
