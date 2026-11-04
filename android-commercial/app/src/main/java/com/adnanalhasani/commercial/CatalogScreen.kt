package com.adnanalhasani.commercial

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogScreen(onBack: () -> Unit, onAddToInventory: (CatalogProduct, String, String, String, String, String) -> Unit, initialOrganizationId: String = "", initialActivityCode: String = "", requireCloudInventoryContext: Boolean = false) {
    val context = LocalContext.current
    val productFormState = remember { CatalogProductFormState() }
    val invoiceTransferState = remember { CatalogInvoiceTransferState() }
    val sessionUiState = remember { CatalogSessionUiState(initialOrganizationId) }
    var email by sessionUiState::email
    var password by sessionUiState::password
    var token by sessionUiState::token
    var signedInUserId by sessionUiState::signedInUserId
    var organizationId by sessionUiState::organizationId
    var section by remember { mutableStateOf("browse") }
    val browseUiState = remember(initialActivityCode) { CatalogBrowseUiState(initialActivityCode) }
    var sector by browseUiState::sector
    var businessLevel by browseUiState::businessLevel
    var country by browseUiState::country
    var region by browseUiState::region
    var city by browseUiState::city
    var locale by browseUiState::locale
    var query by browseUiState::query
    var items by browseUiState::items
    var catalogOffset by browseUiState::catalogOffset
    var catalogHasMore by browseUiState::catalogHasMore
    var catalogPageLoadFailed by browseUiState::catalogPageLoadFailed
    var selectedCatalogIds by browseUiState::selectedCatalogIds
    var activityNodes by browseUiState::activityNodes
    var selectedActivityCode by browseUiState::selectedActivityCode
    var sectorMenuExpanded by browseUiState::sectorMenuExpanded
    var businessLevelMenuExpanded by browseUiState::businessLevelMenuExpanded
    var activityMenuExpanded by browseUiState::activityMenuExpanded
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
    val directoryDataState = remember { CatalogDirectoryDataState() }
    var localUnitCodes by directoryDataState::localUnitCodes
    var invoiceReceiveLocationId by invoiceTransferState::invoiceReceiveLocationId
    var invoiceReceiveLocationMenuExpanded by invoiceTransferState::invoiceReceiveLocationMenuExpanded
    val catalogListState = rememberLazyListState()
    var categories by directoryDataState::categories
    var directoryUnits by directoryDataState::directoryUnits
    var busy by sessionUiState::busy
    var restoringSession by sessionUiState::restoringSession
    var feedback by sessionUiState::feedback
    var editingProduct by remember { mutableStateOf<CatalogProduct?>(null) }

    val directoryFormState = remember { CatalogDirectoryFormState() }
    var categoryCode by directoryFormState.categoryCode
    var parentCode by directoryFormState.parentCode
    var categoryNameAr by directoryFormState.categoryNameAr
    var categoryNameEn by directoryFormState.categoryNameEn
    var categoryDescriptionAr by directoryFormState.categoryDescriptionAr
    var categoryDescriptionEn by directoryFormState.categoryDescriptionEn
    var categoryTranslations by directoryFormState.categoryTranslations

    var currencyCode by productFormState::currencyCode

    var unitShortCode by directoryFormState.unitShortCode
    var unitNameAr by directoryFormState.unitNameAr
    var unitNameEn by directoryFormState.unitNameEn
    var unitDimension by directoryFormState.unitDimension
    var unitFactor by directoryFormState.unitFactor
    var unitBaseCode by directoryFormState.unitBaseCode
    var unitDecimals by directoryFormState.unitDecimals
    var unitTranslations by directoryFormState.unitTranslations

    val transferFormState = remember { CatalogTransferFormState() }
    var pendingTransfers by transferFormState.pendingTransfers
    var transferSource by transferFormState.transferSource
    var transferDestination by transferFormState.transferDestination
    var transferIds by transferFormState.transferIds

    fun request(action: () -> String, onSuccess: (String) -> Unit, onFailure: ((Throwable) -> Unit)? = null) {
        CatalogRequestDispatcher.request(
            isBusy = busy,
            setBusy = { busy = it },
            setFeedback = { feedback = it },
            setRestoringSession = { restoringSession = it },
            action = action,
            onSuccess = onSuccess,
            onFailure = onFailure
        )
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
    CatalogPaginationEffects(
        section = section,
        catalogItemCount = items.size,
        catalogHasMore = catalogHasMore,
        catalogBusy = busy,
        catalogPageLoadFailed = catalogPageLoadFailed,
        catalogListState = catalogListState,
        nearbyItemCount = nearbyMarketItems.size,
        nearbyHasMore = nearbyHasMore,
        nearbyLoadGeneration = nearbyLoadGeneration,
        nearbyBusy = busy,
        nearbyPageLoadFailed = nearbyPageLoadFailed,
        nearbyRadius = marketRadius,
        nearbyListState = nearbyListState,
        onLoadCatalogPage = { loadCatalog(reset = false) },
        onLoadNearbyPage = { expand -> marketWorkflow.loadNearbyCatalog(expand) }
    )

    val requestDeviceLocation = CatalogDeviceLocationController(
        context = context,
        state = marketUiState,
        onStartNearbyCatalog = marketWorkflow::startNearbyCatalog,
        onFeedback = { feedback = it }
    )

    val directoryWorkflow = CatalogDirectoryWorkflow(
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
        onDirectoriesLoaded = { marketWorkflow.loadMarketContext() }
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
    val productLinkWorkflow = CatalogProductLinkWorkflow(
        accessToken = { token },
        organizationId = { org() },
        selectedCatalogIds = { selectedCatalogIds },
        selectedActivityCode = { selectedActivityCode },
        request = { action, onSuccess, onFailure -> request(action, onSuccess, onFailure) },
        onSelectionCleared = { selectedCatalogIds = emptySet() },
        onFeedback = { feedback = it }
    )
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
        CatalogScreenHeader(
            onBack = onBack,
            signedIn = token != null,
            onLogout = { authWorkflow.logout() }
        )
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
            CatalogSectionNavigation(
                selectedSection = section,
                onSectionSelected = { section = it }
            )
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
                        onLoadNearbyFromDevice=requestDeviceLocation,
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
                                    Button(onClick={productLinkWorkflow.linkSelectedItems()}, enabled=!busy && selectedCatalogIds.size<=100) { Text("موافق — ربط بدون تسعير") }
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
                "category" -> CatalogCategorySection(directoryFormState.categoryCode, directoryFormState.parentCode, directoryFormState.categoryNameAr, directoryFormState.categoryNameEn, directoryFormState.categoryDescriptionAr, directoryFormState.categoryDescriptionEn, directoryFormState.categoryTranslations, categories, locale, busy, ::createCategory, ::loadDirectories)
                "unit" -> CatalogUnitSection(directoryFormState.unitShortCode, directoryFormState.unitNameAr, directoryFormState.unitNameEn, directoryFormState.unitDimension, directoryFormState.unitBaseCode, directoryFormState.unitFactor, directoryFormState.unitDecimals, directoryFormState.unitTranslations, directoryUnits, busy, ::createUnit, ::loadDirectories)
                "product" -> CatalogProductFormSection(productFormState, categories, directoryUnits, busy, ::createProduct)
                "invoice_transfer" -> CatalogInvoiceTransferSection(invoiceTransferState, marketBranches, selectedBranchId, invoiceRequests, invoiceMappingLines, invoiceMappingOfferings, invoiceMappingWarehouses, categories, directoryUnits, organizationId.trim(), busy, invoiceWorkflow::requestInvoiceAsPurchase, invoiceWorkflow::loadInvoiceRequests, invoiceWorkflow::reviewInvoicePurchaseRequest, invoiceWorkflow::mapKnownInvoicePurchaseItems, invoiceWorkflow::loadInvoicePurchaseUnmappedLines, invoiceWorkflow::linkSelectedInvoicePurchaseLine, invoiceWorkflow::openInvoiceItemCreationDialog)
                "transfer" -> CatalogTransferSection(transferFormState.transferSource, transferFormState.transferDestination, transferFormState.transferIds, pendingTransfers, busy, ::transferCatalog, ::loadPendingTransfers, ::respondToTransfer)
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
