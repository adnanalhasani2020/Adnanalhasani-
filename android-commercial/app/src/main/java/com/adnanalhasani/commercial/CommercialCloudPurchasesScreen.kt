package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.Executors

private val CloudPurchasesInk = Color(0xFF183B35)
private val CloudPurchasesGreen = Color(0xFF167D63)
private val CloudPurchasesMuted = Color(0xFF71817B)
private val CloudPurchasesWorker = Executors.newSingleThreadExecutor()
private val CloudPurchasesMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudPurchasesScreen(context: Context, onBack: () -> Unit) {
    var orders by remember { mutableStateOf<List<CommercialCloudPurchaseDocument>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var invoiceOrder by remember { mutableStateOf<CommercialCloudPurchaseDocument?>(null) }
    var receiptOrder by remember { mutableStateOf<CommercialCloudPurchaseDocument?>(null) }
    var mappingOrder by remember { mutableStateOf<CommercialCloudPurchaseDocument?>(null) }
    var resolvingReceipt by remember { mutableStateOf(false) }
    var createOrderOpen by remember { mutableStateOf(false) }
    var paymentInvoice by remember { mutableStateOf<CommercialCloudSupplierInvoiceSummary?>(null) }
    var transitionConfirmation by remember { mutableStateOf<Pair<CommercialCloudPurchaseDocument, String>?>(null) }

    LaunchedEffect(reloadToken) {
        loading = true
        error = ""
        CloudPurchasesWorker.execute {
            val result = runCatching { loadCommercialCloudPurchaseDocuments(context) }
            CloudPurchasesMain.post {
                result.onSuccess { orders = it }
                    .onFailure { error = it.message ?: "تعذر تحميل أوامر الشراء." }
                loading = false
            }
        }
    }

    val filtered = orders.filter { order ->
        order.orderNumber.contains(search, true) ||
            order.supplierName.contains(search, true) ||
            order.status.contains(search, true) ||
            order.lines.any { it.description.contains(search, true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("أوامر الشراء والاستلام", color = CloudPurchasesInk, fontWeight = FontWeight.Bold)
                        Text("تفاصيل الموردين والبنود · اعتماد الحالة والاستلام يحدّثان السجلات التشغيلية الفعلية", color = CloudPurchasesMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = { IconButton(onClick = { reloadToken++ }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F7F6))
            )
        },
        containerColor = Color(0xFFF5F7F6)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("ابحث برقم الأمر أو المورد أو البند") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
            }
            item {
                Button(onClick = { createOrderOpen = true }, enabled = !loading) { Text("إنشاء أمر شراء جديد") }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).background(Color(0xFFE8F5EF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = CloudPurchasesGreen)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("أوامر الشراء المحمّلة", color = CloudPurchasesMuted, fontSize = 12.sp)
                            Text(orders.size.toString(), color = CloudPurchasesInk, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                        }
                        Text("النتائج: ${filtered.size}", color = CloudPurchasesMuted, fontSize = 11.sp)
                    }
                }
            }
            if (loading) item {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CloudPurchasesGreen, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ تحميل أوامر الشراء...", color = CloudPurchasesMuted)
                }
            }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0ED)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("تعذر تحميل أوامر الشراء", color = Color(0xFF9B3024), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(error, color = Color(0xFF9B3024), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { reloadToken++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && filtered.isEmpty()) item {
                Text(if (orders.isEmpty()) "لا توجد أوامر شراء متاحة لهذه المؤسسة." else "لا توجد نتائج مطابقة للبحث.", color = CloudPurchasesMuted, modifier = Modifier.padding(8.dp))
            }
            items(filtered, key = { it.id }) { order ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(17.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5EBE8))
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("أمر شراء #${order.orderNumber}", color = CloudPurchasesInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(order.supplierName, color = CloudPurchasesMuted, fontSize = 12.sp)
                            }
                            Surface(color = purchaseStatusColor(order.status).copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                                Text(purchaseStatusLabel(order.status), color = purchaseStatusColor(order.status), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("تاريخ الطلب", color = CloudPurchasesMuted, fontSize = 10.sp)
                                Text(order.orderedAt.replace('T', ' ').take(10).ifBlank { "غير محدد" }, color = CloudPurchasesInk, fontSize = 12.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("الاستلام المتوقع", color = CloudPurchasesMuted, fontSize = 10.sp)
                                Text(order.expectedAt.replace('T', ' ').take(10).ifBlank { "غير محدد" }, color = CloudPurchasesInk, fontSize = 12.sp)
                            }
                        }
                        Text("البنود: ${order.lines.size}", color = CloudPurchasesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        order.lines.forEach { line ->
                            Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 2.dp)) {
                                Text("${line.lineNumber}. ${line.description}", color = CloudPurchasesInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                if (line.sourceSellingUnitName.isNotBlank() && line.sourceSellingQuantity.isNotBlank()) {
                                    Text("وحدة فاتورة المصدر: ${line.sourceSellingQuantity} ${line.sourceSellingUnitName} • المكافئ المخزني: ${line.sourceQuantityInStockUnits}",
                                        color = CloudPurchasesMuted, fontSize = 10.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("مطلوب: ${line.quantityOrdered}", color = CloudPurchasesMuted, fontSize = 11.sp)
                                    Text("مستلم: ${line.quantityReceived.ifBlank { "0" }}", color = CloudPurchasesMuted, fontSize = 11.sp)
                                    Text("تكلفة الوحدة: ${line.unitCostMinor} ${order.currencyCode} (وحدة صغرى)", color = CloudPurchasesMuted, fontSize = 10.sp)
                                }
                            }
                        }
                        if (order.supplierInvoices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("فواتير المورد المسجلة (${order.supplierInvoices.size})", color = CloudPurchasesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                order.supplierInvoices.forEach { invoice ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFF5F7F6),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("فاتورة المورد #${invoice.invoiceNumber}", color = CloudPurchasesInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                            Text("الإجمالي: ${invoice.totalMinor} ${invoice.currencyCode} (وحدة صغرى)", color = CloudPurchasesMuted, fontSize = 11.sp)
                                            Text("الحالة: ${if (invoice.state == "recorded") "مسجلة" else invoice.state.ifBlank { "غير محددة" }}", color = CloudPurchasesMuted, fontSize = 11.sp)
                                            Text("تاريخ التسجيل: ${invoice.recordedAt.replace('T', ' ').take(16).ifBlank { "غير محدد" }}", color = CloudPurchasesMuted, fontSize = 10.sp)
                                            invoice.obligation?.let { obligation ->
                                                val outstanding = (obligation.amountDueMinor - obligation.amountPaidMinor).coerceAtLeast(0L)
                                                Text("المستحق: ${obligation.amountDueMinor} · المدفوع: ${obligation.amountPaidMinor} · المتبقي: $outstanding ${obligation.currencyCode}", color = CloudPurchasesInk, fontSize = 11.sp)
                                                if (outstanding > 0L) TextButton(onClick = { paymentInvoice = invoice }) { Text("تسجيل دفعة للمورد") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (order.notes.isNotBlank()) Text("ملاحظات: ${order.notes}", color = CloudPurchasesMuted, fontSize = 11.sp)
                        if (hasPendingCommercialPurchaseReceipt(context, order.id)) {
                            Button(enabled = !resolvingReceipt, onClick = {
                                resolvingReceipt = true
                                CloudPurchasesWorker.execute {
                                    val result = runCatching { resolvePendingCommercialPurchaseReceipt(context, order.id) }
                                    CloudPurchasesMain.post {
                                        resolvingReceipt = false
                                        result.onSuccess { found ->
                                            error = if (found) "تم العثور على استلام محفوظ في الخادم وتأكيده." else "لم يُسجل الاستلام السابق؛ يمكنك بدء استلام جديد."
                                            reloadToken++
                                        }.onFailure { error = it.message ?: "تعذر التحقق من نتيجة الاستلام." }
                                    }
                                }
                            }) { Text(if (resolvingReceipt) "جارٍ التحقق..." else "التحقق من الاستلام المعلق") }
                        }
                        if (order.status.lowercase() in setOf("draft", "submitted", "approved") && purchaseOrderHasUnmappedRemainingLines(order)) {
                            TextButton(onClick = { mappingOrder = order }) { Text("ربط عناصر المخزون للبنود القديمة") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            when (order.status.lowercase()) {
                                "draft" -> TextButton(onClick = { transitionConfirmation = order to "submit" }) { Text("إرسال للاعتماد") }
                                "submitted" -> TextButton(onClick = { transitionConfirmation = order to "approve" }) { Text("اعتماد الأمر") }
                                "approved" -> {
                                    if (!hasPendingCommercialPurchaseReceipt(context, order.id)) Button(onClick = { receiptOrder = order }, enabled = order.receiveLocationId != null && !purchaseOrderHasUnmappedRemainingLines(order)) { Text("استلام البضاعة") }
                                    TextButton(onClick = { transitionConfirmation = order to "cancel" }) { Text("إلغاء الأمر") }
                                }
                                "partially_received" -> {
                                    if (!hasPendingCommercialPurchaseReceipt(context, order.id)) Button(onClick = { receiptOrder = order }, enabled = order.receiveLocationId != null && !purchaseOrderHasUnmappedRemainingLines(order)) { Text("استلام الباقي") }
                                    TextButton(onClick = { transitionConfirmation = order to "close" }) { Text("إغلاق بالنقص") }
                                }
                                "received" -> TextButton(onClick = { transitionConfirmation = order to "close" }) { Text("إغلاق الأمر") }
                            }
                        }
                        Button(onClick = { invoiceOrder = order }, enabled = order.lines.isNotEmpty() && order.status.lowercase() in setOf("approved", "partially_received", "received", "closed")) { Text("تسجيل فاتورة المورد") }
                        if (invoiceOrder?.id == order.id) {
                            SupplierInvoiceEntryDialog(
                                context = context,
                                order = order,
                                onDismiss = { invoiceOrder = null },
                                onSaved = { invoiceOrder = null; reloadToken++ }
                            )
                        }
                        Text("مرجع: ${order.id.take(8)} · فاتورة المورد", color = CloudPurchasesMuted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text(
                    "اعتماد الاستلام يحدّث المخزون وحركة الاستلام وأمر الشراء ذريًا. تسجيل الفاتورة ينشئ رصيد المورد وقيدًا محاسبيًا؛ تسجيل الدفعة يتطلب إثباتًا منفصلًا.",
                    color = CloudPurchasesMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
 
    mappingOrder?.let { order ->
        PurchaseLineOfferingLinkDialog(
            context = context,
            order = order,
            onDismiss = { mappingOrder = null },
            onSaved = { mappingOrder = null; reloadToken++ }
        )
    }

    receiptOrder?.let { order ->
        PurchaseReceiptEntryDialog(
            context = context,
            order = order,
            onDismiss = { receiptOrder = null },
            onSaved = { receiptOrder = null; reloadToken++ }
        )
    }
    transitionConfirmation?.let { (order, action) ->
        val label = when (action) {
            "submit" -> "إرسال أمر الشراء للاعتماد"
            "approve" -> "اعتماد أمر الشراء"
            "cancel" -> "إلغاء أمر الشراء"
            else -> "إغلاق أمر الشراء"
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { transitionConfirmation = null },
            title = { Text(label) },
            text = { Text("سيُتحقق من الحالة والكميات المستلمة والصلاحيات داخل قاعدة البيانات قبل تنفيذ العملية.") },
            confirmButton = {
                Button(onClick = {
                    val selected = transitionConfirmation ?: return@Button
                    transitionConfirmation = null
                    CloudPurchasesWorker.execute {
                        val result = runCatching { transitionCommercialCloudPurchaseOrder(context, selected.first.id, selected.second) }
                        CloudPurchasesMain.post {
                            result.onSuccess { reloadToken++ }
                                .onFailure { error = it.message ?: "تعذر تحديث حالة أمر الشراء." }
                        }
                    }
                }) { Text("تأكيد") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { transitionConfirmation = null }) { Text("رجوع") } }
        )
    }

    if (createOrderOpen) {
        PurchaseOrderEntryDialog(
            context = context,
            onDismiss = { createOrderOpen = false },
            onSaved = { createOrderOpen = false; reloadToken++ }
        )
    }

    paymentInvoice?.let { invoice ->
        SupplierPaymentEntryDialog(
            context = context,
            invoice = invoice,
            onDismiss = { paymentInvoice = null },
            onSaved = { paymentInvoice = null; reloadToken++ }
        )
    }

}

private fun purchaseOrderHasUnmappedRemainingLines(order: CommercialCloudPurchaseDocument): Boolean =
    order.lines.any { line ->
        val ordered = line.quantityOrdered.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        val received = line.quantityReceived.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
        received < ordered && line.offeringId.isNullOrBlank()
    }

private fun purchaseStatusLabel(value: String): String = when (value.lowercase()) {
    "draft" -> "مسودة"
    "submitted", "pending" -> "بانتظار الاعتماد"
    "approved", "open" -> "معتمد"
    "partially_received", "partial" -> "استلام جزئي"
    "received", "completed" -> "مستلم"
    "cancelled", "canceled" -> "ملغى"
    else -> value.ifBlank { "غير محدد" }
}

private fun purchaseStatusColor(value: String): Color = when (value.lowercase()) {
    "received", "completed" -> Color(0xFF167D63)
    "approved", "open", "partially_received", "partial" -> Color(0xFFB36B00)
    "cancelled", "canceled" -> Color(0xFFB3261E)
    else -> CloudPurchasesMuted
}
