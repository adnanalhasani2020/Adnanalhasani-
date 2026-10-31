package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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

private val StockAlertsInk = Color(0xFF183B35)
private val StockAlertsGreen = Color(0xFF167D63)
private val StockAlertsMuted = Color(0xFF71817B)
private val StockAlertsWorker = Executors.newSingleThreadExecutor()
private val StockAlertsMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudStockAlertsScreen(context: Context, onBack: () -> Unit) {
    var products by remember { mutableStateOf<List<CommercialCloudStockAlert>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var costingPolicy by remember { mutableStateOf<CommercialInventoryCostingPolicyState?>(null) }
    var layerSummary by remember { mutableStateOf<CommercialCloudCostLayerSummary?>(null) }
    var selectedCostMethod by remember { mutableStateOf("weighted_average") }
    var costingScope by remember { mutableStateOf("location") }
    var costingLoading by remember { mutableStateOf(false) }
    var costingSaving by remember { mutableStateOf(false) }
    var costingError by remember { mutableStateOf("") }
    var costingNotice by remember { mutableStateOf("") }

    LaunchedEffect(reloadToken) {
        loading = true
        error = ""
        StockAlertsWorker.execute {
            val result = runCatching { loadCommercialCloudStockAlerts(context) }
            val policyResult = runCatching { loadCommercialInventoryCostingPolicy(context) }
            val layersResult = runCatching { loadCommercialCloudCostLayerSummary(context) }
            StockAlertsMain.post {
                result.onSuccess { products = it }
                    .onFailure { error = it.message ?: "تعذر تحميل المخزون السحابي." }
                policyResult.onSuccess {
                    costingPolicy = it
                    selectedCostMethod = if (costingScope == "organization") it.organizationMethod ?: it.effectiveMethod else it.locationMethod ?: it.effectiveMethod
                    costingError = ""
                }.onFailure {
                    costingError = it.message ?: "تعذر تحميل سياسة تكلفة المخزون."
                }
                layersResult.onSuccess { layerSummary = it }
                    .onFailure { layerSummary = null }
                loading = false
            }
        }
    }

    val lowStockCount = products.count { it.availableQuantity > 0.0 && it.availableQuantity <= it.reorderPoint }
    val outOfStockCount = products.count { it.availableQuantity <= 0.0 }
    val filtered = products.filter { product ->
        val matchesSearch = search.isBlank() ||
            product.productName.contains(search.trim(), ignoreCase = true) ||
            product.categoryName.contains(search.trim(), ignoreCase = true)
        val matchesFilter = when (filter) {
            "low" -> product.availableQuantity > 0.0 && product.availableQuantity <= product.reorderPoint
            "out" -> product.availableQuantity <= 0.0
            else -> true
        }
        matchesSearch && matchesFilter
    }.sortedWith(compareBy<CommercialCloudStockAlert> { if (it.availableQuantity <= 0.0) 0 else if (it.availableQuantity <= it.reorderPoint) 1 else 2 }.thenBy { it.productName.lowercase() })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("تنبيهات المخزون السحابي", color = StockAlertsInk, fontWeight = FontWeight.Bold)
                        Text("الكميات المتاحة وحدود إعادة الطلب", color = StockAlertsMuted, fontSize = 11.sp)
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard("الأصناف", products.size.toString(), Modifier.weight(1f))
                    MetricCard("تحت حد الطلب", lowStockCount.toString(), Modifier.weight(1f))
                    MetricCard("نفد المتاح", outOfStockCount.toString(), Modifier.weight(1f))
                }
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5EBE8))
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("طريقة تقييم تكلفة المخزون", color = StockAlertsInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "الطريقة الفعالة حاليًا: " + when (costingPolicy?.effectiveMethod ?: "weighted_average") {
                                "fifo" -> "FIFO — الوارد أولًا يصرف أولًا"
                                "lifo" -> "LIFO — الوارد أخيرًا يصرف أولًا"
                                else -> "المتوسط المرجح"
                            },
                            color = StockAlertsGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp
                        )
                        Text("اختر الطريقة التي ستطبق على عمليات الصرف الجديدة:", color = StockAlertsMuted, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(selected = selectedCostMethod == "fifo", onClick = { selectedCostMethod = "fifo" }, label = { Text("FIFO") })
                            FilterChip(selected = selectedCostMethod == "lifo", onClick = { selectedCostMethod = "lifo" }, label = { Text("LIFO") })
                            FilterChip(selected = selectedCostMethod == "weighted_average", onClick = { selectedCostMethod = "weighted_average" }, label = { Text("المتوسط") })
                        }
                        Text("نطاق تطبيق الإعداد:", color = StockAlertsMuted, fontSize = 11.sp)
                        if (costingScope == "organization") {
                            Text(
                                "سيؤثر الإعداد على المواقع التي لا تملك سياسة خاصة. ملخص التغطية أعلاه يخص الموقع الحالي فقط؛ تحقق من بقية المواقع قبل الحفظ.",
                                color = Color(0xFF9A5B00), fontSize = 10.sp
                            )
                        } else {
                            Text("سيطبق الإعداد على الموقع الحالي فقط.", color = StockAlertsMuted, fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            FilterChip(
                                selected = costingScope == "location",
                                onClick = {
                                    costingScope = "location"
                                    selectedCostMethod = costingPolicy?.locationMethod ?: costingPolicy?.effectiveMethod ?: "weighted_average"
                                },
                                label = { Text("هذا الموقع") }
                            )
                            FilterChip(
                                selected = costingScope == "organization",
                                onClick = {
                                    costingScope = "organization"
                                    selectedCostMethod = costingPolicy?.organizationMethod ?: "weighted_average"
                                },
                                label = { Text("المؤسسة بالكامل") }
                            )
                        }
                        Button(
                            onClick = {
                                costingSaving = true
                                costingError = ""
                                costingNotice = ""
                                StockAlertsWorker.execute {
                                    val savedResult = runCatching {
                                        saveCommercialInventoryCostingPolicy(context, selectedCostMethod, costingScope)
                                        loadCommercialInventoryCostingPolicy(context)
                                    }
                                    StockAlertsMain.post {
                                        savedResult.onSuccess {
                                            costingPolicy = it
                                            costingNotice = "تم حفظ طريقة التكلفة. لن تتغير تخصيصات الفواتير السابقة."
                                        }.onFailure {
                                            costingError = it.message ?: "تعذر حفظ طريقة التكلفة."
                                        }
                                        costingSaving = false
                                    }
                                }
                            },
                            enabled = !costingSaving && !loading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (costingSaving) "جارٍ الحفظ…" else "حفظ طريقة التكلفة")
                        }
                        if (costingError.isNotBlank()) Text(costingError, color = Color(0xFFB3261E), fontSize = 11.sp)
                        if (costingNotice.isNotBlank()) Text(costingNotice, color = StockAlertsGreen, fontSize = 11.sp)
                        Text(
                            "تغيير الطريقة لا يعيد كتابة تكلفة المبيعات السابقة. صلاحية الحفظ محكومة بدورك في المؤسسة على الخادم.",
                            color = StockAlertsMuted, fontSize = 10.sp
                        )
                    }
                }
            }
            item {
                val summary = layerSummary
                val totalOnHand = products.fold(java.math.BigDecimal.ZERO) { total, item -> total.add(item.quantityOnHand) }
                val uncovered = totalOnHand.subtract(summary?.remainingQuantity ?: java.math.BigDecimal.ZERO).max(java.math.BigDecimal.ZERO)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5EF)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("ملخص طبقات التكلفة النشطة", color = StockAlertsInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (summary == null) {
                            Text("بيانات طبقات التكلفة غير متاحة حاليًا. تحقق من تطبيق ترحيلات التكلفة على الخادم.", color = StockAlertsMuted, fontSize = 11.sp)
                        } else {
                            Text("الطبقات النشطة: ${summary.activeLayerCount} • الأصناف المغطاة: ${summary.offeringCount}", color = StockAlertsInk, fontSize = 12.sp)
                            Text("الكمية المغطاة بطبقات التكلفة: ${summary.remainingQuantity.stripTrailingZeros().toPlainString()}", color = StockAlertsInk, fontSize = 12.sp)
                            Text("قيمة التكلفة بوحدات العملة الصغرى: ${summary.inventoryValueMinor.toPlainString()}", color = StockAlertsInk, fontSize = 12.sp)
                            if (uncovered.signum() > 0) {
                                Text("كمية الرصيد التي لا تغطيها طبقات تكلفة: ${uncovered.stripTrailingZeros().toPlainString()} — لا تُخمن لها تكلفة.", color = Color(0xFF9A5B00), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("ابحث باسم الصنف أو التصنيف أو الباركود") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == "all", onClick = { filter = "all" }, label = { Text("الكل") })
                    FilterChip(selected = filter == "low", onClick = { filter = "low" }, label = { Text("تحت حد الطلب") })
                    FilterChip(selected = filter == "out", onClick = { filter = "out" }, label = { Text("نفد المتاح") })
                }
            }
            if (loading) item {
                Row(Modifier.fillMaxWidth().padding(22.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = StockAlertsGreen, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("جارٍ تحميل أرصدة المخزون...", color = StockAlertsMuted)
                }
            }
            if (error.isNotBlank()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0ED)), shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("تعذر تحميل المخزون", color = Color(0xFF9B3024), fontWeight = FontWeight.Bold)
                        Text(error, color = Color(0xFF9B3024), fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                        Button(onClick = { reloadToken++ }, modifier = Modifier.padding(top = 8.dp)) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && filtered.isEmpty()) item {
                Text(
                    if (products.isEmpty()) "لا توجد أرصدة مخزون متاحة لهذا النشاط والموقع." else "لا توجد أصناف تطابق الفلتر الحالي.",
                    color = StockAlertsMuted,
                    modifier = Modifier.padding(8.dp)
                )
            }
            items(filtered, key = { it.offeringId }) { product ->
                val out = product.availableQuantity <= 0.0
                val low = !out && product.availableQuantity <= product.reorderPoint
                val accent = if (out) Color(0xFFB3261E) else if (low) Color(0xFFB36B00) else StockAlertsGreen
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Color(0xFFE5EBE8))
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory2, contentDescription = null, tint = accent, modifier = Modifier.size(25.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(product.productName, color = StockAlertsInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(product.categoryName.ifBlank { "غير مصنف" }, color = StockAlertsMuted, fontSize = 11.sp)
                            }
                            Text(
                                when { out -> "نفد"; low -> "إعادة طلب"; else -> "متوفر" },
                                color = accent, fontWeight = FontWeight.SemiBold, fontSize = 11.sp
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("المتاح للبيع", color = StockAlertsMuted, fontSize = 10.sp)
                                Text("${formatStockQuantity(product.availableQuantity)} ${product.unitName}", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("حد إعادة الطلب", color = StockAlertsMuted, fontSize = 10.sp)
                                Text("${formatStockQuantity(product.reorderPoint)} ${product.unitName}", color = StockAlertsInk, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "الكمية المعروضة هي المتاح للبيع بعد طرح المحجوز من الرصيد. هذه شاشة متابعة فقط؛ لا تعدّل الأرصدة ولا تنشئ حركات مخزون.",
                    color = StockAlertsMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(0xFFE5EBE8))
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = StockAlertsMuted, fontSize = 10.sp)
            Text(value, color = StockAlertsInk, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

private fun formatStockQuantity(value: Double): String = java.text.DecimalFormat("0.######").format(value)
