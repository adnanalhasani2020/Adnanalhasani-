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
            val error = runCatching { JSONObject(raw) }.getOrNull()
            throw IllegalStateException(
                listOf(error?.optString("message"), error?.optString("hint"))
                    .filter { !it.isNullOrBlank() && it != "null" }
                    .joinToString(" — ").ifBlank { "فشل الاتصال بقاعدة البيانات (HTTP $status)." }
            )
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
        "&organization_id=eq.$orgId&location_id=eq.$locationId&offering.activity_id=eq.$activityId&order=updated_at.desc"
    val rows = JSONArray(commercialCloudRequest(query, "GET", token = access))
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
                .map { it.optString("name_ar").ifBlank { it.optString("name_en") } }
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


internal data class CommercialCloudSaleLine(
    val productName: String,
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long
)

internal data class CommercialCloudSaleDocument(
    val id: String,
    val receiptNumber: String,
    val customerName: String,
    val totalMinor: Long,
    val paymentStatus: String,
    val createdAt: String,
    val currencyCode: String,
    val lines: List<CommercialCloudSaleLine>
)

/** Detailed, read-only invoice projection for the cloud sales history screen. */
internal fun loadCommercialCloudSaleDocuments(context: Context): List<CommercialCloudSaleDocument> {
    val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
    val activityId = saved.getString("activity_id", null).orEmpty()
    require(activityId.isNotBlank()) { "ملف النشاط غير محفوظ." }
    val (orgId, access) = commercialMasterDataAccess(context)
    val path = "/rest/v1/commercial_sales" +
        "?select=id,receipt_number,total_minor,payment_status,created_at,currency_code,customer:commercial_customers(display_name),items:commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&order=created_at.desc&limit=200"
    val rows = JSONArray(commercialCloudRequest(path, "GET", token = access))
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
            add(CommercialCloudSaleDocument(
                id = row.optString("id"),
                receiptNumber = row.optString("receipt_number").ifBlank { "—" },
                customerName = customer?.optString("display_name").orEmpty().takeUnless { it == "null" }.orEmpty().ifBlank { "عميل نقدي / غير محدد" },
                totalMinor = row.optLong("total_minor", 0L),
                paymentStatus = row.optString("payment_status").ifBlank { "غير محدد" },
                createdAt = row.optString("created_at").takeUnless { it == "null" }.orEmpty(),
                currencyCode = row.optString("currency_code").takeUnless { it == "null" }.orEmpty(),
                lines = lines
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
        "&organization_id=eq.$orgId&order=created_at.desc.nullslast&limit=200"
    val rows = JSONArray(commercialCloudRequest(path, "GET", token = access))
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
        "?select=id,receipt_number,total_minor,payment_status,created_at,customer:commercial_customers(display_name),items:commercial_sale_items(product_name_snapshot,quantity,line_total_minor,unit_price_minor)" +
        "&organization_id=eq.$orgId&activity_id=eq.$activityId&order=created_at.desc&limit=200"
    val rows = JSONArray(commercialCloudRequest(path, "GET", token = access))
    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val items = row.optJSONArray("items")
            val firstItem = items?.optJSONObject(0)
            val total = row.optLong("total_minor", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val paymentStatus = row.optString("payment_status").lowercase(Locale.ROOT)
            val isCredit = paymentStatus in setOf("unpaid", "partially_paid", "partial", "pending", "due", "credit", "failed")
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
                paid = if (paymentStatus == "paid") total else 0,
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
    val warehouseId: String = ""
)

internal data class CommercialUnitOption(
    val code: String,
    val label: String,
    val dimension: String,
    val decimalPlaces: Int
)

internal fun loadCommercialUnitsOfMeasure(context: Context): List<CommercialUnitOption> {
    val (_, access) = commercialMasterDataAccess(context)
    val rows = JSONArray(commercialCloudRequest(
        "/rest/v1/commercial_unit_of_measure?select=code,display_name,dimension,decimal_places&is_active=eq.true&order=dimension.asc,code.asc",
        "GET", token = access
    ))
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
    val rows = JSONArray(commercialCloudRequest(
        "/rest/v1/$table?select=$fields&organization_id=eq.$orgId&order=code.asc",
        "GET", token = access
    ))
    return buildList {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            add(CommercialMasterDataRow(
                id = row.optString("id"),
                code = row.optString("code"),
                label = row.optString("name").ifBlank { row.optString("name_ar") },
                kind = row.optString(when (table) { "commercial_storage_locations" -> "location_type"; "commercial_warehouses" -> "warehouse_type"; else -> "state" }).ifBlank { table.removePrefix("commercial_") },
                parentId = row.optString(if (table == "commercial_storage_locations") "parent_location_id" else "parent_id").takeUnless { it == "null" }.orEmpty(),
                warehouseId = row.optString("warehouse_id").takeUnless { it == "null" }.orEmpty()
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


internal data class CommercialCloudRecord(
    val id: String, val title: String, val subtitle: String, val status: String,
    val amountMinor: Long = 0L, val createdAt: String = ""
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
    val rows = JSONArray(commercialCloudRequest(
        "/rest/v1/${config.first}?select=${config.second}&organization_id=eq.$orgId&order=${config.third}.desc.nullslast&limit=200",
        "GET", token = access
    ))
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
            add(CommercialCloudRecord(id, title, subtitle, status, amount, row.optString("created_at").takeUnless { it == "null" }.orEmpty()))
        }
    }
}
