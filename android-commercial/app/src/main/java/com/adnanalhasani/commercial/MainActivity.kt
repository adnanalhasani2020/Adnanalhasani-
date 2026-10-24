package com.adnanalhasani.commercial

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF183B35)
private val Green = Color(0xFF167D63)
private val Mint = Color(0xFFE8F5EF)
private val Canvas = Color(0xFFF5F7F6)
private val Muted = Color(0xFF71817B)
private val Orange = Color(0xFFF4A340)
private val Border = Color(0xFFE5EBE8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Canvas, surface = Color.White)) { StoreApp(applicationContext) } }
    }
}

internal data class Product(val name: String, val category: String, val stock: Int, val price: String, val costPrice: Int = 0, val catalogCode: String = "", val barcode: String = "", val unitCode: String = "EA", val imageUrl: String = "", val description: String = "", val brand: String = "", val manufacturer: String = "", val countryOfManufacture: String = "", val createdAt: Long = System.currentTimeMillis(), val createdBy: String = "", val unitVariantsJson: String = "[]", val cloudOfferingId: String = "")

private data class LocalSaleUnit(
    val code: String,
    val role: String,
    val label: String,
    val quantityInBase: Double,
    val barcode: String,
    val imageUrl: String,
    val isDefault: Boolean
)

private fun productMatchesUnitQuery(product: Product, query: String): Boolean {
    if (query.isBlank()) return false
    return runCatching {
        val units = JSONArray(product.unitVariantsJson)
        (0 until units.length()).any { index ->
            val unit = units.getJSONObject(index)
            listOf("barcode","unitCode","sku").any { key -> unit.optString(key).contains(query,true) }
        }
    }.getOrDefault(false)
}

private fun localSaleUnits(product: Product): List<LocalSaleUnit> {
    val parsed = runCatching {
        val json = JSONArray(product.unitVariantsJson)
        List(json.length()) { index ->
            val item = json.getJSONObject(index)
            LocalSaleUnit(
                code = item.optString("unitCode", product.unitCode).ifBlank { product.unitCode },
                role = item.optString("role", "base").ifBlank { "base" },
                label = item.optString("packageName").takeUnless { it.isNullOrBlank() || it == "null" }
                    ?: item.optString("unitName").takeUnless { it.isNullOrBlank() || it == "null" }
                    ?: item.optString("unitCode", product.unitCode).ifBlank { product.unitCode },
                quantityInBase = item.optString("quantityInBase", "1").toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
                barcode = item.optString("barcode").takeUnless { it == "null" }.orEmpty(),
                imageUrl = item.optString("imageUrl").takeUnless { it == "null" }.orEmpty(),
                isDefault = item.optBoolean("isDefault", false)
            )
        }.filter { it.code.isNotBlank() && it.role in setOf("base", "sale") }
    }.getOrDefault(emptyList())
    return parsed.ifEmpty {
        listOf(LocalSaleUnit(product.unitCode.ifBlank { "EA" }, "base", product.unitCode.ifBlank { "EA" }, 1.0, product.barcode, product.imageUrl, true))
    }
}
internal data class SaleRecord(val number: Int, val product: String, val quantity: Int, val total: Int, val credit: Boolean, val customer: String = "", val paid: Int = 0, val createdAt: Long = System.currentTimeMillis(), val unitCost: Int = 0, val unitCode: String = "EA", val baseQuantitySold: Int = quantity)
internal data class SettlementRecord(val invoiceNumber: Int, val customer: String, val amount: Int, val timestamp: Long = System.currentTimeMillis())
internal data class ExpenseRecord(val id: Long, val category: String, val amount: Int, val note: String, val timestamp: Long = System.currentTimeMillis())
private fun money(value: Int): String = String.format(Locale.US, "%,d ر.ي", value)

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

private fun loadProducts(context: Context): List<Product> {
    val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, PRODUCTS_KEY), null)
        ?: return products
    return runCatching {
        val json = JSONArray(saved)
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            Product(item.getString("name"), item.getString("category"), item.getInt("stock"), item.getString("price"), item.optInt("costPrice", 0), item.optString("catalogCode", ""), item.optString("barcode", ""), item.optString("unitCode", "EA"), item.optString("imageUrl", ""), item.optString("description", ""), item.optString("brand", ""), item.optString("manufacturer", ""), item.optString("countryOfManufacture", ""), item.optLong("createdAt", System.currentTimeMillis()), item.optString("createdBy", ""), item.optString("unitVariantsJson", "[]"))
        }
    }.getOrElse { products }
}

private fun saveProducts(context: Context, items: List<Product>) {
    val json = JSONArray()
    items.forEach { item ->
        json.put(JSONObject().put("name", item.name).put("category", item.category)
            .put("stock", item.stock).put("price", item.price).put("costPrice", item.costPrice).put("catalogCode", item.catalogCode).put("barcode", item.barcode).put("unitCode", item.unitCode).put("imageUrl", item.imageUrl).put("description", item.description).put("brand", item.brand).put("manufacturer", item.manufacturer).put("countryOfManufacture", item.countryOfManufacture).put("createdAt", item.createdAt).put("createdBy", item.createdBy).put("unitVariantsJson", item.unitVariantsJson))
    }
    context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, PRODUCTS_KEY), json.toString()).apply()
}

private fun loadSales(context: Context): List<SaleRecord> {
    val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, SALES_KEY), null) ?: return emptyList()
    return runCatching {
        val json = JSONArray(saved)
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            SaleRecord(item.getInt("number"), item.getString("product"), item.getInt("quantity"),
                item.getInt("total"), item.getBoolean("credit"), item.optString("customer", ""), item.optInt("paid", 0), item.optLong("createdAt", 0L), item.optInt("unitCost", 0), item.optString("unitCode", "EA"), item.optInt("baseQuantitySold", item.optInt("quantity", 1)))
        }
    }.getOrElse { emptyList() }
}

private fun loadSettlements(context: Context): List<SettlementRecord> {
    val saved = context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).getString(scopedStoreKey(context, SETTLEMENTS_KEY), null) ?: return emptyList()
    return runCatching {
        val json = JSONArray(saved)
        List(json.length()) { i ->
            val item = json.getJSONObject(i)
            SettlementRecord(item.getInt("invoiceNumber"), item.optString("customer", ""), item.getInt("amount"), item.optLong("timestamp", 0L))
        }
    }.getOrElse { emptyList() }
}

private fun loadExpenses(context: Context): List<ExpenseRecord> {
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

private fun saveExpenses(context: Context, items: List<ExpenseRecord>) {
    val json = JSONArray()
    items.forEach { item ->
        json.put(JSONObject().put("id", item.id).put("category", item.category)
            .put("amount", item.amount).put("note", item.note).put("timestamp", item.timestamp))
    }
    context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, EXPENSES_KEY), json.toString()).apply()
}

private fun saveSettlements(context: Context, items: List<SettlementRecord>) {
    val json = JSONArray()
    items.forEach { item ->
        json.put(JSONObject().put("invoiceNumber", item.invoiceNumber).put("customer", item.customer)
            .put("amount", item.amount).put("timestamp", item.timestamp))
    }
    context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, SETTLEMENTS_KEY), json.toString()).apply()
}

private fun saveSales(context: Context, items: List<SaleRecord>) {
    val json = JSONArray()
    items.forEach { item ->
        json.put(JSONObject().put("number", item.number).put("product", item.product)
            .put("quantity", item.quantity).put("total", item.total).put("credit", item.credit)
            .put("customer", item.customer).put("paid", item.paid).put("createdAt", item.createdAt).put("unitCost", item.unitCost).put("unitCode", item.unitCode).put("baseQuantitySold", item.baseQuantitySold))
    }
    context.getSharedPreferences(STORE_PREFS, Context.MODE_PRIVATE).edit().putString(scopedStoreKey(context, SALES_KEY), json.toString()).apply()
}
private val storeCloudIo = Executors.newSingleThreadExecutor()
private val storeCloudMain = Handler(Looper.getMainLooper())

// Do not seed fake inventory into a real store. Products must be added by the user
// or loaded from the authenticated organization's Supabase inventory.
private val products = emptyList<Product>()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoreApp(context: Context) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var newSale by remember { mutableStateOf(false) }
    var addProduct by remember { mutableStateOf(false) }
    var editingProductIndex by remember { mutableIntStateOf(-1) }
    var showDebts by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showExpenses by remember { mutableStateOf(false) }
    var showActivityProfile by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var settlingSaleNumber by remember { mutableIntStateOf(-1) }
    val cloudContextExists = remember {
        val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
        !saved.getString("organization_id", null).isNullOrBlank() &&
            !saved.getString("activity_id", null).isNullOrBlank() &&
            !saved.getString("location_id", null).isNullOrBlank()
    }
    val sales = remember { mutableStateListOf<SaleRecord>().apply { addAll(loadSales(context)) } }
    val settlements = remember { mutableStateListOf<SettlementRecord>().apply { addAll(loadSettlements(context)) } }
    val expenses = remember { mutableStateListOf<ExpenseRecord>().apply { addAll(loadExpenses(context)) } }
    var expenseDialog by remember { mutableStateOf(false) }
    val inventory = remember { mutableStateListOf<Product>().apply { if (!cloudContextExists) addAll(loadProducts(context)) } }
    var cloudInventoryLoaded by remember { mutableStateOf(!cloudContextExists) }
    var cloudInventoryBusy by remember { mutableStateOf(false) }
    var cloudInventoryError by remember { mutableStateOf("") }
    var cloudNotice by remember { mutableStateOf("") }
    var cloudMode by remember { mutableStateOf(cloudContextExists) }
    var nextInvoice by remember { mutableIntStateOf((sales.maxOfOrNull { it.number } ?: 1048) + 1) }

    LaunchedEffect(cloudContextExists) {
        if (!cloudContextExists) return@LaunchedEffect
        cloudInventoryBusy = true
        storeCloudIo.execute {
            val result = runCatching { loadCommercialCloudInventory(context) }
            storeCloudMain.post {
                cloudInventoryBusy = false
                cloudInventoryLoaded = true
                result.onSuccess { snapshot ->
                    inventory.clear()
                    inventory.addAll(snapshot.products)
                    cloudMode = true
                    cloudInventoryError = ""
                }.onFailure {
                    inventory.clear()
                    cloudInventoryError = it.message ?: "تعذر تحميل المخزون من قاعدة البيانات."
                }
            }
        }
    }
    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Storefront, null, tint = Green)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("متجري", fontWeight = FontWeight.Bold, color = Ink)
                            Text("إدارة متجرك ببساطة", fontSize = 11.sp, color = Muted)
                        }
                    }
                },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.NotificationsNone, "الإشعارات", tint = Ink) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val nav = listOf("الرئيسية" to Icons.Default.Storefront, "المبيعات" to Icons.Default.PointOfSale, "المخزون" to Icons.Default.Inventory2, "المزيد" to Icons.Default.MoreHoriz)
                nav.forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first, fontSize = 11.sp) })
                }
            }
        },
        floatingActionButton = {
            if (tab == 1) FloatingActionButton(onClick = { newSale = true }, containerColor = Green, contentColor = Color.White) {
                Icon(Icons.Default.Add, "فاتورة جديدة")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> Dashboard(products = inventory, sales = sales, onNewSale = { newSale = true }, onInventory = { tab = 2 })
                1 -> SalesScreen(sales = sales, onNewSale = { newSale = true })
                2 -> InventoryScreen(inventory, query, { query = it }, { addProduct = true }, { editingProductIndex = it })
                else -> when {
                    showCatalog -> CatalogScreen(onBack = { showCatalog = false }, onAddToInventory = { catalogItem ->
                        val existing = inventory.indexOfFirst { it.catalogCode.isNotBlank() && it.catalogCode == catalogItem.catalogCode }
                        val mapped = Product(name = catalogItem.name, category = catalogItem.category, stock = 0, price = "0 ر.ي", catalogCode = catalogItem.catalogCode, barcode = catalogItem.barcode.ifBlank { catalogItem.availableUnits.firstOrNull { it.isDefault && it.barcode.isNotBlank() }?.barcode.orEmpty() }, unitCode = catalogItem.unitCode, imageUrl = catalogItem.imageUrl, description = catalogItem.description, brand = catalogItem.brand, manufacturer = catalogItem.manufacturer, countryOfManufacture = catalogItem.countryOfManufacture, createdBy = catalogItem.importedBy, unitVariantsJson = JSONArray().also { rows -> catalogItem.availableUnits.forEach { unit -> rows.put(JSONObject().put("unitCode",unit.unitCode).put("unitName",unit.unitName).put("role",unit.role).put("quantityInBase",unit.quantityInBase).put("barcode",unit.barcode).put("sku",unit.sku).put("netQuantity",unit.netQuantity).put("netUnitCode",unit.netUnitCode).put("packageName",unit.packageName).put("imageUrl",unit.imageUrl).put("isDefault",unit.isDefault)) } }.toString())
                        if (cloudMode) {
                            cloudNotice = "تم اختيار قالب الدليل. لإضافته للمخزون السحابي أنشئ الصنف مع سعره وكميته من شاشة المخزون؛ لن يتم حفظ نسخة محلية مضللة."
                        } else {
                            if (existing >= 0) inventory[existing] = inventory[existing].copy(name = mapped.name, category = mapped.category, catalogCode = mapped.catalogCode, barcode = mapped.barcode, unitCode = mapped.unitCode, imageUrl = mapped.imageUrl, description = mapped.description, brand = mapped.brand, manufacturer = mapped.manufacturer, countryOfManufacture = mapped.countryOfManufacture, unitVariantsJson = mapped.unitVariantsJson)
                            else inventory.add(mapped)
                            saveProducts(context, inventory)
                            tab = 2
                        }
                        showCatalog = false
                    })
                    showDebts -> DebtScreen(sales = sales, settlements = settlements, onBack = { showDebts = false }, onSettle = { settlingSaleNumber = it.number })
                    showReports -> ReportsScreen(sales = sales, settlements = settlements, expenses = expenses, onBack = { showReports = false })
                    showExpenses -> ExpensesScreen(expenses = expenses, onBack = { showExpenses = false }, onAdd = { expenseDialog = true })
                    else -> if (showActivityProfile) ActivityProfileScreen(onBack = { showActivityProfile = false }) else MoreScreen(onCustomers = { showDebts = true }, onReports = { showReports = true }, onExpenses = { showExpenses = true }, onActivityProfile = { showActivityProfile = true }, onCatalog = { showCatalog = true })
                }
            }
        }
    }
    if (editingProductIndex in inventory.indices) EditProductDialog(
        product = inventory[editingProductIndex],
        onDismiss = { editingProductIndex = -1 },
        onSave = { name, category, stock, price, costPrice ->
            if (cloudMode) {
                cloudNotice = "تعديل الصنف أو رصيده محجوب مؤقتًا حتى نوصل عملية التعديل إلى سجل حركات المخزون على الخادم. لم تُحفظ تغييرات محلية."
            } else {
                inventory[editingProductIndex] = inventory[editingProductIndex].copy(name = name, category = category, stock = stock, price = money(price), costPrice = costPrice)
                saveProducts(context, inventory)
            }
            editingProductIndex = -1
        },
        onDelete = {
            if (cloudMode) {
                cloudNotice = "حذف الأصناف السحابية يحتاج إيقاف العرض وحفظ أثر تدقيقي؛ لم يتم حذف أي بيانات."
            } else {
                inventory.removeAt(editingProductIndex)
                saveProducts(context, inventory)
            }
            editingProductIndex = -1
        }
    )
    if (addProduct) AddProductDialog(
        onDismiss = { addProduct = false },
        onSave = { name, category, stock, price, costPrice ->
            if (cloudMode) {
                cloudInventoryBusy = true
                cloudNotice = "جارٍ حفظ الصنف في قاعدة البيانات..."
                storeCloudIo.execute {
                    val result = runCatching {
                        createCommercialCloudInventoryItem(context, name, "قطعة", price.toLong(), stock)
                        loadCommercialCloudInventory(context)
                    }
                    storeCloudMain.post {
                        cloudInventoryBusy = false
                        result.onSuccess { snapshot ->
                            inventory.clear()
                            inventory.addAll(snapshot.products)
                            cloudInventoryError = ""
                            cloudNotice = "تم حفظ الصنف والمخزون في قاعدة البيانات."
                        }.onFailure {
                            cloudInventoryError = it.message ?: "تعذر حفظ الصنف في قاعدة البيانات."
                            cloudNotice = cloudInventoryError
                        }
                        addProduct = false
                    }
                }
            } else {
                inventory.add(Product(name, category, stock, money(price), costPrice))
                saveProducts(context, inventory)
                addProduct = false
            }
        }
    )
    if (newSale && inventory.isNotEmpty()) SaleEntryDialog(
        inventory = inventory,
        onDismiss = { newSale = false },
        onSave = { productIndex, product, quantity, credit, unitPrice, customer, baseQuantity, unitCode ->
            val currentProduct = inventory[productIndex]
            if (cloudMode) {
                if (credit) {
                    cloudNotice = "البيع الآجل غير مفعّل على الخادم بعد؛ لم تُسجّل أي فاتورة."
                    newSale = false
                } else if (currentProduct.cloudOfferingId.isBlank()) {
                    cloudNotice = "هذا الصنف غير مرتبط بمعرّف عرض في قاعدة البيانات؛ لم تُسجّل أي فاتورة."
                    newSale = false
                } else {
                    cloudInventoryBusy = true
                    cloudNotice = "جارٍ تسجيل البيع وتحديث المخزون في قاعدة البيانات..."
                    val idempotencyKey = UUID.randomUUID().toString()
                    storeCloudIo.execute {
                        val result = runCatching {
                            val sale = checkoutCommercialCloudSale(context, currentProduct.cloudOfferingId, baseQuantity, idempotencyKey)
                            val snapshot = loadCommercialCloudInventory(context)
                            sale to snapshot
                        }
                        storeCloudMain.post {
                            cloudInventoryBusy = false
                            result.onSuccess { pair ->
                                val sale = pair.first
                                inventory.clear()
                                inventory.addAll(pair.second.products)
                                val receipt = sale.optLong("receipt_number", nextInvoice.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                val total = sale.optLong("total_minor", (quantity * unitPrice).toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                                sales.add(0, SaleRecord(receipt, product, quantity, total, false, customer, total, System.currentTimeMillis(), currentProduct.costPrice, unitCode, baseQuantity))
                                saveSales(context, sales)
                                nextInvoice = maxOf(nextInvoice + 1, receipt + 1)
                                cloudInventoryError = ""
                                cloudNotice = "تم تسجيل الفاتورة رقم $receipt وتحديث المخزون في قاعدة البيانات."
                            }.onFailure {
                                cloudInventoryError = it.message ?: "فشل تسجيل البيع على الخادم."
                                cloudNotice = cloudInventoryError
                            }
                        }
                    }
                    newSale = false
                }
            } else {
                inventory[productIndex] = currentProduct.copy(stock = currentProduct.stock - baseQuantity)
                saveProducts(context, inventory)
                val soldUnitCost = if (quantity > 0) kotlin.math.round(currentProduct.costPrice.toDouble() * baseQuantity / quantity).toInt() else currentProduct.costPrice
                sales.add(0, SaleRecord(nextInvoice, product, quantity, quantity * unitPrice, credit, customer, 0, System.currentTimeMillis(), soldUnitCost, unitCode, baseQuantity))
                saveSales(context, sales)
                nextInvoice = maxOf(nextInvoice + 1, (sales.maxOfOrNull { it.number } ?: 1048) + 1)
                newSale = false
            }
        }
    )
    if (settlingSaleNumber >= 0) {
        val saleIndex = sales.indexOfFirst { it.number == settlingSaleNumber }
        if (saleIndex >= 0) SettlementDialog(
            sale = sales[saleIndex],
            onDismiss = { settlingSaleNumber = -1 },
            onSave = { amount ->
                val current = sales[saleIndex]
                sales[saleIndex] = current.copy(paid = (current.paid + amount).coerceAtMost(current.total))
                settlements.add(0, SettlementRecord(current.number, current.customer, amount))
                saveSales(context, sales)
                saveSettlements(context, settlements)
                settlingSaleNumber = -1
            }
        ) else settlingSaleNumber = -1
    }
    if (expenseDialog) ExpenseEntryDialog(onDismiss = { expenseDialog = false }, onSave = { category, amount, note ->
        expenses.add(0, ExpenseRecord(System.currentTimeMillis(), category, amount, note))
        saveExpenses(context, expenses)
        expenseDialog = false
    })
    if (cloudMode && cloudNotice.isNotBlank()) AlertDialog(
        onDismissRequest = { cloudNotice = "" },
        title = { Text("حالة قاعدة البيانات") },
        text = { Text(cloudNotice) },
        confirmButton = { TextButton(onClick = { cloudNotice = "" }) { Text("حسنًا") } }
    )
    if (cloudContextExists && cloudInventoryError.isNotBlank() && cloudNotice.isBlank()) AlertDialog(
        onDismissRequest = { cloudInventoryError = "" },
        title = { Text("تعذر الاتصال بالمخزون السحابي") },
        text = { Text(cloudInventoryError) },
        confirmButton = { TextButton(onClick = { cloudInventoryError = "" }) { Text("إغلاق") } }
    )
    if (cloudMode && cloudInventoryBusy) AlertDialog(
        onDismissRequest = { },
        title = { Text("جارٍ الاتصال بقاعدة البيانات") },
        text = { Column { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("يتم تنفيذ العملية على الخادم. لا تغلق التطبيق.") } },
        confirmButton = {}
    )
    if (newSale && inventory.isEmpty()) AlertDialog(
        onDismissRequest = { newSale = false },
        title = { Text("لا توجد منتجات") },
        text = { Text("أضف منتجًا إلى المخزون أولًا قبل تسجيل عملية بيع.") },
        confirmButton = { Button(onClick = { newSale = false; tab = 2; addProduct = true }) { Text("إضافة منتج") } },
        dismissButton = { TextButton(onClick = { newSale = false }) { Text("إغلاق") } }
    )
}

@Composable
private fun Dashboard(products: List<Product>, sales: List<SaleRecord>, onNewSale: () -> Unit, onInventory: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("أهلًا بك 👋", color = Color(0xFFBFD9D0), fontSize = 14.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("إليك ملخص المبيعات المسجلة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        Box(Modifier.size(46.dp).background(Color(0xFF31584F), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.TrendingUp, null, tint = Color(0xFF8DE0B8))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("إجمالي المبيعات المسجلة", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(sales.sumOf { it.total }), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 29.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("${sales.size} فاتورة محفوظة على هذا الجهاز", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                    Spacer(Modifier.height(17.dp))
                    Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(7.dp))
                        Text("تسجيل عملية بيع")
                    }
                }
            }
        }
        item { Heading("ملخص المبيعات", "إحصاءات الفواتير المحفوظة محليًا") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الفواتير", sales.size.toString(), "فاتورة محفوظة", Icons.Default.PointOfSale, Modifier.weight(1f))
                Metric("نقدي", money(sales.filter { !it.credit }.sumOf { it.total }), "إجمالي النقدي", Icons.Default.AttachMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الآجل", money(sales.filter { it.credit }.sumOf { it.total }), "إجمالي الآجل", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("المنتجات", products.size.toString(), "صنف مسجل", Icons.Default.Inventory2, Modifier.weight(1f))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Heading("تنبيهات المخزون", "أصناف تحتاج إلى إعادة طلب") }
                TextButton(onClick = onInventory) { Text("عرض الكل") }
            }
        }
        items(products.filter { it.stock <= 9 }.take(3)) { ProductRow(it) }
        item { Heading("آخر المبيعات", "أحدث الفواتير المسجلة") }
        items(sales.take(3)) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} ${sale.unitCode}", money(sale.total), sale.credit) }
        
    }
}

@Composable
private fun Metric(title: String, value: String, note: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(19.dp), border = BorderStroke(1.dp, Border)) {
        Column(Modifier.padding(14.dp)) {
            Box(Modifier.size(36.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Green) }
            Spacer(Modifier.height(10.dp))
            Text(title, color = Muted, fontSize = 12.sp)
            Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(note, color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(3.dp))
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SalesScreen(sales: List<SaleRecord>, onNewSale: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Heading("المبيعات والفواتير", "تابع عمليات البيع والتحصيل") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("إجمالي المبيعات", money(sales.sumOf { it.total }), "الفواتير المحفوظة", Icons.Default.AttachMoney, Modifier.weight(1f))
                Metric("عدد الفواتير", sales.size.toString(), "فاتورة", Icons.Default.PointOfSale, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("فاتورة بيع جديدة") } }
        item { Heading("أحدث الفواتير", "الفواتير المحفوظة على هذا الجهاز") }
        items(sales) { sale -> SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} ${sale.unitCode}", money(sale.total), sale.credit) }
        
    }
}

@Composable
private fun InventoryScreen(products: List<Product>, query: String, onQuery: (String) -> Unit, onAddProduct: () -> Unit, onEditProduct: (Int) -> Unit) {
    val filtered = products.filter { it.name.contains(query, true) || it.category.contains(query, true) || it.barcode.contains(query, true) || it.catalogCode.contains(query, true) || productMatchesUnitQuery(it, query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item { Heading("المخزون والمنتجات", "إدارة الأصناف والكميات والأسعار") }
        item {
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("ابحث عن منتج أو تصنيف") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("إجمالي الأصناف", products.size.toString(), "صنف", Icons.Default.Inventory2, Modifier.weight(1f))
                Metric("مخزون منخفض", products.count { it.stock <= 9 }.toString(), "أصناف", Icons.Default.WarningAmber, Modifier.weight(1f))
            }
        }
        item { Button(onClick = onAddProduct, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("إضافة منتج") } }
        item { Heading("قائمة المنتجات", "عدد النتائج: " + filtered.size) }
        items(filtered) { product -> ProductRow(product, onClick = { onEditProduct(products.indexOf(product)) }) }
        if (filtered.isEmpty()) item { Text("لا توجد منتجات تطابق البحث.", color = Muted) }
    }
}

@Composable
private fun ProductRow(product: Product, onClick: () -> Unit = {}) {
    val warning = product.stock <= 9
    val displayImage = product.imageUrl.ifBlank { runCatching { val units = JSONArray(product.unitVariantsJson); (0 until units.length()).map { units.getJSONObject(it) }.firstOrNull { it.optBoolean("isDefault") && it.optString("imageUrl").startsWith("https://", true) }?.optString("imageUrl").orEmpty() }.getOrDefault("") }
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (displayImage.startsWith("https://", true)) {
                AsyncImage(model=displayImage,contentDescription=product.name,modifier=Modifier.size(45.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop)
            } else {
                Box(Modifier.size(45.dp).background(if (warning) Color(0xFFFFF1DF) else Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                    Icon(if (warning) Icons.Default.WarningAmber else Icons.Default.Inventory2, null, tint = if (warning) Color(0xFFB96A14) else Green)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(product.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(product.category, color = Muted, fontSize = 12.sp)
                Text("المتوفر: ${product.stock} ${product.unitCode.ifBlank { "EA" }}", color = if (warning) Color(0xFFB96A14) else Muted, fontSize = 11.sp)
                if (product.barcode.isNotBlank() || product.catalogCode.isNotBlank()) Text(listOf(product.barcode, product.catalogCode).filter { it.isNotBlank() }.joinToString(" · "), color = Muted, fontSize = 10.sp)
                val unitCount = runCatching { JSONArray(product.unitVariantsJson).length() }.getOrDefault(0)
                if (unitCount > 1) Text("$unitCount وحدات/عبوات مرتبطة", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text(product.price, color = Green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SaleRow(title: String, subtitle: String, amount: String, credit: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.PointOfSale, null, tint = Green) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amount, color = Ink, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Surface(color = (if (credit) Orange else Green).copy(alpha = 0.12f), shape = RoundedCornerShape(7.dp)) {
                    Text(if (credit) "آجل" else "مكتمل", color = if (credit) Orange else Green, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaleEntryDialog(
    inventory: List<Product>,
    onDismiss: () -> Unit,
    onSave: (Int, String, Int, Boolean, Int, String, Int, String) -> Unit
) {
    if (inventory.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("لا توجد أصناف") },
            text = { Text("أضف صنفًا إلى المخزون أو استورد صنفًا من دليل الأصناف قبل إنشاء فاتورة.") },
            confirmButton = { Button(onClick = onDismiss) { Text("حسنًا") } }
        )
        return
    }
    var selected by remember { mutableIntStateOf(0) }
    var selectedUnitIndex by remember(selected) { mutableIntStateOf(-1) }
    var quantityText by remember { mutableStateOf("1") }
    var priceOverrideText by remember(selected, selectedUnitIndex) { mutableStateOf("") }
    var credit by remember { mutableStateOf(false) }
    var customer by remember { mutableStateOf("") }
    var unitExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val product = inventory[selected.coerceIn(0, inventory.lastIndex)]
    val saleUnits = localSaleUnits(product)
    val defaultUnitIndex = saleUnits.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
    val effectiveUnitIndex = selectedUnitIndex.takeIf { it in saleUnits.indices } ?: defaultUnitIndex
    val selectedUnit = saleUnits[effectiveUnitIndex]
    val basePrice = product.price.replace(",", "").replace(" ر.ي", "").toIntOrNull() ?: 0
    val suggestedUnitPrice = kotlin.math.round(basePrice.toDouble() * selectedUnit.quantityInBase).toInt()
    val quantity = quantityText.toIntOrNull()
    val unitPrice = priceOverrideText.toIntOrNull() ?: suggestedUnitPrice
    val baseQuantityExact = (quantity ?: 0) * selectedUnit.quantityInBase
    val baseQuantity = kotlin.math.round(baseQuantityExact).toInt()
    val quantityIsWholeBaseUnits = kotlin.math.abs(baseQuantityExact - baseQuantity) < 0.000001
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("فاتورة بيع جديدة", fontWeight = FontWeight.Bold, color = Ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختر المنتج", color = Muted, fontSize = 12.sp)
                inventory.forEachIndexed { index, item ->
                    Surface(
                        onClick = { selected = index; error = null },
                        color = if (selected == index) Mint else Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selected == index) Green else Border)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected == index, onClick = { selected = index; error = null })
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text(item.price, color = Green, fontSize = 12.sp)
                            }
                        }
                    }
                }
                ExposedDropdownMenuBox(expanded = unitExpanded, onExpandedChange = { unitExpanded = it }) {
                    OutlinedTextField(
                        value = "${selectedUnit.label} × ${selectedUnit.quantityInBase} ${product.unitCode} • ${selectedUnit.barcode.ifBlank { "بدون باركود" }}",
                        onValueChange = {}, readOnly = true, label = { Text("وحدة البيع / العبوة") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                        saleUnits.forEachIndexed { index, unit ->
                            DropdownMenuItem(
                                text = { Text("${unit.label} × ${unit.quantityInBase} ${product.unitCode}${if (unit.barcode.isNotBlank()) " • ${unit.barcode}" else ""}") },
                                onClick = { selectedUnitIndex = index; priceOverrideText = ""; unitExpanded = false; error = null }
                            )
                        }
                    }
                }
                if (selectedUnit.imageUrl.startsWith("https://", true)) {
                    AsyncImage(model=selectedUnit.imageUrl,contentDescription=selectedUnit.label,modifier=Modifier.fillMaxWidth().heightIn(max=130.dp),contentScale=ContentScale.Fit)
                }
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { quantityText = it.filter(Char::isDigit).take(4); error = null },
                    label = { Text("الكمية بوحدة البيع المختارة") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = priceOverrideText.ifBlank { suggestedUnitPrice.toString() },
                    onValueChange = { priceOverrideText = it.filter(Char::isDigit).take(9); error = null },
                    label = { Text("سعر الوحدة/العبوة (اقتراح نسبي، عدّله حسب سعرك)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (credit) OutlinedTextField(value = customer, onValueChange = { customer = it; error = null }, label = { Text("اسم العميل المدين") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("طريقة الدفع", color = Muted, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !credit, onClick = { credit = false })
                    Text("نقدي", color = Ink)
                    Spacer(Modifier.width(12.dp))
                    RadioButton(selected = credit, onClick = { credit = true })
                    Text("آجل", color = Ink)
                }
                HorizontalDivider(color = Border)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("الإجمالي", color = Muted)
                    Text(if (quantity != null && quantity > 0) money(quantity * unitPrice) else "—", color = Green, fontWeight = FontWeight.Bold)
                }
                if (credit) Text("سيُسجّل المبلغ كبيع آجل (تجريبي).", color = Orange, fontSize = 12.sp)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (quantity == null || quantity <= 0) {
                    error = "أدخل كمية صحيحة أكبر من صفر."
                } else if (!quantityIsWholeBaseUnits) {
                    error = "تحويل هذه العبوة ينتج كمية كسرية، والمخزون المحلي الحالي يدعم الكميات الصحيحة فقط."
                } else if (baseQuantity > product.stock) {
                    error = "الكمية المطلوبة تعادل $baseQuantity من وحدة الأساس، والمتاح ${product.stock}."
                } else if (unitPrice <= 0) {
                    error = "أدخل سعر بيع أكبر من صفر."
                } else if (credit && customer.isBlank()) {
                    error = "أدخل اسم العميل للبيع الآجل."
                } else {
                    onSave(selected, product.name, quantity, credit, unitPrice, if (credit) customer.trim() else "", baseQuantity, selectedUnit.code)
                }
            }) { Text("حفظ الفاتورة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun EditProductDialog(
    product: Product,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(product) { mutableStateOf(product.name) }
    var category by remember(product) { mutableStateOf(product.category) }
    var stockText by remember(product) { mutableStateOf(product.stock.toString()) }
    var priceText by remember(product) { mutableStateOf(product.price.replace(",", "").replace(" ر.ي", "")) }
    var costText by remember(product) { mutableStateOf(if (product.costPrice > 0) product.costPrice.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember(product) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل المنتج", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("تاريخ الإدخال: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(product.createdAt))} • الموظف/المستخدم: ${product.createdBy.ifBlank { "محلي / غير محدد" }}", color = Muted, fontSize = 11.sp)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("اسم المنتج") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it; error = null }, label = { Text("التصنيف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stockText, { stockText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("الكمية الحالية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priceText, { priceText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("سعر البيع للوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(costText, { costText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("تكلفة شراء الوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val stock = stockText.toIntOrNull()
                val price = priceText.toIntOrNull()
                val cost = costText.toIntOrNull()
                when {
                    name.isBlank() -> error = "أدخل اسم المنتج."
                    category.isBlank() -> error = "أدخل تصنيف المنتج."
                    stock == null || stock < 0 -> error = "أدخل كمية صحيحة."
                    price == null || price <= 0 -> error = "أدخل سعر بيع أكبر من صفر."
                    cost == null || cost < 0 -> error = "أدخل تكلفة شراء صحيحة (يمكن أن تكون صفرًا)."
                    else -> onSave(name.trim(), category.trim(), stock, price, cost)
                }
            }) { Text("حفظ التعديلات") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("إلغاء") }
            }
        }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("تأكيد حذف المنتج") },
        text = { Text("هل تريد حذف «${product.name}» من المخزون؟ لا يمكن التراجع عن هذا الإجراء.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; onDelete() }) {
                Text("حذف المنتج", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
    )
}

@Composable
private fun AddProductDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var stockText by remember { mutableStateOf("0") }
    var priceText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة منتج جديد", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("اسم المنتج") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(category, { category = it; error = null }, label = { Text("التصنيف") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(stockText, { stockText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("الكمية الافتتاحية") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priceText, { priceText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("سعر البيع للوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(costText, { costText = it.filter(Char::isDigit).take(7); error = null }, label = { Text("تكلفة شراء الوحدة (ر.ي)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val stock = stockText.toIntOrNull()
                val price = priceText.toIntOrNull()
                val cost = costText.toIntOrNull()
                when {
                    name.isBlank() -> error = "أدخل اسم المنتج."
                    category.isBlank() -> error = "أدخل تصنيف المنتج."
                    stock == null || stock < 0 -> error = "أدخل كمية صحيحة."
                    price == null || price <= 0 -> error = "أدخل سعر بيع أكبر من صفر."
                    cost == null || cost < 0 -> error = "أدخل تكلفة شراء صحيحة (يمكن أن تكون صفرًا)."
                    else -> onSave(name.trim(), category.trim(), stock, price, cost)
                }
            }) { Text("حفظ المنتج") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun MoreScreen(onCustomers: () -> Unit, onReports: () -> Unit, onExpenses: () -> Unit, onActivityProfile: () -> Unit, onCatalog: () -> Unit) {
    val options = listOf("دليل الأصناف العالمي", "العملاء والمديونيات", "الموردون والمشتريات", "التقارير والأرباح", "المصروفات", "إعدادات المتجر", "النسخ الاحتياطي")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Heading("إدارة المتجر", "الأدوات والإعدادات") }
        items(options) { label ->
            Card(onClick = { when (label) { "العملاء والمديونيات" -> onCustomers(); "التقارير والأرباح" -> onReports(); "المصروفات" -> onExpenses(); "إعدادات المتجر" -> onActivityProfile(); "دليل الأصناف العالمي" -> onCatalog() } }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Settings, null, tint = Green) }
                    Spacer(Modifier.width(12.dp))
                    Text(label, modifier = Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.ChevronLeft, null, tint = Muted)
                }
            }
        }
        item { Text("نسخة واجهات أولية • البيانات المعروضة تجريبية", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)) }
    }
}

@Composable
private fun ExpensesScreen(expenses: List<ExpenseRecord>, onBack: () -> Unit, onAdd: () -> Unit) {
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
    val start = calendar.timeInMillis
    val today = expenses.filter { it.timestamp >= start && it.timestamp < start + 86400000L }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Heading("المصروفات", "تسجيل ومتابعة مصروفات المتجر")
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي مصروفات اليوم", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(today.sumOf { it.amount }), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("${today.size} مصروف مسجل", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                }
            }
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("تسجيل مصروف") } }
        item { Heading("سجل المصروفات", "الأحدث أولًا") }
        if (expenses.isEmpty()) item { Text("لا توجد مصروفات مسجلة بعد.", color = Muted) }
        items(expenses.sortedByDescending { it.timestamp }, key = { it.id }) { expense ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(expense.category, color = Ink, fontWeight = FontWeight.SemiBold)
                    if (expense.note.isNotBlank()) Text(expense.note, color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(money(expense.amount), color = Orange, fontWeight = FontWeight.Bold)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(expense.timestamp)), color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpenseEntryDialog(onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    val categories = listOf("إيجار", "كهرباء ومياه", "نقل ومواصلات", "رواتب", "مشتريات تشغيلية", "صيانة", "أخرى")
    var category by remember { mutableStateOf(categories.first()) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل مصروف جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = category, onValueChange = {}, readOnly = true, label = { Text("تصنيف المصروف") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { category = option; expanded = false }) }
                    }
                }
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(9); error = null },
                    label = { Text("المبلغ بالريال اليمني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it.take(120) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = {
            val amount = amountText.toIntOrNull()
            if (amount == null || amount <= 0) error = "أدخل مبلغًا أكبر من صفر."
            else onSave(category, amount, note.trim())
        }) { Text("حفظ المصروف") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun ReportsScreen(sales: List<SaleRecord>, settlements: List<SettlementRecord>, expenses: List<ExpenseRecord>, onBack: () -> Unit) {
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    val dayStart = calendar.timeInMillis
    val dayEnd = dayStart + 24L * 60L * 60L * 1000L
    val todaySales = sales.filter { it.createdAt >= dayStart && it.createdAt < dayEnd }
    val todaySettlements = settlements.filter { it.timestamp >= dayStart && it.timestamp < dayEnd }
    val todayExpenses = expenses.filter { it.timestamp >= dayStart && it.timestamp < dayEnd }
    val totalExpenses = todayExpenses.sumOf { it.amount }
    val costOfGoods = todaySales.sumOf { it.quantity * it.unitCost }
    val grossProfit = todaySales.sumOf { it.total } - costOfGoods
    val netOperatingResult = grossProfit - totalExpenses
    val cashSales = todaySales.filter { !it.credit }.sumOf { it.total }
    val creditSales = todaySales.filter { it.credit }.sumOf { it.total }
    val collections = cashSales + todaySettlements.sumOf { it.amount }
    val netCashFlow = collections - totalExpenses
    val outstanding = sales.filter { it.credit }.sumOf { (it.total - it.paid).coerceAtLeast(0) }
    val dateLabel = java.text.DateFormat.getDateInstance(java.text.DateFormat.FULL, Locale("ar")).format(java.util.Date(dayStart))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Heading("التقارير اليومية", dateLabel)
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("إجمالي التحصيل اليوم", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(collections), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("يشمل المبيعات النقدية ودفعات تسديد الديون اليوم", color = Color(0xFF8DE0B8), fontSize = 11.sp)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("مبيعات اليوم", money(todaySales.sumOf { it.total }), "${todaySales.size} فاتورة", Icons.Default.PointOfSale, Modifier.weight(1f))
                Metric("المبيعات النقدية", money(cashSales), "مدفوعة عند البيع", Icons.Default.AttachMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("المبيعات الآجلة", money(creditSales), "فواتير اليوم الآجلة", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("تحصيل الديون", money(todaySettlements.sumOf { it.amount }), "${todaySettlements.size} دفعة اليوم", Icons.Default.Payments, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("مصروفات اليوم", money(totalExpenses), "${todayExpenses.size} مصروف", Icons.Default.ReceiptLong, Modifier.weight(1f))
                Metric("تكلفة البضاعة", money(costOfGoods), "تكلفة وحدات اليوم المباعة", Icons.Default.Inventory2, Modifier.weight(1f))
            }
        }
        if (todaySales.any { it.unitCost == 0 }) {
            item {
                Text(
                    "تنبيه: توجد ${todaySales.count { it.unitCost == 0 }} فاتورة/فواتير اليوم بتكلفة شراء صفرية أو غير مسجلة؛ قد يكون تقدير الربح أعلى من الواقع. أدخل تكلفة الشراء الصحيحة لكل منتج.",
                    color = Muted,
                    fontSize = 11.sp
                )
            }
        } else {
            item { Text("تقدير الربح يعتمد على تكاليف الشراء المسجلة، ولا يشمل الضرائب أو التكاليف الأخرى.", color = Muted, fontSize = 11.sp) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الربح الإجمالي التقديري", money(grossProfit), "المبيعات ناقص تكلفة البضاعة", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("النتيجة بعد المصروفات", money(netOperatingResult), "قبل أي تكاليف أخرى أو ضرائب", Icons.Default.AccountBalanceWallet, Modifier.weight(1f))
            }
        }
        item {
            Metric(
                "صافي حركة النقد",
                money(netCashFlow),
                "التحصيلات ناقص المصروفات؛ ليس صافي الربح",
                Icons.Default.AccountBalanceWallet,
                Modifier.fillMaxWidth()
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("إجمالي الديون المتبقية", color = Muted, fontSize = 12.sp)
                    Text(money(outstanding), color = Orange, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("هذا الرصيد يشمل جميع الفواتير الآجلة غير المسددة بالكامل.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Heading("فواتير اليوم", "المبيعات المسجلة خلال اليوم الحالي") }
        if (todaySales.isEmpty()) item { Text("لا توجد فواتير مسجلة اليوم.", color = Muted) }
        items(todaySales, key = { "daily-sale-${it.number}" }) { sale ->
            SaleRow("فاتورة #${sale.number}", "${sale.product} · ${sale.quantity} قطعة", money(sale.total), sale.credit)
        }
        item { Heading("مصروفات اليوم", "المصروفات المسجلة خلال اليوم الحالي") }
        if (todayExpenses.isEmpty()) item { Text("لا توجد مصروفات مسجلة اليوم.", color = Muted) }
        items(todayExpenses, key = { "daily-expense-${it.id}" }) { expense ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(expense.category, color = Ink, fontWeight = FontWeight.SemiBold)
                    if (expense.note.isNotBlank()) Text(expense.note, color = Muted, fontSize = 11.sp)
                    Text(money(expense.amount), color = Orange, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Heading("دفعات التحصيل اليوم", "التسديدات التي تم تسجيلها اليوم") }
        if (todaySettlements.isEmpty()) item { Text("لا توجد دفعات تحصيل مسجلة اليوم.", color = Muted) }
        items(todaySettlements, key = { "daily-payment-${it.invoiceNumber}-${it.timestamp}" }) { payment ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(payment.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("فاتورة #${payment.invoiceNumber}", color = Muted, fontSize = 11.sp)
                    }
                    Text(money(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun DebtScreen(sales: List<SaleRecord>, settlements: List<SettlementRecord>, onBack: () -> Unit, onSettle: (SaleRecord) -> Unit) {
    var customerQuery by remember { mutableStateOf("") }
    val allDebts = sales.filter { it.credit && it.total > it.paid }
    val debts = allDebts.filter {
        customerQuery.isBlank() ||
            it.customer.contains(customerQuery.trim(), ignoreCase = true) ||
            it.product.contains(customerQuery.trim(), ignoreCase = true) ||
            it.number.toString().contains(customerQuery.trim())
    }
    val totalOutstanding = allDebts.sumOf { (it.total - it.paid).coerceAtLeast(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") }
                Heading("العملاء والمديونيات", "الأرصدة المتبقية من المبيعات الآجلة")
            }
        }
        item {
            OutlinedTextField(
                value = customerQuery,
                onValueChange = { customerQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ابحث باسم العميل أو المنتج أو رقم الفاتورة") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي الديون المتبقية", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(totalOutstanding), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("${allDebts.size} فاتورة غير مسددة بالكامل", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                }
            }
        }
        item { Heading("ملخص العملاء", "إجمالي المستحق لكل عميل") }
        val customerBalances = debts.groupBy { it.customer.trim().ifBlank { "عميل غير محدد" } }
            .mapValues { (_, invoices) -> invoices.sumOf { (it.total - it.paid).coerceAtLeast(0) } }
            .toList().sortedByDescending { it.second }
        items(customerBalances, key = { it.first }) { (customerName, balance) ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(customerName, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("الرصيد المستحق", color = Muted, fontSize = 11.sp)
                    }
                    Text(money(balance), color = Orange, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (debts.isEmpty()) item { Text("لا توجد ديون مستحقة حاليًا.", color = Muted) }
        items(debts, key = { it.number }) { sale ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(sale.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("فاتورة #${sale.number} • ${sale.product} × ${sale.quantity} ${sale.unitCode}", color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("قيمة الفاتورة: ${money(sale.total)}", color = Muted, fontSize = 12.sp)
                        Text("المسدّد: ${money(sale.paid)}", color = Green, fontSize = 12.sp)
                    }
                    Text("المتبقي: ${money((sale.total - sale.paid).coerceAtLeast(0))}", color = Orange, fontWeight = FontWeight.Bold)
                    Button(onClick = { onSettle(sale) }, modifier = Modifier.fillMaxWidth()) { Text("تسجيل تسديد") }
                }
            }
        }
        item { Heading("سجل التسديدات", "أحدث الدفعات المسجلة") }
        if (settlements.isEmpty()) item { Text("لم تُسجّل دفعات تسديد بعد.", color = Muted, fontSize = 12.sp) }
        items(settlements.take(30), key = { "${it.invoiceNumber}-${it.timestamp}" }) { payment ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(payment.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("فاتورة #${payment.invoiceNumber}", color = Muted, fontSize = 11.sp)
                    Text("التسديد: ${money(payment.amount)}", color = Green, fontWeight = FontWeight.Bold)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(payment.timestamp)), color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SettlementDialog(sale: SaleRecord, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var amountText by remember(sale.number, sale.paid) { mutableStateOf((sale.total - sale.paid).coerceAtLeast(0).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining = (sale.total - sale.paid).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسديد دين العميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${sale.customer.ifBlank { "عميل غير محدد" }} • فاتورة #${sale.number}", color = Muted)
                Text("المتبقي: ${money(remaining)}", color = Green, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(9); error = null }, label = { Text("مبلغ التسديد بالريال اليمني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toIntOrNull()
                when {
                    amount == null || amount <= 0 -> error = "أدخل مبلغًا أكبر من صفر."
                    amount > remaining -> error = "مبلغ التسديد أكبر من الرصيد المتبقي."
                    else -> onSave(amount)
                }
            }) { Text("حفظ التسديد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
