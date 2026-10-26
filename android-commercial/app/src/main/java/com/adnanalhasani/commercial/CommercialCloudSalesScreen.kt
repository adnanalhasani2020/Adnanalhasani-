package com.adnanalhasani.commercial

import android.content.Context
import android.content.Intent
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CloudSalesInk = Color(0xFF183B35)
private val CloudSalesGreen = Color(0xFF167D63)
private val CloudSalesMuted = Color(0xFF71817B)
private val CloudSalesBorder = Color(0xFFE5EBE8)
private val CloudSalesWorker = Executors.newSingleThreadExecutor()
private val CloudSalesMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudSalesScreen(context: Context, onBack: () -> Unit) {
    var documents by remember { mutableStateOf<List<CommercialCloudSaleDocument>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf("all") }
    var selectedSort by remember { mutableStateOf("newest") }
    var expandedInvoiceIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var collectionTarget by remember { mutableStateOf<CommercialCloudSaleDocument?>(null) }
    var collectionAmountText by remember { mutableStateOf("") }
    var collectionBusy by remember { mutableStateOf(false) }
    var collectionError by remember { mutableStateOf("") }

    LaunchedEffect(reloadToken) {
        loading = true
        error = ""
        CloudSalesWorker.execute {
            val result = runCatching { loadCommercialCloudSaleDocuments(context) }
            CloudSalesMain.post {
                result.onSuccess { documents = it }
                    .onFailure { error = it.message ?: "تعذر تحميل فواتير المبيعات." }
                loading = false
            }
        }
    }

    val filtered = documents.filter { doc ->
        val matchesSearch = search.isBlank() ||
            doc.receiptNumber.contains(search, true) ||
            doc.customerName.contains(search, true) ||
            doc.paymentStatus.contains(search, true) ||
            doc.id.contains(search, true) ||
            doc.saleStatus.contains(search, true) ||
            doc.payments.any { it.status.contains(search, true) || it.methodType.contains(search, true) } ||
            doc.returns.any { it.status.contains(search, true) || it.refundStatus.contains(search, true) || it.restockStatus.contains(search, true) } ||
            doc.lines.any { it.productName.contains(search, true) }
        val status = doc.paymentStatus.lowercase()
        val saleStatus = doc.saleStatus.lowercase()
        val matchesStatus = when (selectedStatus) {
            "paid" -> status == "paid"
            "partial" -> status in setOf("partially_paid", "partial")
            "due" -> status in setOf("unpaid", "due", "pending", "credit")
            "cancelled" -> saleStatus in setOf("cancelled", "canceled") || status in setOf("failed", "cancelled", "canceled")
            "refunded" -> status in setOf("refunded", "partially_refunded") ||
                doc.returns.any { it.refundStatus.lowercase() in setOf("refunded", "partially_refunded") }
            else -> true
        }
        matchesSearch && matchesStatus
    }
    val sortedFiltered = when (selectedSort) {
        "highest" -> filtered.sortedByDescending { it.totalMinor }
        "outstanding" -> filtered.sortedByDescending { doc ->
            (doc.totalMinor - doc.payments.filter { it.status.equals("succeeded", true) }
                .sumOf { it.amountMinor.coerceAtLeast(0L) }).coerceAtLeast(0L)
        }
        else -> filtered.sortedByDescending { it.createdAt }
    }
    val paidCount = documents.count { it.paymentStatus.equals("paid", true) }
    val dueCount = documents.count { it.paymentStatus.lowercase() in setOf("unpaid", "due", "pending", "credit", "partially_paid", "partial") }
    val totalsByCurrency = filtered.groupBy { it.currencyCode.ifBlank { "غير محدد" } }
        .mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
        .toSortedMap()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("المبيعات", fontWeight = FontWeight.Bold, color = CloudSalesInk, fontSize = 20.sp)
                        Text("إدارة الفواتير والتحصيل", fontSize = 11.sp, color = CloudSalesMuted)
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CloudSalesBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("سجل فواتير البيع", color = CloudSalesInk, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("ابحث وراجع حالة الدفع والتفاصيل", color = CloudSalesMuted, fontSize = 11.sp)
                            }
                            Surface(color = Color(0xFFE8F5EF), shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = CloudSalesGreen, modifier = Modifier.padding(10.dp).size(22.dp))
                            }
                        }
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(15.dp),
                            placeholder = { Text("رقم الفاتورة، العميل، أو الصنف") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("مسح") }
                            }
                        )
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "all" to "الكل",
                        "paid" to "مدفوعة",
                        "partial" to "مدفوعة جزئيًا",
                        "due" to "مستحقة",
                        "cancelled" to "ملغاة / فاشلة",
                        "refunded" to "مستردة"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = selectedStatus == key,
                            onClick = { selectedStatus = key },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("ترتيب الفواتير", color = CloudSalesMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("newest" to "الأحدث", "highest" to "الأعلى قيمة", "outstanding" to "الأكثر استحقاقًا").forEach { (key, label) ->
                            FilterChip(
                                selected = selectedSort == key,
                                onClick = { selectedSort = key },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CloudSalesInk),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("ملخص المبيعات", color = Color(0xFFD6E8E0), fontSize = 12.sp)
                                Text("${documents.size} فاتورة", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 25.sp)
                                Text("المعروض بعد التصفية: ${filtered.size}", color = Color(0xFFD6E8E0), fontSize = 11.sp)
                            }
                            Box(Modifier.size(54.dp).background(Color(0xFF2D5A4F), RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Surface(modifier = Modifier.weight(1f), color = Color(0xFF24483F), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("مدفوعة", color = Color(0xFFD6E8E0), fontSize = 10.sp)
                                    Text(paidCount.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                }
                            }
                            Surface(modifier = Modifier.weight(1f), color = Color(0xFF24483F), shape = RoundedCornerShape(14.dp)) {
                                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("تحتاج تحصيلًا", color = Color(0xFFD6E8E0), fontSize = 10.sp)
                                    Text(dueCount.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                                }
                            }
                        }
                        if (totalsByCurrency.isNotEmpty()) {
                            Surface(color = Color(0xFF24483F), shape = RoundedCornerShape(15.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("إجمالي الفواتير الظاهرة حسب العملة", color = Color(0xFFD6E8E0), fontSize = 11.sp)
                                    totalsByCurrency.forEach { (currency, total) ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                            Text(currency, color = Color.White, fontSize = 12.sp)
                                            Text(String.format(java.util.Locale.US, "%,d", total), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        }
                                    }
                                    Text("القيم بوحدة الصرف الصغرى", color = Color(0xFFB8D0C7), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
            if (loading) item {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = CloudSalesGreen, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ تحميل الفواتير...", color = CloudSalesMuted)
                }
            }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0ED)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("تعذر تحميل فواتير المبيعات", color = Color(0xFF9B3024), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(error, color = Color(0xFF9B3024), fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { reloadToken++ }) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && filtered.isEmpty()) item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CloudSalesBorder)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).background(Color(0xFFE8F5EF), RoundedCornerShape(17.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = CloudSalesGreen, modifier = Modifier.size(27.dp))
                        }
                        Text(
                            if (documents.isEmpty()) "لا توجد فواتير بعد" else "لم نعثر على فواتير مطابقة",
                            color = CloudSalesInk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            if (documents.isEmpty()) "ستظهر فواتير المبيعات هنا بعد تسجيلها." else "جرّب تغيير عبارة البحث أو حالة الفاتورة.",
                            color = CloudSalesMuted,
                            fontSize = 12.sp
                        )
                        if (documents.isNotEmpty() && (search.isNotBlank() || selectedStatus != "all")) {
                            OutlinedButton(onClick = {
                                search = ""
                                selectedStatus = "all"
                            }) {
                                Text("مسح البحث والفلاتر")
                            }
                        }
                    }
                }
            }
            items(sortedFiltered, key = { it.id }) { document ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CloudSalesBorder),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("#${document.receiptNumber}", color = CloudSalesInk, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                                Text(document.customerName.ifBlank { "عميل نقدي" }, color = CloudSalesMuted, fontSize = 13.sp)
                                if (document.customerPhone.isNotBlank()) Text("هاتف: ${document.customerPhone}", color = CloudSalesMuted, fontSize = 11.sp)
                                if (document.customerEmail.isNotBlank()) Text(document.customerEmail, color = CloudSalesMuted, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(color = statusColor(document.saleStatus).copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                                    Text("الفاتورة: ${statusLabel(document.saleStatus)}", color = statusColor(document.saleStatus), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                }
                                Surface(color = statusColor(document.paymentStatus).copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                                    Text("الدفع: ${statusLabel(document.paymentStatus)}", color = statusColor(document.paymentStatus), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                                }
                            }
                        }
                        Surface(color = Color(0xFFF0F7F3), shape = RoundedCornerShape(15.dp)) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("إجمالي الفاتورة", color = CloudSalesMuted, fontSize = 11.sp)
                                    Text("${formatMinorAmount(document.totalMinor)} ${document.currencyCode.ifBlank { "—" }}", color = CloudSalesGreen, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                                }
                                Surface(color = Color.White, shape = RoundedCornerShape(10.dp)) {
                                    Text("${document.lines.size} بنود", color = CloudSalesInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("الإجمالي قبل الخصم: ${formatMinorAmount(document.subtotalMinor)} ${document.currencyCode}", color = CloudSalesMuted, fontSize = 11.sp)
                            Text("الخصم: ${formatMinorAmount(document.discountMinor)} ${document.currencyCode}", color = CloudSalesMuted, fontSize = 11.sp)
                            Text("الضريبة: ${formatMinorAmount(document.taxMinor)} ${document.currencyCode}", color = CloudSalesMuted, fontSize = 11.sp)
                        }
                        if (document.createdAt.isNotBlank()) Text(formatInvoiceTimestamp(document.createdAt), color = CloudSalesMuted, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("تفاصيل الفاتورة", color = CloudSalesInk, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                expandedInvoiceIds = if (document.id in expandedInvoiceIds) expandedInvoiceIds - document.id else expandedInvoiceIds + document.id
                            }) {
                                Text(if (document.id in expandedInvoiceIds) "إخفاء التفاصيل ▲" else "عرض التفاصيل ▼", color = CloudSalesGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (document.id in expandedInvoiceIds) {
                            document.lines.forEach { line ->
                                Surface(color = Color(0xFFF7F9F8), shape = RoundedCornerShape(10.dp)) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(line.productName, modifier = Modifier.weight(1f), color = CloudSalesInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                                            Spacer(Modifier.width(8.dp))
                                            Text("الكمية: ${line.quantity}", color = CloudSalesMuted, fontSize = 11.sp)
                                        }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("سعر الوحدة: ${formatMinorAmount(line.unitPriceMinor)}", color = CloudSalesMuted, fontSize = 11.sp)
                                            Text("الإجمالي: ${formatMinorAmount(line.lineTotalMinor)}", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                            Text("معرّف الفاتورة: ${document.id}", color = CloudSalesMuted, fontSize = 10.sp)
                        }
                        val confirmedPaidMinor = document.payments
                            .filter { it.status.equals("succeeded", ignoreCase = true) }
                            .sumOf { it.amountMinor }
                        Surface(color = Color(0xFFF3F8F5), shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("إجمالي الفاتورة", color = CloudSalesMuted, fontSize = 11.sp)
                                    Text("${formatMinorAmount(document.totalMinor)} ${document.currencyCode}", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("المدفوعات الناجحة فقط", color = CloudSalesMuted, fontSize = 11.sp)
                                    Text("${formatMinorAmount(confirmedPaidMinor)} ${document.currencyCode}", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("المتبقي قبل احتساب المرتجعات", color = CloudSalesMuted, fontSize = 11.sp)
                                    Text("${formatMinorAmount((document.totalMinor - confirmedPaidMinor).coerceAtLeast(0L))} ${document.currencyCode}", color = CloudSalesInk, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { shareSavedInvoice(context, document, confirmedPaidMinor) },
                                modifier = Modifier.weight(1f)
                            ) { Text("مشاركة الفاتورة") }
                            OutlinedButton(
                                onClick = { printSavedInvoice(context, document, confirmedPaidMinor) },
                                modifier = Modifier.weight(1f)
                            ) { Text("طباعة الفاتورة") }
                        }
                        if (document.payments.isNotEmpty()) {
                            Text("المدفوعات المسجلة", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            document.payments.forEach { payment ->
                                Surface(color = Color(0xFFF7F9F8), shape = RoundedCornerShape(9.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("${statusLabel(payment.status)} · ${formatMinorAmount(payment.amountMinor)} ${document.currencyCode}", color = CloudSalesInk, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                        if (payment.methodType.isNotBlank()) Text("الطريقة: ${payment.methodType}", color = CloudSalesMuted, fontSize = 10.sp)
                                        if (payment.createdAt.isNotBlank()) Text(formatInvoiceTimestamp(payment.createdAt), color = CloudSalesMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        if (document.returns.isNotEmpty()) {
                            Text("المرتجعات المرتبطة", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            document.returns.forEach { returned ->
                                Surface(color = Color(0xFFFFF7E8), shape = RoundedCornerShape(9.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text("الطلب: ${statusLabel(returned.status)} · الاسترداد: ${statusLabel(returned.refundStatus)}", color = CloudSalesInk, fontWeight = FontWeight.Medium, fontSize = 11.sp)
                                        Text("إعادة المخزون: ${statusLabel(returned.restockStatus)}", color = CloudSalesMuted, fontSize = 10.sp)
                                        Text("المبلغ المطلوب: ${formatMinorAmount(returned.requestedRefundMinor)} ${document.currencyCode}" +
                                            (returned.approvedRefundMinor?.let { " · المعتمد: ${formatMinorAmount(it)}" } ?: ""), color = CloudSalesMuted, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        if (document.saleStatus.equals("completed", ignoreCase = true) &&
                            document.paymentStatus in setOf("pending", "partially_paid", "failed") &&
                            document.totalMinor > confirmedPaidMinor) {
                            Button(
                                onClick = {
                                    collectionTarget = document
                                    collectionAmountText = ""
                                    collectionError = ""
                                },
                                enabled = !collectionBusy,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("تسجيل تحصيل سحابي")
                            }
                        }
                        Text("معرّف السجل: ${document.id.take(8)} · بيانات محفوظة سحابيًا", color = CloudSalesMuted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text(
                    "هذه الشاشة للعرض فقط: تعرض الفواتير وبنودها من الخادم، وتعرض بنود الفاتورة المحفوظة، ولا تنشئ فاتورة أو تعدّلها، ولا يُفترض مقياس عشري للعملة.",
                    color = CloudSalesMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }

    val target = collectionTarget
    if (target != null) {
        val paidMinor = target.payments.filter { it.status.equals("succeeded", ignoreCase = true) }
            .sumOf { it.amountMinor.coerceAtLeast(0L) }
        val outstandingMinor = (target.totalMinor - paidMinor).coerceAtLeast(0L)
        AlertDialog(
            onDismissRequest = { if (!collectionBusy) collectionTarget = null },
            title = { Text("تسجيل تحصيل · فاتورة #${target.receiptNumber}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("المتبقي قبل المرتجعات: ${formatMinorAmount(outstandingMinor)} ${target.currencyCode}", color = CloudSalesMuted, fontSize = 12.sp)
                    OutlinedTextField(
                        value = collectionAmountText,
                        onValueChange = { collectionAmountText = it.filter(Char::isDigit).take(18); collectionError = "" },
                        enabled = !collectionBusy,
                        singleLine = true,
                        label = { Text("مبلغ التحصيل بوحدة الصرف الصغرى") }
                    )
                    Text("يسجل التحصيل على الخادم فقط. تُمنع الزيادة عن المتبقي، ويُوقف التحصيل عند وجود مرتجع يحتاج إلى تسوية.", color = CloudSalesMuted, fontSize = 11.sp)
                    if (collectionError.isNotBlank()) Text(collectionError, color = Color(0xFFB3261E), fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(
                    enabled = !collectionBusy,
                    onClick = {
                        val amount = collectionAmountText.toLongOrNull()
                        if (amount == null || amount <= 0L) {
                            collectionError = "أدخل مبلغًا صحيحًا أكبر من صفر."
                        } else if (amount > outstandingMinor) {
                            collectionError = "المبلغ يتجاوز المتبقي الظاهر؛ حدّث الفاتورة قبل المتابعة."
                        } else {
                            collectionBusy = true
                            collectionError = ""
                            CloudSalesWorker.execute {
                                val result = runCatching {
                                    recordCommercialCloudCustomerPayment(context, target.id, amount, "cash")
                                }
                                CloudSalesMain.post {
                                    collectionBusy = false
                                    result.onSuccess {
                                        reloadToken++
                                        collectionTarget = null
                                        collectionAmountText = ""
                                        collectionError = ""
                                    }.onFailure {
                                        collectionError = it.message ?: "تعذر تأكيد التحصيل؛ لم يتم عرضه كتحصيل ناجح."
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (collectionBusy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("تأكيد التحصيل")
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!collectionBusy) collectionTarget = null }, enabled = !collectionBusy) {
                    Text("إلغاء")
                }
            }
        )
    }
}


private fun shareSavedInvoice(
    context: Context,
    document: CommercialCloudSaleDocument,
    paidMinor: Long
) {
    val outstanding = (document.totalMinor - paidMinor).coerceAtLeast(0L)
    val lines = document.lines.joinToString("\n") {
        "• ${it.productName} — ${it.quantity} × ${formatMinorAmount(it.unitPriceMinor)} = ${formatMinorAmount(it.lineTotalMinor)}"
    }
    val payments = document.payments.filter { it.status.equals("succeeded", true) }
        .joinToString("\n") { "• ${formatMinorAmount(it.amountMinor)} ${document.currencyCode} — ${statusLabel(it.status)}" }
        .ifBlank { "لا توجد دفعات ناجحة مسجلة" }
    val body = buildString {
        append("فاتورة تشغيلية رقم #${document.receiptNumber}\n")
        append("التاريخ: ${formatInvoiceTimestamp(document.createdAt)}\n")
        append("العميل: ${document.customerName}\n")
        append("الحالة: ${statusLabel(document.saleStatus)} / ${statusLabel(document.paymentStatus)}\n\n")
        append("البنود:\n").append(lines).append("\n\n")
        append("الإجمالي قبل الخصم: ${formatMinorAmount(document.subtotalMinor)} ${document.currencyCode}\n")
        append("الخصم: ${formatMinorAmount(document.discountMinor)} ${document.currencyCode}\n")
        append("الضريبة: ${formatMinorAmount(document.taxMinor)} ${document.currencyCode}\n")
        append("الإجمالي: ${formatMinorAmount(document.totalMinor)} ${document.currencyCode}\n")
        append("المدفوع: ${formatMinorAmount(paidMinor)} ${document.currencyCode}\n")
        append("المتبقي: ${formatMinorAmount(outstanding)} ${document.currencyCode}\n\n")
        append("الدفعات:\n").append(payments).append("\n")
        append("معرّف السجل المحفوظ: ${document.id}\n")
        append("هذه نسخة تشغيلية من بيانات الخادم وليست إقرارًا بالامتثال لمتطلبات فاتورة ضريبية محلية.")
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "فاتورة رقم #${document.receiptNumber}")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة الفاتورة"))
}

private fun printSavedInvoice(
    context: Context,
    document: CommercialCloudSaleDocument,
    paidMinor: Long
) {
    val outstanding = (document.totalMinor - paidMinor).coerceAtLeast(0L)
    fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
    val rows = document.lines.joinToString("") {
        "<tr><td>${escape(it.productName)}</td><td>${escape(it.quantity.toString())}</td>" +
            "<td>${formatMinorAmount(it.unitPriceMinor)}</td><td>${formatMinorAmount(it.lineTotalMinor)}</td></tr>"
    }
    val html = """
        <!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body{font-family:sans-serif;margin:24px;color:#183b35;direction:rtl}
        h1{font-size:22px} .muted{color:#596963;font-size:12px}
        table{width:100%;border-collapse:collapse;margin-top:18px}
        th,td{border:1px solid #ccd7d2;padding:8px;text-align:right}
        th{background:#edf4f0} .totals{margin-top:18px;line-height:2}
        </style></head><body>
        <h1>فاتورة تشغيلية رقم #${document.receiptNumber}</h1>
        <p>العميل: ${escape(document.customerName)}</p>
        <p>التاريخ: ${escape(formatInvoiceTimestamp(document.createdAt))}</p>
        <p>الحالة: ${escape(statusLabel(document.saleStatus))} — الدفع: ${escape(statusLabel(document.paymentStatus))}</p>
        <table><thead><tr><th>الصنف</th><th>الكمية</th><th>سعر الوحدة</th><th>الإجمالي</th></tr></thead>
        <tbody>$rows</tbody></table>
        <div class="totals">
        <div>الإجمالي قبل الخصم: ${formatMinorAmount(document.subtotalMinor)} ${escape(document.currencyCode)}</div>
        <div>الخصم: ${formatMinorAmount(document.discountMinor)} ${escape(document.currencyCode)}</div>
        <div>الضريبة: ${formatMinorAmount(document.taxMinor)} ${escape(document.currencyCode)}</div>
        <div>الإجمالي: <strong>${formatMinorAmount(document.totalMinor)} ${escape(document.currencyCode)}</strong></div>
        <div>المدفوع: ${formatMinorAmount(paidMinor)} ${escape(document.currencyCode)}</div>
        <div>المتبقي: <strong>${formatMinorAmount(outstanding)} ${escape(document.currencyCode)}</strong></div>
        </div>
        <p class="muted">معرّف السجل المحفوظ: ${escape(document.id)}</p>
        <p class="muted">نسخة تشغيلية مطبوعة من بيانات الخادم؛ لا تُعد إثبات امتثال ضريبي محلي.</p>
        </body></html>
    """.trimIndent()
    val webView = WebView(context)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            val manager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
            val adapter = view.createPrintDocumentAdapter("invoice-${document.receiptNumber}")
            manager.print(
                "فاتورة-${document.receiptNumber}",
                adapter,
                PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build()
            )
        }
    }
    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
}


private fun formatMinorAmount(value: Long): String = String.format(Locale.US, "%,d", value)

private fun formatInvoiceTimestamp(value: String): String {
    // Supabase timestamps are ISO-8601 and commonly include Z or an explicit UTC offset.
    // Parse the instant first so the displayed local time is correct across time zones.
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull()
        ?: runCatching {
            val normalized = value.replace('T', ' ').take(19)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(normalized)?.toInstant()
        }.getOrNull()
        ?: return value.take(19).replace('T', ' ')
    return java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm", Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}

private fun statusLabel(value: String): String = when (value.lowercase()) {
    "paid", "succeeded" -> "مدفوعة"
    "completed" -> "مكتملة"
    "partially_paid", "partial" -> "مدفوعة جزئيًا"
    "unpaid", "due", "pending", "requested" -> "قيد الانتظار"
    "approved" -> "مقبول"
    "rejected" -> "مرفوض"
    "cancelled", "canceled" -> "ملغاة"
    "failed" -> "فاشلة"
    "refunded" -> "مستردة"
    "partially_refunded" -> "مستردة جزئيًا"
    "not_started" -> "لم تبدأ"
    "not_required" -> "غير مطلوب"
    "restocked" -> "أعيد للمخزون"
    "partially_restocked" -> "أعيد جزئيًا"
    "blocked" -> "محظور"
    "authorized" -> "مصرح"
    "draft" -> "مسودة"
    else -> value.ifBlank { "غير محدد" }
}

private fun statusColor(value: String): Color = when (value.lowercase()) {
    "paid", "completed", "succeeded", "approved", "refunded", "restocked" -> Color(0xFF167D63)
    "partially_paid", "partial", "partially_refunded", "partially_restocked", "pending", "requested", "authorized" -> Color(0xFFB36B00)
    "unpaid", "due", "credit", "not_started", "not_required", "draft" -> Color(0xFFB36B00)
    "failed", "cancelled", "canceled", "rejected", "blocked" -> Color(0xFFB3261E)
    else -> CloudSalesMuted
}
