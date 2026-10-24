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
        "?select=offering_id,quantity_on_hand,quantity_reserved,reorder_point,offering:commercial_offerings!inner(id,price_minor,currency_code,activity_id,product:commercial_products!inner(name,description,unit_name,state))" +
        "&organization_id=eq.$orgId&location_id=eq.$locationId&order=updated_at.desc"
    val rows = JSONArray(commercialCloudRequest(query, "GET", token = access))
    val products = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val offering = row.optJSONObject("offering") ?: continue
            val product = offering.optJSONObject("product") ?: continue
            if (product.optString("state") != "active") continue
            val quantity = (row.optDouble("quantity_on_hand", 0.0) - row.optDouble("quantity_reserved", 0.0)).coerceAtLeast(0.0).toInt()
            val price = offering.optLong("price_minor", 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            add(Product(
                name = product.optString("name"),
                category = "غير مصنف",
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
    reorderPoint: Double = 0.0
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
    return commercialCloudRequest("/rest/v1/rpc/commercial_create_inventory_item", "POST", payload, access)
}



internal fun getOrCreateCommercialCloudSaleKey(context: Context, offeringId: String, quantity: Int): String {
    require(offeringId.isNotBlank() && quantity > 0) { "بيانات البيع غير صحيحة." }
    val prefs = context.getSharedPreferences("commercial_cloud_pending_sale_v1", Context.MODE_PRIVATE)
    val storedOffering = prefs.getString("offering_id", null).orEmpty()
    val storedQuantity = prefs.getInt("quantity", -1)
    val storedKey = prefs.getString("idempotency_key", null).orEmpty()
    if (storedOffering == offeringId && storedQuantity == quantity && storedKey.isNotBlank()) return storedKey
    val key = UUID.randomUUID().toString()
    prefs.edit()
        .putString("offering_id", offeringId)
        .putInt("quantity", quantity)
        .putString("idempotency_key", key)
        .apply()
    return key
}

internal fun clearCommercialCloudSaleKey(context: Context, idempotencyKey: String) {
    val prefs = context.getSharedPreferences("commercial_cloud_pending_sale_v1", Context.MODE_PRIVATE)
    if (prefs.getString("idempotency_key", null) == idempotencyKey) prefs.edit().clear().apply()
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
                kind = row.optString("warehouse_type").ifBlank { table.removePrefix("commercial_") },
                parentId = row.optString(if (table == "commercial_storage_locations") "parent_location_id" else "parent_id").takeUnless { it == "null" }.orEmpty(),
                warehouseId = row.optString("warehouse_id").takeUnless { it == "null" }.orEmpty()
            ))
        }
    }
}

internal fun createCommercialMasterData(
    context: Context,
    table: String,
    code: String,
    name: String,
    parentId: String = "",
    warehouseType: String = "main",
    warehouseId: String = ""
) {
    require(table in setOf("commercial_warehouses", "commercial_product_categories", "commercial_departments", "commercial_storage_locations")) {
        "نوع الدليل غير مدعوم."
    }
    require(code.trim().isNotEmpty() && name.trim().isNotEmpty()) { "أدخل الرمز والاسم." }
    val (orgId, access) = commercialMasterDataAccess(context)
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
        payload.put("warehouse_id", warehouseId).put("name", name.trim())
            .put("location_type", "internal").put("state", "active")
        if (parentId.isNotBlank()) payload.put("parent_location_id", parentId)
    } else {
        payload.put("name_ar", name.trim())
        if (parentId.isNotBlank()) payload.put("parent_id", parentId)
    }
    commercialCloudRequest("/rest/v1/$table", "POST", payload, access)
}
