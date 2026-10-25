package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID
import java.util.concurrent.Executors

private val PurchaseOrderWorker = Executors.newSingleThreadExecutor()
private val PurchaseOrderMain = Handler(Looper.getMainLooper())

private class PurchaseOrderDraftLine {
    var offeringId by mutableStateOf("")
    var description by mutableStateOf("")
    var quantity by mutableStateOf("1")
    var unitCostMinor by mutableStateOf("0")
    var offeringMenuExpanded by mutableStateOf(false)
}

@Composable
internal fun PurchaseOrderEntryDialog(
    context: Context,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var options by remember { mutableStateOf<CommercialPurchaseOrderOptions?>(null) }
    var loadingOptions by remember { mutableStateOf(true) }
    var supplierId by remember { mutableStateOf("") }
    var locationId by remember { mutableStateOf("") }
    var orderNumber by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var supplierMenuExpanded by remember { mutableStateOf(false) }
    var locationMenuExpanded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val lines = remember { mutableStateListOf(PurchaseOrderDraftLine()) }
    val idempotencyKey = remember { UUID.randomUUID().toString() }

    LaunchedEffect(Unit) {
        PurchaseOrderWorker.execute {
            val result = runCatching { loadCommercialCloudPurchaseOrderOptions(context) }
            PurchaseOrderMain.post {
                result.onSuccess { options = it }.onFailure { error = it.message ?: "تعذر تحميل خيارات أمر الشراء." }
                loadingOptions = false
            }
        }
    }

    val available = options
    val selectedCurrency = lines.firstOrNull { it.offeringId.isNotBlank() }?.let { line ->
        available?.offerings?.firstOrNull { it.id == line.offeringId }?.currencyCode
    }.orEmpty()
    val currenciesMatch = lines.filter { it.offeringId.isNotBlank() }.all { line ->
        available?.offerings?.firstOrNull { it.id == line.offeringId }?.currencyCode == selectedCurrency
    }

    AlertDialog(
        onDismissRequest = { if (!saving && !attempted) onDismiss() },
        title = { Text("إنشاء أمر شراء") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loadingOptions) {
                    Text("جارٍ تحميل الموردين والمواقع وعناصر المخزون...")
                } else if (available == null) {
                    Text("تعذر تحميل خيارات أمر الشراء.")
                } else if (available.suppliers.isEmpty() || available.locations.isEmpty() || available.offerings.isEmpty()) {
                    Text("يلزم وجود مورد نشط وموقع نشط وعنصر مخزون نشط ضمن المؤسسة قبل إنشاء أمر شراء.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            OutlinedTextField(
                                value = orderNumber, onValueChange = { if (!attempted) orderNumber = it },
                                label = { Text("رقم أمر الشراء") }, modifier = Modifier.fillMaxWidth(),
                                singleLine = true, enabled = !attempted && !saving
                            )
                            Column {
                                TextButton(enabled = !attempted && !saving, onClick = { supplierMenuExpanded = true }) {
                                    Text("المورد: ${available.suppliers.firstOrNull { it.id == supplierId }?.name ?: "اختر المورد"}")
                                }
                                DropdownMenu(expanded = supplierMenuExpanded, onDismissRequest = { supplierMenuExpanded = false }) {
                                    available.suppliers.forEach { supplier ->
                                        DropdownMenuItem(text = { Text(supplier.name) }, onClick = {
                                            supplierId = supplier.id
                                            supplierMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            Column {
                                TextButton(enabled = !attempted && !saving, onClick = { locationMenuExpanded = true }) {
                                    Text("موقع الاستلام: ${available.locations.firstOrNull { it.id == locationId }?.name ?: "اختر الموقع"}")
                                }
                                DropdownMenu(expanded = locationMenuExpanded, onDismissRequest = { locationMenuExpanded = false }) {
                                    available.locations.forEach { location ->
                                        DropdownMenuItem(text = { Text(location.name) }, onClick = {
                                            locationId = location.id
                                            locationMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = notes, onValueChange = { if (!attempted) notes = it },
                                label = { Text("ملاحظات (اختياري)") }, modifier = Modifier.fillMaxWidth(),
                                enabled = !attempted && !saving
                            )
                            Text(if (selectedCurrency.isBlank()) "العملة ستُحدد من أول عنصر مخزون تختاره." else "عملة الأمر: $selectedCurrency")
                        }
                        itemsIndexed(lines) { index, line ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("بند ${index + 1}")
                                TextButton(enabled = !attempted && !saving, onClick = { line.offeringMenuExpanded = true }) {
                                    Text("عنصر المخزون: ${available.offerings.firstOrNull { it.id == line.offeringId }?.name ?: "اختر العنصر"}")
                                }
                                DropdownMenu(expanded = line.offeringMenuExpanded, onDismissRequest = { line.offeringMenuExpanded = false }) {
                                    available.offerings.filter { selectedCurrency.isBlank() || it.currencyCode == selectedCurrency }.forEach { offering ->
                                        DropdownMenuItem(text = { Text("${offering.name} · ${offering.currencyCode}") }, onClick = {
                                            line.offeringId = offering.id
                                            line.description = offering.name
                                            line.offeringMenuExpanded = false
                                        })
                                    }
                                }
                                OutlinedTextField(
                                    value = line.description, onValueChange = { if (!attempted) line.description = it },
                                    label = { Text("وصف البند") }, modifier = Modifier.fillMaxWidth(),
                                    singleLine = true, enabled = !attempted && !saving
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = line.quantity, onValueChange = { if (!attempted) line.quantity = it },
                                        label = { Text("الكمية") }, modifier = Modifier.weight(1f),
                                        singleLine = true, enabled = !attempted && !saving
                                    )
                                    OutlinedTextField(
                                        value = line.unitCostMinor, onValueChange = { if (!attempted) line.unitCostMinor = it },
                                        label = { Text("تكلفة الوحدة (صغرى)") }, modifier = Modifier.weight(1f),
                                        singleLine = true, enabled = !attempted && !saving
                                    )
                                }
                                if (lines.size > 1) TextButton(enabled = !attempted && !saving, onClick = { lines.remove(line) }) { Text("حذف البند") }
                            }
                        }
                        item {
                            TextButton(enabled = !attempted && !saving, onClick = { lines.add(PurchaseOrderDraftLine()) }) { Text("إضافة بند آخر") }
                            if (error.isNotBlank()) Text(error)
                            if (attempted && !saving) Text("تم إرسال الطلب؛ أعد المحاولة بنفس البيانات والمفتاح لتفادي إنشاء أمر مكرر.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !loadingOptions && !saving && available != null && (attempted || (available.suppliers.isNotEmpty() && available.locations.isNotEmpty() && available.offerings.isNotEmpty())), onClick = {
                    if (!attempted) {
                        val validLines = lines.all { line ->
                            val qty = line.quantity.toBigDecimalOrNull()
                            line.offeringId.isNotBlank() && line.description.isNotBlank() && qty != null &&
                                qty > java.math.BigDecimal.ZERO && qty.stripTrailingZeros().scale() <= 0 &&
                                (line.unitCostMinor.toLongOrNull() ?: -1L) >= 0L
                        }
                        when {
                            orderNumber.isBlank() -> error = "أدخل رقم أمر الشراء."
                            supplierId.isBlank() -> error = "اختر المورد."
                            locationId.isBlank() -> error = "اختر موقع الاستلام."
                            !validLines -> error = "تحقق من عناصر المخزون والكميات الصحيحة وتكلفة الوحدة."
                            !currenciesMatch -> error = "يجب أن تكون جميع البنود بعملة واحدة."
                            else -> { attempted = true; error = "" }
                        }
                    }
                    if (attempted) error = ""
                    if (attempted && error.isBlank()) {
                        saving = true
                        val selectedLines = lines.map { line ->
                            CommercialPurchaseOrderLineInput(line.offeringId, line.description, line.quantity, line.unitCostMinor.toLong())
                        }
                        val currency = selectedCurrency
                        PurchaseOrderWorker.execute {
                            val result = runCatching {
                                createCommercialCloudPurchaseOrder(context, supplierId, orderNumber, currency, locationId, selectedLines, idempotencyKey, notes)
                            }
                            PurchaseOrderMain.post {
                                result.onSuccess { onSaved() }.onFailure {
                                    saving = false
                                    error = it.message ?: "تعذر إنشاء أمر الشراء."
                                }
                            }
                        }
                    }
                }) { Text(if (saving) "جارٍ الإنشاء..." else if (attempted) "إعادة المحاولة" else "إنشاء مسودة") }
            }
        },
        dismissButton = {
            TextButton(enabled = !saving && !attempted, onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
