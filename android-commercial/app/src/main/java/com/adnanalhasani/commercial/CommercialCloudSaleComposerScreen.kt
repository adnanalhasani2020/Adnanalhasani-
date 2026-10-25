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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
private data class ComposerSellUnit(val code: String, val label: String, val factor: Long, val isDefault: Boolean)

private fun cloudComposerSellUnits(product: Product): List<ComposerSellUnit> {
    val parsed = runCatching {
        val json = org.json.JSONArray(product.unitVariantsJson)
        List(json.length()) { index ->
            val row = json.getJSONObject(index)
            ComposerSellUnit(
                code = row.optString("unitCode").ifBlank { product.unitCode.ifBlank { "each" } },
                label = row.optString("unitName").ifBlank { row.optString("unitCode", product.unitCode) },
                factor = row.optLong("quantityInBase", 1L).coerceAtLeast(1L),
                isDefault = row.optBoolean("isDefault", false)
            )
        }.filter { it.code.isNotBlank() }.distinctBy { it.code }
    }.getOrDefault(emptyList())
    return parsed.ifEmpty { listOf(ComposerSellUnit(product.unitCode.ifBlank { "each" }, product.unitCode.ifBlank { "حبة" }, 1L, true)) }
}

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
    var discountText by remember { mutableStateOf("0") }
    var taxText by remember { mutableStateOf("0") }
    var quantities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedUnitCodes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var configureUnitProduct by remember { mutableStateOf<Product?>(null) }
    var newUnitCode by remember { mutableStateOf("carton") }
    var newUnitName by remember { mutableStateOf("كرتون") }
    var newUnitFactor by remember { mutableStateOf("12") }
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
        val units = cloudComposerSellUnits(product)
        val unit = units.firstOrNull { it.code == selectedUnitCodes[product.cloudOfferingId] }
            ?: units.firstOrNull { it.isDefault } ?: units.first()
        if (product.cloudOfferingId.isBlank() || quantity == null || quantity <= 0) null else Triple(product, quantity, unit)
    }
    val estimatedSubtotal = selectedLines.fold(BigInteger.ZERO) { sum, entry ->
        sum + BigInteger.valueOf(entry.first.cloudPriceMinor.coerceAtLeast(0L))
            .multiply(BigInteger.valueOf(entry.third.factor))
            .multiply(BigInteger.valueOf(entry.second.toLong()))
    }
    val discountMinor = discountText.toLongOrNull() ?: 0L
    val taxMinor = taxText.toLongOrNull() ?: 0L
    val estimatedTotal = (estimatedSubtotal - BigInteger.valueOf(discountMinor) + BigInteger.valueOf(taxMinor))
        .coerceAtLeast(BigInteger.ZERO)

    if (configureUnitProduct != null) {
        AlertDialog(
            onDismissRequest = { if (!submitting) configureUnitProduct = null },
            title = { Text("إضافة وحدة بيع للصنف") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(configureUnitProduct?.name.orEmpty(), color = CloudComposerMuted)
                    OutlinedTextField(value = newUnitCode, onValueChange = { newUnitCode = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' }.take(32) }, label = { Text("رمز الوحدة (مثال carton)") }, singleLine = true)
                    OutlinedTextField(value = newUnitName, onValueChange = { newUnitName = it.take(80) }, label = { Text("اسم الوحدة (مثال كرتون)") }, singleLine = true)
                    OutlinedTextField(value = newUnitFactor, onValueChange = { newUnitFactor = it.filter(Char::isDigit).take(7) }, label = { Text("عدد وحدات المخزون داخلها (مثال 12 حبة)") }, singleLine = true)
                    Text("السعر يُحسب من سعر وحدة المخزون × معامل التحويل. يجب أن يكون المعامل عددًا صحيحًا.", color = CloudComposerMuted, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val product = configureUnitProduct ?: return@Button
                    val factor = newUnitFactor.toLongOrNull()
                    if (newUnitCode.isBlank() || newUnitName.isBlank() || factor == null || factor !in 2L..1_000_000L) {
                        error = "أدخل رمزًا واسمًا ومعامل تحويل أكبر من 1."
                    } else {
                        submitting = true
                        CloudComposerWorker.execute {
                            val result = runCatching { configureCommercialCloudSellUnit(context, product.cloudOfferingId, newUnitCode, newUnitName, factor) }
                            CloudComposerMain.post {
                                submitting = false
                                result.onSuccess {
                                    configureUnitProduct = null
                                    selectedUnitCodes = selectedUnitCodes + (product.cloudOfferingId to newUnitCode)
                                    notice = "تم حفظ وحدة البيع " + newUnitName + " ومعامل التحويل " + factor + "."
                                    reloadToken++
                                }.onFailure { error = it.message ?: "تعذر حفظ وحدة البيع." }
                            }
                        }
                    }
                }, enabled = !submitting) { Text("حفظ الوحدة") }
            },
            dismissButton = { OutlinedButton(onClick = { configureUnitProduct = null }, enabled = !submitting) { Text("إلغاء") } }
        )
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
                var unitMenuExpanded by remember(product.cloudOfferingId) { mutableStateOf(false) }
                val availableUnits = cloudComposerSellUnits(product)
                val selectedUnit = availableUnits.firstOrNull { it.code == selectedUnitCodes[product.cloudOfferingId] }
                    ?: availableUnits.firstOrNull { it.isDefault } ?: availableUnits.first()
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, CloudComposerBorder)
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(product.name, color = CloudComposerInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            val sellPrice = BigInteger.valueOf(product.cloudPriceMinor.coerceAtLeast(0L)).multiply(BigInteger.valueOf(selectedUnit.factor))
                            Text("السعر: ${String.format(Locale.US, "%,d", sellPrice)} $currency / ${selectedUnit.label}", color = CloudComposerGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("المتاح: ${product.stock / selectedUnit.factor.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)} ${selectedUnit.label}", color = CloudComposerMuted, fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (availableUnits.size > 1) {
                                Box {
                                OutlinedButton(onClick = { unitMenuExpanded = true }, enabled = !submitting) {
                                    Text("وحدة البيع: ${selectedUnit.label}")
                                }
                                DropdownMenu(expanded = unitMenuExpanded, onDismissRequest = { unitMenuExpanded = false }) {
                                    availableUnits.forEach { unit ->
                                        DropdownMenuItem(
                                            text = { Text("${unit.label} (يساوي ${unit.factor} ${product.unitCode})") },
                                            onClick = {
                                                selectedUnitCodes = selectedUnitCodes + (product.cloudOfferingId to unit.code)
                                                unitMenuExpanded = false
                                                error = ""
                                            }
                                        )
                                    }
                                }
                                }
                            }
                            OutlinedButton(onClick = {
                                configureUnitProduct = product
                                newUnitCode = "carton"
                                newUnitName = "كرتون"
                                newUnitFactor = "12"
                            }, enabled = !submitting) { Text("إضافة وحدة") }
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
                            Text("الإجمالي الفرعي", color = CloudComposerMuted)
                            Text("${String.format(Locale.US, "%,d", estimatedSubtotal)} $currency", color = CloudComposerInk, fontWeight = FontWeight.Medium)
                        }
                        OutlinedTextField(
                            value = discountText,
                            onValueChange = { discountText = it.filter(Char::isDigit).take(15); error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("خصم الفاتورة بمبلغ ثابت ($currency)") }
                        )
                        OutlinedTextField(
                            value = taxText,
                            onValueChange = { taxText = it.filter(Char::isDigit).take(15); error = "" },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("ضريبة الفاتورة بمبلغ ثابت ($currency)") }
                        )
                        Text("أدخل مبالغ الخصم والضريبة يدويًا بأصغر وحدة نقدية. لا تُستنتج نسبة ضريبية أو ولاية قضائية تلقائيًا.", color = CloudComposerMuted, fontSize = 10.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("الإجمالي التقديري بعد الخصم والضريبة", color = CloudComposerMuted)
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
                        } else if (selectedLines.any { it.second.toLong() * it.third.factor > it.first.stock.toLong() }) {
                            error = "توجد كمية تتجاوز المخزون المتاح؛ حدّث المخزون وصحح الكميات."
                        } else if (selectedLines.any { it.first.cloudPriceMinor < 0L }) {
                            error = "يوجد سعر غير صالح؛ حدّث الأصناف."
                        } else if (discountMinor < 0L || taxMinor < 0L || discountMinor > estimatedSubtotal.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()) {
                            error = "تحقق من الخصم والضريبة؛ يجب ألا يتجاوز الخصم إجمالي الأصناف."
                        } else if (estimatedSubtotal > BigInteger.valueOf(Long.MAX_VALUE) || estimatedTotal > BigInteger.valueOf(Long.MAX_VALUE)) {
                            error = "إجمالي الفاتورة يتجاوز الحد المالي المسموح."
                        } else {
                            submitting = true
                            notice = "جارٍ التحقق من الأصناف والمخزون وتسجيل فاتورة واحدة على الخادم..."
                            val requestLines = selectedLines.map { CommercialCloudCheckoutLine(it.first.cloudOfferingId, it.second, it.third.code) }
                            val customerId = selectedCustomer?.id
                            val paymentModeForRequest = paymentMode
                            val initialPaymentMinor = initialPaymentText.toLongOrNull()
                            val discountForRequest = discountMinor
                            val taxForRequest = taxMinor
                            CloudComposerWorker.execute {
                                val result = runCatching {
                                    require(paymentModeForRequest != "credit" || !customerId.isNullOrBlank()) { "اختر العميل قبل تسجيل البيع الآجل." }
                                    require(paymentModeForRequest != "credit" || initialPaymentMinor != null) { "أدخل مبلغًا صحيحًا للدفعة الأولى." }
                                    val key = getOrCreateCommercialCloudMultiSaleKey(context, requestLines, customerId, paymentModeForRequest, initialPaymentMinor ?: 0L, discountForRequest, taxForRequest)
                                    val sale = checkoutCommercialCloudSaleMulti(context, requestLines, key, customerId, paymentModeForRequest, initialPaymentMinor ?: 0L, discountForRequest, taxForRequest)
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
