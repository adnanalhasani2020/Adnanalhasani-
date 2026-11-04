package com.adnanalhasani.commercial

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

private val catalogIo = Executors.newSingleThreadExecutor()
private val catalogMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogScreen(onBack: () -> Unit, onAddToInventory: (CatalogProduct, String, String, String, String, String) -> Unit, initialOrganizationId: String = "", initialActivityCode: String = "", requireCloudInventoryContext: Boolean = false) {
    val context = LocalContext.current
    val productFormState = remember { CatalogProductFormState() }
    val invoiceTransferState = remember { CatalogInvoiceTransferState() }
    var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }; var signedInUserId by remember { mutableStateOf("") }; var organizationId by remember { mutableStateOf(initialOrganizationId) }
    var section by remember { mutableStateOf("browse") }
    var sector by remember { mutableStateOf("TRADE") }; var businessLevel by remember { mutableStateOf("shop") }
    var country by remember { mutableStateOf("YE") }; var region by remember { mutableStateOf("") }; var city by remember { mutableStateOf("") }
    var locale by remember { mutableStateOf("ar") }; var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<CatalogProduct>>(emptyList()) }
    var catalogOffset by remember { mutableIntStateOf(0) }
    var catalogHasMore by remember { mutableStateOf(true) }
    var catalogPageLoadFailed by remember { mutableStateOf(false) }
    var selectedCatalogIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activityNodes by remember { mutableStateOf<List<CatalogActivityNode>>(emptyList()) }
    var selectedActivityCode by remember { mutableStateOf(initialActivityCode.uppercase()) }
    var sectorMenuExpanded by remember { mutableStateOf(false) }
    var businessLevelMenuExpanded by remember { mutableStateOf(false) }
    var activityMenuExpanded by remember { mutableStateOf(false) }
    val sectorOptions = remember(activityNodes) { activityNodes.filter { it.nodeKind.equals("sector", true) }.sortedBy { it.pathAr } }
    val activityOptions = remember(activityNodes, sector) { activityNodes.filter { it.selectable && !it.nodeKind.equals("sector", true) && catalogNodeBelongsToSector(it, sector, activityNodes) }.sortedBy { it.pathAr } }
    val businessLevelOptions = remember(activityOptions) { activityOptions.map { it.defaultBusinessLevelCode }.filter { it.isNotBlank() }.distinct().sorted() }
    val marketUiState = remember { CatalogMarketUiState() }
    var marketBranches by marketUiState::marketBranches
    var marketWarehouses by marketUiState::marketWarehouses
    var selectedBranchId by marketUiState::selectedBranchId
    var selectedWarehouseId by marketUiState::selectedWarehouseId
    var branchLatitude by marketUiState::branchLatitude
    var branchLongitude by marketUiState::branchLongitude
    var branchMenuExpanded by marketUiState::branchMenuExpanded
    var warehouseMenuExpanded by marketUiState::warehouseMenuExpanded
    var nearbyMarketItems by marketUiState::nearbyMarketItems
    var marketRadius by marketUiState::marketRadius
    var deviceLatitude by marketUiState::deviceLatitude
    var deviceLongitude by marketUiState::deviceLongitude
    var locatingDevice by marketUiState::locatingDevice
    var nearbyOffset by marketUiState::nearbyOffset
    var nearbyHasMore by marketUiState::nearbyHasMore
    var nearbyPageLoadFailed by marketUiState::nearbyPageLoadFailed
    var nearbyLoadGeneration by marketUiState::nearbyLoadGeneration
    var marketPriceHints by marketUiState::marketPriceHints
    val locationScope = rememberCoroutineScope()
    val nearbyListState = rememberLazyListState()
    var invoiceSaleId by invoiceTransferState::invoiceSaleId
    var invoiceDestinationOrg by invoiceTransferState::invoiceDestinationOrg
    var invoiceRequests by invoiceTransferState::invoiceRequests
    var invoiceMappingRequestId by invoiceTransferState::invoiceMappingRequestId
    var invoiceMappingLines by invoiceTransferState::mappingLines
    var invoiceMappingOfferings by invoiceTransferState::mappingOfferings
    var invoiceMappingLineId by invoiceTransferState::invoiceMappingLineId
    var invoiceMappingOfferingId by invoiceTransferState::invoiceMappingOfferingId
    var invoiceMappingLineMenuExpanded by invoiceTransferState::invoiceMappingLineMenuExpanded
    var invoiceMappingOfferingMenuExpanded by invoiceTransferState::invoiceMappingOfferingMenuExpanded
    var invoiceMappingWarehouses by invoiceTransferState::mappingWarehouses
    var invoiceMappingWarehouseId by invoiceTransferState::invoiceMappingWarehouseId
    var invoiceMappingWarehouseMenuExpanded by invoiceTransferState::invoiceMappingWarehouseMenuExpanded
    var localUnitCodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var invoiceReceiveLocationId by invoiceTransferState::invoiceReceiveLocationId
    var invoiceReceiveLocationMenuExpanded by invoiceTransferState::invoiceReceiveLocationMenuExpanded
    val catalogListState = rememberLazyListState()
    var categories by remember { mutableStateOf<List<CatalogCategory>>(emptyList()) }
    var directoryUnits by remember { mutableStateOf<List<DirectoryUnit>>(emptyList()) }; var pendingTransfers by remember { mutableStateOf<List<PendingTransfer>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }; var restoringSession by remember { mutableStateOf(true) }; var feedback by remember { mutableStateOf("") }; var editingProduct by remember { mutableStateOf<CatalogProduct?>(null) }

    val categoryCodeState = remember { mutableStateOf("") }; var categoryCode by categoryCodeState; val parentCodeState = remember { mutableStateOf("") }; var parentCode by parentCodeState
    val categoryNameArState = remember { mutableStateOf("") }; var categoryNameAr by categoryNameArState; val categoryNameEnState = remember { mutableStateOf("") }; var categoryNameEn by categoryNameEnState
    val categoryDescriptionArState = remember { mutableStateOf("") }; var categoryDescriptionAr by categoryDescriptionArState; val categoryDescriptionEnState = remember { mutableStateOf("") }; var categoryDescriptionEn by categoryDescriptionEnState; val categoryTranslationsState = remember { mutableStateOf("") }; var categoryTranslations by categoryTranslationsState

    var nameAr by productFormState::nameAr; var nameEn by productFormState::nameEn
    var descriptionAr by productFormState::descriptionAr; var descriptionEn by productFormState::descriptionEn; var extraTranslations by productFormState::extraTranslations
    var productCategory by productFormState::productCategory; var itemType by productFormState::itemType
    var unitCode by productFormState::unitCode; var barcode by productFormState::barcode
    var brand by productFormState::brand; var manufacturer by productFormState::manufacturer
    var origin by productFormState::origin; var model by productFormState::model; var partNumber by productFormState::partNumber
    var imageUrl by productFormState::imageUrl; var imageAlt by productFormState::imageAlt
    var ingredients by productFormState::ingredients; var materialComposition by productFormState::materialComposition
    var recipe by productFormState::recipe; var recipeIngredientsText by productFormState::recipeIngredientsText; var recipeYieldQty by productFormState::recipeYieldQty; var recipeYieldUnit by productFormState::recipeYieldUnit; var recipePrepMinutes by productFormState::recipePrepMinutes; var recipeCookMinutes by productFormState::recipeCookMinutes; var recipeSource by productFormState::recipeSource; var recipeAllergenNotes by productFormState::recipeAllergenNotes; var specifications by productFormState::specifications; var customAttributesJson by productFormState::customAttributesJson
    var allergens by productFormState::allergens; var storage by productFormState::storage
    var usage by productFormState::usage; var warnings by productFormState::warnings
    var shelfLife by productFormState::shelfLife; var expiryRequired by productFormState::expiryRequired
    var batchTracked by productFormState::batchTracked; var serialTracked by productFormState::serialTracked
    var prescriptionOnly by productFormState::prescriptionOnly
    var extraUnitCode by productFormState::extraUnitCode; var extraUnitQty by productFormState::extraUnitQty
    var extraUnitBarcode by productFormState::extraUnitBarcode; var extraUnitSku by productFormState::extraUnitSku; var extraUnitPackage by productFormState::extraUnitPackage; var extraUnitNetQuantity by productFormState::extraUnitNetQuantity; var extraUnitNetUnitCode by productFormState::extraUnitNetUnitCode; var extraUnitImageUrl by productFormState::extraUnitImageUrl; var extraUnitsText by productFormState::extraUnitsText
    var currencyCode by productFormState::currencyCode; var sectors by productFormState::sectors
    var levels by productFormState::levels; var regionCountry by productFormState::regionCountry
    var regionCode by productFormState::regionCode; var regionCity by productFormState::regionCity
    var regionExcluded by productFormState::regionExcluded; var extraRegionRules by productFormState::extraRegionRules
    var rulingDomain by productFormState::rulingDomain; var authority by productFormState::authority
    var religion by productFormState::religion; var jurisdiction by productFormState::jurisdiction
    var rulingOutcome by productFormState::rulingOutcome; var rulingRationale by productFormState::rulingRationale
    var rulingSourceTitle by productFormState::rulingSourceTitle; var rulingSourceUrl by productFormState::rulingSourceUrl
    var rulingConfidence by productFormState::rulingConfidence; var extraRulingsText by productFormState::extraRulingsText

    val unitShortCodeState = remember { mutableStateOf("") }; var unitShortCode by unitShortCodeState; val unitNameArState = remember { mutableStateOf("") }; var unitNameAr by unitNameArState
    val unitNameEnState = remember { mutableStateOf("") }; var unitNameEn by unitNameEnState; val unitDimensionState = remember { mutableStateOf("count") }; var unitDimension by unitDimensionState
    val unitFactorState = remember { mutableStateOf("1") }; var unitFactor by unitFactorState; val unitBaseCodeState = remember { mutableStateOf("EA") }; var unitBaseCode by unitBaseCodeState
    val unitDecimalsState = remember { mutableStateOf("0") }; var unitDecimals by unitDecimalsState; val unitTranslationsState = remember { mutableStateOf("") }; var unitTranslations by unitTranslationsState
    val transferSourceState = remember { mutableStateOf("") }; var transferSource by transferSourceState; val transferDestinationState = remember { mutableStateOf("") }; var transferDestination by transferDestinationState
    val transferIdsState = remember { mutableStateOf("") }; var transferIds by transferIdsState

    fun request(action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)? = null) {
        if (busy) return
        busy = true; feedback = ""
        catalogIo.execute {
            val result = runCatching(action)
            catalogMain.post { busy = false; result.onSuccess(onSuccess).onFailure { error -> if (onFailure != null) onFailure(error) else feedback = error.message ?: "تعذر إكمال العملية."; restoringSession = false } }
        }
    }
    val invoiceWorkflow = CatalogInvoiceWorkflow(
        context = context,
        state = invoiceTransferState,
        accessToken = { token },
        organizationId = { org() },
        marketBranches = { marketBranches },
        selectedBranchId = { selectedBranchId },
        selectedActivityCode = { selectedActivityCode },
        directoryUnits = { directoryUnits },
        localUnitCodes = { localUnitCodes },
        categories = { categories },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onFeedback = { feedback = it }
    )
    val authWorkflow = CatalogAuthWorkflow(
        context = context,
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        accessToken = { token = it },
        signedInUserId = { signedInUserId = it },
        password = { password = it },
        feedback = { feedback = it },
        restoringSession = { restoringSession = it },
        organizationId = { organizationId = it }
    )
    LaunchedEffect(Unit) { authWorkflow.restoreSession() }

    fun org(): String? = organizationId.trim().takeIf { it.isNotBlank() }

    val marketWorkflow = CatalogMarketWorkflow(
        state = marketUiState,
        accessToken = { token },
        organizationId = { org() },
        selectedActivityCode = { selectedActivityCode },
        selectedCatalogIds = { selectedCatalogIds },
        currencyCode = { currencyCode },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onFeedback = { feedback = it }
    )

    val browseWorkflow = CatalogBrowseWorkflow(
        accessToken = { token },
        organizationId = { org() },
        activityCode = { selectedActivityCode },
        sector = { sector },
        businessLevel = { businessLevel },
        country = { country },
        region = { region },
        city = { city },
        locale = { locale },
        query = { query },
        signedInUserId = { signedInUserId },
        currentItems = { items },
        currentOffset = { catalogOffset },
        hasMore = { catalogHasMore },
        pageLoadFailed = { catalogPageLoadFailed },
        busy = { busy },
        setItems = { items = it },
        setOffset = { catalogOffset = it },
        setHasMore = { catalogHasMore = it },
        setPageLoadFailed = { catalogPageLoadFailed = it },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onFeedback = { feedback = it }
    )
    fun loadCatalog(reset: Boolean = true) = browseWorkflow.loadCatalog(reset)
    LaunchedEffect(section, items.size, catalogHasMore, busy, catalogPageLoadFailed) {
        if (section != "browse" || busy || !catalogHasMore || catalogPageLoadFailed || items.isEmpty()) return@LaunchedEffect
        snapshotFlow {
            val lastVisible = catalogListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total = catalogListState.layoutInfo.totalItemsCount
            lastVisible to total
        }.collect { (lastVisible, total) ->
            if (total > 0 && lastVisible >= total - 5 && !busy && catalogHasMore && !catalogPageLoadFailed) {
                loadCatalog(reset = false)
            }
        }
    }

    suspend fun updateNearbyFromDevice() {
        locatingDevice = true
        try {
            val location = getFreshCatalogDeviceLocation(context)
            deviceLatitude = location.latitude.toString()
            deviceLongitude = location.longitude.toString()
            feedback = "تم تحديد موقعك لهذه الجلسة فقط. لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية."
            marketWorkflow.startNearbyCatalog()
        } catch (error: Exception) {
            feedback = error.message ?: "تعذر تحديد موقع الجهاز. تحقق من تشغيل خدمات الموقع."
        } finally {
            locatingDevice = false
        }
    }

    val deviceLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            locationScope.launch { updateNearbyFromDevice() }
        } else {
            feedback = "لم تمنح إذن الموقع. يمكنك متابعة البحث باستخدام إحداثيات الفرع المحفوظة."
        }
    }

    fun loadNearbyFromDevice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationScope.launch { updateNearbyFromDevice() }
        } else {
            deviceLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(section, nearbyMarketItems.size, nearbyHasMore, nearbyLoadGeneration, busy, marketRadius) {
        if(section!="browse" || nearbyMarketItems.isEmpty() || busy || nearbyPageLoadFailed || marketRadius>=100000) return@LaunchedEffect
        snapshotFlow {
            val lastVisible=nearbyListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            val total=nearbyListState.layoutInfo.totalItemsCount
            Triple(lastVisible,total,nearbyListState.isScrollInProgress)
        }.collect { (lastVisible,total,scrolling) ->
            if(scrolling && total>0 && lastVisible>=total-2 && !busy) {
                if(nearbyHasMore) marketWorkflow.loadNearbyCatalog(false) else marketWorkflow.loadNearbyCatalog(true)
            }
        }
    }

    lateinit var directoryWorkflow: CatalogDirectoryWorkflow
    directoryWorkflow = CatalogDirectoryWorkflow(
        accessToken = { token },
        organizationId = { org() },
        locale = { locale },
        initialActivityCode = { initialActivityCode },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onLocalUnitCodes = { localUnitCodes = it },
        onActivityNodes = { activityNodes = it },
        onCategories = { categories = it },
        onUnits = { directoryUnits = it },
        onSector = { sector = it },
        onBusinessLevel = { businessLevel = it },
        onFeedback = { feedback = it },
        onDirectoriesLoaded = { marketWorkflow.loadMarketContext() },
        onReloadDirectories = { directoryWorkflow.loadDirectories() }
    )

    fun loadDirectories() = directoryWorkflow.loadDirectories()
    LaunchedEffect(token, organizationId) {
        if (token != null && organizationId.trim().isNotBlank() && activityNodes.isEmpty() && !busy) {
            loadDirectories()
        }
    }

    fun createCategory() = directoryWorkflow.createCategory(categoryCode, parentCode, categoryNameAr, categoryNameEn, categoryDescriptionAr, categoryDescriptionEn, categoryTranslations)
    fun createUnit() = directoryWorkflow.createUnit(unitShortCode, unitNameAr, unitNameEn, unitDimension, unitFactor, unitBaseCode, unitDecimals, unitTranslations)
    val productWorkflow = CatalogProductWorkflow(
        state = productFormState,
        accessToken = { token },
        organizationId = { org() },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onFeedback = { feedback = it },
        onCreated = { result ->
            feedback = "تم حفظ الصنف وتفاصيل وحداته ونطاق ظهوره ومعلومات الحكم المرتبطة به: $result"
            section = "browse"
            loadCatalog()
        }
    )
    fun createProduct() = productWorkflow.createProduct()
    val transferWorkflow = CatalogTransferWorkflow(
        accessToken = { token },
        organizationId = { org() },
        transferSource = { transferSource },
        transferDestination = { transferDestination },
        transferIds = { transferIds },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onPendingTransfers = { pendingTransfers = it },
        onFeedback = { feedback = it }
    )
    fun transferCatalog() = transferWorkflow.transferCatalog()
    fun loadPendingTransfers() = transferWorkflow.loadPendingTransfers()
    fun respondToTransfer(id: String, accept: Boolean) = transferWorkflow.respondToTransfer(id, accept)
    val productMaintenanceWorkflow = CatalogProductMaintenanceWorkflow(
        accessToken = { token },
        organizationId = { org() },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onFeedback = { feedback = it },
        onProductUpdated = { item, _ ->
            feedback = "تم تحديث تفاصيل الصنف ${item.catalogCode} مع الحفاظ على وحداته واستهدافه وأحكامه."
            editingProduct = null
            loadCatalog()
        },
        onRulingReviewed = { result ->
            feedback = "تم تسجيل مراجعة المصدر: $result"
            loadCatalog()
        }
    )
    fun reviewRuling(rulingId: String) = productMaintenanceWorkflow.reviewRuling(rulingId)
    fun updateProductDetails(item: CatalogProduct, payload: JSONObject) = productMaintenanceWorkflow.updateProductDetails(item, payload)

    Column(Modifier.fillMaxSize().padding(horizontal=14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,contentDescription="رجوع") }
            Column(Modifier.weight(1f)) {
                Text("دليل الأصناف والوحدات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text("دليل متعدد الأنشطة والمستويات والمناطق",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            }
            if (token != null) TextButton(onClick = { authWorkflow.logout() }) { Text("تسجيل الخروج") }
        }
        if (restoringSession) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if(token==null) {
            CatalogLoginPanel(
                email = email,
                onEmailChange = { email = it.trim() },
                password = password,
                onPasswordChange = { password = it },
                busy = busy,
                onLogin = { authWorkflow.login(email, password) }
            )
        } else {
            OutlinedTextField(organizationId,{organizationId=it.trim()},Modifier.fillMaxWidth(),label={Text("معرّف المؤسسة UUID")},singleLine=true)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("browse" to "بحث", "product" to "صنف جديد", "category" to "تصنيف", "unit" to "وحدة", "transfer" to "نقل", "invoice_transfer" to "فاتورة بيع → مشتريات").forEach { (key,label) ->
                    FilterChip(selected=section==key,onClick={section=key},label={Text(label,style=MaterialTheme.typography.labelSmall)})
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(feedback.isNotBlank()) Text(feedback,color=if(feedback.startsWith("تم")||feedback.startsWith("اكتملت")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            when(section) {
                "browse" -> {
                    CatalogMarketDiscoverySection(
                        state=marketUiState,
                        selectedCatalogIds=selectedCatalogIds,
                        busy=busy,
                        selectedActivityCode=selectedActivityCode,
                        nearbyListState=nearbyListState,
                        onToggleCatalogItem={id ->
                            selectedCatalogIds=if(id in selectedCatalogIds) selectedCatalogIds-id
                            else if(selectedCatalogIds.size<100) selectedCatalogIds+id else selectedCatalogIds
                        },
                        onSelectBranch={branch ->
                            selectedBranchId=branch.id
                            branchLatitude=branch.latitude
                            branchLongitude=branch.longitude
                            selectedWarehouseId=marketWarehouses.firstOrNull { it.branchId==branch.id }?.id.orEmpty()
                            nearbyMarketItems=emptyList()
                            nearbyOffset=0
                            nearbyHasMore=true
                            nearbyLoadGeneration++
                            marketRadius=5000
                            marketPriceHints=emptyMap()
                            branchMenuExpanded=false
                        },
                        onSelectWarehouse={warehouseId ->
                            selectedWarehouseId=warehouseId
                            warehouseMenuExpanded=false
                        },
                        onBranchMenuExpandedChange={branchMenuExpanded=it},
                        onWarehouseMenuExpandedChange={warehouseMenuExpanded=it},
                        onBranchLatitudeChange={branchLatitude=it},
                        onBranchLongitudeChange={branchLongitude=it},
                        onUseBranchLocation={
                            deviceLatitude=null
                            deviceLongitude=null
                            marketWorkflow.startNearbyCatalog()
                        },
                        onSaveCoordinates=marketWorkflow::saveBranchCoordinates,
                        onLoadMarketContext=marketWorkflow::loadMarketContext,
                        onLoadNearbyFromDevice=::loadNearbyFromDevice,
                        onLoadSelectedPriceSuggestions=marketWorkflow::loadSelectedPriceSuggestions,
                        onCreateProduct={section="product"},
                        onConfigureSelectedCatalogItems=marketWorkflow::configureSelectedCatalogItems,
                        onLoadNearbyCatalog={ expand -> marketWorkflow.loadNearbyCatalog(expand) }
                    )
                    if (selectedCatalogIds.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("تم تحديد ${selectedCatalogIds.size} صنفًا", fontWeight=FontWeight.SemiBold)
                                Text("سيتم ربطها بدليل المؤسسة دون تسعير، وستبقى غير متاحة للبيع حتى ضبط أسعارها.", style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Button(onClick={
                                        val active=token
                                        val orgId=org()
                                        if(active==null || orgId==null) { feedback="أدخل معرّف المؤسسة الصحيح وسجّل الدخول."; return@Button }
                                        if(selectedCatalogIds.size>100) { feedback="الحد الأقصى للعملية الواحدة 100 صنف."; return@Button }
                                        val ids=selectedCatalogIds.toList()
                                        request({
                                            catalogHttp("/rest/v1/rpc/commercial_catalog_link_unpriced_items",
                                                JSONObject().put("p_organization_id",orgId).put("p_global_item_ids",JSONArray(ids)),active)
                                        }, { raw ->
                                            val result=runCatching { JSONObject(raw) }.getOrNull()
                                            val added=result?.optInt("newly_linked_count",-1) ?: -1
                                            val existing=result?.optInt("already_linked_count",-1) ?: -1
                                            if (selectedActivityCode.isBlank()) {
                                                selectedCatalogIds=emptySet()
                                                feedback=if(added>=0 && existing>=0) "تم ربط $added صنفًا جديدًا؛ $existing كان مرتبطًا مسبقًا. لم تُضبط أسعار ولم تُفعّل للبيع." else "اكتملت عملية الربط دون تسعير. الأصناف الجديدة غير متاحة للبيع."
                                            } else {
                                                request({
                                                    catalogHttp("/rest/v1/rpc/commercial_catalog_assign_items_to_activity",
                                                        JSONObject().put("p_organization_id",orgId).put("p_activity_node_code",selectedActivityCode).put("p_global_item_ids",JSONArray(ids)),active)
                                                }, { mappingRaw ->
                                                    val mapped=runCatching { JSONObject(mappingRaw) }.getOrNull()
                                                    selectedCatalogIds=emptySet()
                                                    feedback="تم ربط $added صنفًا جديدًا ($existing موجود مسبقًا)، وإسناد ${mapped?.optInt("newly_assigned_count",0) ?: 0} صنف للنشاط المحدد. الأصناف غير مسعّرة وغير متاحة للبيع."
                                                }, { failure ->
                                                    feedback="تم ربط الأصناف بدليل المؤسسة لكنها لم تُسند للنشاط: ${failure.message ?: "خطأ غير معروف"}. أعد المحاولة مع بقاء التحديد."
                                                })
                                            }
                                        })
                                    }, enabled=!busy && selectedCatalogIds.size<=100) { Text("موافق — ربط بدون تسعير") }
                                    TextButton(onClick={selectedCatalogIds=emptySet()}, enabled=!busy) { Text("إلغاء التحديد") }
                                }
                            }
                        }
                    }
                    CatalogBrowseSection(
                        items=items,
                        categories=categories,
                        directoryUnits=directoryUnits,
                        query=query,
                        onQueryChange={query=it},
                        sectorOptions=sectorOptions,
                        sector=sector,
                        onSectorSelected={ selectedCode ->
                            sectorMenuExpanded=false
                            if(selectedCode.isBlank()) {
                                sector=""; selectedActivityCode=""; businessLevel=""
                            } else {
                                sector=selectedCode
                                val selectedNode=activityNodes.firstOrNull { it.code==selectedActivityCode }
                                if(selectedNode!=null && !catalogNodeBelongsToSector(selectedNode,selectedCode,activityNodes)) selectedActivityCode=""
                                val validLevels=activityNodes.filter { it.selectable && catalogNodeBelongsToSector(it,selectedCode,activityNodes) }.map { it.defaultBusinessLevelCode }.filter { it.isNotBlank() }.distinct()
                                if(businessLevel !in validLevels) businessLevel=""
                            }
                            loadCatalog(true)
                        },
                        sectorMenuExpanded=sectorMenuExpanded,
                        onSectorMenuExpandedChange={sectorMenuExpanded=it},
                        businessLevelOptions=businessLevelOptions,
                        businessLevel=businessLevel,
                        onBusinessLevelSelected={businessLevel=it;businessLevelMenuExpanded=false;loadCatalog(true)},
                        businessLevelMenuExpanded=businessLevelMenuExpanded,
                        onBusinessLevelMenuExpandedChange={businessLevelMenuExpanded=it},
                        activityOptions=activityOptions,
                        selectedActivityCode=selectedActivityCode,
                        onActivitySelected={ node ->
                            activityMenuExpanded=false
                            selectedActivityCode=node?.code.orEmpty()
                            if(node!=null && node.defaultBusinessLevelCode.isNotBlank()) businessLevel=node.defaultBusinessLevelCode
                            loadCatalog(true)
                        },
                        activityMenuExpanded=activityMenuExpanded,
                        onActivityMenuExpandedChange={activityMenuExpanded=it},
                        country=country,
                        onCountryChange={country=it.uppercase()},
                        region=region,
                        onRegionChange={region=it},
                        city=city,
                        onCityChange={city=it},
                        locale=locale,
                        onLocaleChange={locale=it},
                        busy=busy,
                        onSearch={loadCatalog()},
                        onRefreshDirectories=::loadDirectories,
                        listState=catalogListState,
                        selectedCatalogIds=selectedCatalogIds,
                        marketPriceHints=marketPriceHints,
                        organizationId=organizationId.trim(),
                        requireCloudInventoryContext=requireCloudInventoryContext,
                        selectedBranchId=selectedBranchId,
                        selectedWarehouseId=selectedWarehouseId,
                        onSelectionChange={item,checked ->
                            selectedCatalogIds = when {
                                !checked -> selectedCatalogIds - item.id
                                item.id in selectedCatalogIds -> selectedCatalogIds
                                selectedCatalogIds.size < 100 -> selectedCatalogIds + item.id
                                else -> { feedback="الحد الأقصى للعملية الواحدة 100 صنف. أزل تحديد صنف قبل إضافة آخر."; selectedCatalogIds }
                            }
                        },
                        onFeedback={feedback=it},
                        onOpenSource={url ->
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                                .onFailure { feedback="تعذر فتح رابط المصدر: ${it.message.orEmpty()}" }
                        },
                        onReviewRuling=::reviewRuling,
                        onAddToInventory=onAddToInventory,
                        onEditProduct={editingProduct=it}
                    )
                }
                "category" -> CatalogCategorySection(categoryCodeState, parentCodeState, categoryNameArState, categoryNameEnState, categoryDescriptionArState, categoryDescriptionEnState, categoryTranslationsState, categories, locale, busy, ::createCategory, ::loadDirectories)
                "unit" -> CatalogUnitSection(unitShortCodeState, unitNameArState, unitNameEnState, unitDimensionState, unitBaseCodeState, unitFactorState, unitDecimalsState, unitTranslationsState, directoryUnits, busy, ::createUnit, ::loadDirectories)
                "product" -> CatalogProductFormSection(productFormState, categories, directoryUnits, busy, ::createProduct)
                "invoice_transfer" -> CatalogInvoiceTransferSection(invoiceTransferState, marketBranches, selectedBranchId, invoiceRequests, invoiceMappingLines, invoiceMappingOfferings, invoiceMappingWarehouses, categories, directoryUnits, organizationId.trim(), busy, invoiceWorkflow::requestInvoiceAsPurchase, invoiceWorkflow::loadInvoiceRequests, invoiceWorkflow::reviewInvoicePurchaseRequest, invoiceWorkflow::mapKnownInvoicePurchaseItems, invoiceWorkflow::loadInvoicePurchaseUnmappedLines, invoiceWorkflow::linkSelectedInvoicePurchaseLine, invoiceWorkflow::openInvoiceItemCreationDialog)
                "transfer" -> CatalogTransferSection(transferSourceState, transferDestinationState, transferIdsState, pendingTransfers, busy, ::transferCatalog, ::loadPendingTransfers, ::respondToTransfer)
            }
        }
    }
    CatalogInvoiceItemCreationDialog(
        state = invoiceTransferState,
        categories = categories,
        directoryUnits = directoryUnits,
        localUnitCodes = localUnitCodes,
        organizationId = organizationId.trim(),
        mappingLines = invoiceMappingLines,
        selectedLineId = invoiceMappingLineId,
        busy = busy,
        onCreate = invoiceWorkflow::createInvoicePurchaseItem
    )

    editingProduct?.let { item ->
        CatalogProductEditDialog(item=item,busy=busy,onDismiss={editingProduct=null},onSave={payload -> updateProductDetails(item,payload)})
    }
}
