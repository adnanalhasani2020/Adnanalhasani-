package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import java.util.concurrent.Executors

private data class PurchaseDraftLine(
    val offering: CommercialPurchaseOfferingOption,
    val supplierId: String,
    val quantity: String,
    val unitCostMinor: Long
)

private val purchaseDraftExecutor = Executors.newSingleThreadExecutor()
private val purchaseDraftMain = Handler(Looper.getMainLooper())

@Composable
internal fun MerchantPurchaseRequestScreen(context: Context, onBack: () -> Unit) {
    var options by remember { mutableStateOf<CommercialPurchaseOrderOptions?>(null) }
    var lines by remember { mutableStateOf<List<PurchaseDraftLine>>(emptyList()) }
    var selectedOffering by remember { mutableStateOf<CommercialPurchaseOfferingOption?>(null) }
    var selectedSupplier by remember { mutableStateOf("") }
    var selectedLocation by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var supplierMenu by remember { mutableStateOf(false) }
    var locationMenu by remember { mutableStateOf(false) }
    var offeringMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        purchaseDraftExecutor.execute {
            val result = runCatching { loadCommercialCloudPurchaseOrderOptions(context) }
            purchaseDraftMain.post {
                loading = false
                result.onSuccess { value ->
                    options = value
                    selectedSupplier = value.suppliers.firstOrNull()?.id.orEmpty()
                    selectedLocation = value.locations.firstOrNull()?.id.orEmpty()
                }.onFailure { error = it.message ?: "تعذر تحميل كتالوج الأصناف والموردين." }
            }
        }
    }

    val data = options
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("رجوع") }
            Text("إنشاء طلب شراء", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text("اختر الصنف والمورد والكمية. يمكنك إضافة أصناف لموردين مختلفين؛ سيُنشأ أمر مستقل لكل مورد.", fontSize = 12.sp)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        Box {
            OutlinedButton(onClick = { offeringMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                Text(selectedOffering?.name ?: "اختر صنفًا من الكتالوج")
            }
            DropdownMenu(expanded = offeringMenu, onDismissRequest = { offeringMenu = false }) {
                data?.offerings?.forEach { item ->
                    DropdownMenuItem(text = { Text(item.name) }, onClick = { selectedOffering = item; offeringMenu = false })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { supplierMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = data?.suppliers?.isNotEmpty() == true) {
                Text(data?.suppliers?.firstOrNull { it.id == selectedSupplier }?.name ?: "اختر المورد")
            }
            DropdownMenu(expanded = supplierMenu, onDismissRequest = { supplierMenu = false }) {
                data?.suppliers?.forEach { supplier ->
                    DropdownMenuItem(text = { Text(supplier.name) }, onClick = { selectedSupplier = supplier.id; supplierMenu = false })
                }
            }
        }
        OutlinedTextField(value = quantity, onValueChange = { quantity = it.filter(Char::isDigit).take(9) },
            label = { Text("الكمية المطلوبة (عدد صحيح)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && selectedOffering != null && selectedSupplier.isNotBlank() &&
            quantity.toLongOrNull()?.let { it > 0 } == true, onClick = {
            val offering = selectedOffering ?: return@Button
            val newLine = PurchaseDraftLine(offering, selectedSupplier, quantity, 0L)
            lines = lines.filterNot { it.offering.id == offering.id && it.supplierId == selectedSupplier } + newLine
            selectedOffering = null
            quantity = "1"
        }, modifier = Modifier.fillMaxWidth()) { Text("إضافة الصنف إلى طلب الشراء") }

        Text("الأصناف المختارة (${lines.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lines, key = { it.offering.id + it.supplierId }) { line ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.offering.name, style = MaterialTheme.typography.titleSmall)
                            Text("المورد: ${data?.suppliers?.firstOrNull { it.id == line.supplierId }?.name ?: "—"}")
                            Text("الكمية: ${line.quantity} • التكلفة تُحدد عند تحرير أمر الشراء", fontSize = 12.sp)
                        }
                        TextButton(onClick = { lines = lines.filterNot { it.offering.id == line.offering.id && it.supplierId == line.supplierId } }) { Text("حذف") }
                    }
                }
            }
        }
        Box {
            OutlinedButton(onClick = { locationMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = data?.locations?.isNotEmpty() == true) {
                Text(data?.locations?.firstOrNull { it.id == selectedLocation }?.name ?: "اختر مستودع الاستلام")
            }
            DropdownMenu(expanded = locationMenu, onDismissRequest = { locationMenu = false }) {
                data?.locations?.forEach { location ->
                    DropdownMenuItem(text = { Text(location.name) }, onClick = { selectedLocation = location.id; locationMenu = false })
                }
            }
        }
        Button(enabled = !busy && lines.isNotEmpty() && selectedLocation.isNotBlank(), onClick = {
            busy = true; error = ""; notice = "جارٍ إنشاء أوامر الشراء..."
            val grouped = lines.groupBy { it.supplierId }
            purchaseDraftExecutor.execute {
                val result = runCatching {
                    grouped.map { (supplierId, supplierLines) ->
                        val currencies = supplierLines.map { it.offering.currencyCode }.distinct()
                        require(currencies.size == 1) { "أصناف المورد الواحد يجب أن تكون بعملة واحدة." }
                        createCommercialCloudPurchaseOrder(context, supplierId,
                            "PO-" + UUID.randomUUID().toString().take(8).uppercase(), currencies.first(),
                            selectedLocation, supplierLines.map { line ->
                                CommercialPurchaseOrderLineInput(line.offering.id, line.offering.name, line.quantity, line.unitCostMinor)
                            }, UUID.randomUUID().toString(), "طلب شراء أنشئ من كتالوج الأصناف.")
                    }
                }
                purchaseDraftMain.post {
                    busy = false
                    result.onSuccess { created ->
                        notice = "تم إنشاء ${created.size} أمر شراء مسودة حسب المورد. أدخل الأسعار وشروط الدفع من شاشة أوامر الشراء."
                        lines = emptyList()
                    }.onFailure { error = it.message ?: "تعذر إنشاء طلب الشراء." }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "جارٍ الحفظ..." else "إنشاء طلب الشراء") }
    }
}
