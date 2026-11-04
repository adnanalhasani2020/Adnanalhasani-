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
    var invoiceRequestKey by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    var invoiceRequests by remember { mutableStateOf<List<InvoicePurchaseRequestRow>>(emptyList()) }
    var invoiceMappingRequestId by invoiceTransferState::invoiceMappingRequestId
    var invoiceMappingPurchaseOrderId by remember { mutableStateOf("") }
    var invoiceMappingLines by remember { mutableStateOf<List<InvoicePurchaseUnmappedLine>>(emptyList()) }
    var invoiceMappingOfferings by remember { mutableStateOf<List<InvoicePurchaseOffering>>(emptyList()) }
    var invoiceMappingLineId by invoiceTransferState::invoiceMappingLineId
    var invoiceMappingOfferingId by invoiceTransferState::invoiceMappingOfferingId
    var invoiceMappingLineMenuExpanded by invoiceTransferState::invoiceMappingLineMenuExpanded
    var invoiceMappingOfferingMenuExpanded by invoiceTransferState::invoiceMappingOfferingMenuExpanded
    var invoiceMappingLocationId by remember { mutableStateOf("") }
    var invoiceMappingWarehouses by remember { mutableStateOf<List<CatalogWarehouseOption>>(emptyList()) }
    var invoiceMappingWarehouseId by invoiceTransferState::invoiceMappingWarehouseId
    var invoiceMappingWarehouseMenuExpanded by invoiceTransferState::invoiceMappingWarehouseMenuExpanded
    var showInvoiceItemCreation by remember { mutableStateOf(false) }
    var invoiceNewNameAr by remember { mutableStateOf("") }
    var invoiceNewNameEn by remember { mutableStateOf("") }
    var invoiceNewCategoryCode by remember { mutableStateOf("") }
    var invoiceNewBaseUnitCode by remember { mutableStateOf("") }
    var invoiceNewSaleUnitCode by remember { mutableStateOf("") }
    var invoiceNewCategoryMenuExpanded by remember { mutableStateOf(false) }
    var invoiceNewBaseUnitMenuExpanded by remember { mutableStateOf(false) }
    var invoiceNewSaleUnitMenuExpanded by remember { mutableStateOf(false) }
    var invoiceNewIdempotencyKey by remember { mutableStateOf("") }
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
    LaunchedEffect(Unit) {
        val savedRefresh = runCatching { commercialCloudSessionPreferences(context).getString("refresh_token", null) }.getOrNull()
        if (!savedRefresh.isNullOrBlank()) {
            request({
                val raw = catalogHttp("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", savedRefresh))
                val session = JSONObject(raw)
                val access = session.optString("access_token")
                val refresh = session.optString("refresh_token").ifBlank { savedRefresh }
                if (access.isBlank()) throw IllegalStateException("انتهت الجلسة؛ سجّل الدخول مجددًا.")
                "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
            }, { sessionValue ->
                val parts = sessionValue.split("|", limit = 3)
                token = parts[0]
                commercialCloudSessionPreferences(context).edit().putString("refresh_token", parts.getOrNull(1).orEmpty()).apply()
                signedInUserId = parts.getOrNull(2).orEmpty()
                feedback = "تم استعادة جلستك السابقة."
                restoringSession = false
            })
        } else {
            restoringSession = false
        }
    }

    fun org(): String? = organizationId.trim().takeIf { it.isNotBlank() }
    fun loadCatalog(reset: Boolean = true) {
        val active = token ?: return
        val orgId = org() ?: run { feedback = "أدخل معرّف المؤسسة UUID أولًا."; return }
        if (!reset && (!catalogHasMore || busy || catalogPageLoadFailed)) return
        catalogPageLoadFailed = false
        val requestedOffset = if (reset) 0 else catalogOffset
        if (reset) { catalogOffset = 0; catalogHasMore = true; items = emptyList() }
        request({
            val payload = JSONObject().put("p_organization_id",orgId)
                .put("p_activity_node_code",selectedActivityCode.trim().uppercase().ifBlank { JSONObject.NULL })
                .put("p_sector_code",sector.trim().ifBlank { JSONObject.NULL })
                .put("p_business_level_code",businessLevel.trim().ifBlank { JSONObject.NULL })
                .put("p_country_code",country.trim().ifBlank { JSONObject.NULL })
                .put("p_region_code",region.trim().ifBlank { JSONObject.NULL }).put("p_city",city.trim().ifBlank { JSONObject.NULL })
                 .put("p_locale_code",locale.trim().lowercase().ifBlank { "ar" }).put("p_query",query.trim().ifBlank { JSONObject.NULL }).put("p_limit",100).put("p_offset",requestedOffset)
            catalogHttp("/rest/v1/rpc/commercial_catalog_discover_products_page",payload,active)
        }, { raw ->
            val pageItems=parseCatalogProducts(raw, signedInUserId)
            items = if (reset) pageItems.distinctBy { it.id } else (items + pageItems).distinctBy { it.id }
            catalogOffset = requestedOffset + pageItems.size
            catalogHasMore = pageItems.size == 100
            feedback = if (catalogHasMore) "تم تحميل ${items.size} صنفًا؛ اسحب إلى الأسفل لتحميل المزيد." else "اكتمل تحميل ${items.size} صنفًا مطابقًا."
        }, { catalogPageLoadFailed = true })
    }
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

    fun saveMarketBranchCoordinates(latitude: String?, longitude: String?) {
        val active=token
        val orgId=org()
        if(active==null || orgId==null || selectedBranchId.isBlank()) {
            feedback="اختر مؤسسة وفرعًا صالحين أولًا."
            return
        }
        request({
            catalogHttp("/rest/v1/rpc/commercial_update_location_coordinates",JSONObject()
                .put("p_organization_id",orgId)
                .put("p_location_id",selectedBranchId)
                .put("p_latitude",latitude ?: JSONObject.NULL)
                .put("p_longitude",longitude ?: JSONObject.NULL),active)
        }, { raw ->
            val result=JSONObject(raw)
            val savedLat=result.optString("latitude").takeUnless { it=="null" }.orEmpty()
            val savedLon=result.optString("longitude").takeUnless { it=="null" }.orEmpty()
            marketBranches=marketBranches.map { branch ->
                if(branch.id==selectedBranchId) branch.copy(latitude=savedLat,longitude=savedLon) else branch
            }
            branchLatitude=savedLat
            branchLongitude=savedLon
            feedback=if(savedLat.isBlank()) "تم مسح إحداثيات الفرع." else "تم حفظ إحداثيات الفرع وتسجيل التغيير في سجل التدقيق."
        })
    }

    fun loadMarketContext() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            val branches=catalogGet("/rest/v1/commercial_organization_locations?select=id,name,latitude,longitude&organization_id=eq."+orgId+"&state=eq.active&order=name.asc",active)
            val warehouses=catalogGet("/rest/v1/commercial_warehouses?select=id,name,branch_location_id&organization_id=eq."+orgId+"&state=eq.active&order=name.asc",active)
            JSONObject().put("branches",JSONArray(branches)).put("warehouses",JSONArray(warehouses)).toString()
        }, { raw ->
            val marketContext = parseCatalogMarketContext(raw)
            marketBranches = marketContext.branches
            marketWarehouses = marketContext.warehouses
            if(selectedBranchId.isBlank()) selectedBranchId=marketBranches.firstOrNull()?.id.orEmpty()
            if(selectedWarehouseId.isBlank() || marketWarehouses.none { it.id==selectedWarehouseId && it.branchId==selectedBranchId }) selectedWarehouseId=marketWarehouses.firstOrNull { it.branchId==selectedBranchId }?.id.orEmpty()
            val selectedBranch=marketBranches.firstOrNull { it.id==selectedBranchId }
            branchLatitude=selectedBranch?.latitude.orEmpty()
            branchLongitude=selectedBranch?.longitude.orEmpty()
            feedback="تم تحميل الفروع والمستودعات: "+marketBranches.size+" / "+marketWarehouses.size
        })
    }

    fun configureSelectedCatalogItems() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        if(selectedActivityCode.isBlank() || selectedBranchId.isBlank() || selectedWarehouseId.isBlank() || selectedCatalogIds.isEmpty()) {
            feedback="اختر النشاط والفرع والمستودع وحدد الأصناف أولًا."; return
        }
        if(selectedCatalogIds.size>100) { feedback="الحد الأقصى للعملية الواحدة 100 صنف."; return }
        val selectedIds=selectedCatalogIds.toList()
        request({
            // Fetch authoritative unit rows for every selected item, including items selected from nearby discovery.
            // Product-unit RLS inherits visibility from the global catalog item.
            val filter=java.net.URLEncoder.encode("in.("+selectedIds.joinToString(",")+")","UTF-8")
            val unitRaw=catalogGet("/rest/v1/commercial_catalog_product_units?select=product_id,unit_code,unit_role,quantity_in_base,is_default&is_active=eq.true&product_id=$filter&order=product_id.asc,is_default.desc,created_at.asc",active)
            val payloadItems=buildCatalogConfigurationItems(selectedIds, JSONArray(unitRaw))
            catalogHttp("/rest/v1/rpc/commercial_catalog_configure_items",JSONObject().put("p_organization_id",orgId)
                .put("p_activity_node_code",selectedActivityCode).put("p_branch_location_id",selectedBranchId)
                .put("p_warehouse_id",selectedWarehouseId).put("p_items",payloadItems),active)
        }, { raw ->
            val result=runCatching { JSONObject(raw) }.getOrNull()
            feedback="تمت تهيئة "+(result?.optInt("configured_count",selectedIds.size) ?: selectedIds.size)+
                " صنفًا للنشاط والفرع والمستودع، بما فيها نتائج السوق القريب. الأصناف غير المسعرة تبقى غير متاحة للبيع ولا يُنشأ مخزون تلقائيًا."
        })
    }

    fun loadNearbyCatalog(expandRadius: Boolean = false) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        val branch=marketBranches.firstOrNull { it.id==selectedBranchId }
        val useDeviceCenter=deviceLatitude!=null && deviceLongitude!=null
        val lat=if(useDeviceCenter) deviceLatitude?.toBigDecimalOrNull() else branch?.latitude?.toBigDecimalOrNull()
        val lon=if(useDeviceCenter) deviceLongitude?.toBigDecimalOrNull() else branch?.longitude?.toBigDecimalOrNull()
        if(lat==null || lon==null) { feedback="حدد موقعك الحالي أو اختر فرعًا بإحداثيات محفوظة."; return }
        val radiusSteps=listOf(5000,10000,25000,50000,100000)
        val nextRadius=if(expandRadius) radiusSteps.firstOrNull { it>marketRadius } ?: marketRadius else marketRadius
        if(expandRadius && nextRadius==marketRadius) { feedback="تم بلوغ أقصى نطاق بحث (100 كم)."; return }
        val requestedOffset=if(expandRadius) 0 else nearbyOffset
        nearbyPageLoadFailed=false
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_nearby_popularity",JSONObject().put("p_organization_id",orgId)
                .put("p_latitude",lat.toPlainString()).put("p_longitude",lon.toPlainString()).put("p_radius_m",nextRadius)
                .put("p_offset",requestedOffset).put("p_limit",30),active)
        }, { raw ->
            val page = parseNearbyCatalogItems(raw)
            if(expandRadius) marketRadius=nextRadius
            nearbyOffset=requestedOffset+page.size
            nearbyHasMore=page.size==30
            nearbyMarketItems=(nearbyMarketItems+page).distinctBy { it.id }
            nearbyLoadGeneration++
            nearbyPageLoadFailed=false
            feedback=if(page.isEmpty()) "لا توجد نتائج إضافية في هذا النطاق؛ اسحب قائمة السوق لتوسيع البحث."
                else "تم تحميل "+page.size+" نتيجة جديدة. النتائج مجمعة ولا تكشف هوية التجار."
        }, { error ->
            nearbyPageLoadFailed=true
            feedback="تعذر تحميل نتائج السوق القريب. تحقق من الاتصال ثم أعد المحاولة: "+(error.message ?: "خطأ غير معروف")
        })
    }

    fun startNearbyCatalog() {
        nearbyMarketItems=emptyList()
        nearbyOffset=0
        nearbyHasMore=true
        nearbyPageLoadFailed=false
        nearbyLoadGeneration++
        marketRadius=5000
        loadNearbyCatalog(false)
    }

    val deviceLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            locationScope.launch {
                locatingDevice = true
                try {
                    val location = getFreshCatalogDeviceLocation(context)
                    deviceLatitude = location.latitude.toString()
                    deviceLongitude = location.longitude.toString()
                    feedback = "تم تحديد موقعك لهذه الجلسة فقط. لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية."
                    startNearbyCatalog()
                } catch (error: Exception) {
                    feedback = error.message ?: "تعذر تحديد موقع الجهاز. تحقق من تشغيل خدمات الموقع."
                } finally {
                    locatingDevice = false
                }
            }
        } else {
            feedback = "لم تمنح إذن الموقع. يمكنك متابعة البحث باستخدام إحداثيات الفرع المحفوظة."
        }
    }

    fun loadNearbyFromDevice() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationScope.launch {
                locatingDevice = true
                try {
                    val location = getFreshCatalogDeviceLocation(context)
                    deviceLatitude = location.latitude.toString()
                    deviceLongitude = location.longitude.toString()
                    feedback = "تم تحديد موقعك لهذه الجلسة فقط. لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية."
                    startNearbyCatalog()
                } catch (error: Exception) {
                    feedback = error.message ?: "تعذر تحديد موقع الجهاز. تحقق من تشغيل خدمات الموقع."
                } finally {
                    locatingDevice = false
                }
            }
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
                if(nearbyHasMore) loadNearbyCatalog(false) else loadNearbyCatalog(true)
            }
        }
    }

    fun loadSelectedPriceSuggestions() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        val branch=marketBranches.firstOrNull { it.id==selectedBranchId }
        val useDeviceCenter=deviceLatitude!=null && deviceLongitude!=null
        val lat=if(useDeviceCenter) deviceLatitude?.toBigDecimalOrNull() else branch?.latitude?.toBigDecimalOrNull()
        val lon=if(useDeviceCenter) deviceLongitude?.toBigDecimalOrNull() else branch?.longitude?.toBigDecimalOrNull()
        if(lat==null || lon==null || selectedCatalogIds.isEmpty()) { feedback="حدد موقعك الحالي أو اختر فرعًا بإحداثيات صحيحة، ثم حدد الأصناف."; return }
        val ids=selectedCatalogIds.toList()
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_price_suggestions",JSONObject().put("p_organization_id",orgId)
                .put("p_global_item_ids",JSONArray(ids)).put("p_latitude",lat.toPlainString()).put("p_longitude",lon.toPlainString())
                .put("p_radius_m",marketRadius.coerceAtLeast(1000)).put("p_currency_code",currencyCode.uppercase()),active)
        }, { raw ->
            val hints = parseCatalogMarketPriceHints(raw)
            marketPriceHints=hints
            feedback=if(hints.isEmpty()) "لا توجد أسعار استرشادية مؤهلة؛ لم يتم اختلاق أي سعر." else "وصلت أسعار مجمعة لـ "+hints.size+" صنفًا من مجموعات تضم خمسة تجار مستقلين على الأقل."
        })
    }

    fun loadDirectories() {
        val active=token ?: return
        request({
            val catRaw=catalogGet("/rest/v1/commercial_global_catalog_categories?select=code,parent_code,name_ar,name_en,description_ar,description_en&is_active=eq.true&order=parent_code.asc,sort_order.asc,code.asc",active)
            val unitRaw=catalogGet("/rest/v1/commercial_global_units?select=code,name_ar,name_en,dimension,base_unit_code,factor_to_base,owner_organization_id&is_active=eq.true&order=dimension.asc,code.asc",active)
            val localUnitRaw=catalogGet("/rest/v1/commercial_unit_of_measure?select=code&is_active=eq.true&order=code.asc",active)
            val categoryTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.name&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            val categoryDescriptionTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.catalog_category&field_key=eq.description&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            val unitTranslationRaw=catalogGet("/rest/v1/commercial_global_entity_translations?select=entity_key,translated_value&entity_type=eq.unit&field_key=eq.name&locale_code=eq.${locale.trim().ifBlank { "ar" }}",active)
            val activityDirectoryRaw=catalogHttp("/rest/v1/rpc/commercial_list_business_activity_nodes",JSONObject(),active)
            val activityDirectory=JSONObject(activityDirectoryRaw).optJSONArray("nodes") ?: JSONArray()
            JSONObject().put("categories",JSONArray(catRaw)).put("units",JSONArray(unitRaw)).put("localUnits",JSONArray(localUnitRaw))
                .put("categoryTranslations",JSONArray(categoryTranslationRaw)).put("categoryDescriptionTranslations",JSONArray(categoryDescriptionTranslationRaw)).put("unitTranslations",JSONArray(unitTranslationRaw)).put("activityDirectory",activityDirectory).toString()
        }, { raw ->
            val directory = parseCatalogDirectory(raw)
            localUnitCodes = directory.localUnitCodes
            activityNodes = directory.activityNodes
            categories = directory.categories
            directoryUnits = directory.units
            if (initialActivityCode.isNotBlank()) {
                val inferredSector = catalogSectorForNode(initialActivityCode.uppercase(), activityNodes)
                if (inferredSector.isNotBlank()) sector = inferredSector
                activityNodes.firstOrNull { it.code.equals(initialActivityCode, true) }?.defaultBusinessLevelCode?.takeIf { it.isNotBlank() }?.let { businessLevel = it }
            }
            feedback="تم تحميل ${categories.size} تصنيفًا و${directoryUnits.size} وحدة قياس و${sectorOptions.size} قطاعًا و${activityNodes.count { it.nodeKind.equals("activity", true) }} نشاطًا و${activityNodes.count { it.nodeKind.equals("specialization", true) }} تخصصًا."
            loadMarketContext()
        })
    }
    LaunchedEffect(token, organizationId) {
        if (token != null && organizationId.trim().isNotBlank() && activityNodes.isEmpty() && !busy) {
            loadDirectories()
        }
    }

    fun createCategory() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_category",JSONObject()
                .put("p_organization_id",orgId).put("p_code",categoryCode).put("p_parent_code",parentCode.ifBlank { JSONObject.NULL })
                .put("p_name_ar",categoryNameAr).put("p_name_en",categoryNameEn)
                .put("p_description_ar",categoryDescriptionAr.ifBlank { JSONObject.NULL })
                .put("p_description_en",categoryDescriptionEn.ifBlank { JSONObject.NULL }).put("p_sort_order",100)
                .put("p_translations",JSONArray().also { rows -> categoryTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { feedback="تم إنشاء التصنيف: $it"; loadDirectories() })
    }
    fun createUnit() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_unit",JSONObject()
                .put("p_organization_id",orgId).put("p_code",unitShortCode).put("p_name_ar",unitNameAr).put("p_name_en",unitNameEn)
                .put("p_dimension",unitDimension).put("p_factor_to_base",unitFactor.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل التحويل غير صحيح."))
                .put("p_base_unit_code",unitBaseCode).put("p_decimal_places",unitDecimals.toIntOrNull() ?: 0)
                .put("p_translations",JSONArray().also { rows -> unitTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts.all { it.isNotBlank() }) rows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } }),active)
        }, { feedback="تم إنشاء وحدة الحساب الخاصة بالمؤسسة: $it"; loadDirectories() })
    }
    fun createProduct() {
        val active=token ?: return; val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        if(nameAr.isBlank() || nameEn.isBlank() || productCategory.isBlank() || unitCode.isBlank()) { feedback="أكمل الاسم العربي والإنجليزي والتصنيف ووحدة القياس."; return }
        request({
            val product=JSONObject().put("name_ar",nameAr.trim()).put("name_en",nameEn.trim())
                .put("description_ar",descriptionAr.ifBlank { JSONObject.NULL }).put("description_en",descriptionEn.ifBlank { JSONObject.NULL })
                .put("translations",JSONArray().also { translationRows -> extraTranslations.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line -> val parts=line.split("|",limit=3); if(parts.size==3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) translationRows.put(JSONObject().put("locale_code",parts[0].trim().lowercase()).put("field_key",parts[1].trim().lowercase()).put("translated_value",parts[2].trim()).put("source_language",parts[0].trim().lowercase())) } })
                .put("category_code",productCategory.trim()).put("item_type",itemType).put("unit_code",unitCode.trim())
                .put("barcode_gtin",barcode.ifBlank { JSONObject.NULL }).put("brand",brand.ifBlank { JSONObject.NULL })
                .put("manufacturer_name",manufacturer.ifBlank { JSONObject.NULL }).put("country_of_manufacture",origin.ifBlank { JSONObject.NULL })
                .put("model_number",model.ifBlank { JSONObject.NULL }).put("part_number",partNumber.ifBlank { JSONObject.NULL })
                .put("image_url",imageUrl.ifBlank { JSONObject.NULL }).put("image_alt",imageAlt.ifBlank { JSONObject.NULL })
                .put("ingredients",JSONArray(ingredients.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("material_composition",JSONArray(materialComposition.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("recipe",JSONObject().put("instructions",recipe.ifBlank { JSONObject.NULL })
                    .put("recipe_name",nameAr.trim()).put("locale_code","ar")
                    .put("ingredients",JSONArray().also { rows -> recipeIngredientsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                        val p=line.split("|",limit=4)
                        if(p.size<3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw IllegalArgumentException("صيغة مكونات الوصفة: name|quantity|unit_code|note")
                        rows.put(JSONObject().put("ingredient_name",p[0].trim()).put("quantity",p[1].trim().toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("كمية المكون غير صحيحة."))
                            .put("unit_code",p[2].trim().uppercase()).put("preparation_note",p.getOrNull(3)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
                    }})
                    .put("yield_quantity",recipeYieldQty.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL)
                    .put("yield_unit_code",recipeYieldUnit.ifBlank { JSONObject.NULL })
                    .put("preparation_minutes",recipePrepMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("cooking_minutes",recipeCookMinutes.toIntOrNull() ?: JSONObject.NULL)
                    .put("source_reference",recipeSource.ifBlank { JSONObject.NULL })
                    .put("allergen_notes",recipeAllergenNotes.ifBlank { JSONObject.NULL }))
                .put("specifications",JSONObject().put("notes",specifications.ifBlank { JSONObject.NULL }))
                .put("attributes_schema",runCatching { JSONObject(customAttributesJson) }.getOrElse { throw IllegalArgumentException("الحقول الإضافية يجب أن تكون JSON صالحًا.") })
                .put("allergens",JSONArray(allergens.split(",").map { it.trim() }.filter { it.isNotBlank() }))
                .put("storage_instructions",storage.ifBlank { JSONObject.NULL }).put("usage_instructions",usage.ifBlank { JSONObject.NULL })
                .put("warnings",warnings.ifBlank { JSONObject.NULL }).put("shelf_life_days",shelfLife.toIntOrNull() ?: JSONObject.NULL)
                .put("requires_expiry_date",expiryRequired).put("is_batch_tracked",batchTracked)
                .put("is_serial_tracked",serialTracked).put("is_prescription_only",prescriptionOnly).put("currency_code",currencyCode.ifBlank { "YER" })
            val unitRows=JSONArray().put(JSONObject().put("unit_code",unitCode.trim()).put("unit_role","base").put("quantity_in_base",1)
                .put("barcode",barcode.ifBlank { JSONObject.NULL }).put("package_name",JSONObject.NULL).put("image_url",imageUrl.ifBlank { JSONObject.NULL }).put("is_default",true))
            if(extraUnitCode.isNotBlank()) unitRows.put(JSONObject().put("unit_code",extraUnitCode.trim()).put("unit_role","sale")
                .put("quantity_in_base",extraUnitQty.toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل تحويل العبوة غير صحيح."))
                .put("barcode",extraUnitBarcode.ifBlank { JSONObject.NULL }).put("sku",extraUnitSku.ifBlank { JSONObject.NULL }).put("package_name",extraUnitPackage.ifBlank { JSONObject.NULL })
                .put("net_quantity",extraUnitNetQuantity.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL).put("net_unit_code",extraUnitNetUnitCode.ifBlank { JSONObject.NULL })
                .put("image_url",extraUnitImageUrl.ifBlank { imageUrl.ifBlank { JSONObject.NULL } }).put("is_default",false))
            extraUnitsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=9)
                if(p.size<3 || p[0].isBlank() || p[1].isBlank() || p[2].isBlank()) throw IllegalArgumentException("صيغة الوحدة الإضافية: unit_code|role|qty_in_base|barcode|sku|package_name|net_quantity|net_unit_code|image_url")
                unitRows.put(JSONObject().put("unit_code",p[0].trim().uppercase()).put("unit_role",p[1].trim().lowercase())
                    .put("quantity_in_base",p[2].trim().toBigDecimalOrNull()?.toPlainString() ?: throw IllegalArgumentException("معامل تحويل العبوة غير صحيح."))
                    .put("barcode",p.getOrNull(3)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("sku",p.getOrNull(4)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("package_name",p.getOrNull(5)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("net_quantity",p.getOrNull(6)?.trim()?.toBigDecimalOrNull()?.toPlainString() ?: JSONObject.NULL)
                    .put("net_unit_code",p.getOrNull(7)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL)
                    .put("image_url",p.getOrNull(8)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL).put("is_default",false))
            }
            val levelRows=JSONArray(); levels.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { levelRows.put(JSONObject().put("code",it).put("weight",10)) }
            val regionRows=JSONArray()
            if(regionCountry.isNotBlank() || regionCode.isNotBlank() || regionCity.isNotBlank()) regionRows.put(JSONObject()
                .put("country_code",regionCountry.ifBlank { JSONObject.NULL }).put("region_code",regionCode.ifBlank { JSONObject.NULL })
                .put("city",regionCity.ifBlank { JSONObject.NULL }).put("excluded",regionExcluded))
            extraRegionRules.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=4)
                if(p.size<3) throw IllegalArgumentException("صيغة نطاق الظهور: country|region|city|include أو exclude")
                regionRows.put(JSONObject().put("country_code",p[0].trim().ifBlank { JSONObject.NULL })
                    .put("region_code",p[1].trim().ifBlank { JSONObject.NULL }).put("city",p[2].trim().ifBlank { JSONObject.NULL })
                    .put("excluded",p.getOrNull(3)?.trim()?.equals("exclude",true) ?: false))
            }
            val rulingRows=JSONArray()
            if(authority.isNotBlank()) rulingRows.put(JSONObject().put("ruling_domain",rulingDomain).put("authority_code",authority.trim())
                .put("religion_code",if(rulingDomain=="religious") religion.ifBlank { JSONObject.NULL } else JSONObject.NULL)
                .put("jurisdiction_code",jurisdiction.ifBlank { JSONObject.NULL }).put("outcome",rulingOutcome)
                .put("rationale",rulingRationale.ifBlank { JSONObject.NULL }).put("source_title",rulingSourceTitle.ifBlank { JSONObject.NULL })
                .put("source_url",rulingSourceUrl.ifBlank { JSONObject.NULL }).put("confidence",if(rulingConfidence=="reported") "reported" else "unverified"))
            extraRulingsText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val p=line.split("|",limit=9)
                if(p.size<8) throw IllegalArgumentException("صيغة الحكم: domain|authority|religion|jurisdiction|outcome|source_title|source_url|confidence|rationale")
                rulingRows.put(JSONObject().put("ruling_domain",p[0].trim()).put("authority_code",p[1].trim())
                    .put("religion_code",p[2].trim().ifBlank { JSONObject.NULL }).put("jurisdiction_code",p[3].trim().ifBlank { JSONObject.NULL })
                    .put("outcome",p[4].trim()).put("source_title",p[5].trim().ifBlank { JSONObject.NULL })
                    .put("source_url",p[6].trim().ifBlank { JSONObject.NULL }).put("confidence",p[7].trim())
                    .put("rationale",p.getOrNull(8)?.trim()?.ifBlank { JSONObject.NULL } ?: JSONObject.NULL))
            }
            val sectorArray=JSONArray(); sectors.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { sectorArray.put(it) }
            catalogHttp("/rest/v1/rpc/commercial_catalog_create_product",JSONObject().put("p_organization_id",orgId).put("p_product",product)
                .put("p_units",unitRows).put("p_sectors",sectorArray).put("p_business_levels",levelRows).put("p_regions",regionRows).put("p_rulings",rulingRows),active)
        }, { feedback="تم حفظ الصنف وتفاصيل وحداته ونطاق ظهوره ومعلومات الحكم المرتبطة به: $it"; section="browse"; loadCatalog() })
    }
    fun loadInvoiceRequests() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogGet("/rest/v1/commercial_invoice_purchase_requests?select=id,source_organization_id,destination_organization_id,source_invoice_number,currency_code,total_minor,status,destination_purchase_order_id,created_at&or=(source_organization_id.eq."+orgId+",destination_organization_id.eq."+orgId+")&order=created_at.desc&limit=50",active)
        }, { raw ->
            invoiceRequests=parseInvoicePurchaseRequests(raw)
            feedback="طلبات تحويل الفواتير: "+invoiceRequests.size
        })
    }
    fun requestInvoiceAsPurchase() {
        val active=token ?: return
        val source=org() ?: run { feedback="أدخل معرّف المؤسسة البائعة UUID أولًا."; return }
        if(invoiceSaleId.isBlank() || invoiceDestinationOrg.isBlank()) { feedback="أدخل معرّف فاتورة البيع ومعرّف المؤسسة المستقبلة."; return }
        if(source==invoiceDestinationOrg.trim()) { feedback="لا يمكن تحويل الفاتورة إلى المؤسسة نفسها."; return }
        val key=invoiceRequestKey
        request({
            catalogHttp("/rest/v1/rpc/commercial_request_invoice_as_purchase",JSONObject()
                .put("p_source_organization_id",source).put("p_destination_organization_id",invoiceDestinationOrg.trim())
                .put("p_sale_id",invoiceSaleId.trim()).put("p_request_key",key),active)
        }, { raw ->
            val result=runCatching { JSONObject(raw) }.getOrNull()
            feedback="تم إرسال الطلب: "+(result?.optString("state") ?: raw)+". لن يتغير مخزون المؤسسة المستقبلة قبل الاستلام."
            invoiceRequestKey=java.util.UUID.randomUUID().toString()
            loadInvoiceRequests()
        })
    }
    fun reviewInvoicePurchaseRequest(id: String, approve: Boolean) {
        val active=token ?: return
        val receiveLocation=invoiceReceiveLocationId.ifBlank { selectedBranchId }
        if(approve && (receiveLocation.isBlank() || marketBranches.none { it.id==receiveLocation })) {
            feedback="اختر فرع استلام نشطًا تابعًا للمؤسسة المستقبلة قبل الموافقة."
            return
        }
        request({
            catalogHttp("/rest/v1/rpc/commercial_review_invoice_purchase_request",JSONObject()
                .put("p_request_id",id).put("p_decision",if(approve) "approve" else "reject")
                .put("p_review_note",JSONObject.NULL)
                .put("p_receive_location_id",if(approve) receiveLocation else JSONObject.NULL),active)
        }, {
            feedback=if(approve) "تمت الموافقة وإنشاء أمر شراء مسودة في الفرع المحدد. الاستلام منفصل." else "تم رفض طلب تحويل الفاتورة."
            loadInvoiceRequests()
        })
    }

    fun mapKnownInvoicePurchaseItems(id: String) {
        val active=token ?: return
        request({ catalogHttp("/rest/v1/rpc/commercial_invoice_purchase_map_known_items",JSONObject().put("p_request_id",id),active) }, { raw ->
            val result=runCatching { JSONObject(raw) }.getOrNull()
            feedback="تم ربط "+(result?.optInt("mapped_lines",0) ?: 0)+" صنفًا مطابقًا بشكل موثوق. الأصناف غير المطابقة: "+(result?.optInt("unmapped_lines",0) ?: 0)+". لم يتغير المخزون."
            loadInvoiceRequests()
        })
    }
    fun loadInvoicePurchaseUnmappedLines(req: InvoicePurchaseRequestRow) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        if(req.destinationOrganizationId!=orgId || req.status!="approved" || req.purchaseOrderId.isBlank()) {
            feedback="إدارة المطابقة متاحة للمؤسسة المستقبلة بعد الموافقة فقط."
            return
        }
        request({
            val poRows=JSONArray(catalogGet("/rest/v1/commercial_purchase_orders?select=id,receive_location_id,currency_code,status&organization_id=eq."+orgId+"&id=eq."+req.purchaseOrderId+"&limit=1",active))
            if(poRows.length()!=1) throw IllegalStateException("لم يتم العثور على أمر الشراء ضمن المؤسسة المستقبلة.")
            val purchaseOrder=poRows.getJSONObject(0)
            val locationId=purchaseOrder.optString("receive_location_id").takeUnless { it=="null" }.orEmpty()
            val currencyCode=purchaseOrder.optString("currency_code").uppercase()
            val purchaseOrderStatus=purchaseOrder.optString("status")
            if(purchaseOrderStatus !in setOf("draft","submitted","approved")) throw IllegalStateException("لا يمكن تغيير ربط الأصناف بعد استلام أمر الشراء أو إغلاقه أو إلغائه.")
            if(locationId.isBlank() || !currencyCode.matches(Regex("[A-Z]{3}"))) throw IllegalStateException("أمر الشراء لا يحتوي موقع استلام أو عملة صالحة.")
            val supplierInvoices=JSONArray(catalogGet("/rest/v1/commercial_supplier_invoices?select=id&organization_id=eq."+orgId+"&purchase_order_id=eq."+req.purchaseOrderId+"&limit=1",active))
            if(supplierInvoices.length()>0) throw IllegalStateException("تم تسجيل فاتورة مورد لهذا الأمر؛ لا يمكن تغيير ربط الأصناف بعد ذلك.")
            val lineRows=JSONArray(catalogGet("/rest/v1/commercial_purchase_order_items?select=id,line_number,item_description,quantity_ordered,quantity_received,offering_id,source_selling_unit_code&organization_id=eq."+orgId+"&purchase_order_id=eq."+req.purchaseOrderId+"&offering_id=is.null&quantity_received=eq.0&order=line_number.asc",active))
            val warehouseRows=JSONArray(catalogGet("/rest/v1/commercial_warehouses?select=id,name,branch_location_id&organization_id=eq."+orgId+"&branch_location_id=eq."+locationId+"&state=eq.active&order=name.asc",active))
            val mapRows=JSONArray(catalogGet("/rest/v1/commercial_catalog_item_offerings?select=offering_id,global_item_id&organization_id=eq."+orgId+"&location_id=eq."+locationId,active))
            val globalIds=(0 until mapRows.length()).map { mapRows.getJSONObject(it).optString("global_item_id") }.filter { it.isNotBlank() }.distinct()
            val offeringIds=(0 until mapRows.length()).map { mapRows.getJSONObject(it).optString("offering_id") }.filter { it.isNotBlank() }.distinct()
            val encodedGlobals=java.net.URLEncoder.encode("in.("+globalIds.joinToString(",")+")","UTF-8")
            val encodedOfferings=java.net.URLEncoder.encode("in.("+offeringIds.joinToString(",")+")","UTF-8")
            val globalRows=if(globalIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_global_catalog_items?select=id,name_ar,name_en,unit_code&id="+encodedGlobals+"&is_active=eq.true",active))
            val activeOfferings=if(offeringIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_offerings?select=id,product_id,location_ref,state,currency_code&id="+encodedOfferings+"&state=eq.active&currency_code=eq."+currencyCode+"&location_ref=eq."+locationId,active))
            val productIds=(0 until activeOfferings.length()).map { activeOfferings.getJSONObject(it).optString("product_id") }.filter { it.isNotBlank() }.distinct()
            val encodedProducts=java.net.URLEncoder.encode("in.("+productIds.joinToString(",")+")","UTF-8")
            val activeProducts=if(productIds.isEmpty()) JSONArray() else JSONArray(catalogGet("/rest/v1/commercial_products?select=id,state&id="+encodedProducts+"&state=eq.active",active))
            val names=mutableMapOf<String,Pair<String,String>>()
            for(i in 0 until globalRows.length()) {
                val row=globalRows.getJSONObject(i)
                names[row.optString("id")]=Pair(row.optString("name_ar").ifBlank { row.optString("name_en") },row.optString("unit_code"))
            }
            val activeProductIds=(0 until activeProducts.length()).map { activeProducts.getJSONObject(it).optString("id") }.toSet()
            val activeIds=(0 until activeOfferings.length()).filter { activeOfferings.getJSONObject(it).optString("product_id") in activeProductIds }
                .map { activeOfferings.getJSONObject(it).optString("id") }.toSet()
            val candidates=(0 until mapRows.length()).mapNotNull { i ->
                val row=mapRows.getJSONObject(i)
                val offeringId=row.optString("offering_id")
                val globalId=row.optString("global_item_id")
                val name=names[globalId]
                if(offeringId !in activeIds || name==null) null
                else JSONObject().put("id",offeringId).put("global_item_id",globalId)
                    .put("name",name.first.ifBlank { globalId }).put("unit_code",name.second)
            }.distinctBy { it.optString("id") }
            val lines=JSONArray().apply {
                for(i in 0 until lineRows.length()) {
                    val row=lineRows.getJSONObject(i)
                    put(JSONObject().put("id",row.optString("id"))
                        .put("line_number",row.optInt("line_number"))
                        .put("description",row.optString("item_description"))
                        .put("quantity",row.optString("quantity_ordered"))
                        .put("unit_code",row.optString("source_selling_unit_code").takeUnless { it=="null" }.orEmpty()))
                }
            }
            JSONObject().put("location_id",locationId)
                .put("lines",lines)
                .put("offerings",JSONArray().apply { candidates.forEach { put(it) } })
                .put("warehouses",warehouseRows)
                .toString()
        }, { raw ->
            val mapping = parseCatalogInvoiceMapping(raw)
            invoiceMappingLocationId = mapping.locationId
            invoiceMappingWarehouses = mapping.warehouses
            if(invoiceMappingWarehouses.none { it.id==invoiceMappingWarehouseId }) invoiceMappingWarehouseId=invoiceMappingWarehouses.firstOrNull()?.id.orEmpty()
            invoiceMappingLines = mapping.lines
            invoiceMappingOfferings = mapping.offerings
            invoiceMappingRequestId=req.id
            invoiceMappingPurchaseOrderId=req.purchaseOrderId
            invoiceMappingLineId=invoiceMappingLines.firstOrNull()?.id.orEmpty()
            invoiceMappingOfferingId=""
            feedback="الأصناف غير المطابقة: "+invoiceMappingLines.size+". العروض المتاحة في موقع الاستلام: "+invoiceMappingOfferings.size+". لم يتغير المخزون."
        })
    }

    fun linkSelectedInvoicePurchaseLine() {
        val active=token ?: return
        val orgId=org() ?: return
        val requestRow=invoiceRequests.firstOrNull { it.id==invoiceMappingRequestId }
        if(requestRow==null || requestRow.destinationOrganizationId!=orgId || requestRow.status!="approved") {
            feedback="تعذر التحقق من صلاحية طلب المطابقة."
            return
        }
        val line=invoiceMappingLines.firstOrNull { it.id==invoiceMappingLineId } ?: run { feedback="اختر بندًا غير مطابق."; return }
        val offering=invoiceMappingOfferings.firstOrNull { it.id==invoiceMappingOfferingId } ?: run { feedback="اختر صنفًا محليًا مرتبطًا بموقع الاستلام."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_link_purchase_order_item_offering",JSONObject()
                .put("p_organization_id",orgId).put("p_purchase_order_id",invoiceMappingPurchaseOrderId)
                .put("p_purchase_order_item_id",line.id).put("p_offering_id",offering.id),active)
        }, { raw ->
            val result=runCatching { JSONObject(raw) }.getOrNull()
            val orderStatus=result?.optString("status").orEmpty()
            val orderNeedsReapproval=result?.optBoolean("reapproval_required",false) ?: false
            invoiceMappingLines=invoiceMappingLines.filterNot { it.id==line.id }
            invoiceMappingLineId=invoiceMappingLines.firstOrNull()?.id.orEmpty()
            invoiceMappingOfferingId=""
            feedback=if(orderNeedsReapproval) {
                "تم ربط بند الفاتورة بالصنف المحلي "+offering.name+". أعيد أمر الشراء إلى حالة الإرسال ويتطلب إعادة اعتماد؛ لم يتغير المخزون."
            } else {
                "تم ربط بند الفاتورة بالصنف المحلي "+offering.name+". لم يتغير المخزون؛ سيُحدّث عند الاستلام فقط."
            }
            loadInvoicePurchaseUnmappedLines(requestRow)
        })
    }

    fun openInvoiceItemCreationDialog() {
        val line=invoiceMappingLines.firstOrNull { it.id==invoiceMappingLineId } ?: run { feedback="اختر بندًا غير مطابق أولًا."; return }
        val orgId=org() ?: return
        if(invoiceMappingWarehouses.isEmpty() || invoiceMappingWarehouseId.isBlank()) {
            feedback="لا يوجد مستودع نشط مرتبط بفرع استلام أمر الشراء."
            return
        }
        if(selectedActivityCode.isBlank()) {
            feedback="اختر النشاط التجاري من الدليل أولًا."
            return
        }
        val available=directoryUnits.filter { it.owner.isBlank() || it.owner==orgId }
        val baseOptions=available.filter { it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes }
        val sourceUnit=available.firstOrNull { it.code.equals(line.unitCode,true) }
        if(line.unitCode.isNotBlank() && sourceUnit==null) {
            feedback="وحدة الفاتورة "+line.unitCode+" غير موجودة في دليل الوحدات النشطة؛ لم نفترض تحويلًا بديلًا."
            return
        }
        val base=if(sourceUnit==null) baseOptions.firstOrNull() else if(sourceUnit.baseCode.isBlank()) baseOptions.firstOrNull { it.code==sourceUnit.code } else baseOptions.firstOrNull { it.code==sourceUnit.baseCode }
        if(base==null) {
            feedback="لا توجد وحدة أساس نشطة مشتركة بين دليل الوحدات ووحدات المخزون."
            return
        }
        val sale=sourceUnit?.takeIf { it.baseCode.isBlank() || it.baseCode==base.code } ?: base
        invoiceNewNameAr=line.description.trim().take(180)
        invoiceNewNameEn=""
        invoiceNewCategoryCode=categories.firstOrNull()?.code.orEmpty()
        invoiceNewBaseUnitCode=base.code
        invoiceNewSaleUnitCode=sale.code
        invoiceNewIdempotencyKey=java.util.UUID.randomUUID().toString()
        showInvoiceItemCreation=true
    }

    fun createInvoicePurchaseItem() {
        if (!COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY) {
            feedback = "إنشاء صنف جديد من بند فاتورة الشراء متوقف مؤقتًا؛ ترحيل الخادم المطلوب غير منشور في قاعدة الإنتاج بعد. لم يتم إرسال أي بيانات."
            return
        }
        val active=token ?: return
        val orgId=org() ?: return
        val requestRow=invoiceRequests.firstOrNull { it.id==invoiceMappingRequestId }
        if(requestRow==null || requestRow.destinationOrganizationId!=orgId || requestRow.status!="approved") {
            feedback="إنشاء الصنف متاح للمؤسسة المستقبلة بعد اعتماد طلب الشراء فقط."
            return
        }
        val line=invoiceMappingLines.firstOrNull { it.id==invoiceMappingLineId } ?: run { feedback="اختر بند الفاتورة."; return }
        val base=directoryUnits.firstOrNull { it.code==invoiceNewBaseUnitCode && it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes }
            ?: run { feedback="اختر وحدة أساس نشطة ومعتمدة للمخزون."; return }
        val sale=directoryUnits.firstOrNull { it.code==invoiceNewSaleUnitCode && (it.code==base.code || it.baseCode==base.code) && (it.owner.isBlank() || it.owner==orgId) }
            ?: run { feedback="اختر وحدة بيع متوافقة مع وحدة الأساس."; return }
        if(line.unitCode.isNotBlank() && !line.unitCode.equals(sale.code,true)) {
            feedback="وحدة بند الفاتورة لا تطابق وحدة البيع المختارة. لا يمكن تغيير التحويل تلقائيًا."
            return
        }
        if(invoiceNewNameAr.isBlank() || invoiceNewNameEn.isBlank() || invoiceNewCategoryCode.isBlank()) {
            feedback="أدخل الاسم العربي والإنجليزي واختر تصنيف الدليل."
            return
        }
        val contextActivityId=context.getSharedPreferences("commercial_cloud_context_v1",android.content.Context.MODE_PRIVATE)
            .getString("activity_id",null).orEmpty()
        if(contextActivityId.isBlank()) {
            feedback="لم يتم العثور على النشاط الحالي في سياق المؤسسة. افتح ملف النشاط ثم أعد المحاولة."
            return
        }
        if(invoiceMappingWarehouseId.isBlank() || invoiceMappingLocationId.isBlank()) {
            feedback="اختر مستودعًا نشطًا تابعًا لفرع استلام أمر الشراء."
            return
        }
        val key=invoiceNewIdempotencyKey.ifBlank { java.util.UUID.randomUUID().toString().also { invoiceNewIdempotencyKey=it } }
        request({
            catalogHttp("/rest/v1/rpc/commercial_create_and_link_invoice_purchase_item",JSONObject()
                .put("p_organization_id",orgId)
                .put("p_purchase_order_id",invoiceMappingPurchaseOrderId)
                .put("p_purchase_order_item_id",line.id)
                .put("p_activity_id",contextActivityId)
                .put("p_activity_node_code",selectedActivityCode.uppercase())
                .put("p_warehouse_id",invoiceMappingWarehouseId)
                .put("p_name_ar",invoiceNewNameAr.trim())
                .put("p_name_en",invoiceNewNameEn.trim())
                .put("p_category_code",invoiceNewCategoryCode.uppercase())
                .put("p_base_unit_code",base.code)
                .put("p_sale_unit_code",sale.code)
                .put("p_item_type","product")
                .put("p_local_category_id",JSONObject.NULL)
                .put("p_idempotency_key",key),active)
        }, { raw ->
            val result=runCatching { JSONObject(raw) }.getOrNull()
            val status=result?.optString("status").orEmpty()
            val reapprovalRequired=result?.optBoolean("reapproval_required",false) ?: false
            showInvoiceItemCreation=false
            invoiceNewIdempotencyKey=java.util.UUID.randomUUID().toString()
            feedback=if(reapprovalRequired) {
                "تم إنشاء الصنف وربطه. أُعيد أمر الشراء إلى حالة الإرسال ويتطلب إعادة اعتماده. السعر غير محدد والمخزون لم يتغير."
            } else {
                "تم إنشاء الصنف وربطه دون سعر أو كمية افتتاحية. لن يتغير المخزون حتى تسجيل الاستلام."
            }
            loadInvoicePurchaseUnmappedLines(requestRow)
        })
    }

    fun transferCatalog() {
        val active=token ?: return
        if(transferSource.isBlank() || transferDestination.isBlank()) { feedback="أدخل معرّف الحساب المصدر والوجهة."; return }
        request({
            val ids=JSONArray(); transferIds.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { ids.put(it) }
            catalogHttp("/rest/v1/rpc/commercial_catalog_send_transfer",JSONObject().put("p_source_organization_id",transferSource.trim())
                .put("p_destination_organization_id",transferDestination.trim()).put("p_global_item_ids",if(ids.length()==0) JSONObject.NULL else ids),active)
        }, { feedback="اكتملت عملية نقل روابط الدليل: $it" })
    }
    fun loadPendingTransfers() {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة المستقبلة في الحقل العلوي أولًا."; return }
        request({
            catalogGet("/rest/v1/commercial_catalog_transfers?select=id,source_organization_id,destination_organization_id,item_count,status,created_at&destination_organization_id=eq.$orgId&status=eq.pending&order=created_at.desc",active)
        }, { raw ->
            pendingTransfers=parsePendingCatalogTransfers(raw)
            feedback="طلبات النقل الواردة: ${pendingTransfers.size}"
        })
    }
    fun respondToTransfer(id: String, accept: Boolean) {
        val active=token ?: return
        request({
            catalogHttp("/rest/v1/rpc/${if(accept) "commercial_catalog_accept_transfer" else "commercial_catalog_reject_transfer"}",
                JSONObject().put("p_transfer_id",id),active)
        }, { feedback=if(accept) "تم قبول طلب النقل: $it" else "تم رفض طلب النقل: $it"; loadPendingTransfers() })
    }
    fun reviewRuling(rulingId: String) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_review_ruling",JSONObject()
                .put("p_organization_id",orgId).put("p_ruling_id",rulingId),active)
        }, { feedback="تم تسجيل مراجعة المصدر: $it"; loadCatalog() })
    }
    fun updateProductDetails(item: CatalogProduct, payload: JSONObject) {
        val active=token ?: return
        val orgId=org() ?: run { feedback="أدخل معرّف المؤسسة UUID أولًا."; return }
        request({
            catalogHttp("/rest/v1/rpc/commercial_catalog_update_product_details",JSONObject()
                .put("p_organization_id",orgId).put("p_product_id",item.id).put("p_product",payload),active)
        }, { feedback="تم تحديث تفاصيل الصنف ${item.catalogCode} مع الحفاظ على وحداته واستهدافه وأحكامه."; editingProduct=null; loadCatalog() })
    }

    Column(Modifier.fillMaxSize().padding(horizontal=14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,contentDescription="رجوع") }
            Column(Modifier.weight(1f)) {
                Text("دليل الأصناف والوحدات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                Text("دليل متعدد الأنشطة والمستويات والمناطق",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
            }
            if (token != null) TextButton(onClick = {
                runCatching { commercialCloudSessionPreferences(context).edit().clear().apply() }
                token = null; signedInUserId = ""; organizationId = ""; feedback = "تم تسجيل الخروج."
            }) { Text("تسجيل الخروج") }
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
                onLogin = {
                    request({
                        val raw = catalogHttp(
                            "/auth/v1/token?grant_type=password",
                            JSONObject().put("email", email).put("password", password)
                        )
                        val session = JSONObject(raw)
                        val access = session.optString("access_token")
                        val refresh = session.optString("refresh_token")
                        if (access.isBlank() || refresh.isBlank()) {
                            throw IllegalStateException("لم تُرجع خدمة المصادقة جلسة صالحة.")
                        }
                        "${access}|${refresh}|${session.optJSONObject("user")?.optString("id").orEmpty()}"
                    }, { sessionValue ->
                        val parts = sessionValue.split("|", limit = 3)
                        token = parts[0]
                        commercialCloudSessionPreferences(context).edit()
                            .putString("refresh_token", parts.getOrNull(1).orEmpty()).apply()
                        signedInUserId = parts.getOrNull(2).orEmpty()
                        password = ""
                        feedback = "تم تسجيل الدخول. ستُستعاد الجلسة تلقائيًا عند فتح الدليل لاحقًا."
                    })
                }
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
                        onSaveCoordinates=::saveMarketBranchCoordinates,
                        onLoadMarketContext=::loadMarketContext,
                        onLoadNearbyFromDevice=::loadNearbyFromDevice,
                        onStartNearbyCatalog=::startNearbyCatalog,
                        onLoadSelectedPriceSuggestions=::loadSelectedPriceSuggestions,
                        onCreateProduct={section="product"},
                        onConfigureSelectedCatalogItems=::configureSelectedCatalogItems,
                        onLoadNearbyCatalog=::loadNearbyCatalog
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
                    LazyColumn(state=catalogListState,verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(bottom=20.dp)) {
                        item { OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("بحث بالاسم أو الوصف أو الباركود أو الكود")},singleLine=true) }
                        item {
                            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("تصفية الدليل حسب النشاط التجاري", style=MaterialTheme.typography.labelMedium)
                                Box {
                                    OutlinedButton(onClick={sectorMenuExpanded=true}, enabled=!busy && sectorOptions.isNotEmpty(), modifier=Modifier.fillMaxWidth()) {
                                        Text(sectorOptions.firstOrNull { it.code.equals(sector, true) }?.pathAr ?: "كل القطاعات")
                                    }
                                    DropdownMenu(expanded=sectorMenuExpanded, onDismissRequest={sectorMenuExpanded=false}) {
                                        DropdownMenuItem(text={Text("كل القطاعات")}, onClick={
                                            sector=""; selectedActivityCode=""; businessLevel=""; sectorMenuExpanded=false; loadCatalog(true)
                                        })
                                        sectorOptions.forEach { option ->
                                            DropdownMenuItem(text={Text(option.pathAr)}, onClick={
                                                sector=option.code
                                                val selectedNode=activityNodes.firstOrNull { it.code==selectedActivityCode }
                                                if (selectedNode!=null && !catalogNodeBelongsToSector(selectedNode,option.code,activityNodes)) selectedActivityCode=""
                                                val validLevels=activityNodes.filter { it.selectable && catalogNodeBelongsToSector(it,option.code,activityNodes) }.map { it.defaultBusinessLevelCode }.filter { it.isNotBlank() }.distinct()
                                                if (businessLevel !in validLevels) businessLevel=""
                                                sectorMenuExpanded=false; loadCatalog(true)
                                            })
                                        }
                                    }
                                }
                                Box {
                                    OutlinedButton(onClick={businessLevelMenuExpanded=true}, enabled=!busy && businessLevelOptions.isNotEmpty(), modifier=Modifier.fillMaxWidth()) {
                                        Text(businessLevel.takeIf { it.isNotBlank() }?.let { "مستوى النشاط: ${catalogBusinessLevelLabel(it)}" } ?: "كل مستويات النشاط")
                                    }
                                    DropdownMenu(expanded=businessLevelMenuExpanded, onDismissRequest={businessLevelMenuExpanded=false}) {
                                        DropdownMenuItem(text={Text("كل المستويات")}, onClick={businessLevel=""; businessLevelMenuExpanded=false; loadCatalog(true)})
                                        businessLevelOptions.forEach { level ->
                                            DropdownMenuItem(text={Text(catalogBusinessLevelLabel(level))}, onClick={businessLevel=level; businessLevelMenuExpanded=false; loadCatalog(true)})
                                        }
                                    }
                                }
                                Text("اختر القطاع لتصفية الأنشطة ومستوياتها من الدليل الرسمي؛ لا تُكتب الرموز يدويًا.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item {
                            Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text("ربط الأصناف بنشاط تجاري محدد (اختياري)", style=MaterialTheme.typography.labelMedium)
                                Box {
                                    OutlinedButton(onClick={activityMenuExpanded=true}, enabled=!busy && activityOptions.isNotEmpty(), modifier=Modifier.fillMaxWidth()) {
                                        Text(activityOptions.firstOrNull { it.code == selectedActivityCode }?.pathAr ?: "اختر النشاط من الدليل")
                                    }
                                    DropdownMenu(expanded=activityMenuExpanded, onDismissRequest={activityMenuExpanded=false}) {
                                        DropdownMenuItem(text={Text("بدون إسناد لنشاط محدد")}, onClick={selectedActivityCode=""; activityMenuExpanded=false; loadCatalog(true)})
                                        activityOptions.forEach { node ->
                                            DropdownMenuItem(text={Text(node.pathAr)}, onClick={
                                                selectedActivityCode=node.code
                                                if (node.defaultBusinessLevelCode.isNotBlank()) businessLevel=node.defaultBusinessLevelCode
                                                activityMenuExpanded=false
                                                loadCatalog(true)
                                            })
                                        }
                                    }
                                }
                                Text("تُعرض الأنشطة والتخصصات التابعة للقطاع المختار فقط، ويُضبط المستوى المقترح من بيانات الدليل.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(country,{country=it.uppercase()},Modifier.weight(1f),label={Text("الدولة")},singleLine=true)
                            OutlinedTextField(region,{region=it},Modifier.weight(1f),label={Text("المنطقة")},singleLine=true)
                            OutlinedTextField(city,{city=it},Modifier.weight(1f),label={Text("المدينة")},singleLine=true)
                        } }
                        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            OutlinedTextField(locale,{locale=it},Modifier.weight(1f),label={Text("اللغة")},singleLine=true)
                            Button(onClick={loadCatalog()},enabled=!busy) { Icon(Icons.Default.Search,null); Text("بحث") }
                            OutlinedButton(onClick={loadDirectories()},enabled=!busy) { Text("تحديث الأدلة") }
                        } }
                        items(items,key={it.id}) { item ->
                            CatalogProductResultCard(
                                item=item,
                                selected=item.id in selectedCatalogIds,
                                marketPriceHint=marketPriceHints[item.id].orEmpty(),
                                busy=busy,
                                organizationId=organizationId.trim(),
                                requireCloudInventoryContext=requireCloudInventoryContext,
                                selectedBranchId=selectedBranchId,
                                selectedWarehouseId=selectedWarehouseId,
                                selectedActivityCode=selectedActivityCode,
                                onSelectionChange={checked ->
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
                                onEditProduct={editingProduct=item}
                            )
                        }
                        if(items.isEmpty()) item { Text("ابحث عن أصناف أو اضغط تحديث الأدلة لعرض التصنيفات والوحدات المتاحة.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                        if(categories.isNotEmpty()) item { Text("دليل التصنيفات: ${categories.size} تصنيفًا محمّلًا",style=MaterialTheme.typography.bodySmall) }
                        if(directoryUnits.isNotEmpty()) item { Text("دليل الوحدات: ${directoryUnits.size} وحدة محمّلة",style=MaterialTheme.typography.bodySmall) }
                    }
                }
                "category" -> CatalogCategorySection(categoryCodeState, parentCodeState, categoryNameArState, categoryNameEnState, categoryDescriptionArState, categoryDescriptionEnState, categoryTranslationsState, categories, locale, busy, ::createCategory, ::loadDirectories)
                "unit" -> CatalogUnitSection(unitShortCodeState, unitNameArState, unitNameEnState, unitDimensionState, unitBaseCodeState, unitFactorState, unitDecimalsState, unitTranslationsState, directoryUnits, busy, ::createUnit, ::loadDirectories)
                "product" -> CatalogProductFormSection(productFormState, categories, directoryUnits, busy, ::createProduct)
                "invoice_transfer" -> CatalogInvoiceTransferSection(invoiceTransferState, marketBranches, selectedBranchId, invoiceRequests, invoiceMappingLines, invoiceMappingOfferings, invoiceMappingWarehouses, categories, directoryUnits, organizationId.trim(), busy, ::requestInvoiceAsPurchase, ::loadInvoiceRequests, ::reviewInvoicePurchaseRequest, ::mapKnownInvoicePurchaseItems, ::loadInvoicePurchaseUnmappedLines, ::linkSelectedInvoicePurchaseLine, ::openInvoiceItemCreationDialog)
                "transfer" -> CatalogTransferSection(transferSourceState, transferDestinationState, transferIdsState, pendingTransfers, busy, ::transferCatalog, ::loadPendingTransfers, ::respondToTransfer)
            }
        }
    }
    if(showInvoiceItemCreation) {
        val orgId=org().orEmpty()
        val baseOptions=directoryUnits.filter { (it.owner.isBlank() || it.owner==orgId) && it.baseCode.isBlank() && it.code.lowercase() in localUnitCodes }
        val saleOptions=directoryUnits.filter { (it.owner.isBlank() || it.owner==orgId) && (it.code==invoiceNewBaseUnitCode || it.baseCode==invoiceNewBaseUnitCode) }
        AlertDialog(
            onDismissRequest={if(!busy) showInvoiceItemCreation=false},
            title={Text("إنشاء صنف وربطه بأمر الشراء")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("أدخل بيانات الصنف المعتمدة. لا يُستنتج التصنيف أو الاسم الإنجليزي من وصف الفاتورة.",style=MaterialTheme.typography.bodySmall)
                    if (!COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY) {
                        Text(
                            "هذه العملية غير متاحة سحابيًا حتى نشر ترحيل إنشاء الصنف وربطه بأمر الشراء. لن يتم إرسال أي طلب.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedTextField(value=invoiceNewNameAr,onValueChange={invoiceNewNameAr=it.take(180)},label={Text("اسم الصنف بالعربية *")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=invoiceNewNameEn,onValueChange={invoiceNewNameEn=it.take(180)},label={Text("اسم الصنف بالإنجليزية *")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
                    Box {
                        OutlinedButton(onClick={invoiceNewCategoryMenuExpanded=true},modifier=Modifier.fillMaxWidth()) {
                            Text("التصنيف: "+(categories.firstOrNull { it.code==invoiceNewCategoryCode }?.nameAr ?: "اختر تصنيف الدليل"))
                        }
                        DropdownMenu(expanded=invoiceNewCategoryMenuExpanded,onDismissRequest={invoiceNewCategoryMenuExpanded=false}) {
                            categories.forEach { category ->
                                DropdownMenuItem(text={Text(category.nameAr+" • "+category.code)},onClick={
                                    invoiceNewCategoryCode=category.code
                                    invoiceNewCategoryMenuExpanded=false
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick={invoiceNewBaseUnitMenuExpanded=true},modifier=Modifier.fillMaxWidth()) {
                            Text("وحدة الأساس: "+(baseOptions.firstOrNull { it.code==invoiceNewBaseUnitCode }?.let { it.code+" • "+it.nameAr } ?: "اختر وحدة الأساس"))
                        }
                        DropdownMenu(expanded=invoiceNewBaseUnitMenuExpanded,onDismissRequest={invoiceNewBaseUnitMenuExpanded=false}) {
                            baseOptions.forEach { unit ->
                                DropdownMenuItem(text={Text(unit.code+" • "+unit.nameAr)},onClick={
                                    invoiceNewBaseUnitCode=unit.code
                                    invoiceNewSaleUnitCode=unit.code
                                    invoiceNewBaseUnitMenuExpanded=false
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick={invoiceNewSaleUnitMenuExpanded=true},modifier=Modifier.fillMaxWidth()) {
                            Text("وحدة الفاتورة/البيع: "+(saleOptions.firstOrNull { it.code==invoiceNewSaleUnitCode }?.let { it.code+" • "+it.nameAr } ?: "اختر وحدة متوافقة"))
                        }
                        DropdownMenu(expanded=invoiceNewSaleUnitMenuExpanded,onDismissRequest={invoiceNewSaleUnitMenuExpanded=false}) {
                            saleOptions.forEach { unit ->
                                DropdownMenuItem(text={Text(unit.code+" • "+unit.nameAr+" • "+unit.factor)},onClick={
                                    invoiceNewSaleUnitCode=unit.code
                                    invoiceNewSaleUnitMenuExpanded=false
                                })
                            }
                        }
                    }
                    Text("السعر سيبقى صفرًا بحالة غير مسعّر، والرصيد الافتتاحي صفر. إنشاء الصنف وربطه لا يستلم البضاعة؛ كما يتطلب صلاحية المالك أو المسؤول.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("ملاحظة المخزون: المستودع يُحفظ في إعداد الصنف، لكن سجل الرصيد والاستلام الحاليين ما يزالان على مستوى الفرع؛ لا ينشأ رصيد مستقل للمستودع في هذه الخطوة.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    val selectedLine=invoiceMappingLines.firstOrNull { it.id==invoiceMappingLineId }
                    if(selectedLine?.unitCode?.isNotBlank()==true) Text("وحدة بند الفاتورة: "+selectedLine.unitCode+" — يجب اختيارها كوحدة البيع نفسها.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton={
                Button(onClick={createInvoicePurchaseItem()},enabled=!busy && COMMERCIAL_CLOUD_UNMAPPED_INVOICE_ITEM_RPC_READY && invoiceNewNameAr.isNotBlank() && invoiceNewNameEn.isNotBlank() && invoiceNewCategoryCode.isNotBlank() && invoiceNewBaseUnitCode.isNotBlank() && invoiceNewSaleUnitCode.isNotBlank() && invoiceMappingWarehouseId.isNotBlank()) {
                    Text(if(busy) "جارٍ الإنشاء..." else "إنشاء وربط الصنف")
                }
            },
            dismissButton={TextButton(onClick={if(!busy) showInvoiceItemCreation=false}) { Text("إلغاء") }}
        )
    }

    editingProduct?.let { item ->
        CatalogProductEditDialog(item=item,busy=busy,onDismiss={editingProduct=null},onSave={payload -> updateProductDetails(item,payload)})
    }
}
