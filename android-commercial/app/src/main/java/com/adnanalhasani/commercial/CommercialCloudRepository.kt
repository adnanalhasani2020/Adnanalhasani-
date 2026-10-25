package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private fun commercialCloudActionableError(raw: String, status: Int): String {
    val error = runCatching { JSONObject(raw) }.getOrNull()
    val code = error?.optString("code").orEmpty()
    val message = listOf(error?.optString("message"), error?.optString("hint"))
        .filter { !it.isNullOrBlank() && it != "null" }
        .joinToString(" — ")
    val detail = message.ifBlank { "HTTP $status" }
    return when {
        code == "PGRST202" || message.contains("schema cache", ignoreCase = true) ||
            message.contains("could not find the function", ignoreCase = true) ->
            "توقّف الإجراء لأن دالة قاعدة البيانات المطلوبة غير منشورة أو لم تُحدّث بعد. لم يؤكد الخادم تنفيذ العملية. لا تنشئ عملية بديلة بمفتاح جديد؛ أعد المحاولة بنفس العملية بعد إصلاح ترحيلات قاعدة البيانات. التفاصيل: $detail"
        message.contains("idempotency_key_reused_with_different_request", ignoreCase = true) ->
            "تم إيقاف العملية لحماية البيانات: مفتاح إعادة المحاولة مرتبط بطلب مختلف. لا تكرر الدفع أو البيع؛ حدّث البيانات وراجع الفاتورة الموجودة. التفاصيل: $detail"
        message.contains("collection_exceeds_outstanding_balance", ignoreCase = true) ||
            message.contains("overpayment", ignoreCase = true) ->
            "رُفض التحصيل لأن المبلغ يتجاوز الرصيد المستحق. حدّث الفاتورة وأدخل المبلغ المتبقي الفعلي. التفاصيل: $detail"
        message.contains("insufficient_stock", ignoreCase = true) ||
            message.contains("insufficient_inventory", ignoreCase = true) ||
            message.contains("stock", ignoreCase = true) && message.contains("available", ignoreCase = true) ->
            "رُفض البيع لأن الكمية المتاحة غير كافية أو تغيّرت أثناء الحفظ. حدّث المخزون قبل المحاولة مجددًا. التفاصيل: $detail"
        status == 401 || status == 403 || code == "42501" ->
            "لم يُسمح بتنفيذ العملية. تحقق من تسجيل الدخول وصلاحيات المؤسسة قبل إعادة المحاولة. التفاصيل: $detail"
        else -> "تعذر تأكيد العملية من الخادم (HTTP $status). لا تنشئ طلبًا جديدًا بمفتاح مختلف؛ استخدم إعادة المحاولة للعملية نفسها ثم تحقق من الفاتورة/الدفعة في السجل. التفاصيل: $detail"
    }
}

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

private fun commercialCloudSessionPreferences(context: Context) = EncryptedSharedPreferences.create(
    context,
    "ruh_catalog_auth_session",
    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)

private fun commercialCloudRequest(path: String, method: String, payload: JSONObject? = null, token: String? = null): String {
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anon = BuildConfig.SUPABASE_ANON_KEY
    require(base.startsWith("https://") && anon.isNotBlank()) { "إعدادات اتصال Supabase غير مكتملة." }
    val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15000
        readTimeout = 25000
        setRequestProperty("apikey", anon)
        setRequestProperty("Accept", "application/json")
        if (payload != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        if (token != null) setRequestProperty("Authorization", "Bearer $token")
    }
    return try {
        if (payload != null) connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException(commercialCloudActionableError(raw, status))
        }
        raw
    } finally {
        connection.disconnect()
    }
}

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

    val session = JSONObject(commercialCloudRequest(
        "/auth/v1/token?grant_type=refresh_token", "POST",
        JSONObject().put("refresh_token", savedRefresh)
    ))
    val access = session.optString("access_token")
    val rotatedRefresh = session.optString("refresh_token").ifBlank { savedRefresh }
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", rotatedRefresh).apply()

    val query = "/rest/v1/commercial_inventory_balances" +
        "?select=offering_id,quantity_on_hand,quantity_reserved,reorder_point,offering:commercial_offerings!inner(id,price_minor,currency_code,activity_id,product:commercial_products!inner(name,description,unit_name,state),categories:commercial_offering_categories(is_primary,category:commercial_product_categories(name_ar,name_en)))" +
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
            val quantity = (row.optDouble("quantity_on_hand", 0.0) - row.optDouble("quantity_reserved", 0.0)).coerceAtLeast(0.0).toInt()
            val price = offering.optLong("price_minor", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            add(Product(
                name = product.optString("name"),
                category = categoryName,
                description = product.optString("description"),
                stock = quantity,
                price = String.format(Locale.US, "%,d ر.ي", price),
                cloudOfferingId = offering.optString("id"),
                cloudPriceMinor = offering.optLong("price_minor", 0L).coerceAtLeast(0L),
                unitCode = product.optString("unit_name").ifBlank { "قطعة" },
                createdBy = orgId,
                reorderPoint = row.optDouble("reorder_point", 0.0).toInt().coerceAtLeast(0)
            ))
        }
    }
    return CommercialCloudInventory(
        CommercialCloudContext(orgId, activityId, locationId, currency),
        access,
        products
    )
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
    val session = JSONObject(commercialCloudRequest(
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
    return commercialCloudRequest("/rest/v1/rpc/commercial_create_inventory_item_with_category_and_unit", "POST", payload, access)
}



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
    val session = JSONObject(commercialCloudRequest(
        "/auth/v1/token?grant_type=refresh_token", "POST",
        JSONObject().put("refresh_token", savedRefresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { savedRefresh }).apply()
    val result = JSONObject(commercialCloudRequest(
        "/rest/v1/rpc/commercial_checkout_sale", "POST",
        JSONObject().put("p_offering_id", offeringId)
            .put("p_location_id", locationId)
            .put("p_quantity", quantity)
            .put("p_idempotency_key", idempotencyKey),
        access
    ))
    return result
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
        "?select=id,display_name,phone,email,state&organization_id=eq.$orgId&state=eq.active&order=display_name.asc,id.asc&limit=200"
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

internal data class CommercialCloudCheckoutLine(
    val offeringId: String,
    val quantity: Int
)

/** Merge repeated offering IDs before idempotency fingerprinting and checkout. */
internal fun aggregateCommercialCheckoutLines(
    lines: List<CommercialCloudCheckoutLine>
): List<CommercialCloudCheckoutLine> {
    require(lines.isNotEmpty() && lines.size <= 200) { "أضف صنفًا واحدًا على الأقل إلى الفاتورة." }
    val totals = linkedMapOf<String, Int>()
    lines.forEach { line ->
        require(line.offeringId.isNotBlank() && line.quantity in 1..100000) { "تحقق من الأصناف والكميات." }
        val total = Math.addExact(totals[line.offeringId] ?: 0, line.quantity)
        require(total <= 100000) { "إجمالي كمية الصنف يتجاوز الحد المسموح." }
        totals[line.offeringId] = total
    }
    return totals.entries.sortedBy { it.key }.map { CommercialCloudCheckoutLine(it.key, it.value) }
}

internal fun getOrCreateCommercialCloudMultiSaleKey(
    context: Context,
    lines: List<CommercialCloudCheckoutLine>,
    customerId: String? = null,
    paymentMode: String = "cash",
    initialPaymentMinor: Long = 0L
): String {
    val normalizedLines = aggregateCommercialCheckoutLines(lines)
    val canonical = JSONArray()
    normalizedLines.forEach { line ->
        canonical.put(JSONObject().put("offering_id", line.offeringId).put("quantity", line.quantity))
    }
    val payload = JSONObject().put("customer_id", customerId ?: JSONObject.NULL)
        .put("payment_mode", paymentMode)
        .put("initial_payment_minor", if (paymentMode == "cash") JSONObject.NULL else initialPaymentMinor)
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

internal fun checkoutCommercialCloudSaleMulti(
    context: Context,
    lines: List<CommercialCloudCheckoutLine>,
    idempotencyKey: String,
    customerId: String? = null,
    paymentMode: String = "cash",
    initialPaymentMinor: Long = 0L
): JSONObject {
    val normalizedLines = aggregateCommercialCheckoutLines(lines)
    val savedContext = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val locationId = savedContext.getString("location_id", null).orEmpty()
    require(locationId.isNotBlank()) { "معرّف موقع المخزون غير محفوظ." }
    val payload = JSONArray()
    normalizedLines.forEach { line ->
        payload.put(JSONObject().put("offering_id", line.offeringId).put("quantity", line.quantity))
    }
    val sessionPrefs = commercialCloudSessionPreferences(context)
    val savedRefresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(savedRefresh.isNotBlank()) { "سجّل الدخول إلى حساب Supabase أولًا." }
    val session = JSONObject(commercialCloudRequest(
        "/auth/v1/token?grant_type=refresh_token", "POST",
        JSONObject().put("refresh_token", savedRefresh)
    ))
    val access = session.optString("access_token")
    require(access.isNotBlank()) { "انتهت جلسة الحساب؛ سجّل الدخول مجددًا." }
    sessionPrefs.edit().putString("refresh_token", session.optString("refresh_token").ifBlank { savedRefresh }).apply()
    return JSONObject(commercialCloudRequest(
        "/rest/v1/rpc/commercial_checkout_sale_multi", "POST",
        JSONObject().put("p_lines", payload).put("p_location_id", locationId)
            .put("p_idempotency_key", idempotencyKey)
            .put("p_customer_id", customerId ?: JSONObject.NULL)
            .put("p_payment_mode", paymentMode)
            .put("p_initial_payment_minor", if (paymentMode == "cash") JSONObject.NULL else initialPaymentMinor),
        access
    ))
}

internal data class CommercialCloudSaleLine(
    val productName: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long
)

internal data class CommercialCloudPaymentSummary(
    val amountMinor: Long,
    val status: String,
    val methodType: String,
    val createdAt: String
)

internal data class CommercialCloudReturnSummary(
    val status: String,
    val refundStatus: String,
    val restockStatus: String,
    val requestedRefundMinor: Long,
    val approvedRefundMinor: Long?,
    val createdAt: String
)

internal data class CommercialCloudSaleDocument(
    val id: String,
    val receiptNumber: String,
    val customerName: String,
    val customerPhone: String,
    val customerEmail: String,
    val totalMinor: Long,
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
        "?select=id,receipt_number,total_minor,payment_status,status,created_at,currency_code,customer:commercial_customers(display_name,phone,email),items:commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor),payments:commercial_payments(amount_minor,status,payment_method_type,created_at),returns:commercial_sales_returns(status,refund_status,restock_status,requested_refund_minor,approved_refund_minor,created_at)" +
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
                    add(CommercialCloudSaleLine(
                        productName = item.optString("product_name_snapshot").ifBlank { "صنف بدون اسم" },
                        quantity = item.optString("quantity").takeUnless { it == "null" }.orEmpty(),
                        unitPriceMinor = item.optLong("unit_price_minor", 0L),
                        lineTotalMinor = item.optLong("line_total_minor", 0L)
                    ))
                }
            }
            val paymentRows = row.optJSONArray("payments")
            val payments = buildList {
                for (paymentIndex in 0 until (paymentRows?.length() ?: 0)) {
                    val payment = paymentRows?.optJSONObject(paymentIndex) ?: continue
                    add(CommercialCloudPaymentSummary(
                        amountMinor = payment.optLong("amount_minor", 0L),
                        status = payment.optString("status").ifBlank { "غير محدد" },
                        methodType = payment.optString("payment_method_type").takeUnless { it == "null" }.orEmpty(),
                        createdAt = payment.optString("created_at").takeUnless { it == "null" }.orEmpty()
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
                        status = item.optString("status").ifBlank { "غير محدد" },
                        refundStatus = item.optString("refund_status").ifBlank { "غير محدد" },
                        restockStatus = item.optString("restock_status").ifBlank { "غير محدد" },
                        requestedRefundMinor = item.optLong("requested_refund_minor", 0L),
                        approvedRefundMinor = if (item.isNull("approved_refund_minor")) null else item.optLong("approved_refund_minor"),
                        createdAt = item.optString("created_at").takeUnless { it == "null" }.orEmpty()
                    ))
                }
            }
            add(CommercialCloudSaleDocument(
                id = row.optString("id"),
                receiptNumber = row.optString("receipt_number").ifBlank { "—" },
                customerName = customer?.optString("display_name").orEmpty().takeUnless { it == "null" }.orEmpty().ifBlank { "عميل نقدي / غير محدد" },
                customerPhone = customer?.optString("phone").orEmpty().takeUnless { it == "null" }.orEmpty(),
                customerEmail = customer?.optString("email").orEmpty().takeUnless { it == "null" }.orEmpty(),
                totalMinor = totalMinor,
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


internal data class CommercialCloudPurchaseLine(
    val lineNumber: Int,
    val description: String,
    val quantityOrdered: String,
    val quantityReceived: String,
    val unitCostMinor: Long
)

internal data class CommercialCloudPurchaseDocument(
    val id: String,
    val orderNumber: String,
    val supplierName: String,
    val status: String,
    val orderedAt: String,
    val expectedAt: String,
    val currencyCode: String,
    val notes: String,
    val lines: List<CommercialCloudPurchaseLine>
)

/** Read-only purchase order detail projection using the existing order/item/supplier tables. */
internal fun loadCommercialCloudPurchaseDocuments(context: Context): List<CommercialCloudPurchaseDocument> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_purchase_orders" +
        "?select=id,order_number,status,ordered_at,expected_at,currency_code,notes,supplier:commercial_suppliers(display_name),items:commercial_purchase_order_items(line_number,item_description,quantity_ordered,quantity_received,unit_cost_minor)" +
        "&organization_id=eq.$orgId&order=created_at.desc.nullslast,id.asc"
    val rows = commercialCloudPagedGet(path, access)
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val supplier = row.optJSONObject("supplier")
            val items = row.optJSONArray("items")
            val lines = buildList {
                for (lineIndex in 0 until (items?.length() ?: 0)) {
                    val item = items?.optJSONObject(lineIndex) ?: continue
                    add(CommercialCloudPurchaseLine(
                        lineNumber = item.optInt("line_number", lineIndex + 1),
                        description = item.optString("item_description").ifBlank { "بند بدون وصف" },
                        quantityOrdered = item.optString("quantity_ordered").takeUnless { it == "null" }.orEmpty(),
                        quantityReceived = item.optString("quantity_received").takeUnless { it == "null" }.orEmpty(),
                        unitCostMinor = item.optLong("unit_cost_minor", 0L)
                    ))
                }
            }.sortedBy { it.lineNumber }
            add(CommercialCloudPurchaseDocument(
                id = row.optString("id"),
                orderNumber = row.optString("order_number").ifBlank { "—" },
                supplierName = supplier?.optString("display_name").orEmpty().takeUnless { it == "null" }.orEmpty().ifBlank { "مورد غير محدد" },
                status = row.optString("status").ifBlank { "غير محدد" },
                orderedAt = row.optString("ordered_at").takeUnless { it == "null" }.orEmpty(),
                expectedAt = row.optString("expected_at").takeUnless { it == "null" }.orEmpty(),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                notes = row.optString("notes").takeUnless { it == "null" }.orEmpty(),
                lines = lines
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
    val session = JSONObject(commercialCloudRequest(
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
                unitCode = "قطعة"
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
private fun commercialCloudPagedGet(path: String, accessToken: String, pageSize: Int = 500): JSONArray {
    require(pageSize in 1..1000) { "حجم الصفحة غير صالح." }
    require(path.contains("?") && !path.contains("limit=") && !path.contains("offset=")) { "استعلام الصفحات يحتوي على محددات مسبقة." }
    val rows = JSONArray()
    var offset = 0
    while (true) {
        val page = JSONArray(commercialCloudRequest("$path&limit=$pageSize&offset=$offset", "GET", token = accessToken))
        for (index in 0 until page.length()) rows.put(page.getJSONObject(index))
        if (page.length() < pageSize) break
        offset += pageSize
    }
    return rows
}

private fun commercialMasterDataAccess(context: Context): Pair<String, String> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val orgId = saved.getString("organization_id", null).orEmpty()
    require(orgId.isNotBlank()) { "ملف المؤسسة غير محفوظ." }
    val sessionPrefs = commercialCloudSessionPreferences(context)
    val refresh = sessionPrefs.getString("refresh_token", null).orEmpty()
    require(refresh.isNotBlank()) { "سجّل الدخول إلى الحساب أولًا." }
    val session = JSONObject(commercialCloudRequest(
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
    commercialCloudRequest("/rest/v1/rpc/commercial_assign_offering_category", "POST", payload, access)
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
    commercialCloudRequest("/rest/v1/$table", "POST", payload, access)
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
    val existingRows = JSONArray(commercialCloudRequest(
        "/rest/v1/commercial_department_warehouse_assignments?select=department_id,warehouse_id&organization_id=eq.$orgId&department_id=eq.$departmentId&warehouse_id=eq.$warehouseId&limit=1",
        "GET", token = access
    ))
    if (existingRows.length() > 0) return
    val payload = JSONObject()
        .put("organization_id", orgId)
        .put("department_id", departmentId)
        .put("warehouse_id", warehouseId)
        .put("created_by", JSONObject.NULL)
    commercialCloudRequest("/rest/v1/commercial_department_warehouse_assignments", "POST", payload, access)
}

internal data class CommercialCloudStockAlert(
    val offeringId: String,
    val productName: String,
    val categoryName: String,
    val unitName: String,
    val availableQuantity: Double,
    val reorderPoint: Double
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
        "?select=offering_id,quantity_on_hand,quantity_reserved,reorder_point,offering:commercial_offerings!inner(id,activity_id,product:commercial_products!inner(name,unit_name,state),categories:commercial_offering_categories(is_primary,category:commercial_product_categories(name_ar,name_en)))" +
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
                reorderPoint = row.optDouble("reorder_point", 0.0).coerceAtLeast(0.0)
            ))
        }
    }
}

internal data class CommercialCloudReturnableSaleItem(
    val id: String,
    val productName: String,
    val soldQuantity: Int,
    val remainingQuantity: Int,
    val lineTotalMinor: Long
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
    val path = "/rest/v1/commercial_sales?select=id,receipt_number,currency_code,created_at,customer:commercial_customers(display_name),items:commercial_sale_items(id,product_name_snapshot,quantity,line_total_minor),returns:commercial_sales_returns(status,items:commercial_sale_return_items(sale_item_id,quantity))&organization_id=eq.$orgId&status=eq.completed&order=created_at.desc,id.asc"
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
                    val remaining = (qty - (alreadyRequested[itemId] ?: 0)).coerceAtLeast(0)
                    if (remaining <= 0) continue
                    add(CommercialCloudReturnableSaleItem(
                        id = itemId,
                        productName = item.optString("product_name_snapshot").takeUnless { it == "null" || it.isBlank() } ?: "صنف غير معروف",
                        soldQuantity = qty,
                        remainingQuantity = remaining,
                        lineTotalMinor = item.optLong("line_total_minor", 0L)
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
    quantities: Map<String, Int>
): String {
    require(saleId.isNotBlank()) { "اختر الفاتورة أولًا." }
    require(reasonCode in setOf("customer_request", "damaged", "wrong_item", "not_as_described", "duplicate_charge", "other")) { "سبب المرتجع غير صالح." }
    val selected = quantities.filterValues { it > 0 }
    require(selected.isNotEmpty()) { "أدخل كمية مرتجعة لصنف واحد على الأقل." }
    val items = JSONArray()
    selected.forEach { (saleItemId, quantity) ->
        require(saleItemId.isNotBlank() && quantity > 0) { "كمية المرتجع غير صالحة." }
        items.put(JSONObject().put("sale_item_id", saleItemId).put("quantity", quantity).put("restock", true))
    }
    val (_, access) = commercialMasterDataAccess(context)
    val payload = JSONObject()
        .put("p_sale_id", saleId)
        .put("p_idempotency_key", UUID.randomUUID().toString())
        .put("p_reason_code", reasonCode)
        .put("p_items", items)
        .put("p_reason_note", reasonNote.trim().ifBlank { JSONObject.NULL })
    return commercialCloudRequest("/rest/v1/rpc/commercial_request_sale_return", "POST", payload, access)
}

internal data class CommercialCloudReturnItem(
    val id: String,
    val saleItemId: String,
    val productName: String,
    val quantity: Int,
    val refundAmountMinor: Long,
    val restock: Boolean,
    val restockedQuantity: Int,
    val conditionCode: String,
    val note: String
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
    val decisionNote: String,
    val items: List<CommercialCloudReturnItem>
)

internal fun loadCommercialCloudSaleReturns(context: Context): List<CommercialCloudSaleReturn> {
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales_returns?select=id,sale_id,reason_code,reason_note,status,refund_status,restock_status,currency_code,requested_refund_minor,approved_refund_minor,created_at,decision_note,sale:commercial_sales!inner(receipt_number,customer:commercial_customers(display_name)),items:commercial_sale_return_items(id,sale_item_id,quantity,refund_amount_minor,restock,restocked_quantity,condition_code,note,sale_item:commercial_sale_items(product_name_snapshot))&organization_id=eq.$orgId&order=created_at.desc,id.asc"
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
                        restock = item.optBoolean("restock", false),
                        restockedQuantity = item.optInt("restocked_quantity", 0),
                        conditionCode = item.optString("condition_code").takeUnless { it == "null" }.orEmpty(),
                        note = item.optString("note").takeUnless { it == "null" }.orEmpty()
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
    commercialCloudRequest("/rest/v1/rpc/commercial_decide_sale_return", "POST", payload, access)
}

internal fun inspectCommercialCloudSaleReturnItem(context: Context, itemId: String, condition: String, restock: Boolean, note: String) {
    require(condition in setOf("resellable", "damaged", "written_off", "uninspected")) { "حالة الصنف غير صالحة." }
    val (_, access) = commercialMasterDataAccess(context)
    val payload = JSONObject()
        .put("p_return_item_id", itemId)
        .put("p_condition_code", condition)
        .put("p_restock", restock)
        .put("p_note", note.trim().ifBlank { JSONObject.NULL })
    commercialCloudRequest("/rest/v1/rpc/commercial_inspect_sale_return_item", "POST", payload, access)
}

internal fun restockCommercialCloudSaleReturn(context: Context, returnId: String): String {
    val (_, access) = commercialMasterDataAccess(context)
    return commercialCloudRequest(
        "/rest/v1/rpc/commercial_restock_approved_sale_return", "POST",
        JSONObject().put("p_return_id", returnId), access
    )
}

internal data class CommercialCloudRecord(
    val id: String, val title: String, val subtitle: String, val status: String,
    val amountMinor: Long = 0L, val createdAt: String = "",
    val details: List<String> = emptyList()
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
            add(CommercialCloudRecord(id, title, subtitle, status, amount, row.optString("created_at").takeUnless { it == "null" }.orEmpty(), details))
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
    val currencyCode: String,
    val createdAt: String
) {
    val dueMinor: Long get() = (totalMinor - paidMinor).coerceAtLeast(0L)
}

internal fun loadCommercialCloudReceivables(context: Context): List<CommercialCloudReceivable> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales" +
        "?select=id,receipt_number,customer_id,total_minor,currency_code,created_at,payment_status,customer:commercial_customers(display_name),payments:commercial_payments(amount_minor,status)" +
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
                if (payment.optString("status") == "succeeded") {
                    paid = Math.addExact(paid, payment.optLong("amount_minor", 0L).coerceAtLeast(0L))
                }
            }
            val total = row.optLong("total_minor", 0L).coerceAtLeast(0L)
            if (paid < total) add(CommercialCloudReceivable(
                saleId = row.optString("id"),
                receiptNumber = row.optString("receipt_number"),
                customerId = customerId,
                customerName = customerName,
                totalMinor = total,
                paidMinor = paid,
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
    val result = JSONObject(commercialCloudRequest(
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
