package com.adnanalhasani.commercial

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.concurrent.Executors
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MoreScreen(
    cloudMode: Boolean,
    onCustomers: () -> Unit,
    onReports: () -> Unit,
    onExpenses: () -> Unit,
    onActivityProfile: () -> Unit,
    onPersonalAccounts: () -> Unit,
    onPersonalWorkNotes: () -> Unit,
    onAds: () -> Unit,
    onCatalog: () -> Unit,
    onMasterData: () -> Unit,
    onCloudOperational: (String) -> Unit,
    onCloudSales: () -> Unit,
    onCloudPurchases: () -> Unit,
    onCloudStockAlerts: () -> Unit,
    onUnitsDirectory: () -> Unit,
    onCustomerPortal: () -> Unit,
    onCustomerOrders: () -> Unit,
    onCustomerSupplyRequests: () -> Unit,
    onCreatePurchaseRequest: () -> Unit,
    onBackup: () -> Unit
) {
    val sections = listOf(
        "الأصناف والمخزون والمشتريات" to listOf(
            "دليل الأصناف العالمي", "المستودعات والمجموعات والأقسام",
            "دليل الموردين", "إنشاء طلب شراء", "أوامر الشراء", "تنبيهات المخزون السحابي", "دليل وحدات القياس"
        ),
        "العملاء والمبيعات" to listOf(
            "العملاء والمديونيات", "دليل العملاء السحابي", "فواتير المبيعات السحابية",
            "مرتجعات المبيعات", "بوابة الزبون", "طلبات الزبائن", "طلبات توفير الأصناف"
        ),
        "الحسابات والتقارير" to listOf(
            "السندات والمدفوعات", "القيود المحاسبية", "دليل الحسابات",
            "التقارير والأرباح", "المصروفات"
        ),
        "إعدادات وأدوات المتجر" to listOf(
            "إعدادات المتجر", "الحسابات الفرعية", "ملاحظاتي وتنبيهاتي", "الإعلانات والعروض", "النسخ الاحتياطي"
        )
    ).map { (title, entries) ->
        title to entries.filter { cloudMode || it !in setOf(
            "دليل العملاء السحابي", "دليل الموردين", "إنشاء طلب شراء", "أوامر الشراء", "السندات والمدفوعات",
            "مرتجعات المبيعات", "القيود المحاسبية", "دليل الحسابات", "فواتير المبيعات السحابية",
            "تنبيهات المخزون السحابي", "دليل وحدات القياس", "بوابة الزبون", "طلبات الزبائن", "طلبات توفير الأصناف"
        ) }
    }.filter { it.second.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Heading("إدارة المتجر", "اختر القسم الذي تريد العمل عليه") }
        sections.forEach { (sectionTitle, entries) ->
            item { Heading(sectionTitle, "الأدوات المتاحة في هذا القسم") }
            items(entries) { label ->
                Card(
                    onClick = {
                        when (label) {
                            "العملاء والمديونيات" -> onCustomers()
                            "التقارير والأرباح" -> onReports()
                            "المصروفات" -> onExpenses()
                            "إعدادات المتجر" -> onActivityProfile()
                            "الحسابات الفرعية" -> onPersonalAccounts()
                            "ملاحظاتي وتنبيهاتي" -> onPersonalWorkNotes()
                            "الإعلانات والعروض" -> onAds()
                            "دليل الأصناف العالمي" -> onCatalog()
                            "المستودعات والمجموعات والأقسام" -> onMasterData()
                            "دليل العملاء السحابي" -> onCloudOperational("customers")
                            "دليل الموردين" -> onCloudOperational("suppliers")
                            "أوامر الشراء" -> onCloudPurchases()
                            "السندات والمدفوعات" -> onCloudOperational("payments")
                            "مرتجعات المبيعات" -> onCloudOperational("returns")
                            "القيود المحاسبية" -> onCloudOperational("journals")
                            "دليل الحسابات" -> onCloudOperational("accounts")
                            "فواتير المبيعات السحابية" -> onCloudSales()
                            "تنبيهات المخزون السحابي" -> onCloudStockAlerts()
                            "دليل وحدات القياس" -> onUnitsDirectory()
                            "بوابة الزبون" -> onCustomerPortal()
                            "طلبات الزبائن" -> onCustomerOrders()
                            "طلبات توفير الأصناف" -> onCustomerSupplyRequests()
                            "إنشاء طلب شراء" -> onCreatePurchaseRequest()
                            "النسخ الاحتياطي" -> onBackup()
                        }
                    },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, Border)
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).background(Mint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, null, tint = Green)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(label, modifier = Modifier.weight(1f), color = Ink, fontWeight = FontWeight.Medium)
                        Icon(Icons.Default.ChevronLeft, null, tint = Muted)
                    }
                }
            }
        }
        item {
            Text(
                "تعمل الشاشات السحابية على سجلات Supabase الفعلية. إذا كانت عملية غير متاحة بعد، سيظهر تنبيه بدل تنفيذ عملية غير مكتملة.",
                color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
internal fun ExpensesScreen(expenses: List<ExpenseRecord>, onBack: () -> Unit, onAdd: () -> Unit) {
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0); calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0); calendar.set(java.util.Calendar.MILLISECOND, 0)
    val start = calendar.timeInMillis
    val today = expenses.filter { it.timestamp >= start && it.timestamp < start + 86400000L }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Heading("المصروفات", "تسجيل ومتابعة مصروفات المتجر")
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي مصروفات اليوم", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(today.sumOf { it.amount }), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("${today.size} مصروف مسجل", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                }
            }
        }
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("تسجيل مصروف") } }
        item { Heading("سجل المصروفات", "الأحدث أولًا") }
        if (expenses.isEmpty()) item { Text("لا توجد مصروفات مسجلة بعد.", color = Muted) }
        items(expenses.sortedByDescending { it.timestamp }, key = { it.id }) { expense ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(expense.category, color = Ink, fontWeight = FontWeight.SemiBold)
                    if (expense.note.isNotBlank()) Text(expense.note, color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(money(expense.amount), color = Orange, fontWeight = FontWeight.Bold)
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(expense.timestamp)), color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpenseEntryDialog(onDismiss: () -> Unit, onSave: (String, Int, String) -> Unit) {
    val categories = listOf("إيجار", "كهرباء ومياه", "نقل ومواصلات", "رواتب", "مشتريات تشغيلية", "صيانة", "أخرى")
    var category by remember { mutableStateOf(categories.first()) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسجيل مصروف جديد") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(value = category, onValueChange = {}, readOnly = true, label = { Text("تصنيف المصروف") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { category = option; expanded = false }) }
                    }
                }
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(9); error = null },
                    label = { Text("المبلغ بالريال اليمني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = note, onValueChange = { note = it.take(120) }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = { Button(onClick = {
            val amount = amountText.toIntOrNull()
            if (amount == null || amount <= 0) error = "أدخل مبلغًا أكبر من صفر."
            else onSave(category, amount, note.trim())
        }) { Text("حفظ المصروف") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
internal fun ReportsScreen(sales: List<SaleRecord>, settlements: List<SettlementRecord>, expenses: List<ExpenseRecord>, onBack: () -> Unit) {
    val today = remember { java.time.LocalDate.now() }
    var fromText by remember { mutableStateOf(today.toString()) }
    var toText by remember { mutableStateOf(today.toString()) }
    var error by remember { mutableStateOf("") }
    var range by remember { mutableStateOf(today to today) }
    var presetLabel by remember { mutableStateOf("اليوم") }
    val zone = java.time.ZoneId.systemDefault()
    val startMs = range.first.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = range.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val periodSales = sales.filter { it.createdAt >= startMs && it.createdAt < endMs }
    val periodPayments = settlements.filter { it.timestamp >= startMs && it.timestamp < endMs }
    val periodExpenses = expenses.filter { it.timestamp >= startMs && it.timestamp < endMs }
    val salesTotal = periodSales.sumOf { it.total.toLong() }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val cashSales = periodSales.filter { !it.credit }.sumOf { it.total.toLong() }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val creditSales = periodSales.filter { it.credit }.sumOf { it.total.toLong() }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val expenseTotal = periodExpenses.sumOf { it.amount.toLong() }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val cogs = periodSales.sumOf { it.quantity.toLong() * it.unitCost.toLong() }.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val collections = (cashSales.toLong() + periodPayments.sumOf { it.amount.toLong() }).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val grossProfit = (salesTotal.toLong() - cogs).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val netResult = (grossProfit.toLong() - expenseTotal).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val cashFlow = (collections.toLong() - expenseTotal).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    val outstanding = sales.filter { it.credit }.sumOf { (it.total.toLong() - it.paid.toLong()).coerceAtLeast(0L) }.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    fun setPreset(first: java.time.LocalDate, last: java.time.LocalDate, label: String) {
        fromText = first.toString(); toText = last.toString(); range = first to last; presetLabel = label; error = ""
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Heading("التقارير التشغيلية", if (range.first == range.second) range.first.toString() else range.first.toString() + " — " + range.second.toString())
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الفترة الزمنية", color = Ink, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("اليوم", "الأسبوع", "الشهر", "السنة").forEach { p ->
                            TextButton(onClick = {
                                val now = java.time.LocalDate.now()
                                when (p) {
                                    "اليوم" -> setPreset(now, now, p)
                                    "الأسبوع" -> setPreset(now.with(java.time.DayOfWeek.MONDAY), now, p)
                                    "الشهر" -> setPreset(now.withDayOfMonth(1), now, p)
                                    else -> setPreset(now.withDayOfYear(1), now, p)
                                }
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 1.dp)) { Text(p, color = if (presetLabel == p) Green else Muted, fontSize = 12.sp) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(fromText, { fromText = it.take(10); error = "" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("من YYYY-MM-DD") })
                        OutlinedTextField(toText, { toText = it.take(10); error = "" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("إلى YYYY-MM-DD") })
                    }
                    Button(onClick = {
                        val first = runCatching { java.time.LocalDate.parse(fromText.trim()) }.getOrNull()
                        val last = runCatching { java.time.LocalDate.parse(toText.trim()) }.getOrNull()
                        if (first == null || last == null) error = "أدخل التاريخين بصيغة YYYY-MM-DD."
                        else if (first.isAfter(last)) error = "تاريخ البداية يجب ألا يتجاوز تاريخ النهاية."
                        else { range = first to last; presetLabel = "مخصصة"; error = "" }
                    }, modifier = Modifier.fillMaxWidth()) { Text("عرض الفترة المحددة") }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    Text("الفترة المطبقة: " + presetLabel + " • تاريخ النهاية مشمول بالكامل.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Metric("إجمالي التحصيل في الفترة", money(collections), "المبيعات النقدية + دفعات الديون المسجلة", Icons.Default.Payments, Modifier.fillMaxWidth()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("المبيعات", money(salesTotal), periodSales.size.toString() + " فاتورة", Icons.Default.PointOfSale, Modifier.weight(1f))
                Metric("المبيعات النقدية", money(cashSales), "مدفوعة عند البيع", Icons.Default.AttachMoney, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("المبيعات الآجلة", money(creditSales), "فواتير ضمن الفترة", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("تحصيل الديون", money(periodPayments.sumOf { it.amount.toLong() }.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()), periodPayments.size.toString() + " دفعة", Icons.Default.Payments, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("المصروفات", money(expenseTotal), periodExpenses.size.toString() + " مصروف", Icons.Default.ReceiptLong, Modifier.weight(1f))
                Metric("تكلفة البضاعة", money(cogs), "حسب التكلفة المسجلة", Icons.Default.Inventory2, Modifier.weight(1f))
            }
        }
        item { Text(if (periodSales.any { it.unitCost == 0 }) "تنبيه: توجد فواتير دون تكلفة شراء موثوقة؛ قد يكون تقدير الربح مضللًا." else "الربح والنتيجة أدناه مؤشرات تشغيلية وليست قوائم دخل محاسبية معتمدة.", color = Muted, fontSize = 11.sp) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("الربح الإجمالي التقديري", money(grossProfit), "المبيعات ناقص تكلفة البضاعة", Icons.Default.TrendingUp, Modifier.weight(1f))
                Metric("النتيجة بعد المصروفات", money(netResult), "مؤشر غير مدقق", Icons.Default.AccountBalanceWallet, Modifier.weight(1f))
            }
        }
        item { Metric("صافي حركة النقد التقديرية", money(cashFlow), "ليس بيان تدفقات نقدية محاسبيًا", Icons.Default.AccountBalanceWallet, Modifier.fillMaxWidth()) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E8)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("القوائم المحاسبية غير معتمدة", color = Ink, fontWeight = FontWeight.Bold)
                    Text("ميزان المراجعة، قائمة الدخل، الميزانية العمومية، التدفقات النقدية وكشف الأستاذ لا تُعرض كقوائم موثوقة قبل مطابقة القيود المنشورة ومصادرها والتسويات.", color = Muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("الديون المتبقية التقريبية", color = Muted, fontSize = 12.sp)
                    Text(money(outstanding), color = Orange, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                    Text("رصيد جميع الفواتير الآجلة غير المسددة بالكامل؛ ليس كشف أعمار ديون محاسبيًا.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item { Heading("فواتير الفترة", "المبيعات المسجلة ضمن الفترة المحددة") }
        if (periodSales.isEmpty()) item { Text("لا توجد فواتير ضمن هذه الفترة.", color = Muted) }
        items(periodSales, key = { "period-sale-" + it.number + "-" + it.createdAt }) { sale -> SaleRow("فاتورة #" + sale.number, sale.product + " · " + sale.quantity + " " + sale.unitCode, money(sale.total), sale.credit) }
        item { Heading("مصروفات الفترة", "المصروفات المسجلة ضمن الفترة المحددة") }
        if (periodExpenses.isEmpty()) item { Text("لا توجد مصروفات ضمن هذه الفترة.", color = Muted) }
        items(periodExpenses, key = { "period-expense-" + it.id }) { expense ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(expense.category, color = Ink, fontWeight = FontWeight.SemiBold)
                    if (expense.note.isNotBlank()) Text(expense.note, color = Muted, fontSize = 11.sp)
                    Text(money(expense.amount), color = Orange, fontWeight = FontWeight.Bold)
                }
            }
        }
        item { Heading("دفعات التحصيل في الفترة", "التسديدات المسجلة ضمن الفترة المحددة") }
        if (periodPayments.isEmpty()) item { Text("لا توجد دفعات تحصيل ضمن هذه الفترة.", color = Muted) }
        items(periodPayments, key = { "period-payment-" + it.invoiceNumber + "-" + it.timestamp }) { payment ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(payment.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.SemiBold); Text("فاتورة #" + payment.invoiceNumber, color = Muted, fontSize = 11.sp) }
                    Text(money(payment.amount), color = Green, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun CommercialCloudReportsScreen(context: Context, onBack: () -> Unit) {
    var reportingTimeZone by remember { mutableStateOf(java.time.ZoneId.of("UTC")) }
    val today = remember(reportingTimeZone) { java.time.LocalDate.now(reportingTimeZone) }
    var fromText by remember { mutableStateOf(today.toString()) }
    var toText by remember { mutableStateOf(today.toString()) }
    var range by remember { mutableStateOf(today to today) }
    var presetLabel by remember { mutableStateOf("اليوم") }
    var dateRangeEdited by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val sales = remember { mutableStateListOf<SaleRecord>() }
    val expenses = remember { mutableStateListOf<CommercialCloudExpenseVoucher>() }
    val payments = remember { mutableStateListOf<CommercialCloudRecord>() }
    val returns = remember { mutableStateListOf<CommercialCloudSaleReturn>() }
    val io = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    fun refresh() {
        if (loading) return
        loading = true; error = ""
        io.execute {
            val s = runCatching { loadCommercialCloudSales(context) }
            val e = runCatching { loadCommercialCloudExpenses(context) }
            val p = runCatching { loadCommercialCloudRecords(context, "payments") }
            val r = runCatching { loadCommercialCloudSaleReturns(context) }
            val z = runCatching { loadCommercialCloudExpenseReportingTimeZone(context) }
            main.post {
                if (s.isSuccess && e.isSuccess && p.isSuccess && r.isSuccess && z.isSuccess) {
                    sales.apply { clear(); addAll(s.getOrThrow()) }
                    expenses.apply { clear(); addAll(e.getOrThrow()) }
                    payments.apply { clear(); addAll(p.getOrThrow()) }
                    returns.apply { clear(); addAll(r.getOrThrow()) }
                    reportingTimeZone = z.getOrThrow()
                    loaded = true
                } else {
                    error = listOfNotNull(s.exceptionOrNull(), e.exceptionOrNull(), p.exceptionOrNull(), r.exceptionOrNull(), z.exceptionOrNull()).joinToString(" • ") { it.message ?: "تعذر تحميل مصدر" }
                }
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(reportingTimeZone) {
        if (!dateRangeEdited) {
            val reportingToday = java.time.LocalDate.now(reportingTimeZone)
            fromText = reportingToday.toString()
            toText = reportingToday.toString()
            range = reportingToday to reportingToday
        }
    }
    val first = runCatching { java.time.LocalDate.parse(fromText) }.getOrNull()
    val last = runCatching { java.time.LocalDate.parse(toText) }.getOrNull()
    val zone = reportingTimeZone
    val startMs = range.first.atStartOfDay(zone).toInstant().toEpochMilli()
    val endMs = range.second.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun timestamp(value: String): Long? = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
    fun within(value: String): Boolean = timestamp(value)?.let { it >= startMs && it < endMs } ?: false
    val periodSales = sales.filter { it.status.equals("completed", true) && it.createdAt >= startMs && it.createdAt < endMs && it.currencyCode.isNotBlank() }
    val periodExpenses = expenses.filter { it.canceledAt.isBlank() && within(it.recordedAt) }
    val periodPayments = payments.filter { it.status.equals("succeeded", true) && it.currencyCode.isNotBlank() && within(it.createdAt) }
    val periodReturns = returns.filter { it.status.equals("completed", true) && it.approvedRefundMinor != null && within(it.createdAt) }
    val currencies = (periodSales.map { it.currencyCode } + periodExpenses.map { it.currencyCode } + periodPayments.map { it.currencyCode } + periodReturns.map { it.currencyCode }).filter { it.isNotBlank() }.distinct().sorted()
    fun formatMinor(amount: Long, code: String): String {
        val scale = runCatching { java.util.Currency.getInstance(code).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)
        return java.math.BigDecimal.valueOf(amount).movePointLeft(scale).setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString() + " " + code
    }
    fun setPreset(firstDate: java.time.LocalDate, lastDate: java.time.LocalDate, label: String) {
        fromText = firstDate.toString(); toText = lastDate.toString(); range = firstDate to lastDate; presetLabel = label; dateRangeEdited = true; error = ""
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Column(Modifier.weight(1f)) { Text("تقارير السحابة", color = Ink, fontWeight = FontWeight.Bold, fontSize = 19.sp); Text(range.first.toString() + " — " + range.second.toString(), color = Muted, fontSize = 11.sp) }
                IconButton(onClick = { refresh() }, enabled = !loading) { Icon(Icons.Default.Refresh, contentDescription = "تحديث") }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الفترة الزمنية", color = Ink, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("اليوم", "الأسبوع", "الشهر", "السنة").forEach { p ->
                            TextButton(onClick = {
                                val now = java.time.LocalDate.now(reportingTimeZone)
                                when (p) {
                                    "اليوم" -> setPreset(now, now, p)
                                    "الأسبوع" -> setPreset(now.with(java.time.DayOfWeek.MONDAY), now, p)
                                    "الشهر" -> setPreset(now.withDayOfMonth(1), now, p)
                                    else -> setPreset(now.withDayOfYear(1), now, p)
                                }
                            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 1.dp)) { Text(p, color = if (presetLabel == p) Green else Muted, fontSize = 12.sp) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(fromText, { fromText = it.take(10); dateRangeEdited = true; error = "" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("من YYYY-MM-DD") })
                        OutlinedTextField(toText, { toText = it.take(10); dateRangeEdited = true; error = "" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("إلى YYYY-MM-DD") })
                    }
                    Button(onClick = {
                        if (first == null || last == null) error = "أدخل التاريخين بصيغة YYYY-MM-DD."
                        else if (first.isAfter(last)) error = "تاريخ البداية يجب ألا يتجاوز تاريخ النهاية."
                        else { range = first to last; presetLabel = "مخصصة"; error = ""; refresh() }
                    }, modifier = Modifier.fillMaxWidth()) { Text("تطبيق الفترة وتحديث البيانات") }
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (error.isNotBlank() && !loaded) item { Text("تعذر تحميل التقارير السحابية: " + error, color = MaterialTheme.colorScheme.error) }
        if (loaded && currencies.isEmpty()) item { Text("لا توجد حركات ضمن الفترة المحددة.", color = Muted) }
        items(currencies, key = { "cloud-report-" + it }) { code ->
            val salesTotal = periodSales.filter { it.currencyCode == code }.sumOf { it.total.toLong() }
            val collectionTotal = periodPayments.filter { it.currencyCode == code }.sumOf { it.amountMinor }
            val expenseTotal = periodExpenses.filter { it.currencyCode == code }.sumOf { it.amountMinor }
            val returnTotal = periodReturns.filter { it.currencyCode == code }.sumOf { it.approvedRefundMinor ?: 0L }
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("العملة: " + code, color = Ink, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("المبيعات", color = Muted); Text(formatMinor(salesTotal, code), color = Green, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("التحصيلات الناجحة", color = Muted); Text(formatMinor(collectionTotal, code), color = Green, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("المصروفات غير الملغاة", color = Muted); Text(formatMinor(expenseTotal, code), color = Orange, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("المرتجعات المكتملة المعتمدة", color = Muted); Text(formatMinor(returnTotal, code), color = Orange, fontWeight = FontWeight.Bold) }
                    Text("مؤشرات تشغيلية من سجلات المصدر، وليست قائمة دخل أو تدفقات نقدية محاسبية.", color = Muted, fontSize = 11.sp)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E8)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("بوابة اعتماد القوائم المالية", color = Ink, fontWeight = FontWeight.Bold)
                    Text("ميزان المراجعة، قائمة الدخل، الميزانية العمومية، التدفقات النقدية وكشف الأستاذ لا تُعرض كقوائم معتمدة قبل مطابقة القيود المنشورة والمتوازنة مع المبيعات والتحصيلات والمرتجعات والاستردادات والمشتريات وتكلفة المخزون والمصروفات والتسويات.", color = Muted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
internal fun DebtScreen(sales: List<SaleRecord>, settlements: List<SettlementRecord>, onBack: () -> Unit, onSettle: (SaleRecord) -> Unit) {
    var customerQuery by remember { mutableStateOf("") }
    val allDebts = sales.filter { it.credit && it.total > it.paid }
    val debts = allDebts.filter {
        customerQuery.isBlank() ||
            it.customer.contains(customerQuery.trim(), ignoreCase = true) ||
            it.product.contains(customerQuery.trim(), ignoreCase = true) ||
            it.number.toString().contains(customerQuery.trim())
    }
    val totalOutstanding = allDebts.sumOf { (it.total - it.paid).coerceAtLeast(0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "رجوع") }
                Heading("العملاء والمديونيات", "الأرصدة المتبقية من المبيعات الآجلة")
            }
        }
        item {
            OutlinedTextField(
                value = customerQuery,
                onValueChange = { customerQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("ابحث باسم العميل أو المنتج أو رقم الفاتورة") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Ink), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("إجمالي الديون المتبقية", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text(money(totalOutstanding), color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    Text("${allDebts.size} فاتورة غير مسددة بالكامل", color = Color(0xFF8DE0B8), fontSize = 12.sp)
                }
            }
        }
        item { Heading("ملخص العملاء", "إجمالي المستحق لكل عميل") }
        val customerBalances = debts.groupBy { it.customer.trim().ifBlank { "عميل غير محدد" } }
            .mapValues { (_, invoices) -> invoices.sumOf { (it.total - it.paid).coerceAtLeast(0) } }
            .toList().sortedByDescending { it.second }
        items(customerBalances, key = { it.first }) { (customerName, balance) ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(customerName, color = Ink, fontWeight = FontWeight.SemiBold)
                        Text("الرصيد المستحق", color = Muted, fontSize = 11.sp)
                    }
                    Text(money(balance), color = Orange, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (debts.isEmpty()) item { Text("لا توجد ديون مستحقة حاليًا.", color = Muted) }
        items(debts, key = { it.number }) { sale ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(sale.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("فاتورة #${sale.number} • ${sale.product} × ${sale.quantity} ${sale.unitCode}", color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("قيمة الفاتورة: ${money(sale.total)}", color = Muted, fontSize = 12.sp)
                        Text("المسدّد: ${money(sale.paid)}", color = Green, fontSize = 12.sp)
                    }
                    Text("المتبقي: ${money((sale.total - sale.paid).coerceAtLeast(0))}", color = Orange, fontWeight = FontWeight.Bold)
                    Button(onClick = { onSettle(sale) }, modifier = Modifier.fillMaxWidth()) { Text("تسجيل تسديد") }
                }
            }
        }
        item { Heading("سجل التسديدات", "أحدث الدفعات المسجلة") }
        if (settlements.isEmpty()) item { Text("لم تُسجّل دفعات تسديد بعد.", color = Muted, fontSize = 12.sp) }
        items(settlements.take(30), key = { "${it.invoiceNumber}-${it.timestamp}" }) { payment ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Border), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(payment.customer.ifBlank { "عميل غير محدد" }, color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("فاتورة #${payment.invoiceNumber}", color = Muted, fontSize = 11.sp)
                    Text("التسديد: ${money(payment.amount)}", color = Green, fontWeight = FontWeight.Bold)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, Locale.getDefault()).format(java.util.Date(payment.timestamp)), color = Muted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun SettlementDialog(sale: SaleRecord, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var amountText by remember(sale.number, sale.paid) { mutableStateOf((sale.total - sale.paid).coerceAtLeast(0).toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val remaining = (sale.total - sale.paid).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تسديد دين العميل") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${sale.customer.ifBlank { "عميل غير محدد" }} • فاتورة #${sale.number}", color = Muted)
                Text("المتبقي: ${money(remaining)}", color = Green, fontWeight = FontWeight.Bold)
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter(Char::isDigit).take(9); error = null }, label = { Text("مبلغ التسديد بالريال اليمني") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toIntOrNull()
                when {
                    amount == null || amount <= 0 -> error = "أدخل مبلغًا أكبر من صفر."
                    amount > remaining -> error = "مبلغ التسديد أكبر من الرصيد المتبقي."
                    else -> onSave(amount)
                }
            }) { Text("حفظ التسديد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
