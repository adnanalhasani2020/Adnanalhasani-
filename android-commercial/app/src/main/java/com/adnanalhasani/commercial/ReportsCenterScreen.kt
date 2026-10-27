package com.adnanalhasani.commercial

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Currency
import java.util.concurrent.Executors

private val ReportInk = Color(0xFF183B35)
private val ReportGreen = Color(0xFF167D63)
private val ReportMint = Color(0xFFE8F5EF)
private val ReportMuted = Color(0xFF71817B)
private val ReportBorder = Color(0xFFE5EBE8)
private val ReportIo = Executors.newSingleThreadExecutor()
private val ReportMain = Handler(Looper.getMainLooper())

private fun reportCurrencyScale(code: String): Int =
    runCatching { Currency.getInstance(code).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)

private fun reportMoney(amount: Long, currency: String, cloudMinorUnits: Boolean): String {
    val value = if (cloudMinorUnits) java.math.BigDecimal.valueOf(amount).movePointLeft(reportCurrencyScale(currency))
    else java.math.BigDecimal.valueOf(amount)
    return value.setScale(if (cloudMinorUnits) reportCurrencyScale(currency) else 0, java.math.RoundingMode.HALF_UP)
        .toPlainString() + " " + currency
}

private fun reportDateMillis(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

private data class ReportCatalogItem(val title: String, val category: String, val source: String, val supported: Boolean, val note: String)
private data class ReportLine(val date: String, val reference: String, val name: String, val amount: Long, val currency: String, val status: String)

@Composable
internal fun ReportsCenterScreen(
    context: Context,
    sales: List<SaleRecord>,
    settlements: List<SettlementRecord>,
    expenses: List<ExpenseRecord>,
    products: List<Product>,
    cloudMode: Boolean,
    onBack: () -> Unit,
    onOpenExistingReports: () -> Unit
) {
    val today = remember { LocalDate.now() }
    var selectedPeriod by remember { mutableStateOf("الشهر الحالي") }
    var startText by remember { mutableStateOf(today.withDayOfMonth(1).toString()) }
    var endText by remember { mutableStateOf(today.toString()) }
    var search by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("الكل") }
    var selectedReport by remember { mutableStateOf("ملخص المبيعات حسب الفترة") }
    var exportRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var exportTitle by remember { mutableStateOf("تقرير متجري") }
    var error by remember { mutableStateOf("") }
    var cloudSalesDocuments by remember { mutableStateOf<List<CommercialCloudSaleDocument>>(emptyList()) }
    var cloudPurchases by remember { mutableStateOf<List<CommercialCloudPurchaseDocument>>(emptyList()) }
    var cloudReturns by remember { mutableStateOf<List<CommercialCloudSaleReturn>>(emptyList()) }
    var cloudPayments by remember { mutableStateOf<List<CommercialCloudRecord>>(emptyList()) }
    var cloudJournals by remember { mutableStateOf<List<CommercialCloudRecord>>(emptyList()) }
    var cloudLedgerLines by remember { mutableStateOf<List<CommercialCloudJournalLine>>(emptyList()) }
    var cloudExpenses by remember { mutableStateOf<List<CommercialCloudExpenseVoucher>>(emptyList()) }
    var cloudLoading by remember { mutableStateOf(false) }
    var cloudLoadError by remember { mutableStateOf("") }
    var cloudReload by remember { mutableIntStateOf(0) }

    LaunchedEffect(cloudMode, cloudReload) {
        if (!cloudMode) return@LaunchedEffect
        cloudLoading = true
        cloudLoadError = ""
        ReportIo.execute {
            val salesDocuments = runCatching { loadCommercialCloudSaleDocuments(context) }
            val purchases = runCatching { loadCommercialCloudPurchaseDocuments(context) }
            val returns = runCatching { loadCommercialCloudSaleReturns(context) }
            val payments = runCatching { loadCommercialCloudRecords(context, "payments") }
            val journals = runCatching { loadCommercialCloudRecords(context, "journals") }
            val ledgerLines = runCatching { loadCommercialCloudPostedJournalLines(context) }
            val expenses = runCatching { loadCommercialCloudExpenses(context) }
            ReportMain.post {
                cloudLoading = false
                if (salesDocuments.isSuccess) cloudSalesDocuments = salesDocuments.getOrThrow()
                if (purchases.isSuccess) cloudPurchases = purchases.getOrThrow()
                if (returns.isSuccess) cloudReturns = returns.getOrThrow()
                if (payments.isSuccess) cloudPayments = payments.getOrThrow()
                if (journals.isSuccess) cloudJournals = journals.getOrThrow()
                if (ledgerLines.isSuccess) cloudLedgerLines = ledgerLines.getOrThrow()
                if (expenses.isSuccess) cloudExpenses = expenses.getOrThrow()
                val errors = listOf(
                    salesDocuments.exceptionOrNull(), purchases.exceptionOrNull(), returns.exceptionOrNull(), payments.exceptionOrNull(),
                    journals.exceptionOrNull(), ledgerLines.exceptionOrNull(), expenses.exceptionOrNull()
                ).filterNotNull()
                cloudLoadError = errors.firstOrNull()?.message ?: if (errors.isNotEmpty()) "تعذر تحميل بعض مصادر التقارير." else ""
            }
        }
    }

    val categories = listOf(
        "لوحة المعلومات", "المبيعات", "المشتريات", "العملاء والديون", "الموردون والمستحقات",
        "السندات والتحصيل والصرف", "المحاسبة والمالية", "الأرباح والتكاليف", "المرتجعات",
        "الأصناف والمخزون", "الصندوق والنقدية", "الضرائب والخصومات", "المستخدمون والعمليات",
        "الفترات والمقارنات", "الإدارة الشاملة"
    )
    val catalog = remember(cloudMode) {
        listOf(
            ReportCatalogItem("لوحة المؤشرات", "لوحة المعلومات", if (cloudMode) "مبيعات السحابة المحملة" else "المبيعات والمصروفات المحلية", true, "مؤشرات تشغيلية وليست قوائم مالية معتمدة."),
            ReportCatalogItem("ملخص المبيعات حسب الفترة", "المبيعات", if (cloudMode) "commercial_sales عبر مستودع السحابة" else "sales_json على الجهاز", true, "الفواتير المكتملة فقط؛ فصل حسب العملة."),
            ReportCatalogItem("تفاصيل فواتير المبيعات", "المبيعات", if (cloudMode) "فواتير السحابة" else "sales_json على الجهاز", true, "تفاصيل الفاتورة المتاحة في نموذج التطبيق."),
            ReportCatalogItem("المبيعات حسب الصنف", "المبيعات", if (cloudMode) "commercial_sale_items" else "sales_json", true, "يعرض بنود الفواتير السحابية الفعلية؛ في المحلي يمثل كل سجل بيع الصنف المسجل فيه."),
            ReportCatalogItem("الخصومات على فواتير المبيعات", "الضرائب والخصومات", "commercial_sales.discount_minor", cloudMode, "يعرض الخصومات المخزنة على الفواتير المكتملة، دون افتراض نسبة خصم."),
            ReportCatalogItem("الضرائب المسجلة على المبيعات", "الضرائب والخصومات", "commercial_sales.tax_minor", cloudMode, "يعرض قيمة الضريبة المخزنة فقط؛ لا يستنتج نسبة ضريبية من الإجمالي."),
            ReportCatalogItem("المبيعات حسب العميل", "المبيعات", "اسم العميل المحفوظ في سجل البيع", true, "يعرض مجموع الفواتير المكتملة المسجلة للعميل حسب العملة."),
            ReportCatalogItem("المبيعات النقدية والآجلة", "المبيعات", "نوع البيع المسجل في الفاتورة", true, "تصنيف حسب علامة النقدي/الآجل في سجل البيع، وليس تسوية الرصيد."),
            ReportCatalogItem("متوسط قيمة الفاتورة", "المبيعات", "إجمالي الفواتير المكتملة وعددها", true, "متوسط تشغيلي منفصل حسب العملة."),
            ReportCatalogItem("مقارنة الفترات", "الفترات والمقارنات", "سجلات المبيعات المكتملة المؤرخة", true, "يقارن الفترة الحالية بفترة سابقة مساوية لها في عدد الأيام حسب العملة."),
            ReportCatalogItem(if (cloudMode) "الدفعات السحابية الناجحة" else "التحصيلات المسجلة محليًا", "السندات والتحصيل والصرف", if (cloudMode) "commercial_payments" else "settlements_json", true, "يعرض الدفعات الناجحة فقط في السحابة؛ السندات لا تُجمع منفصلة عن الدفعة المرتبطة."),
            ReportCatalogItem("المصروفات التشغيلية", "الأرباح والتكاليف", if (cloudMode) "commercial_expense_vouchers" else "expenses_json", true, "تُستبعد سندات الصرف الملغاة؛ هذا تقرير مصروفات مسجلة وليس قائمة دخل."),
            ReportCatalogItem("رصيد المخزون الحالي", "الأصناف والمخزون", if (cloudMode) "commercial_inventory_balances" else "products_json", true, "رصيد حالي فقط؛ ليس رصيدًا تاريخيًا للفترة."),
            ReportCatalogItem("تنبيهات المخزون المنخفض", "الأصناف والمخزون", "الرصيد وحد إعادة الطلب", true, "يعتمد على حد التنبيه المسجل لكل صنف."),
            ReportCatalogItem("فواتير الشراء والمستحقات المسجلة", "المشتريات", "commercial_purchase_orders ← supplier_invoices ← supplier_obligations", cloudMode, "يُعرض مبلغ الفاتورة والرصيد المستحق من سجل الالتزام؛ لا يُخلط مع قيمة أمر الشراء."),
            ReportCatalogItem("مستحقات فواتير الموردين", "الموردون والمستحقات", "commercial_supplier_obligations المرتبطة بالفواتير", cloudMode, "مجموع الالتزامات المسجلة ناقص المبالغ المسددة المسجلة فقط؛ لا يمثل رصيد المورد الشامل إذا وجدت تسويات أو مرتجعات خارج هذا المصدر."),
            ReportCatalogItem("مرتجعات المبيعات وحالة التسوية", "المرتجعات", "commercial_sales_returns + عناصر المرتجع", cloudMode, "يفصل حالة الموافقة عن الاسترداد الفعلي وإعادة التخزين؛ لا تُعامل الطلبات المعلقة كمرتجعات منفذة."),
            ReportCatalogItem("أرصدة العملاء وكشف الحساب", "العملاء والديون", "حركات الفواتير والتحصيل والمرتجعات والتسويات", false, "يتطلب دفتر حركات موحدًا ومطابقة المرتجعات والتسويات؛ لا يصح استنتاج الرصيد من الفواتير وحدها."),
            ReportCatalogItem("أرصدة الموردين والمستحقات", "الموردون والمستحقات", "فواتير المورد والمدفوعات والمرتجعات", false, "يتطلب مطابقة الالتزامات والدفعات والمرتجعات والتسويات."),
            ReportCatalogItem("سندات القبض والصرف", "السندات والتحصيل والصرف", "commercial_payments وحالة كل دفعة", cloudMode, "يعرض حالة كل دفعة؛ لحساب التحصيل الفعلي استخدم تقرير الدفعات الناجحة فقط، ولا تجمع القيد والدفعة مرتين."),
            ReportCatalogItem("سجل القيود المحاسبية", "المحاسبة والمالية", "commercial_journal_entries", cloudMode, "قائمة قراءة فقط لحالات القيود ومصادرها."),
            ReportCatalogItem("ميزان المراجعة حسب الحساب", "المحاسبة والمالية", "commercial_journal_lines + commercial_ledger_accounts + القيود المرحلة", cloudMode, "رصيد حتى نهاية التاريخ المحدد، مع إظهار المدين والدائن والفروقات حسب العملة."),
            ReportCatalogItem("حركة دفتر الأستاذ العام", "المحاسبة والمالية", "سطور القيود المرحلة ومصادرها", cloudMode, "تفاصيل سطور القيود المرحلة ضمن الفترة؛ ليست كشف حساب لحساب واحد لعدم وجود مرشح حساب في هذه النسخة."),

            ReportCatalogItem("قائمة الدخل والميزانية العمومية", "المحاسبة والمالية", "دفتر أستاذ مكتمل وتسويات الفترة", false, "غير متاحة لتجنب عرض قوائم مضللة."),
            ReportCatalogItem("التدفقات النقدية والصندوق", "الصندوق والنقدية", "حركات نقدية فريدة ورصيد افتتاحي موثوق", false, "يلزم تحديد جميع الحركات النقدية ومنع تكرار الدفعات والسندات."),

            ReportCatalogItem("الضرائب والخصومات", "الضرائب والخصومات", "تفاصيل الضرائب والخصومات من المستندات", false, "لا تُخمن النسب أو مبالغ الضريبة إذا لم تسجل في المصدر."),
            ReportCatalogItem("أداء المستخدمين والفروع والمستودعات", "المستخدمون والعمليات", "مستخدم/فرع/مستودع محفوظ على المستند", false, "لا تتوفر كل هذه الأبعاد في نموذج البيانات المحمل حاليًا."),

            ReportCatalogItem("تكلفة البضاعة والربح", "الأرباح والتكاليف", "تكلفة شراء موثوقة لكل بند ومرتجعات منفذة", false, "تكلفة الصنف المحلي قد تكون مفقودة أو غير تاريخية؛ لا يعرض ربحًا محاسبيًا معتمدًا.")
        )
    }
    val filteredCatalog = catalog.filter {
        (selectedCategory == "الكل" || it.category == selectedCategory) &&
            (search.isBlank() || it.title.contains(search, true) || it.category.contains(search, true))
    }
    val startDate = runCatching { LocalDate.parse(startText) }.getOrNull()
    val endDate = runCatching { LocalDate.parse(endText) }.getOrNull()
    val dateError = startDate == null || endDate == null || startDate.isAfter(endDate)
    val zone = remember { ZoneId.systemDefault() }
    val startMillis = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    val endExclusive = endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
    val inPeriodSales = sales.filter { it.createdAt >= startMillis && it.createdAt < endExclusive && it.status.equals("completed", true) }
    val inPeriodCloudSalesDocuments = cloudSalesDocuments.filter { doc ->
        doc.saleStatus.equals("completed", true) &&
            (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
    }
    val inPeriodSettlements = if (cloudMode) emptyList() else settlements.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val inPeriodExpenses = if (cloudMode) emptyList() else expenses.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val salesByCurrency = inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.toSortedMap()
    val stockLow = products.filter { it.stock <= (it.reorderPoint.takeIf { n -> n >= 0 } ?: 9) }
    val selectedRows = when (selectedReport) {
        "ملخص المبيعات حسب الفترة" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            val total = rows.sumOf { it.totalMinor }
            ReportLine("${startText} — ${endText}", "ملخص", "إجمالي المبيعات المكتملة", total, currency, "${rows.size} فاتورة · متوسط ${reportMoney(if (rows.isEmpty()) 0L else total / rows.size, currency, true)}")
        } else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            ReportLine("${startText} — ${endText}", "ملخص", "إجمالي المبيعات المكتملة", rows.sumOf { it.total.toLong() }, currency, "${rows.size} فاتورة · متوسط ${if (rows.isEmpty()) 0L else rows.sumOf { it.total.toLong() } / rows.size}")
        }
        "تفاصيل فواتير المبيعات" -> if (cloudMode) cloudSalesDocuments.filter { doc ->
            val timestamp = reportDateMillis(doc.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.sortedByDescending { it.createdAt }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName.ifBlank { "عميل غير محدد" },
                doc.totalMinor, doc.currencyCode, "الفاتورة: ${doc.saleStatus} · السداد: ${doc.paymentStatus} · بنود: ${doc.lines.size}")
        } else inPeriodSales.sortedByDescending { it.createdAt }.map {
            ReportLine(Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate().toString(), "فاتورة #${it.number}", it.product, it.total.toLong(), it.currencyCode, if (it.credit) "آجل" else "نقدي")
        }
        "المبيعات حسب الصنف" -> if (cloudMode) cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.flatMap { doc -> doc.lines.map { line -> Triple(line.productName, doc.currencyCode, line) } }
            .groupBy { it.first to it.second }.map { (key, rows) ->
                ReportLine("-", "-", key.first, rows.sumOf { it.third.lineTotalMinor }, key.second,
                    "${rows.sumOf { it.third.quantity.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO }} وحدة · بنود فعلية")
            }.sortedByDescending { it.amount }
        else inPeriodSales.groupBy { it.product to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, rows.sumOf { it.total.toLong() }, key.second, "${rows.sumOf { it.quantity }} وحدة · ${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "الخصومات على فواتير المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.filter { it.discountMinor > 0L }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.discountMinor, doc.currencyCode, "خصم مسجل على الفاتورة")
        }.sortedByDescending { it.date }
        "الضرائب المسجلة على المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.filter { it.taxMinor > 0L }.map { doc ->
            ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.taxMinor, doc.currencyCode, "ضريبة مسجلة على الفاتورة")
        }.sortedByDescending { it.date }
        "المبيعات حسب العميل" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.customerName.ifBlank { "عميل غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, rows.sumOf { it.totalMinor }, key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount } else inPeriodSales.groupBy { it.customer.ifBlank { "عميل غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, rows.sumOf { it.total.toLong() }, key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "المبيعات النقدية والآجلة" -> inPeriodSales.groupBy { (if (it.credit) "آجل" else "نقدي") to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, rows.sumOf { it.total.toLong() }, key.second, "${rows.size} فاتورة")
        }.sortedByDescending { it.amount }
        "متوسط قيمة الفاتورة" -> if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            val total = rows.sumOf { it.totalMinor }
            ReportLine("-", "-", "متوسط قيمة الفاتورة", if (rows.isEmpty()) 0L else total / rows.size, currency, "${rows.size} فاتورة · الإجمالي ${reportMoney(total, currency, true)}")
        } else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
            ReportLine("-", "-", "متوسط قيمة الفاتورة", if (rows.isEmpty()) 0L else rows.sumOf { it.total.toLong() } / rows.size, currency, "${rows.size} فاتورة · إجمالي ${rows.sumOf { it.total.toLong() }}")
        }
        "مقارنة فترتين", "مقارنة الفترات" -> {
            val days = if (startDate != null && endDate != null) java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1 else 0
            val previousStart = startDate?.minusDays(days.toLong())
            val previousEnd = startDate?.minusDays(1)
            if (cloudMode) {
                val previousCloudRows = if (previousStart != null && previousEnd != null) cloudSalesDocuments.filter { doc ->
                    doc.saleStatus.equals("completed", true) &&
                        (reportDateMillis(doc.createdAt)?.let { it >= previousStart.atStartOfDay(zone).toInstant().toEpochMilli() && it < previousEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() } == true)
                } else emptyList()
                (inPeriodCloudSalesDocuments.map { it.currencyCode.ifBlank { "غير محددة" } } + previousCloudRows.map { it.currencyCode.ifBlank { "غير محددة" } }).distinct().sorted().map { currency ->
                    val currentTotal = inPeriodCloudSalesDocuments.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.sumOf { it.totalMinor }
                    val previousTotal = previousCloudRows.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.sumOf { it.totalMinor }
                    ReportLine("${startText} — ${endText}", "مقارنة", "مبيعات الفترة الحالية", currentTotal, currency,
                        "الفترة السابقة: ${reportMoney(previousTotal, currency, true)} · الفرق: ${reportMoney(currentTotal - previousTotal, currency, true)}")
                }
            } else {
                val previousRows = if (previousStart != null && previousEnd != null) sales.filter {
                    it.createdAt >= previousStart.atStartOfDay(zone).toInstant().toEpochMilli() &&
                        it.createdAt < previousEnd.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                        it.status.equals("completed", true)
                } else emptyList()
                (inPeriodSales.map { it.currencyCode.ifBlank { "غير محددة" } } + previousRows.map { it.currencyCode.ifBlank { "غير محددة" } }).distinct().sorted().map { currency ->
                    val currentTotal = inPeriodSales.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.sumOf { it.total.toLong() }
                    val previousTotal = previousRows.filter { it.currencyCode.ifBlank { "غير محددة" } == currency }.sumOf { it.total.toLong() }
                    ReportLine("${startText} — ${endText}", "مقارنة", "مبيعات الفترة الحالية", currentTotal, currency, "الفترة السابقة: $previousTotal · الفرق: ${currentTotal - previousTotal}")
                }
            }
        }
        "الدفعات السحابية الناجحة" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                row.status.equals("succeeded", true)
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, row.amountMinor, row.currencyCode, row.status)
        }
        "التحصيلات المسجلة محليًا" -> inPeriodSettlements.map {
            ReportLine(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toString(), "فاتورة #${it.invoiceNumber}", it.customer, it.amount.toLong(), "YER", "تحصيل محلي")
        }
        "فواتير الشراء والمستحقات المسجلة" -> cloudPurchases.flatMap { purchase ->
            purchase.supplierInvoices.filter { invoice ->
                val timestamp = reportDateMillis(invoice.recordedAt)
                timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
            }.map { invoice ->
                val due = invoice.obligation?.amountDueMinor
                val paid = invoice.obligation?.amountPaidMinor
                ReportLine(invoice.recordedAt.take(10), invoice.invoiceNumber.ifBlank { purchase.orderNumber }, purchase.supplierName,
                    invoice.totalMinor, invoice.currencyCode.ifBlank { purchase.currencyCode },
                    if (due != null && paid != null) "مستحق: ${(due - paid).coerceAtLeast(0)} · ${invoice.state}" else invoice.state)
            }
        }.sortedByDescending { it.date }
        "مستحقات فواتير الموردين" -> cloudPurchases.flatMap { purchase ->
            purchase.supplierInvoices.filter { invoice ->
                val timestamp = reportDateMillis(invoice.recordedAt)
                timestamp != null && timestamp < endExclusive &&
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                    invoice.obligation != null &&
                    invoice.obligation.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
            }.mapNotNull { invoice ->
                val obligation = invoice.obligation ?: return@mapNotNull null
                Triple(purchase.supplierName.ifBlank { "مورد غير محدد" }, invoice.currencyCode.ifBlank { purchase.currencyCode }, obligation)
            }
        }.groupBy { it.first to it.second }.map { (key, rows) ->
            val due = rows.sumOf { it.third.amountDueMinor }
            val paid = rows.sumOf { it.third.amountPaidMinor }
            ReportLine(endText, "-", key.first, (due - paid).coerceAtLeast(0L), key.second,
                "إجمالي الالتزامات: ${reportMoney(due, key.second, true)} · المسدد: ${reportMoney(paid, key.second, true)} · عدد الالتزامات: ${rows.size}")
        }.sortedByDescending { it.amount }
        "مرتجعات المبيعات وحالة التسوية" -> cloudReturns.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            val approved = if (row.status in setOf("approved", "completed")) (row.approvedRefundMinor ?: 0L) else 0L
            ReportLine(row.createdAt.take(10), row.receiptNumber, row.customerName, approved, row.currencyCode,
                "الطلب: ${row.status} · الاسترداد: ${row.refundStatus} · المخزون: ${row.restockStatus}")
        }.sortedByDescending { it.date }
        "سندات القبض والصرف" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, row.amountMinor, row.currencyCode, row.status)
        }.sortedByDescending { it.date }
        "ميزان المراجعة حسب الحساب" -> {
            val throughEnd = cloudLedgerLines.filter { line ->
                val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                date != null && endDate != null && !date.isAfter(endDate)
            }
            val accountRows = throughEnd.groupBy { Triple(it.accountId, it.accountName.ifBlank { "حساب غير معروف" }, it.currencyCode.ifBlank { "غير محددة" }) }
                .map { (key, rows) ->
                    val debit = rows.sumOf { it.debitMinor }
                    val credit = rows.sumOf { it.creditMinor }
                    ReportLine(endText, key.first, key.second, debit - credit, key.third,
                        "مدين: ${reportMoney(debit, key.third, true)} · دائن: ${reportMoney(credit, key.third, true)}")
                }
            val totals = throughEnd.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.map { (currency, rows) ->
                val debit = rows.sumOf { it.debitMinor }
                val credit = rows.sumOf { it.creditMinor }
                ReportLine(endText, "إجمالي", "إجمالي الميزان", debit - credit, currency,
                    "إجمالي المدين: ${reportMoney(debit, currency, true)} · إجمالي الدائن: ${reportMoney(credit, currency, true)} · الفرق: ${reportMoney(debit - credit, currency, true)}")
            }
            accountRows.sortedBy { it.reference } + totals
        }
        "حركة دفتر الأستاذ العام" -> cloudLedgerLines.filter { line ->
            val timestamp = reportDateMillis(line.entryDate)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { line ->
            ReportLine(line.entryDate.take(10), line.entryNumber.ifBlank { line.entryId }, "${line.accountCode} · ${line.accountName}",
                line.debitMinor - line.creditMinor, line.currencyCode,
                "قيد ${line.entryNumber} · مدين: ${line.debitMinor} · دائن: ${line.creditMinor} · مصدر: ${line.sourceType} ${line.sourceId}")
        }.sortedByDescending { it.date }
        "سجل القيود المحاسبية" -> cloudJournals.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            ReportLine(row.createdAt.take(10), row.id, row.title, 0L, "", row.status + " · " + row.subtitle)
        }.sortedByDescending { it.date }
        "المصروفات التشغيلية" -> (if (cloudMode) cloudExpenses.filter { it.canceledAt.isBlank() && (reportDateMillis(it.recordedAt)?.let { ts -> ts >= startMillis && ts < endExclusive } == true) }.map {
            ReportLine(it.recordedAt.take(10), it.id, it.category, it.amountMinor, it.currencyCode, it.paymentMethod)
        } else inPeriodExpenses.map {
            ReportLine(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toString(), "مصروف #${it.id}", it.category, it.amount.toLong(), "YER", it.note)
        })
        "رصيد المخزون الحالي" -> products.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "${it.unitCode} · سعر ${it.price}")
        }
        "تنبيهات المخزون المنخفض" -> stockLow.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "حد التنبيه ${it.reorderPoint.takeIf { n -> n >= 0 } ?: 9} ${it.unitCode}")
        }
        else -> emptyList()
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write("\uFEFF")
                    writer.write(exportRows.joinToString("\r\n") { row -> row.joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" } })
                }
            } ?: throw IllegalStateException("تعذر فتح ملف التصدير.")
        }.onFailure { error = "تعذر تصدير التقرير: ${it.message ?: "خطأ غير معروف"}" }
    }
    val currencyTotal = if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
        else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.total.toLong() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "رجوع") }
                Column(Modifier.weight(1f)) {
                    Text("مركز التقارير", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = ReportInk)
                    Text(if (cloudMode) "مصدر العرض: سجلات السحابة المحملة؛ لا تُخلط معها البيانات المحلية" else "مصدر العرض: بيانات هذا الجهاز فقط", fontSize = 11.sp, color = ReportMuted)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = ReportInk), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ملخص المبيعات المكتملة", color = Color(0xFFBFD9D0), fontSize = 13.sp)
                    Text("عدد الفواتير: ${if (cloudMode) inPeriodCloudSalesDocuments.size else inPeriodSales.size}", color = Color.White, fontWeight = FontWeight.SemiBold)
                    currencyTotal.toSortedMap().forEach { (currency, total) ->
                        Text(reportMoney(total, currency, cloudMode), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("الفترة: ${startText} — ${endText}", color = Color(0xFFBFD9D0), fontSize = 11.sp)
                }
            }
        }
        item {
            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("ابحث عن تقرير") }, leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("الفترة", fontWeight = FontWeight.SemiBold, color = ReportInk)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("اليوم", "أمس", "الأسبوع الحالي", "الأسبوع السابق", "الشهر الحالي", "الشهر السابق", "الربع الحالي", "الربع السابق", "السنة الحالية", "السنة السابقة").forEach { label ->
                        FilterChip(selected = selectedPeriod == label, onClick = {
                            selectedPeriod = label
                            val now = LocalDate.now()
                            val range = when (label) {
                                "اليوم" -> now to now
                                "أمس" -> now.minusDays(1) to now.minusDays(1)
                                "الأسبوع الحالي" -> now.with(java.time.DayOfWeek.MONDAY) to now
                                "الأسبوع السابق" -> now.with(java.time.DayOfWeek.MONDAY).minusWeeks(1) to now.with(java.time.DayOfWeek.MONDAY).minusDays(1)
                                "الشهر الحالي" -> now.withDayOfMonth(1) to now
                                "الشهر السابق" -> now.minusMonths(1).withDayOfMonth(1) to now.withDayOfMonth(1).minusDays(1)
                                "الربع الحالي" -> now.withMonth(((now.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1) to now
                                "الربع السابق" -> {
                                    val currentQuarterStart = now.withMonth(((now.monthValue - 1) / 3) * 3 + 1).withDayOfMonth(1)
                                    val previousQuarterEnd = currentQuarterStart.minusDays(1)
                                    previousQuarterEnd.minusMonths(2).withDayOfMonth(1) to previousQuarterEnd
                                }
                                "السنة السابقة" -> now.minusYears(1).withDayOfYear(1) to now.withDayOfYear(1).minusDays(1)
                                else -> now.withDayOfYear(1) to now
                            }
                            startText = range.first.toString(); endText = range.second.toString(); error = ""
                        }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(startText, { startText = it; selectedPeriod = "مخصص" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("من YYYY-MM-DD") })
                    OutlinedTextField(endText, { endText = it; selectedPeriod = "مخصص" }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("إلى YYYY-MM-DD") })
                }
                if (dateError) Text("تحقق من التاريخين؛ يجب أن تكون البداية قبل النهاية أو مساوية لها.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
        item {
            Text("تصنيفات التقارير", fontWeight = FontWeight.Bold, color = ReportInk, fontSize = 17.sp)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = selectedCategory == "الكل", onClick = { selectedCategory = "الكل" }, label = { Text("الكل") }) }
                items(categories) { category -> FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category, fontSize = 10.sp) }) }
            }
        }
        item {
            Text("التقارير المتاحة", fontWeight = FontWeight.Bold, color = ReportInk, fontSize = 17.sp)
            Text("لا يظهر التقرير كمتاح إلا عندما يكون مصدر البيانات الموجود كافيًا لحساب النتيجة المعروضة.", fontSize = 11.sp, color = ReportMuted)
        }
        items(filteredCatalog, key = { it.title }) { report ->
            val selected = selectedReport == report.title
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = report.supported && !dateError, onClick = {
                    when (report.title) {
                        "ملخص المبيعات حسب الفترة", "تفاصيل فواتير المبيعات", "المبيعات حسب الصنف",
                        "المبيعات حسب العميل", "المبيعات النقدية والآجلة", "متوسط قيمة الفاتورة", "مقارنة فترتين", "مقارنة الفترات", "الخصومات على فواتير المبيعات", "الضرائب المسجلة على المبيعات",
                        "التحصيلات المسجلة محليًا", "الدفعات السحابية الناجحة", "المصروفات التشغيلية", "رصيد المخزون الحالي", "تنبيهات المخزون المنخفض",
                        "فواتير الشراء والمستحقات المسجلة", "مستحقات فواتير الموردين", "مرتجعات المبيعات وحالة التسوية", "سندات القبض والصرف", "سجل القيود المحاسبية",
                        "ميزان المراجعة حسب الحساب", "حركة دفتر الأستاذ العام" -> selectedReport = report.title
                        "لوحة المؤشرات" -> selectedReport = "ملخص المبيعات حسب الفترة"
                    }
                }),
                colors = CardDefaults.cardColors(containerColor = if (selected) ReportMint else Color.White),
                border = BorderStroke(1.dp, if (selected) ReportGreen else ReportBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(report.title, fontWeight = FontWeight.SemiBold, color = ReportInk)
                            Text(report.category, color = ReportMuted, fontSize = 11.sp)
                        }
                        Surface(color = if (report.supported) ReportMint else Color(0xFFFFF1DF), shape = RoundedCornerShape(8.dp)) {
                            Text(if (report.supported) "متاح" else "يتطلب مصدرًا مكتملًا", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), color = if (report.supported) ReportGreen else Color(0xFF9A5B12), fontSize = 10.sp)
                        }
                    }
                    Text(report.source, color = ReportMuted, fontSize = 11.sp)
                    Text(report.note, color = ReportMuted, fontSize = 11.sp)
                }
            }
        }
        if (selectedReport != "ملخص المبيعات" && selectedRows.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(selectedReport, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ReportInk)
                        Text("عدد السجلات: ${selectedRows.size}", fontSize = 11.sp, color = ReportMuted)
                    }
                    OutlinedButton(onClick = {
                        exportTitle = selectedReport
                        exportRows = buildList {
                            add(listOf("التقرير", exportTitle))
                            add(listOf("الفترة", startText + " — " + endText))
                            add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
                            add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
                            add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
                            selectedRows.forEach { row -> add(listOf(row.date, row.reference, row.name, if (row.currency.isBlank() || row.currency == "-") row.amount.toString() else reportMoney(row.amount, row.currency, cloudMode), row.currency, row.status)) }
                        }
                        csvLauncher.launch("mutajri-report-${selectedReport.hashCode()}.csv")
                    }) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("تصدير CSV") }
                }
            }
            items(selectedRows) { row ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = BorderStroke(1.dp, ReportBorder), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.name, fontWeight = FontWeight.SemiBold, color = ReportInk)
                        Text("${row.reference} · ${row.date}", color = ReportMuted, fontSize = 11.sp)
                        Text(if (row.currency.isBlank() || row.currency == "-") "${java.text.NumberFormat.getNumberInstance(Locale.US).format(row.amount)} ${row.currency}" else reportMoney(row.amount, row.currency, cloudMode), color = ReportGreen, fontWeight = FontWeight.Bold)
                        if (row.status.isNotBlank()) Text(row.status, color = ReportMuted, fontSize = 11.sp)
                    }
                }
            }
        } else if (selectedReport != "ملخص المبيعات" && !dateError) {
            item { Text("لا توجد بيانات لهذا التقرير ضمن الفترة المحددة.", color = ReportMuted, modifier = Modifier.padding(8.dp)) }
        }
        if (cloudMode && cloudLoading) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("جارٍ تحميل مصادر التقارير السحابية...", color = ReportMuted, fontSize = 12.sp)
            }
        }
        if (cloudMode && cloudLoadError.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3DE)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("تعذر تحميل بعض التقارير: $cloudLoadError", color = Color(0xFF8A4B08), fontSize = 12.sp)
                    OutlinedButton(onClick = { cloudReload++ }) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("إعادة المحاولة") }
                }
            }
        }
        item {
            OutlinedButton(onClick = onOpenExistingReports, modifier = Modifier.fillMaxWidth()) {
                Icon(if (cloudMode) Icons.Default.Cloud else Icons.Default.Assessment, null)
                Spacer(Modifier.width(8.dp))
                Text(if (cloudMode) "فتح تقرير السحابة التشغيلي التفصيلي" else "فتح شاشة التقارير التشغيلية الحالية")
            }
        }
        item {
            Text("حدود الدقة: لا تُجمع العملات المختلفة. لا تُعرض القوائم المالية الرسمية أو أرصدة العملاء/الموردين أو الربح المحاسبي قبل مطابقة القيود والمرتجعات والتسويات ومصادرها.", color = ReportMuted, fontSize = 11.sp)
        }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}
