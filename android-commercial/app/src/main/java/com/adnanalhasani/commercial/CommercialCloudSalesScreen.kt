package com.adnanalhasani.commercial

import android.content.Context
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }

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
            doc.lines.any { it.productName.contains(search, true) }
        val status = doc.paymentStatus.lowercase()
        val matchesStatus = when (selectedStatus) {
            "paid" -> status in setOf("paid", "completed", "succeeded")
            "partial" -> status in setOf("partially_paid", "partial")
            "due" -> status in setOf("unpaid", "due", "pending", "credit")
            "cancelled" -> status in setOf("failed", "cancelled", "canceled")
            else -> true
        }
        matchesSearch && matchesStatus
    }
    val totalsByCurrency = filtered.groupBy { it.currencyCode.ifBlank { "غير محدد" } }
        .mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
        .toSortedMap()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("فواتير المبيعات السحابية", fontWeight = FontWeight.Bold, color = CloudSalesInk)
                        Text("الفواتير الفعلية وبنود كل فاتورة من Supabase", fontSize = 11.sp, color = CloudSalesMuted)
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
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("ابحث برقم الفاتورة أو العميل أو الصنف") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
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
                        "cancelled" to "فشل / ملغاة"
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
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).background(Color(0xFFE8F5EF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = CloudSalesGreen)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("الفواتير المحمّلة", color = CloudSalesMuted, fontSize = 12.sp)
                                Text(documents.size.toString(), color = CloudSalesInk, fontWeight = FontWeight.Bold, fontSize = 23.sp)
                            }
                            Text("النتائج: ${filtered.size}", color = CloudSalesMuted, fontSize = 11.sp)
                        }
                        if (totalsByCurrency.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text("إجمالي النتائج حسب العملة (بوحدة الصرف الصغرى)", color = CloudSalesMuted, fontSize = 11.sp)
                            totalsByCurrency.forEach { (currency, total) ->
                                Text("$currency: ${String.format(java.util.Locale.US, "%,d", total)}", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
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
                Text(if (documents.isEmpty()) "لا توجد فواتير سحابية متاحة لهذا النشاط." else "لا توجد نتائج مطابقة للبحث.", color = CloudSalesMuted, modifier = Modifier.padding(8.dp))
            }
            items(filtered, key = { it.id }) { document ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(17.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CloudSalesBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("فاتورة #${document.receiptNumber}", color = CloudSalesInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(document.customerName, color = CloudSalesMuted, fontSize = 12.sp)
                            }
                            Surface(color = statusColor(document.paymentStatus).copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                                Text(statusLabel(document.paymentStatus), color = statusColor(document.paymentStatus), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                            }
                        }
                        Text(
                            "الإجمالي: ${document.totalMinor} ${document.currencyCode.ifBlank { "وحدة نقدية" }} (بوحدة الصرف الصغرى)",
                            color = CloudSalesGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp
                        )
                        if (document.createdAt.isNotBlank()) Text(document.createdAt.replace('T', ' ').take(19), color = CloudSalesMuted, fontSize = 10.sp)
                        Text("بنود الفاتورة: ${document.lines.size}", color = CloudSalesInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        document.lines.forEach { line ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                Text(line.productName, modifier = Modifier.weight(1f), color = CloudSalesInk, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text("× ${line.quantity}", color = CloudSalesMuted, fontSize = 12.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(line.lineTotalMinor.toString(), color = CloudSalesInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            }
                        }
                        Text("مرجع: ${document.id.take(8)} · عرض فقط", color = CloudSalesMuted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Text(
                    "هذه الشاشة تعرض الفواتير وبنودها من الخادم فقط. لا تنشئ فاتورة أو تعدّلها من هذه الشاشة، ولا يُفترض مقياس عشري للعملة.",
                    color = CloudSalesMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

private fun statusLabel(value: String): String = when (value.lowercase()) {
    "paid", "completed", "succeeded" -> "مدفوعة"
    "partially_paid", "partial" -> "مدفوعة جزئيًا"
    "unpaid", "due", "pending", "credit" -> "مستحقة"
    "failed", "cancelled", "canceled" -> "فشل / ملغاة"
    else -> value.ifBlank { "غير محدد" }
}

private fun statusColor(value: String): Color = when (value.lowercase()) {
    "paid", "completed", "succeeded" -> Color(0xFF167D63)
    "partially_paid", "partial" -> Color(0xFFB36B00)
    "unpaid", "due", "pending", "credit" -> Color(0xFFB36B00)
    "failed", "cancelled", "canceled" -> Color(0xFFB3261E)
    else -> CloudSalesMuted
}
