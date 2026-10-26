package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.Executors

private val CustomerOrdersInk = Color(0xFF183B35)
private val CustomerOrdersGreen = Color(0xFF167D63)
private val CustomerOrdersMuted = Color(0xFF71817B)
private val CustomerOrdersBorder = Color(0xFFE5EBE8)
private val customerOrdersExecutor = Executors.newSingleThreadExecutor()
private val customerOrdersMain = Handler(Looper.getMainLooper())

private fun customerOrderMoney(value: Long, currency: String) =
    String.format(Locale.US, "%,d %s", value, currency)

private fun customerOrderStatus(status: String) = when (status) {
    "pending" -> "بانتظار المراجعة"
    "confirmed" -> "معتمد"
    "in_progress" -> "قيد التنفيذ"
    "fulfilled" -> "تم البيع"
    "cancelled" -> "ملغى"
    "rejected" -> "مرفوض"
    else -> status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerOrderFulfillmentScreen(context: Context, onBack: () -> Unit) {
    var orders by remember { mutableStateOf<List<CustomerOrderFulfillmentOrder>>(emptyList()) }
    var locations by remember { mutableStateOf<List<CustomerOrderFulfillmentLocation>>(emptyList()) }
    var selectedLocationId by remember { mutableStateOf("") }
    var locationMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var busyOrderId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var cashConfirmationOrder by remember { mutableStateOf<CustomerOrderFulfillmentOrder?>(null) }

    fun submitFulfillment(order: CustomerOrderFulfillmentOrder) {
        val id = order.id
        val location = selectedLocationId
        busyOrderId = id
        error = ""
        notice = "جارٍ التحقق من المخزون والأسعار وتسجيل البيع..."
        customerOrdersExecutor.execute {
            val result = runCatching {
                fulfillCustomerPortalOrder(context, id, location, "customer-order-fulfill:" + id)
            }
            customerOrdersMain.post {
                busyOrderId = null
                result.onSuccess { response ->
                    notice = "اكتمل البيع " + response.optString("receipt_number") +
                        " بمبلغ " + customerOrderMoney(response.optLong("total_minor"), response.optString("currency_code")) +
                        ". حالة الدفع: " + when (response.optString("payment_status")) {
                            "paid" -> "مدفوع"
                            "credit" -> "آجل"
                            else -> response.optString("payment_status")
                        } + "."
                    refresh++
                }.onFailure {
                    notice = ""
                    error = it.message ?: "تعذر إتمام البيع. راجع المخزون والإعدادات المحاسبية ثم أعد المحاولة."
                }
            }
        }
    }

    LaunchedEffect(refresh) {
        loading = true
        error = ""
        customerOrdersExecutor.execute {
            val orderResult = runCatching { loadCustomerOrderFulfillmentQueue(context) }
            val locationResult = runCatching { loadCustomerOrderFulfillmentLocations(context) }
            customerOrdersMain.post {
                loading = false
                orderResult.onSuccess { orders = it }
                    .onFailure { error = it.message ?: "تعذر تحميل طلبات الزبائن." }
                locationResult.onSuccess { rows ->
                    locations = rows
                    if (rows.none { it.id == selectedLocationId }) selectedLocationId = rows.firstOrNull()?.id.orEmpty()
                }.onFailure {
                    if (error.isBlank()) error = it.message ?: "تعذر تحميل مواقع المخزون."
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("طلبات الزبائن", color = CustomerOrdersInk, fontWeight = FontWeight.Bold)
                        Text("مراجعة الطلب وإنشاء البيع من المخزون", color = CustomerOrdersMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = CustomerOrdersInk)
                    }
                },
                actions = {
                    IconButton(onClick = { if (!loading && busyOrderId == null) refresh++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث", tint = CustomerOrdersInk)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CustomerOrdersBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("إتمام البيع", color = CustomerOrdersInk, fontWeight = FontWeight.Bold)
                        Text(
                            "الطلب لا يحجز المخزون عند إنشائه. عند اختيار «اعتماد وإتمام البيع»، يتحقق الخادم من الأسعار والمخزون ثم يسجل البيع والحركة المحاسبية والدفع أو المديونية في معاملة واحدة.",
                            color = CustomerOrdersMuted, fontSize = 12.sp
                        )
                        Box {
                            OutlinedButton(
                                onClick = { locationMenu = true },
                                enabled = !loading && busyOrderId == null && locations.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    locations.firstOrNull { it.id == selectedLocationId }?.name ?: "اختر موقع المخزون",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            DropdownMenu(expanded = locationMenu, onDismissRequest = { locationMenu = false }) {
                                locations.forEach { location ->
                                    DropdownMenuItem(
                                        text = { Text(location.name) },
                                        onClick = { selectedLocationId = location.id; locationMenu = false }
                                    )
                                }
                            }
                        }
                        if (locations.isEmpty() && !loading) {
                            Text("لا يوجد موقع مخزون نشط. لن يمكن إتمام البيع حتى إعداد موقع صالح.", color = CustomerOrdersMuted, fontSize = 12.sp)
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = { refresh++ }, enabled = !loading && busyOrderId == null) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (notice.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF6EF))) {
                    Text(notice, Modifier.padding(14.dp), color = CustomerOrdersInk, fontSize = 13.sp)
                }
            }
            if (!loading && orders.isEmpty() && error.isBlank()) {
                item { Text("لا توجد طلبات في سجل المؤسسة.", color = CustomerOrdersMuted) }
            }
            items(orders, key = { it.id }) { order ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, CustomerOrdersBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("طلب " + order.orderNumber, color = CustomerOrdersInk, fontWeight = FontWeight.Bold)
                                Text(order.customerName.ifBlank { "عميل مرتبط بحساب المؤسسة" }, color = CustomerOrdersMuted, fontSize = 12.sp)
                            }
                            AssistChip(onClick = {}, label = { Text(customerOrderStatus(order.status), fontSize = 11.sp) })
                        }
                        order.items.forEach { line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(line.name + " × " + line.quantity, Modifier.weight(1f), color = CustomerOrdersInk, fontSize = 13.sp)
                                Text(customerOrderMoney(line.lineTotalMinor, order.currencyCode), color = CustomerOrdersMuted, fontSize = 12.sp)
                            }
                        }
                        if (order.customerNote.isNotBlank()) {
                            Text("ملاحظة العميل: " + order.customerNote, color = CustomerOrdersMuted, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = CustomerOrdersBorder)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("الإجمالي", color = CustomerOrdersMuted, modifier = Modifier.weight(1f))
                            Text(customerOrderMoney(order.totalMinor, order.currencyCode), color = CustomerOrdersInk, fontWeight = FontWeight.Bold)
                        }
                        if (order.status == "fulfilled") {
                            Text("تم تحويل هذا الطلب إلى عملية بيع.", color = CustomerOrdersGreen, fontSize = 12.sp)
                        } else if (order.status in setOf("pending", "confirmed", "in_progress")) {
                            if (!order.fulfillmentSupported) {
                                Text(
                                    "يتطلب هذا الطلب معالجة متخصصة: قد يحتوي خدمة أو كمية كسرية أو أصنافًا من أنشطة متعددة.",
                                    color = CustomerOrdersMuted, fontSize = 12.sp
                                )
                            } else {
                                Button(
                                    onClick = {
                                        if (order.paymentStatus == "unpaid") cashConfirmationOrder = order
                                        else submitFulfillment(order)
                                    },
                                    enabled = !loading && busyOrderId == null && selectedLocationId.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (busyOrderId == order.id) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Text("اعتماد وإتمام البيع")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    cashConfirmationOrder?.let { order ->
        AlertDialog(
            onDismissRequest = { cashConfirmationOrder = null },
            title = { Text("تأكيد استلام المبلغ نقدًا") },
            text = {
                Text(
                    "سيُسجّل النظام فاتورة مكتملة ومدفوعة نقدًا بقيمة " +
                        customerOrderMoney(order.totalMinor, order.currencyCode) +
                        " ويخصم الكمية من المخزون. لا تتابع إلا إذا استلمت المبلغ كاملًا بالفعل."
                )
            },
            confirmButton = {
                Button(onClick = {
                    cashConfirmationOrder = null
                    submitFulfillment(order)
                }, enabled = busyOrderId == null && selectedLocationId.isNotBlank()) {
                    Text("استلمت المبلغ — إتمام البيع")
                }
            },
            dismissButton = {
                TextButton(onClick = { cashConfirmationOrder = null }) { Text("رجوع") }
            }
        )
    }
}
