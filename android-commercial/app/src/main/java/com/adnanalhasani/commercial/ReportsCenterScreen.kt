package com.adnanalhasani.commercial

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
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
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Currency
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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


/** Creates a valid XLSX OOXML workbook; Arabic text is stored as UTF-8 inline strings. */
private fun writeReportXlsx(output: java.io.OutputStream, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات لتصديرها." }
    fun xml(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20) append(ch)
            }
        }
    }
    fun colName(index: Int): String {
        var n = index + 1
        val out = StringBuilder()
        while (n > 0) { val rem = (n - 1) % 26; out.append(('A'.code + rem).toChar()); n = (n - 1) / 26 }
        return out.reverse().toString()
    }
    val sheet = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0" rightToLeft="1"/></sheetViews><sheetData>""")
        rows.forEachIndexed { ri, row ->
            append("<row r=\"").append(ri + 1).append("\">")
            row.forEachIndexed { ci, cell ->
                append("<c r=\"").append(colName(ci)).append(ri + 1).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                append(xml(cell))
                append("</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="التقرير" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
        entry("xl/worksheets/sheet1.xml", sheet)
    }
}

/** Writes a paginated PDF report using Android's native PDF renderer. */
private fun writeReportPdf(output: java.io.OutputStream, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات لتصديرها." }
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val left = 36f
    val right = pageWidth - 36f
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(35, 48, 44)
        textSize = 10f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.RIGHT
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(22, 91, 72)
        textSize = 16f
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(210, 222, 216)
        strokeWidth = 1f
    }
    var pageNumber = 0
    var page: PdfDocument.Page? = null
    var canvas: android.graphics.Canvas? = null
    var y = 0f
    fun startPage() {
        pageNumber++
        page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        canvas = page!!.canvas
        canvas!!.drawColor(android.graphics.Color.WHITE)
        y = 42f
        canvas!!.drawText("تقرير متجري", right, y, titlePaint)
        y += 22f
        canvas!!.drawLine(left, y, right, y, rulePaint)
        y += 20f
    }
    fun finishPage() {
        val current = page ?: return
        canvas?.drawText("صفحة $pageNumber", right, (pageHeight - 22).toFloat(), bodyPaint)
        document.finishPage(current)
        page = null
        canvas = null
    }
    try {
        startPage()
        rows.forEach { row ->
            val text = row.joinToString("    |    ") { it.replace('\n', ' ').replace('\r', ' ') }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, (right - left).toInt())
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setLineSpacing(2f, 1f)
                .build()
            if (y + layout.height > pageHeight - 54f) { finishPage(); startPage() }
            canvas!!.save()
            canvas!!.translate(left, y)
            layout.draw(canvas!!)
            canvas!!.restore()
            y += layout.height + 3f
        }
        finishPage()
        document.writeTo(output)
    } finally { document.close() }
}
/** Opens Android's system print preview and streams the same PDF report into the print spooler. */
private fun printReport(context: Context, title: String, rows: List<List<String>>) {
    require(rows.isNotEmpty()) { "لا توجد بيانات للطباعة." }
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        ?: throw IllegalStateException("خدمة الطباعة غير متاحة على هذا الجهاز.")
    val adapter = object : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes,
            cancellationSignal: CancellationSignal,
            callback: LayoutResultCallback,
            extras: android.os.Bundle?
        ) {
            if (cancellationSignal.isCanceled) { callback.onLayoutCancelled(); return }
            val info = PrintDocumentInfo.Builder("$title.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, oldAttributes != newAttributes)
        }
        override fun onWrite(
            pages: Array<out PageRange>,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal,
            callback: WriteResultCallback
        ) {
            if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return }
            try {
                FileOutputStream(destination.fileDescriptor).use { writeReportPdf(it, rows) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (failure: Exception) {
                callback.onWriteFailed(failure.message ?: "تعذر إنشاء ملف الطباعة.")
            }
        }
    }
    printManager.print(title, adapter, PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
        .build())
}
private data class ReportCatalogItem(val title: String, val category: String, val source: String, val supported: Boolean, val note: String)
private data class ReportLine(val date: String, val reference: String, val name: String, val amount: Long, val currency: String, val status: String, val displayAmount: String? = null)
private fun reportLineAmount(row: ReportLine, cloudMode: Boolean): String = row.displayAmount ?: if (row.currency.isBlank() || row.currency == "-") row.amount.toString() else reportMoney(row.amount, row.currency, cloudMode)
private data class ReportCustomerMovement(val date: String, val customerId: String, val customer: String, val currency: String, val reference: String, val amount: Long, val description: String)
private data class ReportSupplierMovement(val date: String, val supplier: String, val currency: String, val reference: String, val amount: Long, val description: String)

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
    var rowFilter by remember { mutableStateOf("") }
    var currencyFilter by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("") }
    var accountFilter by remember { mutableStateOf("") }
    var customerFilter by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("الكل") }
    var selectedReport by remember { mutableStateOf("ملخص المبيعات حسب الفترة") }
    var exportRows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var exportTitle by remember { mutableStateOf("تقرير متجري") }
    var error by remember { mutableStateOf("") }
    var cloudSalesDocuments by remember { mutableStateOf<List<CommercialCloudSaleDocument>>(emptyList()) }
    var cloudPurchases by remember { mutableStateOf<List<CommercialCloudPurchaseDocument>>(emptyList()) }
    var cloudSupplierPayments by remember { mutableStateOf<List<CommercialCloudSupplierPaymentMovement>>(emptyList()) }
    var cloudStockMovements by remember { mutableStateOf<List<CommercialCloudStockMovement>>(emptyList()) }
    var supplierFilter by remember { mutableStateOf("") }
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
            val supplierPayments = runCatching { loadCommercialCloudSupplierPaymentMovements(context) }
            val stockMovements = runCatching { loadCommercialCloudStockMovements(context) }
            val returns = runCatching { loadCommercialCloudSaleReturns(context) }
            val payments = runCatching { loadCommercialCloudRecords(context, "payments") }
            val journals = runCatching { loadCommercialCloudRecords(context, "journals") }
            val ledgerLines = runCatching { loadCommercialCloudPostedJournalLines(context) }
            val expenses = runCatching { loadCommercialCloudExpenses(context) }
            ReportMain.post {
                cloudLoading = false
                // Clear failed projections rather than silently showing stale results as fresh.
                cloudSalesDocuments = salesDocuments.getOrDefault(emptyList())
                cloudPurchases = purchases.getOrDefault(emptyList())
                cloudSupplierPayments = supplierPayments.getOrDefault(emptyList())
                cloudStockMovements = stockMovements.getOrDefault(emptyList())
                cloudReturns = returns.getOrDefault(emptyList())
                cloudPayments = payments.getOrDefault(emptyList())
                cloudJournals = journals.getOrDefault(emptyList())
                cloudLedgerLines = ledgerLines.getOrDefault(emptyList())
                cloudExpenses = expenses.getOrDefault(emptyList())
                val errors = listOf(
                    salesDocuments.exceptionOrNull(), purchases.exceptionOrNull(), supplierPayments.exceptionOrNull(), stockMovements.exceptionOrNull(), returns.exceptionOrNull(), payments.exceptionOrNull(),
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
            ReportCatalogItem("تفاصيل بنود فواتير المبيعات", "المبيعات", "commercial_sales + commercial_sale_items", cloudMode, "يعرض لقطة كل بند من الفاتورة الأصلية، الكمية وسعر الوحدة وإجمالي البند؛ لا يستخدم أسعار الصنف الحالية.")
            ReportCatalogItem("المبيعات حسب الصنف", "المبيعات", if (cloudMode) "commercial_sale_items" else "sales_json", true, "يعرض بنود الفواتير السحابية الفعلية؛ في المحلي يمثل كل سجل بيع الصنف المسجل فيه."),
            ReportCatalogItem("الخصومات على فواتير المبيعات", "الضرائب والخصومات", "commercial_sales.discount_minor", cloudMode, "يعرض الخصومات المخزنة على الفواتير المكتملة، دون افتراض نسبة خصم."),
            ReportCatalogItem("الضرائب المسجلة على المبيعات", "الضرائب والخصومات", "commercial_sales.tax_minor", cloudMode, "يعرض قيمة الضريبة المخزنة فقط؛ لا يستنتج نسبة ضريبية من الإجمالي."),
            ReportCatalogItem("المبيعات حسب العميل", "المبيعات", "اسم العميل المحفوظ في سجل البيع", true, "يعرض مجموع الفواتير المكتملة المسجلة للعميل حسب العملة."),
            ReportCatalogItem(if (cloudMode) "المبيعات حسب حالة السداد" else "المبيعات النقدية والآجلة", "المبيعات", "نوع البيع المحلي أو حالة السداد الفعلية في الفاتورة السحابية", true, "في السحابة يصنف حسب حالة السداد المسجلة، ولا يستنتج طريقة الدفع من مجرد إجمالي الفاتورة."),
            ReportCatalogItem("متوسط قيمة الفاتورة", "المبيعات", "إجمالي الفواتير المكتملة وعددها", true, "متوسط تشغيلي منفصل حسب العملة."),
            ReportCatalogItem("مقارنة الفترات", "الفترات والمقارنات", "سجلات المبيعات المكتملة المؤرخة", true, "يقارن الفترة الحالية بفترة سابقة مساوية لها في عدد الأيام حسب العملة."),
            ReportCatalogItem(if (cloudMode) "الدفعات السحابية الناجحة" else "التحصيلات المسجلة محليًا", "السندات والتحصيل والصرف", if (cloudMode) "commercial_payments" else "settlements_json", true, "يعرض الدفعات الناجحة فقط في السحابة؛ السندات لا تُجمع منفصلة عن الدفعة المرتبطة."),
            ReportCatalogItem("المصروفات التشغيلية", "الأرباح والتكاليف", if (cloudMode) "commercial_expense_vouchers" else "expenses_json", true, "تُستبعد سندات الصرف الملغاة؛ هذا تقرير مصروفات مسجلة وليس قائمة دخل."),
            ReportCatalogItem("رصيد المخزون الحالي", "الأصناف والمخزون", if (cloudMode) "commercial_inventory_balances" else "products_json", true, "رصيد حالي فقط؛ ليس رصيدًا تاريخيًا للفترة."),
            ReportCatalogItem("حركة المخزون الفعلية", "الأصناف والمخزون", "commercial_stock_movements المرتبطة بنشاط المؤسسة", cloudMode, "يعرض حركات الكمية المسجلة من دفتر حركة المخزون، مع مرجع المصدر وحالة فاتورة البيع عند توفرها؛ لا يستنتج تكلفة أو قيمة مالية من الكمية.")
            ReportCatalogItem("تنبيهات المخزون المنخفض", "الأصناف والمخزون", "الرصيد وحد إعادة الطلب", true, "يعتمد على حد التنبيه المسجل لكل صنف."),
            ReportCatalogItem("فواتير الشراء والمستحقات المسجلة", "المشتريات", "commercial_purchase_orders ← supplier_invoices ← supplier_obligations", cloudMode, "يُعرض مبلغ الفاتورة والرصيد المستحق من سجل الالتزام؛ لا يُخلط مع قيمة أمر الشراء."),
            ReportCatalogItem("مستحقات فواتير الموردين", "الموردون والمستحقات", "commercial_supplier_obligations المرتبطة بالفواتير", cloudMode, "مجموع الالتزامات المسجلة ناقص المبالغ المسددة المسجلة فقط؛ لا يمثل رصيد المورد الشامل إذا وجدت تسويات أو مرتجعات خارج هذا المصدر."),
            ReportCatalogItem("مرتجعات المبيعات وحالة التسوية", "المرتجعات", "commercial_sales_returns + عناصر المرتجع", cloudMode, "يفصل حالة الموافقة عن الاسترداد الفعلي وإعادة التخزين؛ لا تُعامل الطلبات المعلقة كمرتجعات منفذة."),
            ReportCatalogItem("أرصدة العملاء وكشف الحساب", "العملاء والديون", "فواتير العملاء المكتملة + التحصيلات الناجحة + المرتجعات المعتمدة والاستردادات المنفذة", cloudMode, "يعرض كشف حركة للفواتير المكتملة والتحصيلات والمرتجعات والاستردادات المسجلة في مصادر السحابة، مع رصيد افتتاحي وحركة ورصيد ختامي حسب العميل والعملة."),
            ReportCatalogItem("كشف حركات الموردين", "الموردون والمستحقات", "فواتير المورد ذات الالتزام + دفعات الموردين ذات القيد المرحل", cloudMode, "يعرض الفواتير والدفعات الفريدة حسب المورد والعملة، مع رصيد افتتاحي وختامي مستنتج من هذين المصدرين فقط؛ لا يمثل رصيد المورد النهائي عند وجود مرتجعات أو تسويات غير محملة."),
            ReportCatalogItem("سندات القبض والصرف", "السندات والتحصيل والصرف", "commercial_payments وحالة كل دفعة", cloudMode, "يعرض حالة كل دفعة؛ لحساب التحصيل الفعلي استخدم تقرير الدفعات الناجحة فقط، ولا تجمع القيد والدفعة مرتين."),
            ReportCatalogItem("سجل القيود المحاسبية", "المحاسبة والمالية", "commercial_journal_entries", cloudMode, "قائمة قراءة فقط لحالات القيود ومصادرها."),
            ReportCatalogItem("ميزان المراجعة حسب الحساب", "المحاسبة والمالية", "commercial_journal_lines + commercial_ledger_accounts + القيود المرحلة", cloudMode, "رصيد حتى نهاية التاريخ المحدد، مع إظهار المدين والدائن والفروقات حسب العملة."),
            ReportCatalogItem("حركة دفتر الأستاذ العام", "المحاسبة والمالية", "سطور القيود المرحلة ومصادرها", cloudMode, "تفاصيل سطور القيود المرحلة ضمن الفترة."),
            ReportCatalogItem("كشف حساب محاسبي", "المحاسبة والمالية", "سطور دفتر الأستاذ المرحلة حسب الحساب", cloudMode, "يتطلب إدخال رمز الحساب أو اسمه؛ يعرض الحركات المرحلة فقط ضمن الفترة والعملة المسجلة."),

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
    val inPeriodSales = if (dateError) emptyList() else sales.filter { it.createdAt >= startMillis && it.createdAt < endExclusive && it.status.equals("completed", true) }
    val inPeriodCloudSalesDocuments = if (dateError) emptyList() else cloudSalesDocuments.filter { doc ->
        doc.saleStatus.equals("completed", true) &&
            (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
    }
    val inPeriodSettlements = if (cloudMode) emptyList() else settlements.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val inPeriodExpenses = if (cloudMode) emptyList() else expenses.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val salesByCurrency = inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.toSortedMap()
    val stockLow = products.filter { it.stock <= (it.reorderPoint.takeIf { n -> n >= 0 } ?: 9) }
    val selectedRowsUnvalidated = when (selectedReport) {
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
        "تفاصيل بنود فواتير المبيعات" -> cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) && (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }.flatMap { doc ->
            if (doc.lines.isEmpty()) listOf(ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber}", doc.customerName, doc.totalMinor, doc.currencyCode, "لا توجد بنود متاحة لهذه الفاتورة"))
            else doc.lines.mapIndexed { index, line ->
                ReportLine(doc.createdAt.take(10), "فاتورة #${doc.receiptNumber} · ${index + 1}", line.productName, line.lineTotalMinor, doc.currencyCode,
                    "العميل: ${doc.customerName} · الكمية: ${line.quantity} · سعر الوحدة: ${reportMoney(line.unitPriceMinor, doc.currencyCode, true)} · السداد: ${doc.paymentStatus}")
            }
        }.sortedByDescending { it.date }
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
        "المبيعات حسب حالة السداد" -> inPeriodCloudSalesDocuments.groupBy { it.paymentStatus.ifBlank { "غير محدد" } to it.currencyCode }.map { (key, rows) ->
            ReportLine("-", "-", key.first, rows.sumOf { it.totalMinor }, key.second, "${rows.size} فاتورة")
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
        "كشف حركات الموردين" -> {
            val invoiceMovements = cloudPurchases.flatMap { purchase ->
                purchase.supplierInvoices.filter { invoice ->
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        invoice.obligation != null && invoice.obligation.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        invoice.obligation.amountDueMinor > 0L && invoice.obligation.currencyCode.matches(Regex("^[A-Z]{3}$"))
                }.map { invoice ->
                    val obligation = invoice.obligation!!
                    ReportSupplierMovement(invoice.recordedAt.take(10), purchase.supplierName, obligation.currencyCode,
                        "فاتورة مورد ${invoice.invoiceNumber.ifBlank { invoice.id }}", obligation.amountDueMinor, "مدين · التزام فاتورة مسجل")
                }
            }
            val paymentMovements = cloudSupplierPayments.map { payment ->
                ReportSupplierMovement(payment.recordedAt.take(10), payment.supplierName, payment.currencyCode,
                    "دفعة ${payment.id}", -payment.amountMinor, "دائن · سداد مرحل · فاتورة ${payment.invoiceNumber.ifBlank { payment.supplierInvoiceId }} · ${payment.paymentMethod}")
            }
            (invoiceMovements + paymentMovements).filter { movement -> supplierFilter.isBlank() || movement.supplier.contains(supplierFilter.trim(), true) }
                .groupBy { it.supplier to it.currency }.toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).flatMap { (key, supplierMoves) ->
                    val ordered = supplierMoves.sortedWith(compareBy<ReportSupplierMovement> { it.date }.thenBy { it.reference })
                    val opening = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts < startMillis } == true }.sumOf { it.amount }
                    val periodMoves = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts >= startMillis && ts < endExclusive } == true }
                    if (periodMoves.isEmpty() && opening == 0L) emptyList() else buildList {
                        var balance = opening
                        if (opening != 0L) add(ReportLine(startText, "افتتاحي", key.first, opening, key.second, "رصيد افتتاحي وفق الفواتير والدفعات المحملة"))
                        periodMoves.forEach { movement ->
                            balance = Math.addExact(balance, movement.amount)
                            add(ReportLine(movement.date, movement.reference, movement.supplier, movement.amount, movement.currency, "${movement.description} · الرصيد الجزئي: ${reportMoney(balance, movement.currency, true)}"))
                        }
                        add(ReportLine(endText, "ختامي", key.first, balance, key.second, "رصيد جزئي للفواتير والدفعات فقط؛ لا يشمل مرتجعات أو تسويات غير محملة"))
                    }
                }
        }
        "مرتجعات المبيعات وحالة التسوية" -> cloudReturns.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive
        }.map { row ->
            val approved = if (row.status in setOf("approved", "completed")) (row.approvedRefundMinor ?: 0L) else 0L
            ReportLine(row.createdAt.take(10), row.receiptNumber, row.customerName, approved, row.currencyCode,
                "الطلب: ${row.status} · الاسترداد: ${row.refundStatus} · المخزون: ${row.restockStatus}")
        }.sortedByDescending { it.date }
        "أرصدة العملاء وكشف الحساب" -> {
            val movements = buildList {
                cloudSalesDocuments.filter { it.saleStatus.equals("completed", true) && it.customerId.isNotBlank() }.forEach { doc ->
                    val customerName = doc.customerName.ifBlank { "عميل غير محدد" }
                    val currency = doc.currencyCode.ifBlank { "غير محددة" }
                    add(ReportCustomerMovement(doc.createdAt.take(10), doc.customerId, customerName, currency, "فاتورة #${doc.receiptNumber}", doc.totalMinor.coerceAtLeast(0L), "مدين · فاتورة مكتملة"))
                    doc.payments.filter { it.status.lowercase(Locale.ROOT) in setOf("succeeded", "partially_refunded", "refunded") && it.amountMinor > 0L }.forEach { payment ->
                        val date = payment.createdAt.take(10).ifBlank { doc.createdAt.take(10) }
                        add(ReportCustomerMovement(date, doc.customerId, customerName, currency, "تحصيل ${payment.id.ifBlank { doc.receiptNumber }}", -payment.amountMinor.coerceAtLeast(0L), "دائن · تحصيل ${payment.status}"))
                    }
                    doc.returns.filter { it.status.equals("completed", true) && (it.approvedRefundMinor ?: 0L) > 0L }.forEach { saleReturn ->
                        add(ReportCustomerMovement(saleReturn.createdAt.take(10), doc.customerId, customerName, currency, "مرتجع ${saleReturn.id}", -(saleReturn.approvedRefundMinor ?: 0L).coerceAtLeast(0L), "دائن · مرتجع معتمد"))
                    }
                    doc.returns.filter { it.refundStatus.equals("refunded", true) && (it.approvedRefundMinor ?: 0L) > 0L }.forEach { saleReturn ->
                        add(ReportCustomerMovement(saleReturn.createdAt.take(10), doc.customerId, customerName, currency, "استرداد ${saleReturn.id}", (saleReturn.approvedRefundMinor ?: 0L).coerceAtLeast(0L), "مدين · استرداد منفذ"))
                    }
                }
            }.filter { movement -> customerFilter.isBlank() || movement.customer.contains(customerFilter.trim(), true) || movement.customerId.contains(customerFilter.trim(), true) }
            movements.groupBy { Triple(it.customerId, it.customer, it.currency) }.toSortedMap(compareBy<Triple<String, String, String>> { it.second }.thenBy { it.third }).flatMap { (key, customerMoves) ->
                val ordered = customerMoves.sortedWith(compareBy<ReportCustomerMovement> { it.date }.thenBy { it.reference })
                val opening = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts < startMillis } == true }.sumOf { it.amount }
                val periodMoves = ordered.filter { reportDateMillis(it.date)?.let { ts -> ts >= startMillis && ts < endExclusive } == true }
                if (periodMoves.isEmpty() && opening == 0L) emptyList() else buildList {
                    var balance = opening
                    if (opening != 0L) add(ReportLine(startText, "افتتاحي", key.second, opening, key.third, "رصيد افتتاحي"))
                    periodMoves.forEach { movement ->
                        balance = Math.addExact(balance, movement.amount)
                        add(ReportLine(movement.date, movement.reference, movement.customer, movement.amount, movement.currency, "${movement.description} · الرصيد: ${reportMoney(balance, movement.currency, true)}"))
                    }
                    add(ReportLine(endText, "ختامي", key.second, balance, key.third, "الرصيد الختامي حسب الحركات المعروضة"))
                }
            }
        }
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
        "حركة دفتر الأستاذ العام", "كشف حساب محاسبي" -> {
            val accountQuery = accountFilter.trim()
            cloudLedgerLines.filter { line ->
                val timestamp = reportDateMillis(line.entryDate)
                timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                    (selectedReport != "كشف حساب محاسبي" || (accountQuery.isNotBlank() &&
                        listOf(line.accountId, line.accountCode, line.accountName).any { it.contains(accountQuery, true) }))
            }.map { line ->
                ReportLine(line.entryDate.take(10), line.entryNumber.ifBlank { line.entryId }, "${line.accountCode} · ${line.accountName}",
                    line.debitMinor - line.creditMinor, line.currencyCode,
                    "قيد ${line.entryNumber} · مدين: ${line.debitMinor} · دائن: ${line.creditMinor} · مصدر: ${line.sourceType} ${line.sourceId}")
            }.sortedByDescending { it.date }
        }
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
        "حركة المخزون الفعلية" -> cloudStockMovements.filter { movement ->
            reportDateMillis(movement.createdAt)?.let { it >= startMillis && it < endExclusive } == true
        }.map { movement ->
            ReportLine(movement.createdAt.take(10), movement.sourceReference.ifBlank { movement.id }, movement.productName, 0L, "-",
                "نوع الحركة: ${movement.movementType} · المصدر: ${movement.saleId.ifBlank { movement.sourceReference }} ${movement.saleStatus}",
                displayAmount = movement.quantityDelta.stripTrailingZeros().toPlainString() + if (movement.unitName.isBlank()) "" else " ${movement.unitName}")
        }.sortedByDescending { it.date }
        "رصيد المخزون الحالي" -> products.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "${it.unitCode} · سعر ${it.price}")
        }
        "تنبيهات المخزون المنخفض" -> stockLow.map {
            ReportLine("-", it.catalogCode.ifBlank { it.barcode }, it.name, it.stock.toLong(), "-", "حد التنبيه ${it.reorderPoint.takeIf { n -> n >= 0 } ?: 9} ${it.unitCode}")
        }
        else -> emptyList()
    }
    // Invalid dates must never widen a report to the full history.
    val selectedRows = if (dateError || (cloudMode && (cloudLoading || cloudLoadError.isNotBlank()))) emptyList() else selectedRowsUnvalidated.filter { row ->
        (rowFilter.isBlank() || row.name.contains(rowFilter.trim(), true) || row.reference.contains(rowFilter.trim(), true)) &&
            (currencyFilter.isBlank() || row.currency.contains(currencyFilter.trim(), true)) &&
            (statusFilter.isBlank() || row.status.contains(statusFilter.trim(), true))
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && cloudLoadError.isBlank())) { "تعذر التصدير لأن مصادر البيانات السحابية غير مكتملة." }
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write("\uFEFF")
                    writer.write(exportRows.joinToString("\r\n") { row -> row.joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" } })
                }
            } ?: throw IllegalStateException("تعذر فتح ملف التصدير.")
        }.onFailure { error = "تعذر تصدير التقرير: ${it.message ?: "خطأ غير معروف"}" }
    }
    val xlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && cloudLoadError.isBlank())) { "تعذر التصدير لأن مصادر البيانات السحابية غير مكتملة." }
            context.contentResolver.openOutputStream(uri)?.use { writeReportXlsx(it, exportRows) }
                ?: throw IllegalStateException("تعذر فتح ملف Excel.")
        }.onFailure { error = "تعذر تصدير XLSX: ${it.message ?: "خطأ غير معروف"}" }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) runCatching {
            require(!dateError) { "لا يمكن تصدير تقرير بفترة غير صالحة." }
            require(!cloudMode || (!cloudLoading && cloudLoadError.isBlank())) { "تعذر التصدير لأن مصادر البيانات السحابية غير مكتملة." }
            context.contentResolver.openOutputStream(uri)?.use { writeReportPdf(it, exportRows) }
                ?: throw IllegalStateException("تعذر فتح ملف PDF.")
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "مشاركة تقرير متجري"))
        }.onFailure { error = "تعذر إنشاء أو مشاركة PDF: ${it.message ?: "خطأ غير معروف"}" }
    }
    val currencyTotal = if (dateError || (cloudMode && (cloudLoading || cloudLoadError.isNotBlank()))) emptyMap() else if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
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
                    Text(when { dateError -> "لا يمكن حساب الملخص: تحقق من الفترة المحددة"; cloudMode && cloudLoading -> "جارٍ تحميل مصادر المبيعات"; cloudMode && cloudLoadError.isNotBlank() -> "تعذر حساب الملخص بسبب فشل تحميل مصدر سحابي"; else -> "عدد الفواتير: ${if (cloudMode) inPeriodCloudSalesDocuments.size else inPeriodSales.size}" }, color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (!dateError && (!cloudMode || (!cloudLoading && cloudLoadError.isBlank()))) currencyTotal.toSortedMap().forEach { (currency, total) ->
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
        if (selectedReport == "كشف حركات الموردين") item {
            OutlinedTextField(
                value = supplierFilter,
                onValueChange = { supplierFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم المورد") },
                supportingText = { Text("الرصيد جزئي ويعتمد على التزامات الفواتير ودفعات الموردين ذات القيود المرحلة فقط.") }
            )
        }
        if (selectedReport == "أرصدة العملاء وكشف الحساب") item {
            OutlinedTextField(
                value = customerFilter,
                onValueChange = { customerFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم العميل أو معرفه") },
                supportingText = { Text("يصفّي الكشف حسب العميل؛ الرصيد يحسب من الفواتير والتحصيلات والمرتجعات والاستردادات المحملة.") }
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تصفية نتائج التقرير", fontWeight = FontWeight.SemiBold, color = ReportInk)
                OutlinedTextField(rowFilter, { rowFilter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم أو مرجع المستند") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(currencyFilter, { currencyFilter = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("العملة") })
                    OutlinedTextField(statusFilter, { statusFilter = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("الحالة") })
                }
            }
        }
        if (selectedReport == "كشف حساب محاسبي") item {
            OutlinedTextField(
                value = accountFilter,
                onValueChange = { accountFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("رمز الحساب أو اسمه") },
                supportingText = { Text("أدخل رمزًا أو اسمًا مطابقًا للحساب؛ لا يعرض الكشف كل الحسابات تلقائيًا.") }
            )
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
                modifier = Modifier.fillMaxWidth().clickable(enabled = report.supported && !dateError && (!cloudMode || (!cloudLoading && cloudLoadError.isBlank())), onClick = {
                    when (report.title) {
                        "ملخص المبيعات حسب الفترة", "تفاصيل فواتير المبيعات", "تفاصيل بنود فواتير المبيعات", "المبيعات حسب الصنف",
                        "المبيعات حسب العميل", "المبيعات النقدية والآجلة", "المبيعات حسب حالة السداد", "متوسط قيمة الفاتورة", "مقارنة فترتين", "مقارنة الفترات", "الخصومات على فواتير المبيعات", "الضرائب المسجلة على المبيعات",
                        "التحصيلات المسجلة محليًا", "الدفعات السحابية الناجحة", "المصروفات التشغيلية", "رصيد المخزون الحالي", "تنبيهات المخزون المنخفض",
                        "فواتير الشراء والمستحقات المسجلة", "مستحقات فواتير الموردين", "مرتجعات المبيعات وحالة التسوية", "سندات القبض والصرف", "سجل القيود المحاسبية",
                        "ميزان المراجعة حسب الحساب", "حركة دفتر الأستاذ العام", "كشف حساب محاسبي", "أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "حركة المخزون الفعلية" -> selectedReport = report.title
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
                        val sourceReady = report.supported && (!cloudMode || (!cloudLoading && cloudLoadError.isBlank()))
                        Surface(color = if (sourceReady) ReportMint else Color(0xFFFFF1DF), shape = RoundedCornerShape(8.dp)) {
                            Text(
                                when {
                                    !report.supported -> "يتطلب مصدرًا مكتملًا"
                                    cloudMode && cloudLoading -> "جارٍ تحميل المصدر"
                                    cloudMode && cloudLoadError.isNotBlank() -> "تعذر تحميل المصدر"
                                    else -> "متاح"
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                color = if (sourceReady) ReportGreen else Color(0xFF9A5B12), fontSize = 10.sp
                            )
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
                            selectedRows.forEach { row -> add(listOf(row.date, row.reference, row.name, reportLineAmount(row, cloudMode), row.currency, row.status)) }
                        }
                        csvLauncher.launch("mutajri-report-${selectedReport.hashCode()}.csv")
                    }) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(4.dp)); Text("تصدير CSV") }
                    OutlinedButton(onClick = {
                        exportTitle = selectedReport
                        exportRows = buildList {
                            add(listOf("التقرير", exportTitle))
                            add(listOf("الفترة", startText + " — " + endText))
                            add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
                            add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
                            add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
                            selectedRows.forEach { row -> add(listOf(row.date, row.reference, row.name, reportLineAmount(row, cloudMode), row.currency, row.status)) }
                        }
                        xlsxLauncher.launch("mutajri-report-${selectedReport.hashCode()}.xlsx")
                    }) { Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(4.dp)); Text("Excel XLSX") }
                    OutlinedButton(onClick = {
                        if (dateError || (cloudMode && (cloudLoading || cloudLoadError.isNotBlank()))) {
                            error = "لا يمكن تصدير تقرير بفترة غير صالحة أو بمصدر بيانات غير مكتمل."
                        } else {
                            exportTitle = selectedReport
                            exportRows = buildList {
                                add(listOf("التقرير", exportTitle))
                                add(listOf("الفترة", startText + " — " + endText))
                                add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
                                add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
                                add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
                                selectedRows.forEach { row ->
                                    add(listOf(row.date, row.reference, row.name,
                                        reportLineAmount(row, cloudMode),
                                        row.currency, row.status))
                                }
                            }
                            pdfLauncher.launch("mutajri-report-${selectedReport.hashCode()}.pdf")
                        }
                    }) { Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(4.dp)); Text("PDF ومشاركة") }
                    OutlinedButton(onClick = {
                        if (dateError || (cloudMode && (cloudLoading || cloudLoadError.isNotBlank()))) {
                            error = "لا يمكن طباعة تقرير بفترة غير صالحة أو بمصدر بيانات غير مكتمل."
                        } else {
                            exportTitle = selectedReport
                            exportRows = buildList {
                                add(listOf("التقرير", exportTitle))
                                add(listOf("الفترة", startText + " — " + endText))
                                add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
                                add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
                                add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
                                selectedRows.forEach { row ->
                                    add(listOf(row.date, row.reference, row.name,
                                        reportLineAmount(row, cloudMode),
                                        row.currency, row.status))
                                }
                            }
                            runCatching { printReport(context, selectedReport, exportRows) }
                                .onFailure { error = "تعذرت معاينة الطباعة: ${it.message ?: "خطأ غير معروف"}" }
                        }
                    }) { Icon(Icons.Default.Print, null); Spacer(Modifier.width(4.dp)); Text("معاينة وطباعة") }
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
