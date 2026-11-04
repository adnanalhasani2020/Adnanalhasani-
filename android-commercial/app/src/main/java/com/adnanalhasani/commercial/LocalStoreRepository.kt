package com.adnanalhasani.commercial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object LocalStoreRepository {
    private const val STORE_PREFS = "commercial_store_local_v1"
    private const val PRODUCTS_KEY = "products_json"
    private const val SALES_KEY = "sales_json"
    private const val SETTLEMENTS_KEY = "settlements_json"
    private const val EXPENSES_KEY = "expenses_json"
    
    private fun scopedStoreKey(context: Context, key: String): String {
        val organizationId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
            .getString("organization_id", null).orEmpty()
        return if (organizationId.isBlank()) key else "${key}_${organizationId}"
    }
    
    fun loadProducts(context: Context): List<Product> {
        val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, PRODUCTS_KEY), null)
            ?: return emptyList()
        return runCatching {
            val json = JSONArray(saved)
            List(json.length()) { i ->
                val item = json.getJSONObject(i)
                Product(item.getString("name"), item.getString("category"), item.getInt("stock"), item.getString("price"), item.optInt("costPrice", 0), item.optString("catalogCode", ""), item.optString("barcode", ""), item.optString("unitCode", "EA"), item.optString("imageUrl", ""), item.optString("description", ""), item.optString("brand", ""), item.optString("manufacturer", ""), item.optString("countryOfManufacture", ""), item.optLong("createdAt", System.currentTimeMillis()), item.optString("createdBy", ""), item.optString("unitVariantsJson", "[]"), reorderPoint = item.optInt("reorderPoint", -1))
            }
        }.getOrElse { emptyList() }
    }
    
    fun saveProducts(context: Context, items: List<Product>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().put("name", item.name).put("category", item.category)
                .put("stock", item.stock).put("price", item.price).put("costPrice", item.costPrice).put("catalogCode", item.catalogCode).put("barcode", item.barcode).put("unitCode", item.unitCode).put("imageUrl", item.imageUrl).put("description", item.description).put("brand", item.brand).put("manufacturer", item.manufacturer).put("countryOfManufacture", item.countryOfManufacture).put("createdAt", item.createdAt).put("createdBy", item.createdBy).put("unitVariantsJson", item.unitVariantsJson).put("reorderPoint", item.reorderPoint))
        }
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, PRODUCTS_KEY), json.toString()).apply()
    }
    
    fun loadSales(context: Context): List<SaleRecord> {
        val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, SALES_KEY), null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(saved)
            List(json.length()) { i ->
                val item = json.getJSONObject(i)
                SaleRecord(item.getInt("number"), item.getString("product"), item.getInt("quantity"),
                    item.getInt("total"), item.getBoolean("credit"), item.optString("customer", ""), item.optInt("paid", 0), item.optLong("createdAt", 0L), item.optInt("unitCost", 0), item.optString("unitCode", "EA"), item.optInt("baseQuantitySold", item.optInt("quantity", 1)), item.optString("currencyCode", "YER").ifBlank { "YER" }, item.optString("status", "completed").ifBlank { "completed" })
            }
        }.getOrElse { emptyList() }
    }
    
    fun loadSettlements(context: Context): List<SettlementRecord> {
        val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, SETTLEMENTS_KEY), null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(saved)
            List(json.length()) { i ->
                val item = json.getJSONObject(i)
                SettlementRecord(item.getInt("invoiceNumber"), item.optString("customer", ""), item.getInt("amount"), item.optLong("timestamp", 0L))
            }
        }.getOrElse { emptyList() }
    }
    
    fun loadExpenses(context: Context): List<ExpenseRecord> {
        val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, EXPENSES_KEY), null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(saved)
            List(json.length()) { i ->
                val item = json.getJSONObject(i)
                ExpenseRecord(item.optLong("id", i.toLong()), item.optString("category", "أخرى"),
                    item.getInt("amount"), item.optString("note", ""), item.optLong("timestamp", 0L))
            }
        }.getOrElse { emptyList() }
    }
    
    fun saveExpenses(context: Context, items: List<ExpenseRecord>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().put("id", item.id).put("category", item.category)
                .put("amount", item.amount).put("note", item.note).put("timestamp", item.timestamp))
        }
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, EXPENSES_KEY), json.toString()).apply()
    }
    
    fun saveSettlements(context: Context, items: List<SettlementRecord>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().put("invoiceNumber", item.invoiceNumber).put("customer", item.customer)
                .put("amount", item.amount).put("timestamp", item.timestamp))
        }
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, SETTLEMENTS_KEY), json.toString()).apply()
    }
    
    fun saveSales(context: Context, items: List<SaleRecord>) {
        val json = JSONArray()
        items.forEach { item ->
            json.put(JSONObject().put("number", item.number).put("product", item.product)
                .put("quantity", item.quantity).put("total", item.total).put("credit", item.credit)
                .put("customer", item.customer).put("paid", item.paid).put("createdAt", item.createdAt).put("unitCost", item.unitCost).put("unitCode", item.unitCode).put("baseQuantitySold", item.baseQuantitySold).put("currencyCode", item.currencyCode).put("status", item.status))
        }
        context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, SALES_KEY), json.toString()).apply()
    }
}
