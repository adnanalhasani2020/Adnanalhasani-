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
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors

private val storageInventoryIo = Executors.newSingleThreadExecutor()
private val storageInventoryMain = Handler(Looper.getMainLooper())
private val stockableStorageTypes = setOf("internal", "receiving", "packing", "quality", "quarantine")

private data class StorageInventorySnapshot(
    val warehouses: List<CommercialMasterDataRow>,
    val locations: List<CommercialMasterDataRow>,
    val products: List<Product>,
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
    val warehouses = loadCommercialMasterData(context, "commercial_warehouses")
        .filter { it.state == "active" }
    val locations = loadCommercialMasterData(context, "commercial_storage_locations")
        .filter { it.state == "active" && it.kind in stockableStorageTypes }
    val products = loadCommercialCloudInventory(context).products
        .filter { it.cloudOfferingId.isNotBlank() }
    val balances = loadCommercialStorageInventoryBalances(context)
    return StorageInventorySnapshot(warehouses, locations, products, balances)
}

private fun storageQuantityLabel(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

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
    var warehouses by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var locations by remember { mutableStateOf<List<CommercialMasterDataRow>>(emptyList()) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var balances by remember { mutableStateOf<List<CommercialStorageInventoryBalance>>(emptyList()) }
    var selectedWarehouseId by remember { mutableStateOf("") }
    var selectedOfferingId by remember { mutableStateOf("") }
    var selectedLocationId by remember { mutableStateOf("") }
    var selectedSourceBalanceId by remember { mutableStateOf("") }
    var selectedDestinationLocationId by remember { mutableStateOf("") }
    var quantityText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("allocation") }
    var loading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingStorageInventoryAction?>(null) }

    fun applySnapshot(snapshot: StorageInventorySnapshot) {
        warehouses = snapshot.warehouses
        locations = snapshot.locations
        products = snapshot.products
        balances = snapshot.balances
        if (warehouses.none { it.id == selectedWarehouseId }) selectedWarehouseId = warehouses.firstOrNull()?.id.orEmpty()
        if (products.none { it.cloudOfferingId == selectedOfferingId }) selectedOfferingId = products.firstOrNull()?.cloudOfferingId.orEmpty()
        if (locations.none { it.id == selectedLocationId && it.warehouseId == selectedWarehouseId }) {
            selectedLocationId = locations.firstOrNull { it.warehouseId == selectedWarehouseId }?.id.orEmpty()
        }
        if (balances.none { it.id == selectedSourceBalanceId }) selectedSourceBalanceId = ""
        if (locations.none { it.id == selectedDestinationLocationId }) selectedDestinationLocationId = ""
    }

    fun reload() {
        loading = true
        error = ""
        storageInventoryIo.execute {
            val result = runCatching { loadStorageInventorySnapshot(context) }
            storageInventoryMain.post {
                loading = false
                result.onSuccess { applySnapshot(it); error = "" }
                    .onFailure { error = it.message ?: "تعذر تحميل أرصدة مواقع التخزين." }
            }
        }
    }

    fun executeAction(action: PendingStorageInventoryAction) {
        pending = action
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
                    error = write.exceptionOrNull()?.message ?: "تعذر تنفيذ الحركة."
                    notice = "لم يُحذف طلب العملية؛ أعد المحاولة بنفس البيانات والمفتاح لتجنب تكرار الحركة."
                }
                return@task
            }
            val refreshed = runCatching { loadStorageInventorySnapshot(context) }
            storageInventoryMain.post {
                saving = false
                pending = null
                quantityText = ""
                if (refreshed.isSuccess) {
                    applySnapshot(refreshed.getOrThrow())
                    error = ""
                    notice = "تم تأكيد الحركة من الخادم وتحديث أرصدة مواقع التخزين."
                } else {
                    error = refreshed.exceptionOrNull()?.message ?: "تعذر تحديث القائمة."
                    notice = "أكد الخادم تنفيذ الحركة. حدّث القائمة لاحقًا؛ لا تعِد إرسال العملية."
                }
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    val selectedProduct = products.firstOrNull { it.cloudOfferingId == selectedOfferingId }
    val selectedSource = balances.firstOrNull { it.id == selectedSourceBalanceId }
    val selectedWarehouseLocations = locations.filter { it.warehouseId == selectedWarehouseId }
    val allocatedForProduct = balances.filter { it.offeringId == selectedOfferingId }
        .fold(BigDecimal.ZERO) { total, row -> total.add(row.quantityOnHand) }
    val unallocatedAvailable = (selectedProduct?.stockPrecise ?: BigDecimal.ZERO).subtract(allocatedForProduct)
    val transferSources = balances.filter {
        it.warehouseId == selectedWarehouseId && it.offeringId == selectedOfferingId && it.quantityOnHand.signum() > 0
    }
    val transferDestinations = selectedWarehouseLocations.filter { it.id != selectedSource?.storageLocationId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مخزون مواقع التخزين", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = pending == null && !saving) {
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
                pending == null && !saving
            ) {
                selectedWarehouseId = it
                selectedLocationId = ""
                selectedSourceBalanceId = ""
                selectedDestinationLocationId = ""
            }
            StorageInventoryDropdown(
                "الصنف",
                selectedOfferingId,
                products.map { it.cloudOfferingId to "${it.name} · ${it.stockPrecise.stripTrailingZeros().toPlainString()} ${it.unitCode}" },
                pending == null && !saving
            ) {
                selectedOfferingId = it
                selectedSourceBalanceId = ""
            }
            if (mode == "allocation") {
                Card {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("المتاح غير الموزع", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${storageQuantityLabel(unallocatedAvailable.max(BigDecimal.ZERO))} ${selectedProduct?.unitCode.orEmpty()}",
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
                    pending == null && !saving
                ) { selectedLocationId = it }
            } else {
                StorageInventoryDropdown(
                    "موقع المصدر",
                    selectedSourceBalanceId,
                    transferSources.map { it.id to "${it.storageLocationCode} — ${it.storageLocationName}: ${storageQuantityLabel(it.quantityOnHand)}" },
                    pending == null && !saving
                ) { selectedSourceBalanceId = it; selectedDestinationLocationId = "" }
                StorageInventoryDropdown(
                    "موقع الوجهة",
                    selectedDestinationLocationId,
                    transferDestinations.map { it.id to "${it.code} — ${it.label}" },
                    pending == null && !saving
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
                    "المتاح قبل التوزيع: ${storageQuantityLabel(unallocatedAvailable.max(BigDecimal.ZERO))} ${selectedProduct.unitCode}",
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
                enabled = !loading && !saving && (pending != null || quantityText.isNotBlank()),
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
