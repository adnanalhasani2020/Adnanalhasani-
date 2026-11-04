package com.adnanalhasani.commercial

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal val ReportInk = Color(0xFF183B35)
internal val ReportGreen = Color(0xFF167D63)
internal val ReportMint = Color(0xFFE8F5EF)
internal val ReportMuted = Color(0xFF71817B)
internal val ReportBorder = Color(0xFFE5EBE8)

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
    val uiState = remember { ReportsCenterUiState(today) }
    var selectedPeriod by uiState.selectedPeriod
    var startText by uiState.startText
    var endText by uiState.endText
    var search by uiState.search
    var rowFilter by uiState.rowFilter
    var currencyFilter by uiState.currencyFilter
    var statusFilter by uiState.statusFilter
    var accountFilter by uiState.accountFilter
    var customerFilter by uiState.customerFilter
    var selectedCategory by uiState.selectedCategory
    var selectedReport by uiState.selectedReport
    var exportRows by uiState.exportRows
    var exportTitle by uiState.exportTitle
    var error by uiState.error
    var cloudReload by uiState.cloudReload
    val cloudData = rememberReportsCloudData(context, cloudMode, cloudReload)
    var supplierFilter by uiState.supplierFilter
    val reportData = rememberReportsCenterData(
        today = today,
        cloudMode = cloudMode,
        cloudData = cloudData,
        startText = startText,
        endText = endText,
        search = search,
        selectedCategory = selectedCategory,
        selectedReport = selectedReport,
        rowFilter = rowFilter,
        currencyFilter = currencyFilter,
        statusFilter = statusFilter,
        accountFilter = accountFilter,
        customerFilter = customerFilter,
        supplierFilter = supplierFilter,
        sales = sales,
        settlements = settlements,
        expenses = expenses,
        products = products
    )
    val dateError = reportData.dateError
    val cloudLoading = reportData.cloudLoading
    val cloudLoadError = reportData.cloudLoadError
    val salesDocumentsLoadError = reportData.salesDocumentsLoadError
    val supplierPaymentsLoadError = reportData.supplierPaymentsLoadError
    val stockMovementsLoadError = reportData.stockMovementsLoadError
    val stockAlertsLoadError = reportData.stockAlertsLoadError
    val locationInventoryLoadError = reportData.locationInventoryLoadError
    val inPeriodSales = reportData.inPeriodSales
    val inPeriodCloudSalesDocuments = reportData.inPeriodCloudSalesDocuments
    val categories = reportData.categories
    val filteredCatalog = reportData.filteredCatalog
    val sourceErrorForReport = reportData.sourceErrorForReport
    val selectedRows = reportData.selectedRows
    val selectedReportSourceError = reportData.selectedReportSourceError
    val currencyTotal = reportData.currencyTotal

    val exportLaunchers = rememberReportsExportLaunchers(
        context = context,
        dateError = dateError,
        cloudMode = cloudMode,
        cloudLoading = cloudLoading,
        selectedReportSourceError = selectedReportSourceError,
        exportRows = exportRows,
        onError = { error = it }
    )

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
                    Text(when { dateError -> "لا يمكن حساب الملخص: تحقق من الفترة المحددة"; cloudMode && cloudLoading -> "جارٍ تحميل مصادر المبيعات"; cloudMode && salesDocumentsLoadError.isNotBlank() -> "تعذر حساب الملخص بسبب فشل تحميل مصدر المبيعات"; else -> "عدد الفواتير: ${if (cloudMode) inPeriodCloudSalesDocuments.size else inPeriodSales.size}" }, color = Color.White, fontWeight = FontWeight.SemiBold)
                    if (!dateError && (!cloudMode || (!cloudLoading && salesDocumentsLoadError.isBlank()))) currencyTotal.toSortedMap().forEach { (currency, total) ->
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
            ReportsPeriodControls(
                selectedPeriod = selectedPeriod,
                startText = startText,
                endText = endText,
                dateError = dateError,
                onPeriodSelected = { selectedPeriod = it },
                onRangeChanged = { start, end -> startText = start; endText = end },
                onClearError = { error = "" }
            )
        }
        reportsFilterSection(
            selectedReport = selectedReport,
            supplierFilter = supplierFilter,
            customerFilter = customerFilter,
            rowFilter = rowFilter,
            currencyFilter = currencyFilter,
            statusFilter = statusFilter,
            accountFilter = accountFilter,
            onSupplierFilterChanged = { supplierFilter = it },
            onCustomerFilterChanged = { customerFilter = it },
            onRowFilterChanged = { rowFilter = it },
            onCurrencyFilterChanged = { currencyFilter = it },
            onStatusFilterChanged = { statusFilter = it },
            onAccountFilterChanged = { accountFilter = it }
        )
        reportsCatalogSection(
            categories = categories,
            selectedCategory = selectedCategory,
            onCategorySelected = { selectedCategory = it },
            filteredCatalog = filteredCatalog,
            selectedReport = selectedReport,
            dateError = dateError,
            cloudMode = cloudMode,
            cloudLoading = cloudLoading,
            sourceErrorForReport = sourceErrorForReport,
            supplierPaymentsLoadError = supplierPaymentsLoadError,
            stockMovementsLoadError = stockMovementsLoadError,
            stockAlertsLoadError = stockAlertsLoadError,
            locationInventoryLoadError = locationInventoryLoadError,
            onReportSelected = { selectedReport = it }
        )
        reportsResultSection(
            context = context,
            selectedReport = selectedReport,
            selectedRows = selectedRows,
            dateError = dateError,
            cloudMode = cloudMode,
            cloudLoading = cloudLoading,
            selectedReportSourceError = selectedReportSourceError,
            startText = startText,
            endText = endText,
            launchers = exportLaunchers,
            onExportStateChanged = { title, rows -> exportTitle = title; exportRows = rows },
            onError = { error = it }
        )
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
