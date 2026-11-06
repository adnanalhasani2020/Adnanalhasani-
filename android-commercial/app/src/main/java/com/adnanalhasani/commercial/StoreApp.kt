package com.adnanalhasani.commercial

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
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
import androidx.compose.material.icons.filled.ReceiptLong
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StoreApp(context: Context) {
    var showEntryFlow by remember { mutableStateOf(true) }
    var guideDismissed by remember {
        mutableStateOf(context.getSharedPreferences("commercial_ui_guides_v1", Context.MODE_PRIVATE).getBoolean("dismissed", false))
    }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var newSale by remember { mutableStateOf(false) }
    var addProduct by remember { mutableStateOf(false) }
    var editingProductIndex by remember { mutableIntStateOf(-1) }
    var showDebts by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showReportsCenter by remember { mutableStateOf(false) }
    var showExpenses by remember { mutableStateOf(false) }
    var showActivityProfile by remember { mutableStateOf(false) }
    var showPersonalAccounts by remember { mutableStateOf(false) }
    var personalWorkNotesPage by remember { mutableStateOf(false) }
    var commercialAdsPage by remember { mutableStateOf(false) }
    var appLockSettingsPage by remember { mutableStateOf(false) }
    var barcodeScannerPage by remember { mutableStateOf(false) }
    var showNotifications by remember { mutableStateOf(false) }
    var showCatalog by remember { mutableStateOf(false) }
    var pendingCatalogProduct by remember { mutableStateOf<CatalogProduct?>(null) }
    var pendingCatalogBranchId by remember { mutableStateOf("") }
    var pendingCatalogWarehouseId by remember { mutableStateOf("") }
    var pendingCatalogActivityCode by remember { mutableStateOf("") }
    var pendingCatalogBaseUnitCode by remember { mutableStateOf("") }
    var pendingCatalogSaleUnitCode by remember { mutableStateOf("") }
    var pendingCatalogIdempotencyKey by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var showMasterData by remember { mutableStateOf(false) }
    var cloudOperationalPage by remember { mutableStateOf("") }
    var cloudSalesPage by remember { mutableStateOf(false) }
    var cloudSaleComposerPage by remember { mutableStateOf(false) }
    var cloudPurchasesPage by remember { mutableStateOf(false) }
    var cloudStockAlertsPage by remember { mutableStateOf(false) }
    var cloudUnitsPage by remember { mutableStateOf(false) }
    var customerPortalPage by remember { mutableStateOf(false) }
    var customerPortalHistoryPage by remember { mutableStateOf(false) }
    var customerPaymentRequestsPage by remember { mutableStateOf(false) }
    var customerPaymentOrganizationId by remember { mutableStateOf("") }
    var customerPaymentOrganizationName by remember { mutableStateOf("") }
    var customerOrderFulfillmentPage by remember { mutableStateOf(false) }
    var customerSupplyRequestsPage by remember { mutableStateOf(false) }
    var commercialCrmPage by remember { mutableStateOf(false) }
    var commercialCrmOpportunitiesPage by remember { mutableStateOf(false) }
    var commercialFixedAssetsPage by remember { mutableStateOf(false) }
    var commercialAssetMaintenancePage by remember { mutableStateOf(false) }
    var merchantPurchaseRequestPage by remember { mutableStateOf(false) }
    var settlingSaleNumber by remember { mutableIntStateOf(-1) }
    var cloudContextRevision by remember { mutableIntStateOf(0) }
    var cloudReloadToken by remember { mutableIntStateOf(0) }
    val cloudContextSnapshot = remember(cloudContextRevision) {
        val saved = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
        Triple(
            saved.getString("organization_id", null).orEmpty(),
            saved.getString("activity_id", null).orEmpty(),
            saved.getString("location_id", null).orEmpty()
        )
    }
    val cloudContextExists = cloudContextSnapshot.first.isNotBlank() &&
        cloudContextSnapshot.second.isNotBlank() && cloudContextSnapshot.third.isNotBlank()
    val cloudContextHasAny = cloudContextSnapshot.first.isNotBlank() ||
        cloudContextSnapshot.second.isNotBlank() || cloudContextSnapshot.third.isNotBlank()
    val sales = remember { mutableStateListOf<SaleRecord>().apply { if (!cloudContextHasAny) addAll(LocalStoreRepository.loadSales(context)) } }
    val settlements = remember { mutableStateListOf<SettlementRecord>().apply { if (!cloudContextHasAny) addAll(LocalStoreRepository.loadSettlements(context)) } }
    val expenses = remember { mutableStateListOf<ExpenseRecord>().apply { if (!cloudContextHasAny) addAll(LocalStoreRepository.loadExpenses(context)) } }
    var expenseDialog by remember { mutableStateOf(false) }
    val inventory = remember { mutableStateListOf<Product>().apply { if (!cloudContextHasAny) addAll(LocalStoreRepository.loadProducts(context)) } }
    var cloudInventoryLoaded by remember { mutableStateOf(!cloudContextHasAny) }
    var cloudInventoryBusy by remember { mutableStateOf(false) }
    var cloudInventoryError by remember { mutableStateOf("") }
    var cloudNotice by remember { mutableStateOf("") }
    var cloudCategories by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var cloudUnits by remember { mutableStateOf<List<CommercialUnitOption>>(emptyList()) }
    var cloudMode by remember { mutableStateOf(cloudContextHasAny) }
    var nextInvoice by remember { mutableIntStateOf((sales.maxOfOrNull { it.number } ?: 1048) + 1) }

    LaunchedEffect(cloudContextSnapshot, cloudReloadToken, showEntryFlow) {
        if (showEntryFlow) {
            cloudInventoryBusy = false
            return@LaunchedEffect
        }
        // Once an organization context exists, fail closed into cloud mode before any
        // network request. A failed cloud read must never expose local-only workflows.
        cloudMode = cloudContextHasAny
        if (!cloudContextHasAny) {
            cloudInventoryBusy = false
            cloudInventoryLoaded = true
            return@LaunchedEffect
        }
        inventory.clear()
        sales.clear()
        settlements.clear()
        expenses.clear()
        cloudInventoryError = ""
        if (!cloudContextExists) {
            cloudInventoryBusy = false
            cloudInventoryLoaded = true
            cloudInventoryError = "بيانات المؤسسة السحابية غير مكتملة؛ تحقق من معرّف المؤسسة والنشاط والفرع قبل المتابعة. لم يتم استخدام البيانات المحلية."
            return@LaunchedEffect
        }
        cloudNotice = ""
        cloudInventoryBusy = true
        storeCloudIo.execute {
            val inventoryResult = runCatching { loadCommercialCloudInventory(context) }
            val salesResult = runCatching { loadCommercialCloudSales(context) }
            val categoriesResult = runCatching { loadCommercialMasterData(context, "commercial_product_categories") }
            val unitsResult = runCatching { loadCommercialUnitsOfMeasure(context) }
            storeCloudMain.post {
                cloudInventoryBusy = false
                cloudInventoryLoaded = true
                inventoryResult.onSuccess { snapshot ->
                    inventory.clear()
                    inventory.addAll(snapshot.products)
                    cloudMode = true
                    cloudInventoryError = ""
                }.onFailure {
                    inventory.clear()
                    cloudInventoryError = it.message ?: "تعذر تحميل المخزون من قاعدة البيانات."
                    if (cloudInventoryError.contains("سجّل الدخول") || cloudInventoryError.contains("انتهت جلسة")) {
                        showEntryFlow = true
                    }
                }
                categoriesResult.onSuccess { cloudCategories = it }
                    .onFailure { cloudNotice = "تعذر تحميل تصنيفات الأصناف: " + (it.message ?: "خطأ غير معروف") }
                unitsResult.onSuccess { cloudUnits = it }
                    .onFailure { cloudNotice = "تعذر تحميل دليل وحدات القياس: " + (it.message ?: "خطأ غير معروف") }
                salesResult.onSuccess { serverSales ->
                    sales.clear()
                    sales.addAll(serverSales)
                    nextInvoice = maxOf(nextInvoice, (serverSales.maxOfOrNull { it.number } ?: 0) + 1)
                }.onFailure {
                    sales.clear()
                    cloudNotice = "تعذر تحميل سجل المبيعات من الخادم؛ أخفينا البيانات المحلية حتى لا تختلط بسجلات المؤسسة: " + (it.message ?: "خطأ غير معروف")
                    if (cloudNotice.contains("سجّل الدخول") || cloudNotice.contains("انتهت جلسة")) {
                        showEntryFlow = true
                    }
                }
            }
        }
    }
    if (showEntryFlow) {
        ActivityProfileScreen(
            onBack = {},
            onCatalog = { showEntryFlow = false; cloudContextRevision++; showCatalog = true },
            showBackButton = false,
            onProfileCreated = {
                showEntryFlow = false
                cloudContextRevision++
                tab = 0
            },
            onAuthenticated = {
                if (hasSavedCommercialCloudContext(context)) {
                    showEntryFlow = false
                    cloudContextRevision++
                    tab = 0
                }
            }
        )
    } else {
    Scaffold(
        containerColor = Canvas,
        topBar = {
            Column {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Mint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Storefront, null, tint = Green)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("متجري", fontWeight = FontWeight.Bold, color = Ink)
                            Text(if (cloudMode) when { cloudInventoryBusy || !cloudInventoryLoaded -> "جارٍ التحقق من الاتصال بقاعدة البيانات..."; cloudInventoryError.isNotBlank() -> "تعذر تحميل المخزون السحابي"; else -> "تم تحميل بيانات المؤسسة السحابية" } else "إدارة متجرك ببساطة", fontSize = 11.sp, color = Muted)
                        }
                    }
                },
                actions = { IconButton(onClick = { showNotifications = true }) { Icon(Icons.Default.NotificationsNone, "الإشعارات", tint = Ink) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
            )
            if (!guideDismissed) {
                val hint = when (tab) {
                    0 -> "ابدأ من هنا: أضف أصناف متجرك أولًا، ثم سجّل أول عملية بيع."
                    1 -> "في المبيعات أنشئ الفاتورة، راجع الأصناف والكميات، ثم أكّد الحفظ."
                    2 -> "في المخزون أضف الأصناف وحدد وحداتها وأسعارها وكمياتها الافتتاحية."
                    else -> "من «المزيد» تصل إلى العملاء والمشتريات والمصروفات والتقارير وإعدادات النشاط."
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Mint,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null, tint = Green)
                        Spacer(Modifier.width(8.dp))
                        Text(hint, modifier = Modifier.weight(1f), color = Ink, fontSize = 12.sp)
                        IconButton(onClick = {
                            guideDismissed = true
                            context.getSharedPreferences("commercial_ui_guides_v1", Context.MODE_PRIVATE)
                                .edit().putBoolean("dismissed", true).apply()
                        }) { Icon(Icons.Default.Close, contentDescription = "إخفاء التلميحات", tint = Muted) }
                    }
                }
            }
            }
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
            if (tab == 1) FloatingActionButton(onClick = { if (cloudMode) cloudSaleComposerPage = true else newSale = true }, containerColor = Green, contentColor = Color.White) {
                Icon(Icons.Default.Add, "فاتورة جديدة")
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showNotifications) {
                CommercialNotificationsScreen(context = context, onBack = { showNotifications = false })
            } else if (personalWorkNotesPage) {
                PersonalWorkNotesScreen(context = context, onBack = { personalWorkNotesPage = false })
            } else if (appLockSettingsPage) {
                CommercialAppLockSettingsScreen(context = context, onBack = { appLockSettingsPage = false })
            } else if (commercialAdsPage) {
                CommercialAdsScreen(context = context, onBack = { commercialAdsPage = false })
            } else if (barcodeScannerPage) {
                BarcodeScannerScreen(onBack = { barcodeScannerPage = false }, onBarcodeFound = { code -> query = code; barcodeScannerPage = false })
            } else if (customerPaymentRequestsPage) {
                CustomerPortalPaymentRequestsScreen(
                    context = context,
                    organizationId = customerPaymentOrganizationId,
                    organizationName = customerPaymentOrganizationName,
                    onBack = { customerPaymentRequestsPage = false }
                )
            } else if (merchantPurchaseRequestPage) {
                MerchantPurchaseRequestScreen(context = context, onBack = { merchantPurchaseRequestPage = false })
            } else if (customerSupplyRequestsPage) {
                CustomerSupplyRequestsScreen(context = context, onBack = { customerSupplyRequestsPage = false })
            } else if (customerOrderFulfillmentPage) {
                CustomerOrderFulfillmentScreen(context = context, onBack = { customerOrderFulfillmentPage = false })
            } else if (customerPortalHistoryPage) {
                CustomerPortalHistoryScreen(context = context, onBack = { customerPortalHistoryPage = false })
            } else if (customerPortalPage) {
                CustomerPortalScreen(
                    context = context,
                    onBack = { customerPortalPage = false },
                    onOrders = { customerPortalHistoryPage = true },
                    onPaymentRequests = { organization ->
                        customerPaymentOrganizationId = organization.id
                        customerPaymentOrganizationName = organization.name
                        customerPaymentRequestsPage = true
                    }
                )
            } else if (cloudSaleComposerPage) {
                CommercialCloudSaleComposerScreen(
                    context = context,
                    onBack = { cloudSaleComposerPage = false },
                    onCompleted = {
                        storeCloudIo.execute {
                            val snapshot = runCatching { loadCommercialCloudInventory(context) }.getOrNull()
                            val history = runCatching { loadCommercialCloudSales(context) }.getOrNull()
                            storeCloudMain.post {
                                snapshot?.let { inventory.clear(); inventory.addAll(it.products) }
                                history?.let { sales.clear(); sales.addAll(it) }
                            }
                        }
                    }
                )
            } else when (tab) {
                0 -> Dashboard(products = inventory, sales = sales, cloudMode = cloudMode, context = context, onNewSale = { if (cloudMode) cloudSaleComposerPage = true else newSale = true }, onInventory = { tab = 2 }, onOpenAds = { commercialAdsPage = true })
                1 -> SalesScreen(
                    sales = sales,
                    cloudMode = cloudMode,
                    onNewSale = { if (cloudMode) cloudSaleComposerPage = true else newSale = true },
                    onCloudInvoices = { cloudSalesPage = true }
                )
                2 -> InventoryScreen(inventory, query, { query = it }, {
                    addProduct = true
                    if (cloudMode) storeCloudIo.execute {
                        val categoriesResult = runCatching { loadCommercialMasterData(context, "commercial_product_categories") }
                        val unitsResult = runCatching { loadCommercialUnitsOfMeasure(context) }
                        storeCloudMain.post {
                            categoriesResult.onSuccess { cloudCategories = it }
                                .onFailure { cloudInventoryError = it.message ?: "تعذر تحديث تصنيفات الأصناف." }
                            unitsResult.onSuccess { cloudUnits = it }
                                .onFailure { cloudInventoryError = it.message ?: "تعذر تحديث دليل وحدات القياس." }
                        }
                    }
                }, { index ->
                    if (cloudMode) cloudNotice = "تعديل الصنف غير متاح حتى يكتمل ربط تحديث السعر وحركة المخزون على الخادم."
                    else editingProductIndex = index
                }, { barcodeScannerPage = true })
                else -> when {
                    commercialCrmPage -> CommercialCrmScreen(context = context, onBack = { commercialCrmPage = false })
                    commercialCrmOpportunitiesPage -> CommercialCrmOpportunitiesScreen(context = context, onBack = { commercialCrmOpportunitiesPage = false })
                    commercialFixedAssetsPage -> CommercialFixedAssetsScreen(context = context, onBack = { commercialFixedAssetsPage = false })
                    commercialAssetMaintenancePage -> CommercialFixedAssetMaintenanceScreen(context = context, onBack = { commercialAssetMaintenancePage = false })
                    cloudSalesPage -> CommercialCloudSalesScreen(context = context, onBack = { cloudSalesPage = false })
                    cloudPurchasesPage -> CommercialCloudPurchasesScreen(context = context, onBack = { cloudPurchasesPage = false })
                    cloudStockAlertsPage -> CommercialCloudStockAlertsScreen(context = context, onBack = { cloudStockAlertsPage = false })
                    cloudUnitsPage -> CommercialUnitDirectoryScreen(context = context, onBack = { cloudUnitsPage = false })
                    cloudOperationalPage == "returns" -> CommercialCloudReturnsScreen(context = context, onBack = { cloudOperationalPage = "" })
                    cloudOperationalPage.isNotBlank() -> CommercialCloudOperationalScreen(context = context, screenKey = cloudOperationalPage, onBack = { cloudOperationalPage = "" })
                    showMasterData -> MasterDataScreen(context = context, onBack = { showMasterData = false })
                    showCatalog -> CatalogScreen(
                        onBack = { showCatalog = false },
                        initialOrganizationId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE).getString("organization_id", "").orEmpty(),
                        initialActivityCode = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE).getString("activity_code", "").orEmpty(),
                        requireCloudInventoryContext = cloudMode,
                        onAddToInventory = { catalogItem, branchId, warehouseId, activityNodeCode, baseUnitCode, saleUnitCode ->
                        if (cloudMode) {
                            storeCloudIo.execute {
                                val lookup = runCatching { findCommercialCloudCatalogOffering(context,catalogItem.id,branchId) }
                                storeCloudMain.post {
                                    lookup.onSuccess { existingOffering ->
                                        if (!existingOffering.isNullOrBlank()) {
                                            cloudNotice = "هذا الصنف مرتبط بالفعل بالمخزون في الفرع المحدد (عرض: $existingOffering). لم يتم إنشاء نسخة جديدة."
                                            showCatalog = false
                                        } else {
                                            pendingCatalogProduct = catalogItem
                                            pendingCatalogBranchId = branchId
                                            pendingCatalogWarehouseId = warehouseId
                                            pendingCatalogActivityCode = activityNodeCode
                                            pendingCatalogBaseUnitCode = baseUnitCode
                                            pendingCatalogSaleUnitCode = saleUnitCode
                                            pendingCatalogIdempotencyKey = UUID.randomUUID().toString()
                                            addProduct = true
                                            showCatalog = false
                                        }
                                    }.onFailure {
                                        cloudNotice = "تعذر التحقق من وجود ربط سابق؛ لم يتم فتح الإنشاء لتجنب التكرار. ${it.message.orEmpty()}"
                                    }
                                }
                            }
                        } else {
                            val existing = inventory.indexOfFirst { it.catalogCode.isNotBlank() && it.catalogCode == catalogItem.catalogCode }
                            val mapped = Product(name = catalogItem.name, category = catalogItem.category, stock = 0, price = "0 ر.ي", catalogCode = catalogItem.catalogCode, barcode = catalogItem.barcode.ifBlank { catalogItem.availableUnits.firstOrNull { it.isDefault && it.barcode.isNotBlank() }?.barcode.orEmpty() }, unitCode = catalogItem.unitCode, imageUrl = catalogItem.imageUrl, description = catalogItem.description, brand = catalogItem.brand, manufacturer = catalogItem.manufacturer, countryOfManufacture = catalogItem.countryOfManufacture, createdBy = catalogItem.importedBy, unitVariantsJson = JSONArray().also { rows -> catalogItem.availableUnits.forEach { unit -> rows.put(JSONObject().put("unitCode",unit.unitCode).put("unitName",unit.unitName).put("role",unit.role).put("quantityInBase",unit.quantityInBase).put("barcode",unit.barcode).put("sku",unit.sku).put("netQuantity",unit.netQuantity).put("netUnitCode",unit.netUnitCode).put("packageName",unit.packageName).put("imageUrl",unit.imageUrl).put("isDefault",unit.isDefault)) } }.toString())
                            if (existing >= 0) inventory[existing] = inventory[existing].copy(name = mapped.name, category = mapped.category, catalogCode = mapped.catalogCode, barcode = mapped.barcode, unitCode = mapped.unitCode, imageUrl = mapped.imageUrl, description = mapped.description, brand = mapped.brand, manufacturer = mapped.manufacturer, countryOfManufacture = mapped.countryOfManufacture, unitVariantsJson = mapped.unitVariantsJson)
                            else inventory.add(mapped)
                            LocalStoreRepository.saveProducts(context, inventory)
                            tab = 2
                            showCatalog = false
                        }
                    })
                    showDebts -> if (cloudMode) {
                        CommercialCloudDebtScreen(context = context, onBack = { showDebts = false })
                    } else DebtScreen(sales = sales, settlements = settlements, onBack = { showDebts = false }, onSettle = { settlingSaleNumber = it.number })
                    showReportsCenter -> ReportsCenterScreen(context = context, sales = sales, settlements = settlements, expenses = expenses, products = inventory, cloudMode = cloudMode, onBack = { showReportsCenter = false }, onOpenExistingReports = { showReportsCenter = false; showReports = true })
                    showReports -> if (cloudMode) CommercialCloudReportsScreen(context = context, onBack = { showReports = false }) else ReportsScreen(sales = sales, settlements = settlements, expenses = expenses, onBack = { showReports = false })
                    showExpenses -> if (cloudMode) {
                        CommercialCloudExpensesScreen(context = context, onBack = { showExpenses = false })
                    } else ExpensesScreen(expenses = expenses, onBack = { showExpenses = false }, onAdd = { expenseDialog = true })
                    else -> if (showPersonalAccounts) PersonalAccountsScreen(onBack = { showPersonalAccounts = false }) else if (showActivityProfile) ActivityProfileScreen(
                        onBack = { showActivityProfile = false; cloudContextRevision++ },
                        onCatalog = { showActivityProfile = false; cloudContextRevision++; showCatalog = true }
                    ) else MoreScreen(cloudMode = cloudMode, onCrm = { commercialCrmPage = true }, onCrmOpportunities = { commercialCrmOpportunitiesPage = true }, onFixedAssets = { commercialFixedAssetsPage = true }, onAssetMaintenance = { commercialAssetMaintenancePage = true }, onPersonalAccounts = { showPersonalAccounts = true }, onPersonalWorkNotes = { personalWorkNotesPage = true }, onAppLock = { appLockSettingsPage = true }, onAds = { commercialAdsPage = true }, onCustomers = {
                    showDebts = true
                }, onReports = { showReportsCenter = true }, onExpenses = { showExpenses = true }, onActivityProfile = { showActivityProfile = true }, onCatalog = { showCatalog = true }, onMasterData = { showMasterData = true }, onCloudOperational = { cloudOperationalPage = it }, onCloudSales = { cloudSalesPage = true }, onCloudPurchases = { cloudPurchasesPage = true }, onCloudStockAlerts = { cloudStockAlertsPage = true }, onUnitsDirectory = { cloudUnitsPage = true }, onCustomerPortal = { customerPortalPage = true }, onCustomerOrders = { customerOrderFulfillmentPage = true }, onCustomerSupplyRequests = { customerSupplyRequestsPage = true }, onCreatePurchaseRequest = { merchantPurchaseRequestPage = true }, onBackup = { cloudNotice = "واجهة النسخ الاحتياطي لم تُربط بعد بعملية تصدير واستعادة موثوقة. لم يتم إنشاء نسخة غير مكتملة أو الادعاء بحفظها." })
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
                LocalStoreRepository.saveProducts(context, inventory)
            }
            editingProductIndex = -1
        },
        onDelete = {
            if (cloudMode) {
                cloudNotice = "حذف الأصناف السحابية يحتاج إيقاف العرض وحفظ أثر تدقيقي؛ لم يتم حذف أي بيانات."
            } else {
                inventory.removeAt(editingProductIndex)
                LocalStoreRepository.saveProducts(context, inventory)
            }
            editingProductIndex = -1
        }
    )
    if (addProduct) AddProductDialog(
        cloudMode = cloudMode,
        cloudCategories = cloudCategories,
        cloudUnits = cloudUnits,
        initialName = pendingCatalogProduct?.let { if (it.nameAr.isNotBlank()) it.nameAr else it.name }.orEmpty(),
        initialUnitCode = pendingCatalogProduct?.unitCode.orEmpty(),
        onDismiss = { addProduct = false; pendingCatalogProduct = null },
        onSave = { name, category, unitName, stock, price, costPrice, reorderPoint, categoryId, unitCode ->
            if (cloudMode) {
                cloudInventoryBusy = true
                cloudNotice = "جارٍ حفظ الصنف وربطه بدليل المؤسسة..."
                val catalogItem = pendingCatalogProduct
                val creationKey = pendingCatalogIdempotencyKey
                val branchId = pendingCatalogBranchId
                val warehouseId = pendingCatalogWarehouseId
                val activityNodeCode = pendingCatalogActivityCode
                val baseUnitCode = pendingCatalogBaseUnitCode
                val saleUnitCode = pendingCatalogSaleUnitCode
                storeCloudIo.execute {
                    val result = runCatching {
                        if (catalogItem != null) {
                            val created = createCommercialCloudCatalogInventoryItem(context,catalogItem.id,activityNodeCode,branchId,warehouseId,baseUnitCode,saleUnitCode,unitCode,name,price.toLong(),stock,reorderPoint,categoryId,creationKey)
                            val resultJson = JSONObject(created)
                            Pair(resultJson.optBoolean("created_new_offering",true),loadCommercialCloudInventory(context))
                        } else {
                            createCommercialCloudInventoryItem(context,name,unitName,price.toLong(),stock,reorderPoint,categoryId,unitCode)
                            Pair(true,loadCommercialCloudInventory(context))
                        }
                    }
                    storeCloudMain.post {
                        cloudInventoryBusy = false
                        result.onSuccess { outcome ->
                            inventory.clear()
                            inventory.addAll(outcome.second.products)
                            cloudInventoryError = ""
                            cloudNotice = if (catalogItem != null && !outcome.first) "الصنف مرتبط بالفعل بهذا الفرع؛ لم يتم إنشاء نسخة جديدة." else if (catalogItem != null) "تم إنشاء الصنف وربطه بدليل الأصناف والنشاط والفرع والمستودع ووحداته." else "تم حفظ الصنف والمخزون في قاعدة البيانات."
                            pendingCatalogProduct = null
                            pendingCatalogIdempotencyKey = UUID.randomUUID().toString()
                            addProduct = false
                        }.onFailure {
                            cloudInventoryError = it.message ?: "تعذر حفظ الصنف في قاعدة البيانات."
                            cloudNotice = cloudInventoryError
                            if (catalogItem == null) addProduct = false
                        }
                    }
                }
            } else {
                inventory.add(Product(name, category, stock, money(price), costPrice, unitCode = unitName))
                LocalStoreRepository.saveProducts(context, inventory)
                addProduct = false
            }
        }
    )
    if (newSale && inventory.isNotEmpty()) SaleEntryDialog(
        inventory = inventory,
        cloudMode = cloudMode,
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
                    storeCloudIo.execute {
                        val result = runCatching {
                            val idempotencyKey = getOrCreateCommercialCloudSaleKey(context, currentProduct.cloudOfferingId, baseQuantity)
                            val sale = checkoutCommercialCloudSale(context, currentProduct.cloudOfferingId, baseQuantity, idempotencyKey)
                            clearCommercialCloudSaleKey(context, idempotencyKey)
                            val snapshot = runCatching { loadCommercialCloudInventory(context) }.getOrNull()
                            val history = runCatching { loadCommercialCloudSales(context) }.getOrNull()
                            Triple(sale, snapshot, history)
                        }
                        storeCloudMain.post {
                            cloudInventoryBusy = false
                            result.onSuccess { pair ->
                                val sale = pair.first
                                pair.second?.let { snapshot ->
                                    inventory.clear()
                                    inventory.addAll(snapshot.products)
                                }
                                val receipt = sale.optLong("receipt_number", nextInvoice.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                val total = sale.optLong("total_minor", (quantity * unitPrice).toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                                val serverSales = pair.third
                                if (serverSales != null) {
                                    sales.clear()
                                    sales.addAll(serverSales)
                                }
                                nextInvoice = maxOf(nextInvoice + 1, receipt + 1)
                                cloudInventoryError = ""
                                cloudNotice = if (serverSales != null) {
                                    "تم تسجيل الفاتورة رقم $receipt وتحديث السجل من قاعدة البيانات."
                                } else {
                                    "تم تأكيد الفاتورة رقم $receipt على الخادم، لكن تعذر تحديث قائمة المبيعات. افتح «فواتير المبيعات السحابية» أو أعد المحاولة؛ لم يتم إنشاء سجل محلي بديل."
                                }
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
                LocalStoreRepository.saveProducts(context, inventory)
                val soldUnitCost = if (quantity > 0) kotlin.math.round(currentProduct.costPrice.toDouble() * baseQuantity / quantity).toInt() else currentProduct.costPrice
                sales.add(0, SaleRecord(nextInvoice, product, quantity, quantity * unitPrice, credit, customer, 0, System.currentTimeMillis(), soldUnitCost, unitCode, baseQuantity))
                LocalStoreRepository.saveSales(context, sales)
                nextInvoice = maxOf(nextInvoice + 1, (sales.maxOfOrNull { it.number } ?: 1048) + 1)
                newSale = false
            }
        }
    )
    if (settlingSaleNumber >= 0 && cloudMode) {
        cloudNotice = "تسجيل التحصيل السحابي غير مفعّل بعد؛ لم يتم تغيير رصيد الفاتورة محليًا."
        settlingSaleNumber = -1
    } else if (settlingSaleNumber >= 0) {
        val saleIndex = sales.indexOfFirst { it.number == settlingSaleNumber }
        if (saleIndex >= 0) SettlementDialog(
            sale = sales[saleIndex],
            onDismiss = { settlingSaleNumber = -1 },
            onSave = { amount ->
                val current = sales[saleIndex]
                sales[saleIndex] = current.copy(paid = (current.paid + amount).coerceAtMost(current.total))
                settlements.add(0, SettlementRecord(current.number, current.customer, amount))
                LocalStoreRepository.saveSales(context, sales)
                LocalStoreRepository.saveSettlements(context, settlements)
                settlingSaleNumber = -1
            }
        ) else settlingSaleNumber = -1
    }
    if (expenseDialog) ExpenseEntryDialog(onDismiss = { expenseDialog = false }, onSave = { category, amount, note ->
        expenses.add(0, ExpenseRecord(System.currentTimeMillis(), category, amount, note))
        LocalStoreRepository.saveExpenses(context, expenses)
        expenseDialog = false
    })
    if (cloudNotice.isNotBlank()) AlertDialog(
        onDismissRequest = { cloudNotice = "" },
        title = { Text("حالة قاعدة البيانات") },
        text = { Text(cloudNotice) },
        confirmButton = { TextButton(onClick = { cloudNotice = "" }) { Text("حسنًا") } }
    )
    if (cloudContextHasAny && cloudInventoryError.isNotBlank() && cloudNotice.isBlank()) AlertDialog(
        onDismissRequest = { cloudInventoryError = "" },
        title = { Text("تعذر الاتصال بالمخزون السحابي") },
        text = { Text(cloudInventoryError) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { cloudInventoryError = ""; cloudReloadToken++ }, enabled = !cloudInventoryBusy) { Text("إعادة المحاولة") }
                TextButton(onClick = { cloudInventoryError = "" }) { Text("إغلاق") }
            }
        }
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
}


