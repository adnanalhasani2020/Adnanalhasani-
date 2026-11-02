package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import java.util.concurrent.Executors

private val CustomerRequestInk = Color(0xFF183B35)
private val CustomerRequestGreen = Color(0xFF167D63)
private val CustomerRequestMuted = Color(0xFF71817B)
private val CustomerRequestIo = Executors.newSingleThreadExecutor()
private val CustomerRequestMain = Handler(Looper.getMainLooper())

private fun customerRequestMoney(amount: Long, currency: String): String =
    String.format(Locale.US, "%,d %s", amount, currency)

private fun customerRequestStatus(status: String): String = when (status) {
    "pending" -> "قيد انتظار اعتماد التاجر"
    "change_requested" -> "التاجر طلب تعديل البيانات"
    "rejected" -> "مرفوض"
    "approved" -> "تم الاعتماد وإنشاء سند القبض"
    else -> "حالة غير معروفة"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerPortalPaymentRequestsScreen(
    context: Context,
    organizationId: String,
    organizationName: String,
    onBack: () -> Unit
) {
    var outstanding by remember { mutableStateOf<List<CustomerPortalOutstandingSale>>(emptyList()) }
    var requests by remember { mutableStateOf<List<CustomerPaymentRequest>>(emptyList()) }
    var selectedSale by remember { mutableStateOf<CustomerPortalOutstandingSale?>(null) }
    var editingRequest by remember { mutableStateOf<CustomerPaymentRequest?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    LaunchedEffect(refresh, organizationId) {
        loading = true
        error = ""
        CustomerRequestIo.execute {
            val salesResult = runCatching { loadCustomerPortalOutstandingSales(context, organizationId) }
            val requestsResult = runCatching { loadCustomerPortalPaymentRequests(context, organizationId) }
            CustomerRequestMain.post {
                loading = false
                salesResult.onSuccess { outstanding = it }.onFailure {
                    error = it.message ?: "تعذر تحميل الفواتير المستحقة."
                }
                requestsResult.onSuccess { requests = it }.onFailure {
                    if (error.isBlank()) error = it.message ?: "تعذر تحميل طلبات سندات القبض."
                }
            }
        }
    }

    fun openNewRequest(sale: CustomerPortalOutstandingSale) {
        selectedSale = sale
        editingRequest = null
        amount = sale.dueMinor.toString()
        note = ""
        error = ""
        notice = ""
    }

    fun openEditRequest(request: CustomerPaymentRequest) {
        val sale = outstanding.firstOrNull { it.saleId == request.saleId }
        if (sale == null) {
            error = "تعذر تعديل الطلب لأن الفاتورة لم تعد ضمن الفواتير المستحقة. حدّث الصفحة."
            return
        }
        selectedSale = sale
        editingRequest = request
        amount = request.amountMinor.toString()
        note = request.customerNote
        error = ""
        notice = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("طلبات سندات القبض", color = CustomerRequestInk, fontWeight = FontWeight.Bold)
                        Text(organizationName, color = CustomerRequestMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع", tint = CustomerRequestInk) }
                },
                actions = {
                    IconButton(onClick = { refresh++ }, enabled = !loading && !submitting) {
                        Icon(Icons.Default.Refresh, "تحديث", tint = CustomerRequestInk)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        },
        containerColor = Color(0xFFF5F7F6)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEFEC))) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(error, color = Color(0xFF9C3028))
                        TextButton(onClick = { refresh++ }, enabled = !loading) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (notice.isNotBlank()) item { Text(notice, color = CustomerRequestGreen) }

            item {
                Text("الفواتير التي عليها مبالغ مستحقة", color = CustomerRequestInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    "اختر الفاتورة التي دفعت مبلغها للتاجر، ثم سجّل المبلغ والملاحظة. لن يتغير رصيدك قبل اعتماد التاجر.",
                    color = CustomerRequestMuted, fontSize = 12.sp
                )
            }
            if (!loading && outstanding.isEmpty() && error.isBlank()) {
                item { Text("لا توجد فواتير مستحقة مرتبطة بهذا الحساب.", color = CustomerRequestMuted) }
            }
            items(outstanding, key = { it.saleId }) { sale ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("فاتورة رقم " + sale.invoiceNumber.ifBlank { sale.saleId.take(8) }, color = CustomerRequestInk, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("إجمالي الفاتورة", color = CustomerRequestMuted, fontSize = 12.sp)
                            Text(customerRequestMoney(sale.totalMinor, sale.currencyCode), color = CustomerRequestInk)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المتبقي", color = CustomerRequestMuted, fontSize = 12.sp)
                            Text(customerRequestMoney(sale.dueMinor, sale.currencyCode), color = Color(0xFFB96A14), fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { openNewRequest(sale) },
                            enabled = !loading && !submitting,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("إرسال طلب دفعة لهذه الفاتورة") }
                    }
                }
            }

            item {
                Text("طلباتك السابقة", color = CustomerRequestInk, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("يبقى الطلب قيد الانتظار حتى يراجعه التاجر. لا يُسجل كسند قبض قبل الاعتماد.", color = CustomerRequestMuted, fontSize = 12.sp)
            }
            if (!loading && requests.isEmpty() && error.isBlank()) {
                item { Text("لم ترسل أي طلب سند قبض لهذه المؤسسة بعد.", color = CustomerRequestMuted) }
            }
            items(requests, key = { it.id }) { request ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("طلب فاتورة رقم " + request.invoiceNumber.ifBlank { request.saleId.take(8) }, color = CustomerRequestInk, fontWeight = FontWeight.Bold)
                        Text(customerRequestMoney(request.amountMinor, request.currencyCode), color = CustomerRequestGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(customerRequestStatus(request.status), color = when (request.status) {
                            "approved" -> CustomerRequestGreen
                            "rejected" -> Color(0xFF9C3028)
                            "change_requested" -> Color(0xFFB96A14)
                            else -> CustomerRequestMuted
                        }, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        if (request.customerNote.isNotBlank()) Text("ملاحظتك: " + request.customerNote, color = CustomerRequestMuted, fontSize = 12.sp)
                        if (request.reviewNote.isNotBlank()) Text("رد التاجر: " + request.reviewNote, color = Color(0xFF9C3028), fontSize = 12.sp)
                        if (request.status == "approved") {
                            Text(
                                if (request.receiptNumber.isNotBlank() && request.receiptNumber != "null")
                                    "سند القبض رقم RC-" + request.receiptNumber
                                else "تم إنشاء سند القبض وربطه بالدفعة.",
                                color = CustomerRequestGreen, fontSize = 12.sp
                            )
                        }
                        if (request.status == "change_requested") {
                            Button(
                                onClick = { openEditRequest(request) },
                                enabled = !loading && !submitting,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("تعديل الطلب وإعادة إرساله") }
                        }
                    }
                }
            }
        }
    }

    val sale = selectedSale
    if (sale != null) {
        AlertDialog(
            onDismissRequest = { if (!submitting) { selectedSale = null; editingRequest = null } },
            title = { Text(if (editingRequest == null) "إرسال طلب سند قبض" else "تعديل طلب سند القبض") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("فاتورة رقم " + sale.invoiceNumber.ifBlank { sale.saleId.take(8) }, color = CustomerRequestInk, fontWeight = FontWeight.SemiBold)
                    Text("المتبقي: " + customerRequestMoney(sale.dueMinor, sale.currencyCode), color = CustomerRequestMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it.filter(Char::isDigit).take(18) },
                        enabled = !submitting,
                        label = { Text("المبلغ الذي دفعته") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(1000) },
                        enabled = !submitting,
                        label = { Text("ملاحظة (اختيارية)") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("سيظهر الطلب للتاجر للمراجعة فقط. لن يتغير رصيد الفاتورة حتى يعتمد المبلغ.", color = CustomerRequestMuted, fontSize = 11.sp)
                    if (error.isNotBlank()) Text(error, color = Color(0xFF9C3028), fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    enabled = !submitting && (amount.toLongOrNull() ?: 0L) in 1L..sale.dueMinor,
                    onClick = {
                        val value = amount.toLongOrNull() ?: return@Button
                        val edit = editingRequest
                        val message = note
                        submitting = true
                        error = ""
                        CustomerRequestIo.execute {
                            val result = runCatching {
                                if (edit == null) submitCustomerPortalPaymentRequest(context, sale.saleId, value, message)
                                else updateCustomerPortalPaymentRequest(context, edit.id, value, message)
                            }
                            CustomerRequestMain.post {
                                submitting = false
                                result.onSuccess {
                                    selectedSale = null
                                    editingRequest = null
                                    notice = if (edit == null) "أُرسل طلبك وبقي قيد انتظار اعتماد التاجر." else "أُعيد إرسال الطلب بعد التعديل، وهو الآن قيد الانتظار."
                                    refresh++
                                }.onFailure { error = it.message ?: "تعذر إرسال طلب سند القبض." }
                            }
                        }
                    }
                ) { Text(if (editingRequest == null) "إرسال الطلب" else "حفظ التعديل") }
            },
            dismissButton = {
                TextButton(onClick = { if (!submitting) { selectedSale = null; editingRequest = null } }) { Text("إلغاء") }
            }
        )
    }
}
