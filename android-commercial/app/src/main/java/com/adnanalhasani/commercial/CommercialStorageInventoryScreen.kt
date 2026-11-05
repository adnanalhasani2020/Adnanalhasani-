package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors

private val storageInventoryIo = Executors.newSingleThreadExecutor()
private val storageInventoryMain = Handler(Looper.getMainLooper())
private val stockableStorageTypes = setOf("internal", "receiving", "packing", "quality", "quarantine")

private data class StorageInventorySnapshot(
    val warehouses: List<CommercialMasterDataRow>,
    val locations: List<CommercialMasterDataRow>,
    val products: List<CommercialStorageAllocationProduct>,
    val balances: List<CommercialStorageInventoryBalance>
)

private data class PendingStorageInventoryAction(
    val mode: String,
    val warehouseId: String,
    val locationId: String,
    val sourceLocationId: String,
    val destinationLocationId: String,
    val offeringId: String,
    val quantity: BigDecimal,
    val idempotencyKey: String
)

private fun loadStorageInventorySnapshot(context: Context): StorageInventorySnapshot {
    val branchId = context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
        .getString("location_id", null).orEmpty()
    require(branchId.isNotBlank()) { "موقع المؤسسة غير محفوظ." }
    val warehouses = loadCommercialMasterData(context, "commercial_warehouses")
        .filter { it.state == "active" && it.branchLocationId == branchId }
    val warehouseIds = warehouses.mapTo(mutableSetOf()) { it.id }
    val locations = loadCommercialMasterData(context, "commercial_storage_locations")
        .filter { it.state == "active" && it.kind in stockableStorageTypes && it.warehouseId in warehouseIds }
    val products = loadCommercialStorageAllocationProducts(context)
    val balances = loadCommercialStorageInventoryBalances(context)
    return StorageInventorySnapshot(warehouses, locations, products, balances)
}

private fun storageQuantityLabel(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

private fun isDefinitiveStorageInventoryRejection(message: String): Boolean {
    val markers = listOf(
        "inventory_management_permission_required",
        "inventory_storage_scope_mismatch",
        "inventory_storage_allocation_exceeds_unallocated_stock",
        "inventory_storage_insufficient",
        "invalid_inventory_storage_allocation",
        "invalid_inventory_storage_transfer",
        "inventory_balance_invalid",
        "inventory_balance_not_found",
        "idempotency_key_reused_with_different_request",
        "لم يُسمح بتنفيذ العملية"
    )
    return markers.any { message.contains(it, ignoreCase = true) }
}

private fun loadPendingStorageInventoryAction(context: Context): PendingStorageInventoryAction? {
    val raw = context.getSharedPreferences("commercial_storage_inventory_pending_v1", Context.MODE_PRIVATE)
        .getString("pending_action", null) ?: return null
    return runCatching {
        val json = JSONObject(raw)
        PendingStorageInventoryAction(
            mode = json.getString("mode").also { require(it == "allocation" || it == "transfer") },
            warehouseId = json.getString("warehouse_id").also { require(it.isNotBlank()) },
            locationId = json.optString("location_id"),
            sourceLocationId = json.optString("source_location_id"),
            destinationLocationId = json.optString("destination_location_id"),
            offeringId = json.getString("offering_id"),
            quantity = BigDecimal(json.getString("quantity")).also { require(it.signum() > 0) },
            idempotencyKey = json.getString("idempotency_key").also { require(it.length in 8..200) }
        )
    }.getOrNull()
}

private fun persistPendingStorageInventoryAction(context: Context, action: PendingStorageInventoryAction?): Boolean {
    val editor = context.getSharedPreferences("commercial_storage_inventory_pending_v1", Context.MODE_PRIVATE).edit()
    if (action == null) {
        editor.remove("pending_action")
    } else {
        editor.putString("pending_action", JSONObject()
            .put("mode", action.mode)
            .put("warehouse_id", action.warehouseId)
            .put("location_id", action.locationId)
            .put("source_location_id", action.sourceLocationId)
            .put("destination_location_id", action.destinationLocationId)
            .put("offering_id", action.offeringId)
            .put("quantity", action.quantity.toPlainString())
            .put("idempotency_key", action.idempotencyKey)
            .toString())
    }
    return editor.commit()
}

@Composable
private fun StorageInventoryDropdown(
    title: String,
    selectedId: String,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedId }?.second ?: "اختر"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled && options.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(selectedLabel, maxLines = 2) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.second) },
                        onClick = { onSelected(option.first); expanded = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialStorageInventoryScreen(context: Context, onBack: () -> Unit) {
    val restoredPending = remember { loadPendingStorageInventoryAction(context) }
    var warehouses by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var locations by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var products by remember { mutableStateOf<List<CommercialStorageAllocationProduct>>(emptyList()) }
    var balances by remember { mutableStateOf<List<CommercialStorageInventoryBalance>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf(restoredPending?.warehouseId.orEmpty()) }
    var selectedOfferingId by remember { mutableStateOf(restoredPending?.offeringId.orEmpty()) }
    var selectedLocationId by remember { mutableStateOf(restoredPending?.locationId.orEmpty()) }
    var selectedSourceBalanceId by remember { mutableStateOf("") }
    var selectedDestinationLocationId by remember { mutableStateOf(restoredPending?.destinationLocationId.orEmpty()) }
    var quantityText by remember { mutableStateOf(restoredPending?.quantity?.let { storageQuantityLabel(it) }.orEmpty()) }
    var mode by remember { mutableStateOf(restoredPending?.mode ?: "allocation") }
    var loading by remember { mutableStateOf(false) }
    var schemaReady by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(restoredPending) }

    fun applySnapshot(snapshot: StorageInventorySnapshot) {
        warehouses = snapshot.warehouses
        locations = snapshot.locations
        products = snapshot.products
        balances = snapshot.balances
        if (warehouses.none { it.id == selectedWarehouseId }) selectedWarehouseId = warehouses.firstOrNull()?.id.orEmpty()
        if (products.none { it.offeringId == selectedOfferingId }) selectedOfferingId = products.firstOrNull()?.offeringId.orEmpty()
        if (locations.none { it.id == selectedLocationId && it.warehouseId == selectedWarehouseId }) {
            selectedLocationId = locations.firstOrNull { it.warehouseId == selectedWarehouseId }?.id.orEmpty()
        }
        if (pending != null && pending?.mode == "transfer") {
            selectedSourceBalanceId = balances.firstOrNull {
                it.storageLocationId == pending?.sourceLocationId && it.offeringId == pending?.offeringId
            }?.id.orEmpty()
            selectedDestinationLocationId = pending?.destinationLocationId.orEmpty()
        } else {
            if (balances.none { it.id == selectedSourceBalanceId }) selectedSourceBalanceId = ""
            if (locations.none { it.id == selectedDestinationLocationId }) selectedDestinationLocationId = ""
        }
    }

    fun reload() {
        loading = true
        error = ""
        storageInventoryIo.execute {
            val result = runCatching { loadStorageInventorySnapshot(context) }
            storageInventoryMain.post {
                loading = false
                result.onSuccess {
                    applySnapshot(it)
                    schemaReady = true
                    error = ""
                }.onFailure {
                    schemaReady = false
                    val detail = it.message.orEmpty()
                    error = if (
                        detail.contains("commercial_inventory_storage_balances", ignoreCase = true) ||
                        detail.contains("PGRST205", ignoreCase = true)
                    ) {
                        "ميزة أرصدة مواقع التخزين غير منشورة على قاعدة البيانات الحالية. لم يتم تغيير أي رصيد؛ يلزم نشر الترحيل واختباره أولًا."
                    } else detail.ifBlank { "تعذر تحميل أرصدة مواقع التخزين." }
                }
            }
        }
    }

    fun executeAction(action: PendingStorageInventoryAction) {
        pending = action
        if (!persistPendingStorageInventoryAction(context, action)) {
            pending = null
            error = "تعذر حفظ طلب المخزون محليًا؛ لم نرسل العملية إلى الخادم."
            notice = ""
            return
        }
        saving = true
        error = ""
        notice = ""
        storageInventoryIo.execute task@{
            val write = runCatching {
                if (action.mode == "allocation") {
                    allocateCommercialInventoryToStorageLocation(
                        context, action.warehouseId, action.locationId, action.offeringId,
                        action.quantity, action.idempotencyKey
                    )
                } else {
                    transferCommercialInventoryBetweenStorageLocations(
                        context, action.warehouseId, action.sourceLocationId,
                        action.destinationLocationId, action.offeringId,
                        action.quantity, action.idempotencyKey
                    )
                }
            }
            if (write.isFailure) {
                storageInventoryMain.post {
                    saving = false
                    val detail = write.exceptionOrNull()?.message ?: "تعذر تنفيذ الحركة."
                    error = detail
                    val schemaMissing = detail.contains("PGRST202", ignoreCase = true) ||
                        detail.contains("دالة قاعدة البيانات المطلوبة غير منشورة", ignoreCase = true)
                    if (schemaMissing || isDefinitiveStorageInventoryRejection(detail)) {
                        if (persistPendingStorageInventoryAction(context, null)) {
                            pending = null
                        } else {
                            notice = "رفض الخادم الطلب، لكن تعذر مسح علامة المحاولة المحلية. أعد فتح الشاشة للتحقق قبل أي عملية جديدة."
                        }
                        if (schemaMissing) schemaReady = false
                        notice = if (schemaMissing) {
                            "الوظيفة غير متاحة على قاعدة البيانات الحالية. لم تُنفذ الحركة؛ يلزم نشر الترحيل واختباره."
                        } else {
                            "رفض الخادم الطلب ولم يُرحّل حركة مخزون. يمكنك تصحيح البيانات أو مراجعة صلاحياتك ثم المحاولة."
                        }
                    } else {
                        notice = "لم يُحذف طلب العملية؛ أعد المحاولة بنفس البيانات والمفتاح لتجنب تكرار الحركة."
                    }
                }
                return@task
            }
            val refreshed = runCatching { loadStorageInventorySnapshot(context) }
            storageInventoryMain.post {
                saving = false
                val pendingCleared = persistPendingStorageInventoryAction(context, null)
                if (pendingCleared) pending = null
                quantityText = ""
                if (refreshed.isSuccess) {
                    applySnapshot(refreshed.getOrThrow())
                    error = ""
                    notice = if (pendingCleared) {
                        "تم تأكيد الحركة من الخادم وتحديث أرصدة مواقع التخزين."
                    } else {
                        "أكد الخادم الحركة، لكن تعذر مسح علامة المحاولة المحلية. أعد فتح الشاشة ثم حدّث الأرصدة قبل أي عملية جديدة."
                    }
                } else {
                    error = refreshed.exceptionOrNull()?.message ?: "تعذر تحديث القائمة."
                    notice = if (pendingCleared) {
                        "أكد الخادم تنفيذ الحركة. حدّث القائمة لاحقًا؛ لا تعِد إرسال العملية."
                    } else {
                        "أكد الخادم الحركة، لكن تعذر مسح علامة المحاولة المحلية. أعد فتح الشاشة ثم حدّث الأرصدة قبل أي عملية جديدة."
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val selectedProduct = products.firstOrNull { it.offeringId == selectedOfferingId }
    val selectedSource = balances.firstOrNull { it.id == selectedSourceBalanceId }
    val selectedWarehouseLocations = locations.filter { it.warehouseId == selectedWarehouseId }
    val allocatedForProduct = balances.filter { it.offeringId == selectedOfferingId }
        .fold(BigDecimal.ZERO) { total, row -> total.add(row.quantityOnHand) }
    val unallocatedAvailable = (selectedProduct?.availableQuantity ?: BigDecimal.ZERO).subtract(allocatedForProduct)
    val transferSources = balances.filter {
        it.warehouseId == selectedWarehouseId && it.offeringId == selectedOfferingId && it.quantityOnHand.signum() > 0
    }
    val transferDestinations = selectedWarehouseLocations.filter { it.id != selectedSource?.storageLocationId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مخزون مواقع التخزين", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = (pending == null || !schemaReady) && !saving) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !loading && !saving && pending == null) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "توزيع الكمية المتاحة على موقع تخزين، أو نقل كمية موزعة بين موقعين داخل المستودع نفسه. الرصيد الإجمالي للفرع لا يتغير.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { mode = "allocation"; selectedSourceBalanceId = ""; selectedDestinationLocationId = "" },
                    enabled = pending == null && !saving,
                    modifier = Modifier.weight(1f)
                ) { Text("توزيع للموقع") }
                OutlinedButton(
                    onClick = { mode = "transfer"; selectedLocationId = ""; selectedSourceBalanceId = ""; selectedDestinationLocationId = "" },
                    enabled = pending == null && !saving,
                    modifier = Modifier.weight(1f)
                ) { Text("نقل بين المواقع") }
            }
            StorageInventoryDropdown(
                "المستودع",
                selectedWarehouseId,
                warehouses.map { it.id to "${it.code} — ${it.label}" },
                schemaReady && pending == null && !saving
            ) {
                selectedWarehouseId = it
                selectedLocationId = ""
                selectedSourceBalanceId = ""
                selectedDestinationLocationId = ""
            }
            StorageInventoryDropdown(
                "الصنف",
                selectedOfferingId,
                products.map { it.offeringId to "${it.productName} · ${it.availableQuantity.stripTrailingZeros().toPlainString()} ${it.unitName}" },
                schemaReady && pending == null && !saving
            ) {
                selectedOfferingId = it
                selectedSourceBalanceId = ""
            }
            if (mode == "allocation") {
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("المتاح غير الموزع", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${storageQuantityLabel(unallocatedAvailable.max(BigDecimal.ZERO))} ${selectedProduct?.unitName.orEmpty()}",
                            color = Color(0xFF167D63),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                StorageInventoryDropdown(
                    "موقع التخزين المستهدف",
                    selectedLocationId,
                    selectedWarehouseLocations.map { it.id to "${it.code} — ${it.label}" },
                    schemaReady && pending == null && !saving
                ) { selectedLocationId = it }
            } else {
                StorageInventoryDropdown(
                    "موقع المصدر",
                    selectedSourceBalanceId,
                    transferSources.map { it.id to "${it.storageLocationCode} — ${it.storageLocationName}: ${storageQuantityLabel(it.quantityOnHand)}" },
                    schemaReady && pending == null && !saving
                ) { selectedSourceBalanceId = it; selectedDestinationLocationId = "" }
                StorageInventoryDropdown(
                    "موقع الوجهة",
                    selectedDestinationLocationId,
                    transferDestinations.map { it.id to "${it.code} — ${it.label}" },
                    schemaReady && pending == null && !saving
                ) { selectedDestinationLocationId = it }
            }
            OutlinedTextField(
                value = quantityText,
                onValueChange = { quantityText = it },
                enabled = pending == null && !saving,
                label = { Text("الكمية") },
                placeholder = { Text("مثال: 2.5") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            if (mode == "allocation" && selectedProduct != null) {
                Text(
                    "المتاح قبل التوزيع: ${storageQuantityLabel(unallocatedAvailable.max(BigDecimal.ZERO))} ${selectedProduct.unitName}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            if (pending != null) {
                Text(
                    "هناك طلب سابق لم يتأكد الرد عليه. ستُعاد المحاولة بالمفتاح نفسه وبنفس البيانات.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
            Button(
                onClick = {
                    val action = pending ?: runCatching {
                        val quantity = BigDecimal(quantityText.trim().replace(',', '.'))
                        require(quantity.signum() > 0) { "أدخل كمية أكبر من صفر." }
                        if (mode == "allocation") {
                            require(selectedWarehouseId.isNotBlank() && selectedLocationId.isNotBlank() && selectedOfferingId.isNotBlank()) {
                                "اختر المستودع والصنف وموقع التخزين."
                            }
                            require(unallocatedAvailable.signum() > 0 && quantity <= unallocatedAvailable) {
                                "الكمية تتجاوز المتاح غير الموزع. حدّث الأرصدة قبل المحاولة."
                            }
                            PendingStorageInventoryAction(
                                mode, selectedWarehouseId, selectedLocationId, "", "",
                                selectedOfferingId, quantity, UUID.randomUUID().toString()
                            )
                        } else {
                            val source = requireNotNull(selectedSource) { "اختر رصيد موقع المصدر." }
                            require(selectedDestinationLocationId.isNotBlank()) { "اختر موقع الوجهة." }
                            require(quantity <= source.quantityOnHand) { "الكمية تتجاوز رصيد موقع المصدر." }
                            PendingStorageInventoryAction(
                                mode, selectedWarehouseId, "", source.storageLocationId,
                                selectedDestinationLocationId, source.offeringId, quantity,
                                UUID.randomUUID().toString()
                            )
                        }
                    }.getOrElse {
                        error = it.message ?: "تحقق من بيانات الحركة."
                        null
                    }
                    if (action != null) executeAction(action)
                },
                enabled = schemaReady && !loading && !saving && (pending != null || quantityText.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (pending != null) "إعادة المحاولة بنفس الطلب" else if (mode == "allocation") "تأكيد التوزيع" else "تأكيد النقل")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("الأرصدة الموزعة", fontWeight = FontWeight.Bold)
            if (!loading && balances.isEmpty() && error.isBlank()) {
                Text("لا توجد كميات موزعة على مواقع التخزين بعد.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(balances, key = { it.id }) { row ->
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(row.productName, fontWeight = FontWeight.SemiBold)
                            Text("${row.storageLocationCode} — ${row.storageLocationName}")
                            Text(
                                "${storageQuantityLabel(row.quantityOnHand)} ${row.unitName}",
                                color = Color(0xFF167D63),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
