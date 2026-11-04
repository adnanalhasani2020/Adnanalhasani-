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
