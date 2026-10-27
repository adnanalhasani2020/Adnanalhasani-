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
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private val CloudComposerInk = Color(0xFF183B35)
private val CloudComposerGreen = Color(0xFF167D63)
private val CloudComposerMuted = Color(0xFF71817B)
private val CloudComposerBorder = Color(0xFFE5EBE8)
private data class ComposerSellUnit(val code: String, val label: String, val factor: Long, val isDefault: Boolean)
private data class ComposerDraftLine(val id: String, val offeringId: String, val quantityText: String, val unitCode: String)
private data class ComposerSelectedLine(val draft: ComposerDraftLine, val product: Product, val quantity: Int, val unit: ComposerSellUnit)

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
    var invoiceLines by remember { mutableStateOf<List<ComposerDraftLine>>(emptyList()) }
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
    val selectedLines = invoiceLines.mapNotNull { draft ->
        val product = products.firstOrNull { it.cloudOfferingId == draft.offeringId } ?: return@mapNotNull null
        val quantity = draft.quantityText.toIntOrNull()
        val units = cloudComposerSellUnits(product)
        val unit = units.firstOrNull { it.code == draft.unitCode }
            ?: units.firstOrNull { it.isDefault } ?: units.first()
        if (product.cloudOfferingId.isBlank() || quantity == null || quantity <= 0) null
        else ComposerSelectedLine(draft, product, quantity, unit)
    }
    val estimatedSubtotal = selectedLines.fold(BigInteger.ZERO) { sum, line ->
        sum + BigInteger.valueOf(line.product.cloudPriceMinor.coerceAtLeast(0L))
            .multiply(BigInteger.valueOf(line.unit.factor))
            .multiply(BigInteger.valueOf(line.quantity.toLong()))
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
                                    invoiceLines = invoiceLines + ComposerDraftLine(UUID.randomUUID().toString(), product.cloudOfferingId, "1", newUnitCode)
                                    notice = "تم حفظ وحدة البيع " + newUnitName + " ومعامل التحويل " + factor + " وإضافة سطر بها إلى الفاتورة."
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
                        Text(when (paymentMode) { "credit" -> "فاتورة سحابية · بيع آجل"; "partial" -> "فاتورة سحابية · دفع جزئي"; else -> "فاتورة سحابية متعددة الأصناف · نقدي" }, color = CloudComposerMuted, fontSize = 11.sp)
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
                            OutlinedButton(onClick = { paymentMode = "credit"; initialPaymentText = "0" }, enabled = !submitting) { Text(if (paymentMode == "credit") "✓ آجل / دين" else "آجل / دين") }
                            OutlinedButton(onClick = { paymentMode = "partial"; if (initialPaymentText == "0") initialPaymentText = "" }, enabled = !submitting) { Text(if (paymentMode == "partial") "✓ دفع جزئي" else "دفع جزئي") }
                        }
                        if (paymentMode == "partial") {
                            OutlinedTextField(value = initialPaymentText, onValueChange = { initialPaymentText = it.filter(Char::isDigit).take(15); error = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("المبلغ المدفوع الآن ($currency)") })
                            Text("يجب اختيار عميل. يجب أن يكون الدفع أكبر من صفر وأقل من إجمالي الفاتورة؛ ويسجل الخادم المتبقي كرصيد مستحق.", color = CloudComposerMuted, fontSize = 11.sp)
                        } else if (paymentMode == "credit") {
                            Text("يجب اختيار عميل. لن تُسجل دفعة عند إصدار الفاتورة، ويظل كامل المبلغ مستحقًا.", color = CloudComposerMuted, fontSize = 11.sp)
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
            items(visibleProducts, key = { "catalog-${it.cloudOfferingId}" }) { product ->
                val availableUnits = cloudComposerSellUnits(product)
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CloudComposerBorder)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(product.name, color = CloudComposerInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("المخزون المتاح: ${BigDecimal.valueOf(product.stockPrecise).stripTrailingZeros().toPlainString()} ${product.unitCode}", color = CloudComposerMuted, fontSize = 11.sp)
                        Text("وحدات البيع: " + availableUnits.joinToString("، ") { "${it.label} = ${it.factor} ${product.unitCode}" }, color = CloudComposerMuted, fontSize = 11.sp)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val defaultUnit = availableUnits.firstOrNull { it.isDefault } ?: availableUnits.first()
                                invoiceLines = invoiceLines + ComposerDraftLine(UUID.randomUUID().toString(), product.cloudOfferingId, "1", defaultUnit.code)
                                error = ""; notice = ""
                            }, enabled = !submitting, modifier = Modifier.weight(1f)) { Text("إضافة سطر للفاتورة") }
                            OutlinedButton(onClick = { configureUnitProduct = product; newUnitCode = "carton"; newUnitName = "كرتون"; newUnitFactor = "12" }, enabled = !submitting) { Text("إضافة وحدة") }
                        }
                    }
                }
            }
            if (invoiceLines.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("سطور الفاتورة (${invoiceLines.size})", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        invoiceLines.forEachIndexed { index, draft ->
                            val product = products.firstOrNull { it.cloudOfferingId == draft.offeringId }
                            if (product != null) {
                                val units = cloudComposerSellUnits(product)
                                val unit = units.firstOrNull { it.code == draft.unitCode } ?: units.firstOrNull { it.isDefault } ?: units.first()
                                var unitMenuExpanded by remember(draft.id) { mutableStateOf(false) }
                                Surface(color = Color(0xFFF7F9F8), shape = RoundedCornerShape(11.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text("${index + 1}. ${product.name}", color = CloudComposerInk, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                                Text("السعر: ${String.format(Locale.US, "%,d", BigInteger.valueOf(product.cloudPriceMinor).multiply(BigInteger.valueOf(unit.factor)))} $currency / ${unit.label}", color = CloudComposerGreen, fontSize = 11.sp)
                                            }
                                            OutlinedButton(onClick = { invoiceLines = invoiceLines.filterNot { it.id == draft.id }; error = "" }, enabled = !submitting) { Text("حذف") }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box {
                                                OutlinedButton(onClick = { unitMenuExpanded = true }, enabled = !submitting) { Text(unit.label) }
                                                DropdownMenu(expanded = unitMenuExpanded, onDismissRequest = { unitMenuExpanded = false }) {
                                                    units.forEach { option -> DropdownMenuItem(text = { Text("${option.label} (يساوي ${option.factor} ${product.unitCode})") }, onClick = { invoiceLines = invoiceLines.map { if (it.id == draft.id) it.copy(unitCode = option.code) else it }; unitMenuExpanded = false; error = "" }) }
                                                }
                                            }
                                            OutlinedTextField(value = draft.quantityText, onValueChange = { input -> invoiceLines = invoiceLines.map { if (it.id == draft.id) it.copy(quantityText = input.filter(Char::isDigit).take(6)) else it }; error = "" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("الكمية") })
                                            Text("= ${String.format(Locale.US, "%,d", BigInteger.valueOf(product.cloudPriceMinor).multiply(BigInteger.valueOf(unit.factor)).multiply(BigInteger.valueOf(draft.quantityText.toLongOrNull() ?: 0L)))}", color = CloudComposerInk, fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Text("يمكن تكرار الصنف في أكثر من سطر مع اختيار وحدة وكمية مستقلتين لكل سطر.", color = CloudComposerMuted, fontSize = 11.sp)
                    }
                }
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(15.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ملخص الفاتورة", color = CloudComposerInk, fontWeight = FontWeight.Bold)
                        Text("عدد سطور الفاتورة: ${selectedLines.size}", color = CloudComposerMuted, fontSize = 12.sp)
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
                        } else if (selectedLines.size != invoiceLines.size) {
                            error = "صحح الكميات في جميع سطور الفاتورة قبل المتابعة."
                        } else if (selectedLines.groupBy { it.product.cloudOfferingId }.any { (_, lines) ->
                            val requestedBaseQuantity = lines.fold(BigDecimal.ZERO) { sum, line ->
                                sum + BigDecimal.valueOf(line.quantity.toLong()).multiply(BigDecimal.valueOf(line.unit.factor))
                            }
                            requestedBaseQuantity > BigDecimal.valueOf(lines.first().product.stockPrecise)
                        }) {
                            error = "مجموع الكميات بوحدة المخزون يتجاوز المتاح لأحد الأصناف؛ حدّث المخزون وصحح السطور."
                        } else if (selectedLines.any { it.product.cloudPriceMinor < 0L }) {
                            error = "يوجد سعر غير صالح؛ حدّث الأصناف."
                        } else if (discountMinor < 0L || taxMinor < 0L || discountMinor > estimatedSubtotal.min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()) {
                            error = "تحقق من الخصم والضريبة؛ يجب ألا يتجاوز الخصم إجمالي الأصناف."
                        } else if (estimatedSubtotal > BigInteger.valueOf(Long.MAX_VALUE) || estimatedTotal > BigInteger.valueOf(Long.MAX_VALUE)) {
                            error = "إجمالي الفاتورة يتجاوز الحد المالي المسموح."
                        } else {
                            submitting = true
                            notice = "جارٍ التحقق من الأصناف والمخزون وتسجيل فاتورة واحدة على الخادم..."
                            val requestLines = selectedLines.map { CommercialCloudCheckoutLine(it.product.cloudOfferingId, it.quantity, it.unit.code) }
                            val customerId = selectedCustomer?.id
                            val paymentModeForRequest = if (paymentMode == "cash") "cash" else "credit"
                            val initialPaymentMinor = when (paymentMode) { "partial" -> initialPaymentText.toLongOrNull(); else -> 0L }
                            val discountForRequest = discountMinor
                            val taxForRequest = taxMinor
                            CloudComposerWorker.execute {
                                val result = runCatching {
                                    require(paymentMode != "credit" || !customerId.isNullOrBlank()) { "اختر العميل قبل تسجيل البيع الآجل." }
                                    require(paymentModeForRequest != "credit" || !customerId.isNullOrBlank()) { "اختر العميل قبل تسجيل البيع الآجل أو الدفع الجزئي." }
                                    require(paymentMode != "partial" || initialPaymentMinor != null) { "أدخل مبلغًا صحيحًا للدفع الجزئي." }
                                    require(paymentMode != "partial" || (initialPaymentMinor != null && initialPaymentMinor > 0L && BigInteger.valueOf(initialPaymentMinor) < estimatedTotal)) { "الدفع الجزئي يجب أن يكون أكبر من صفر وأقل من إجمالي الفاتورة؛ اختر النقدي الكامل إذا دفعت الإجمالي." }
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
                                        if (clearError == null) invoiceLines = emptyList()
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
