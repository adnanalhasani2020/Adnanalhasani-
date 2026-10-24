package com.adnanalhasani.commercial

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.Executors

private val CloudComposerInk = Color(0xFF183B35)
private val CloudComposerGreen = Color(0xFF167D63)
private val CloudComposerMuted = Color(0xFF71817B)
private val CloudComposerBorder = Color(0xFFE5EBE8)
private val CloudComposerWorker = Executors.newSingleThreadExecutor()
private val CloudComposerMain = Handler(Looper.getMainLooper())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommercialCloudSaleComposerScreen(
    context: Context,
    onBack: () -> Unit,
    onCompleted: () -> Unit
) {
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var customers by remember { mutableStateOf<List<CommercialCloudCustomer>>(emptyList()) }
    var customerSearch by remember { mutableStateOf("") }
    var selectedCustomer by remember { mutableStateOf<CommercialCloudCustomer?>(null) }
    var paymentMode by remember { mutableStateOf("cash") }
    var initialPaymentText by remember { mutableStateOf("0") }
    var quantities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reloadToken by remember { mutableStateOf(0) }
    val currency = remember {
        context.getSharedPreferences("commercial_cloud_context_v1", Context.MODE_PRIVATE)
            .getString("currency_code", "YER").orEmpty().ifBlank { "YER" }
    }

    LaunchedEffect(reloadToken) {
        loading = true
        error = ""
        CloudComposerWorker.execute {
            val result = runCatching { Pair(loadCommercialCloudInventory(context), loadCommercialCloudCustomers(context)) }
            CloudComposerMain.post {
                result.onSuccess { (inventory, customerRows) -> products = inventory.products; customers = customerRows }
                    .onFailure { error = it.message ?: "تعذر تحميل الأصناف السحابية." }
                loading = false
            }
        }
    }

    val visibleProducts = products.filter { it.name.contains(search, ignoreCase = true) || it.category.contains(search, ignoreCase = true) || it.barcode.contains(search, ignoreCase = true) }
    val selectedLines = products.mapNotNull { product ->
        val quantity = quantities[product.cloudOfferingId]?.toIntOrNull()
        if (product.cloudOfferingId.isBlank() || quantity == null || quantity <= 0) null else product to quantity
    }
    val estimatedTotal = selectedLines.fold(BigInteger.ZERO) { sum, entry ->
        sum + BigInteger.valueOf(entry.first.cloudPriceMinor.coerceAtLeast(0L))
            .multiply(BigInteger.valueOf(entry.second.toLong()))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("فاتورة بيع جديدة", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Text(if (paymentMode == "credit") "فاتورة سحابية · بيع آجل" else "فاتورة سحابية متعددة الأصناف · نقدي", color = CloudComposerMuted, fontSize = 11.sp)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack, enabled = !submitting) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") } },
                actions = {
                    IconButton(onClick = { reloadToken++ }, enabled = !loading && !submitting) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث الأصناف")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF5F7F6))
            )
        },
        containerColor = Color(0xFFF5F7F6)
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("اختر الأصناف والكميات", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Text("السعر والمخزون والتحصيل والدين تُسجل على الخادم ضمن معاملة واحدة.", color = CloudComposerMuted, fontSize = 11.sp)
                    }
                }
            }
            if (loading) item {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = CloudComposerGreen, strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("جارٍ تحميل المخزون...", color = CloudComposerMuted)
                }
            }
            if (error.isNotBlank()) item {
                Surface(color = Color(0xFFFFF0ED), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(error, color = Color(0xFF9B3024), fontSize = 12.sp)
                        OutlinedButton(onClick = { reloadToken++ }, enabled = !loading && !submitting) { Text("إعادة المحاولة") }
                    }
                }
            }
            if (!loading && error.isBlank() && products.isEmpty()) item {
                Text("لا توجد أصناف مرتبطة بمخزون الموقع الحالي.", color = CloudComposerMuted)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("طريقة الدفع", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { paymentMode = "cash"; initialPaymentText = "0" }, enabled = !submitting) { Text(if (paymentMode == "cash") "✓ نقدي كامل" else "نقدي كامل") }
                            OutlinedButton(onClick = { paymentMode = "credit" }, enabled = !submitting) { Text(if (paymentMode == "credit") "✓ آجل / دين" else "آجل / دين") }
                        }
                        if (paymentMode == "credit") {
                            OutlinedTextField(value = initialPaymentText, onValueChange = { initialPaymentText = it.filter(Char::isDigit).take(15) }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("المبلغ المدفوع الآن ($currency)") })
                            Text("يجب اختيار عميل. يُسجل المتبقي كرصيد مستحق على الفاتورة، ويمكن تحصيله لاحقًا.", color = CloudComposerMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ربط الفاتورة بالعميل", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Text(selectedCustomer?.label ?: "لم يتم اختيار عميل — ستُسجل الفاتورة كبيع نقدي عام", color = CloudComposerMuted, fontSize = 12.sp)
                        OutlinedTextField(value = customerSearch, onValueChange = { customerSearch = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ابحث عن عميل بالاسم أو الهاتف أو البريد") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { selectedCustomer = null; customerSearch = "" }, enabled = !submitting) { Text("بيع دون عميل") }
                            OutlinedButton(onClick = { reloadToken++ }, enabled = !loading && !submitting) { Text("تحديث العملاء") }
                        }
                        customers.filter { customerSearch.isBlank() || it.label.contains(customerSearch, ignoreCase = true) }
                            .take(12).forEach { customer ->
                                OutlinedButton(onClick = { selectedCustomer = customer; customerSearch = customer.displayName }, modifier = Modifier.fillMaxWidth(), enabled = !submitting) {
                                    Text(if (selectedCustomer?.id == customer.id) "✓ ${customer.label}" else customer.label, maxLines = 2)
                                }
                            }
                        if (customers.isEmpty() && !loading) Text("لا يوجد عملاء نشطون متاحون في المؤسسة الحالية.", color = CloudComposerMuted, fontSize = 11.sp)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("بحث عن صنف") },
                    placeholder = { Text("الاسم أو التصنيف أو الباركود") }
                )
            }
            items(visibleProducts, key = { it.cloudOfferingId }) { product ->
                var quantityText = quantities[product.cloudOfferingId].orEmpty()
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CloudComposerBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(product.name, color = CloudComposerInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("السعر: ${String.format(Locale.US, "%,d", product.cloudPriceMinor)} $currency", color = CloudComposerGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("المتاح: ${product.stock} ${product.unitCode}", color = CloudComposerMuted, fontSize = 11.sp)
                        }
                        OutlinedTextField(
                            value = quantityText,
                            onValueChange = { input -> quantities = quantities + (product.cloudOfferingId to input.filter(Char::isDigit).take(6)); error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("الكمية المطلوبة") },
                            placeholder = { Text("اتركه فارغًا إذا لم ترد الصنف") }
                        )
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ملخص الفاتورة", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Text("عدد الأصناف: ${selectedLines.size}", color = CloudComposerMuted, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإجمالي التقديري", color = CloudComposerMuted)
                            Text("${String.format(Locale.US, "%,d", estimatedTotal)} $currency", color = CloudComposerGreen, fontWeight = FontWeight.Bold)
                        }
                        Text("الإجمالي النهائي والسعر المعتمد يحددهما الخادم.", color = CloudComposerMuted, fontSize = 10.sp)
                    }
                }
            }
            if (notice.isNotBlank()) item {
                Surface(color = Color(0xFFE8F5EF), shape = RoundedCornerShape(12.dp)) {
                    Text(notice, color = CloudComposerInk, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(12.dp))
                }
            }
            item {
                Button(
                    onClick = {
                        error = ""
                        if (selectedLines.isEmpty()) {
                            error = "أدخل كمية لصنف واحد على الأقل."
                        } else if (selectedLines.any { it.second > it.first.stock }) {
                            error = "توجد كمية تتجاوز المخزون المتاح؛ حدّث المخزون وصحح الكميات."
                        } else if (selectedLines.any { it.first.cloudPriceMinor < 0L }) {
                            error = "يوجد سعر غير صالح؛ حدّث الأصناف."
                        } else {
                            submitting = true
                            notice = "جارٍ التحقق من الأصناف والمخزون وتسجيل فاتورة واحدة على الخادم..."
                            val requestLines = selectedLines.map { CommercialCloudCheckoutLine(it.first.cloudOfferingId, it.second) }
                            val customerId = selectedCustomer?.id
                            val paymentModeForRequest = paymentMode
                            val initialPaymentMinor = initialPaymentText.toLongOrNull()
                            CloudComposerWorker.execute {
                                val result = runCatching {
                                    require(paymentModeForRequest != "credit" || !customerId.isNullOrBlank()) { "اختر العميل قبل تسجيل البيع الآجل." }
                                    require(paymentModeForRequest != "credit" || initialPaymentMinor != null) { "أدخل مبلغًا صحيحًا للدفعة الأولى." }
                                    val key = getOrCreateCommercialCloudMultiSaleKey(context, requestLines, customerId, paymentModeForRequest, initialPaymentMinor ?: 0L)
                                    val sale = checkoutCommercialCloudSaleMulti(context, requestLines, key, customerId, paymentModeForRequest, initialPaymentMinor ?: 0L)
                                    val clearResult = runCatching { clearCommercialCloudMultiSaleKey(context, key) }
                                    val receipt = sale.optString("receipt_number", "—")
                                    val paymentRecorded = sale.optBoolean("payment_recorded", false)
                                    val outstanding = sale.optLong("outstanding_minor", 0L)
                                    Pair(Triple(receipt, paymentRecorded, clearResult.exceptionOrNull()), Pair(outstanding, sale.optString("payment_status")))
                                }
                                CloudComposerMain.post {
                                    submitting = false
                                    result.onSuccess { (summary, debt) ->
                                        val (receipt, paymentRecorded, clearError) = summary
                                        val (outstanding, paymentStatus) = debt
                                        if (clearError == null) quantities = emptyMap()
                                        notice = if (clearError == null) {
                                            "تم تأكيد الفاتورة رقم $receipt على الخادم. الحالة: $paymentStatus. ${if (paymentRecorded) "سُجلت الدفعة الأولى." else "لم يُسجل دفع بعد."} المتبقي على الفاتورة: $outstanding $currency."
                                        } else {
                                            "تم تأكيد الفاتورة رقم $receipt على الخادم، لكن تعذر تنظيف مفتاح المحاولة. لا تنشئ فاتورة جديدة؛ أعد المحاولة بنفس الطلب."
                                        }
                                        onCompleted()
                                    }.onFailure {
                                        error = it.message ?: "تعذر تأكيد الفاتورة؛ لم نعرضها كفاتورة ناجحة."
                                        notice = ""
                                    }
                                }
                            }
                        }
                    },
                    enabled = !loading && !submitting && products.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (submitting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.ReceiptLong, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (submitting) "جارٍ الحفظ..." else "تأكيد الفاتورة السحابية")
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
