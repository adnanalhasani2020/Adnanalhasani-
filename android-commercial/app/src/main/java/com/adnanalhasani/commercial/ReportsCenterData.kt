package com.adnanalhasani.commercial

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal data class ReportsCenterData(
    val dateError: Boolean,
    val cloudLoading: Boolean,
    val cloudLoadError: String,
    val salesDocumentsLoadError: String,
    val supplierPaymentsLoadError: String,
    val stockMovementsLoadError: String,
    val stockAlertsLoadError: String,
    val locationInventoryLoadError: String,
    val inPeriodSales: List<SaleRecord>,
    val inPeriodCloudSalesDocuments: List<CommercialCloudSaleDocument>,
    val categories: List<String>,
    val filteredCatalog: List<ReportCatalogItem>,
    val sourceErrorForReport: (String) -> String,
    val selectedRows: List<ReportLine>,
    val selectedReportSourceError: String,
    val currencyTotal: Map<String, Long>,
    val currencyTotalError: String
)

/**
 * Coordinates the report domain calculations, source-readiness validation,
 * date/filter projection, and report-family row builders. Compose presentation
 * remains in ReportsCenterScreen and its dedicated LazyList sections.
 */
@Composable
internal fun rememberReportsCenterData(
    today: LocalDate,
    cloudMode: Boolean,
    cloudData: ReportsCloudData,
    startText: String,
    endText: String,
    search: String,
    selectedCategory: String,
    selectedReport: String,
    rowFilter: String,
    currencyFilter: String,
    statusFilter: String,
    accountFilter: String,
    customerFilter: String,
    supplierFilter: String,
    sales: List<SaleRecord>,
    settlements: List<SettlementRecord>,
    expenses: List<ExpenseRecord>,
    products: List<Product>
): ReportsCenterData {
    val cloudSalesDocuments = cloudData.salesDocuments
    val cloudReceivables = cloudData.receivables
    val receivablesLoadError = cloudData.receivablesLoadError
    val cloudPurchases = cloudData.purchases
    val cloudSupplierPayments = cloudData.supplierPayments
    val cloudStockMovements = cloudData.stockMovements
    val cloudStockAlerts = cloudData.stockAlerts
    val cloudLocationInventoryBalances = cloudData.locationInventoryBalances
    val locationInventoryLoadError = cloudData.locationInventoryLoadError
    val cloudWarehouseInventoryContext = cloudData.warehouseInventoryContext
    val warehouseInventoryContextLoadError = cloudData.warehouseInventoryContextLoadError
    val cloudReturns = cloudData.returns
    val cloudPayments = cloudData.payments
    val cloudJournals = cloudData.journals
    val cloudLedgerLines = cloudData.ledgerLines
    val cloudSupplierControlAccounts = cloudData.supplierControlAccounts
    val cloudGlMappings = cloudData.glMappings
    val glMappingsLoadError = cloudData.glMappingsLoadError
    val cloudCustomerPartyAccounts = cloudData.customerPartyAccounts
    val cloudSupplierPartyAccounts = cloudData.supplierPartyAccounts
    val customerPartyAccountsLoadError = cloudData.customerPartyAccountsLoadError
    val supplierPartyAccountsLoadError = cloudData.supplierPartyAccountsLoadError
    val supplierControlAccountsLoadError = cloudData.supplierControlAccountsLoadError
    val cloudExpenses = cloudData.expenses
    val cloudLoading = cloudData.loading
    val cloudLoadError = cloudData.loadError
    val salesDocumentsLoadError = cloudData.salesDocumentsLoadError
    val purchasesLoadError = cloudData.purchasesLoadError
    val returnsLoadError = cloudData.returnsLoadError
    val paymentsLoadError = cloudData.paymentsLoadError
    val journalsLoadError = cloudData.journalsLoadError
    val ledgerLinesLoadError = cloudData.ledgerLinesLoadError
    val expensesLoadError = cloudData.expensesLoadError
    val supplierPaymentsLoadError = cloudData.supplierPaymentsLoadError
    val stockMovementsLoadError = cloudData.stockMovementsLoadError
    val stockAlertsLoadError = cloudData.stockAlertsLoadError

    val startDate = runCatching { LocalDate.parse(startText) }.getOrNull()
    val endDate = runCatching { LocalDate.parse(endText) }.getOrNull()
    val dateError = startDate == null || endDate == null || startDate.isAfter(endDate)
    val zone = remember { ZoneId.systemDefault() }
    val startMillis = startDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
    val endExclusive = reportEndExclusiveMillis(endDate, zone)

    val glMappingKeys = remember(cloudGlMappings) { cloudGlMappings.map { it.mappingKey }.toSet() }
    val supplierAccountRoles = remember(cloudSupplierControlAccounts) {
        cloudSupplierControlAccounts.map { it.accountRole }.toSet()
    }
    val ledgerStatementError = remember(
        cloudMode, cloudLedgerLines, endDate, ledgerLinesLoadError, glMappingsLoadError,
        glMappingKeys, supplierControlAccountsLoadError, supplierAccountRoles
    ) {
        reportLedgerStatementValidationError(
            cloudMode = cloudMode,
            ledgerLines = cloudLedgerLines,
            endDate = endDate,
            ledgerLinesLoadError = ledgerLinesLoadError,
            glMappingsLoadError = glMappingsLoadError,
            glMappingKeys = glMappingKeys,
            supplierControlAccountsLoadError = supplierControlAccountsLoadError,
            supplierAccountRoles = supplierAccountRoles
        )
    }
    val expenseReportError = reportExpenseValidationError(
        cloudMode = cloudMode,
        expensesLoadError = expensesLoadError,
        cloudExpenses = cloudExpenses,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val paymentMethodAnalysisError = reportPaymentMethodAnalysisValidationError(
        cloudMode = cloudMode,
        paymentsLoadError = paymentsLoadError,
        cloudPayments = cloudPayments,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val supplierInvoiceVarianceError = reportSupplierInvoiceVarianceError(
        cloudMode = cloudMode,
        purchasesLoadError = purchasesLoadError,
        cloudPurchases = cloudPurchases,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val activeSupplierInvoiceCandidates = remember(cloudPurchases) {
        cloudPurchases.activeSupplierInvoiceCandidates()
    }
    val supplierObligationCandidates = remember(activeSupplierInvoiceCandidates) {
        activeSupplierInvoiceCandidates.activeSupplierObligationCandidates()
    }
    val supplierObligationInputs = remember(supplierObligationCandidates, endExclusive) {
        supplierObligationCandidates.toSupplierObligationInputs(endExclusive)
    }
    val supplierMovementInputs = remember(cloudPurchases, cloudSupplierPayments, supplierFilter) {
        buildSupplierMovementInputs(
            purchases = cloudPurchases,
            payments = cloudSupplierPayments,
            supplierFilter = supplierFilter
        )
    }
    val supplierInvoiceDetailError = reportSupplierInvoiceDetailValidationError(
        cloudMode = cloudMode,
        purchasesLoadError = purchasesLoadError,
        activeSupplierInvoiceCandidates = activeSupplierInvoiceCandidates
    )
    val supplierPurchaseSummaryCalculation = remember(cloudPurchases, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateSupplierPurchaseSummary(
                cloudPurchases.toSupplierPurchaseInvoiceInputs(startMillis, endExclusive)
            )
        }
    }
    val supplierPurchaseSummaryError = reportSupplierPurchaseSummaryValidationError(
        cloudMode = cloudMode,
        purchasesLoadError = purchasesLoadError,
        supplierInvoiceDetailError = supplierInvoiceDetailError,
        activeSupplierInvoiceCandidates = activeSupplierInvoiceCandidates,
        startMillis = startMillis,
        endExclusive = endExclusive,
        calculationError = supplierPurchaseSummaryCalculation.exceptionOrNull()?.message.orEmpty()
    )
    val supplierObligationsError = reportSupplierObligationsValidationError(
        cloudMode = cloudMode,
        purchasesLoadError = purchasesLoadError,
        supplierObligationCandidates = supplierObligationCandidates,
        supplierObligationInputs = supplierObligationInputs
    )
    val supplierMovementError = reportSupplierMovementValidationError(
        purchasesLoadError = purchasesLoadError,
        supplierPaymentsLoadError = supplierPaymentsLoadError,
        movements = supplierMovementInputs,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val supplierLiabilityAgingReport = remember(cloudPurchases, today, cloudMode, purchasesLoadError, supplierInvoiceDetailError) {
        buildSupplierLiabilityAgingReport(
            purchases = cloudPurchases,
            asOfDate = today.toString(),
            cloudMode = cloudMode,
            purchasesLoadError = purchasesLoadError,
            invoiceIntegrityError = supplierInvoiceDetailError
        )
    }
    val supplierLiabilityAgingError = supplierLiabilityAgingReport.error
    val supplierDueDateAgingReport = rememberSupplierDueDateAgingReport(
        purchases = cloudPurchases,
        asOfDate = today.toString(),
        cloudMode = cloudMode,
        purchasesLoadError = purchasesLoadError,
        invoiceIntegrityError = supplierInvoiceDetailError
    )
    val supplierDueDateAgingError = supplierDueDateAgingReport.error
    val taxDetailError = reportTaxDetailValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        purchasesLoadError = purchasesLoadError,
        returnsLoadError = returnsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        cloudPurchases = cloudPurchases,
        cloudReturns = cloudReturns,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val salesTaxDiscountError = reportSalesTaxDiscountValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val taxDiscountSummaryReport = remember(
        cloudSalesDocuments, cloudPurchases, cloudReturns, startMillis, endExclusive, startText, endText, taxDetailError
    ) {
        buildTaxDiscountSummaryReportState(
            cloudSalesDocuments, cloudPurchases, cloudReturns, startMillis, endExclusive, startText, endText, taxDetailError
        )
    }
    val taxDiscountSummaryError = taxDiscountSummaryReport.error
    val costProfitError = reportCostProfitValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        returnsLoadError = returnsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        cloudReturns = cloudReturns,
        startMillis = startMillis,
        endExclusive = endExclusive
    )
    val customerOperationalStatementError = reportCustomerOperationalStatementValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = cloudSalesDocuments
    )
    val customerStatementError = reportCustomerStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = cloudLedgerLines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = customerPartyAccountsLoadError,
        partyAccounts = cloudCustomerPartyAccounts,
        glMappings = cloudGlMappings,
        startDate = startDate,
        endDate = endDate
    )
    val supplierStatementError = reportSupplierStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = cloudLedgerLines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = supplierPartyAccountsLoadError,
        controlAccountsLoadError = supplierControlAccountsLoadError,
        partyAccounts = cloudSupplierPartyAccounts,
        supplierControlAccounts = cloudSupplierControlAccounts,
        startDate = startDate,
        endDate = endDate
    )
    val itemProfitabilityCalculation = remember(cloudSalesDocuments, cloudReturns, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateItemProfitability(
                saleLines = cloudSalesDocuments.toItemProfitSaleLineInputs(startMillis, endExclusive),
                returns = cloudReturns.toItemProfitReturnInputs(startMillis, endExclusive)
            )
        }
    }
    val customerReceivableAgingReport = remember(cloudReceivables, today, cloudMode, receivablesLoadError) {
        buildCustomerReceivableAgingReport(
            receivables = cloudReceivables,
            asOfDate = today.toString(),
            cloudMode = cloudMode,
            loadError = receivablesLoadError
        )
    }
    val customerReceivableAgingError = customerReceivableAgingReport.error
    val customerCollectionsCalculation = remember(cloudSalesDocuments, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateCustomerCollectionsByCustomer(
                cloudSalesDocuments.toCustomerCollectionPaymentInputs(startMillis, endExclusive)
            )
        }
    }
    val customerCollectionsError = reportCustomerCollectionsValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        calculationError = customerCollectionsCalculation.exceptionOrNull()?.message.orEmpty()
    )
    val userSalesPerformanceCalculation = remember(cloudSalesDocuments, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateUserSalesPerformance(
                cloudSalesDocuments.toUserSalesPerformanceInputs(startMillis, endExclusive)
            )
        }
    }
    val userSalesPerformanceError = reportUserSalesPerformanceValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        calculationError = userSalesPerformanceCalculation.exceptionOrNull()?.message.orEmpty()
    )

    val locationInventoryReport = remember(cloudLocationInventoryBalances, locationInventoryLoadError) {
        buildLocationInventoryReportState(cloudLocationInventoryBalances, locationInventoryLoadError)
    }
    val stockMovementUserReport = remember(cloudStockMovements, startDate, endDate, cloudMode, stockMovementsLoadError) {
        buildStockMovementUserReportState(cloudStockMovements, startDate, endDate, cloudMode, stockMovementsLoadError)
    }

    val dailySalesTrendCalculation = remember(cloudSalesDocuments, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateDailySalesTrend(
                cloudSalesDocuments.toDailySalesInvoiceInputs(startMillis, endExclusive)
            )
        }
    }
    val dailySalesTrendError = reportDailySalesTrendValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        calculationError = dailySalesTrendCalculation.exceptionOrNull()?.message.orEmpty()
    )
    val itemProfitabilityError = reportItemProfitabilityValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        returnsLoadError = returnsLoadError,
        cloudSalesDocuments = cloudSalesDocuments,
        cloudReturns = cloudReturns,
        calculationError = itemProfitabilityCalculation.exceptionOrNull()?.message.orEmpty()
    )
    var salesRowsArithmeticError = ""
    var ledgerRowsArithmeticError = ""
    val sourceErrorForReport: (String) -> String = { title ->
        when (title) {
            "ملخص المبيعات حسب الفترة", "تفاصيل فواتير المبيعات", "تفاصيل بنود فواتير المبيعات",
            "المبيعات حسب الصنف", "لوحة المؤشرات", "المبيعات حسب العميل", "المبيعات حسب حالة السداد", "متوسط قيمة الفاتورة", "مقارنة الفترات", "مقارنة فترتين" ->
                salesDocumentsLoadError.ifBlank { salesRowsArithmeticError }
            "الخصومات على فواتير المبيعات", "الضرائب المسجلة على المبيعات" -> salesTaxDiscountError
            "أرصدة العملاء وكشف الحساب" -> customerOperationalStatementError
            "المبيعات النقدية والآجلة" -> salesRowsArithmeticError
            "مرتجعات المبيعات وحالة التسوية" -> returnsLoadError
            "فواتير الشراء والمستحقات المسجلة" -> supplierInvoiceDetailError
            "المشتريات حسب المورد" -> supplierPurchaseSummaryError
            "مستحقات فواتير الموردين" -> supplierObligationsError
            "عمر مستحقات الموردين حسب تاريخ الفاتورة" -> supplierLiabilityAgingError
            "أعمار مستحقات الموردين" -> supplierDueDateAgingError
            "فروقات أسعار فواتير الموردين" -> supplierInvoiceVarianceError
            "كشف حركات الموردين" -> supplierMovementError
            "الدفعات السحابية الناجحة", "سندات القبض والصرف" -> paymentsLoadError
            "التحصيل حسب طريقة الدفع" -> paymentMethodAnalysisError
            "التحصيل حسب العميل" -> customerCollectionsError
            "عمر أرصدة العملاء حسب تاريخ الفاتورة" -> customerReceivableAgingError
            "سجل القيود المحاسبية" -> journalsLoadError
            "ميزان المراجعة حسب الحساب", "حركة دفتر الأستاذ العام", "كشف حساب محاسبي", "تفاصيل القيود المرحلة" -> ledgerLinesLoadError.ifBlank { ledgerRowsArithmeticError }
            "قائمة الدخل والميزانية العمومية" -> ledgerStatementError.ifBlank { ledgerRowsArithmeticError }
            "الضرائب والخصومات التفصيلية" -> taxDetailError
            "ملخص الضرائب والخصومات" -> taxDiscountSummaryError
            "تكلفة البضاعة والربح" -> costProfitError
            "ربحية الأصناف بعد المرتجعات" -> itemProfitabilityError
            "أداء المبيعات حسب المستخدم" -> userSalesPerformanceError
            "اتجاه المبيعات اليومي" -> dailySalesTrendError
            "التقييم المالي الحالي للمخزون" -> reportCurrentInventoryValuationValidationError(
                stockAlertsLoadError = stockAlertsLoadError,
                glMappingsLoadError = glMappingsLoadError,
                cloudGlMappings = cloudGlMappings,
                cloudStockAlerts = cloudStockAlerts
            )
            "التدفقات النقدية والصندوق" -> reportCashFlowValidationError(
                glMappingsLoadError = glMappingsLoadError,
                cloudGlMappings = cloudGlMappings,
                ledgerLinesLoadError = ledgerLinesLoadError,
                cloudLedgerLines = cloudLedgerLines,
                startDate = startDate,
                endDate = endDate
            )
            "الرصيد المحاسبي الكامل للعملاء" -> customerStatementError
            "أرصدة الموردين والمستحقات" -> supplierStatementError
            "المصروفات التشغيلية", "ملخص المصروفات حسب التصنيف وطريقة الصرف" -> expenseReportError
            "حركة المخزون الفعلية" -> stockMovementsLoadError
            "حركة المخزون حسب المستخدم" -> stockMovementUserReport.error
            "رصيد المخزون الحالي", "تنبيهات المخزون المنخفض" -> stockAlertsLoadError
            "رصيد المخزون حسب الموقع" -> locationInventoryReport.error
            "حالة ربط أرصدة المخزون بالمستودعات" -> warehouseInventoryContextLoadError
            else -> ""
        }
    }
    val categories = listOf(
        "لوحة المعلومات", "المبيعات", "المشتريات", "العملاء والديون", "الموردون والمستحقات",
        "السندات والتحصيل والصرف", "المحاسبة والمالية", "الأرباح والتكاليف", "المرتجعات",
        "الأصناف والمخزون", "الصندوق والنقدية", "الضرائب والخصومات", "المستخدمون والعمليات",
        "الفترات والمقارنات", "الإدارة الشاملة"
    )
    val catalog = remember(cloudMode) { buildReportsCatalog(cloudMode) }

    val filteredCatalog = catalog.filter {
        (selectedCategory == "الكل" || it.category == selectedCategory) &&
            (search.isBlank() || it.title.contains(search, true) || it.category.contains(search, true))
    }
    val inPeriodSales = remember(sales, dateError, startMillis, endExclusive) {
        if (dateError) emptyList() else sales.filter {
            it.createdAt >= startMillis && it.createdAt < endExclusive && it.status.equals("completed", true)
        }
    }
    val inPeriodCloudSalesDocuments = remember(cloudSalesDocuments, dateError, startMillis, endExclusive) {
        if (dateError) emptyList() else cloudSalesDocuments.filter { doc ->
            doc.saleStatus.equals("completed", true) &&
                (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
        }
    }
    val inPeriodSettlements = remember(cloudMode, settlements, startMillis, endExclusive) {
        if (cloudMode) emptyList() else settlements.filter { it.timestamp >= startMillis && it < endExclusive }
    }
    val inPeriodExpenses = remember(cloudMode, expenses, startMillis, endExclusive) {
        if (cloudMode) emptyList() else expenses.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    }
    val stockLow = remember(products) {
        products.filter { it.stock <= (it.reorderPoint.takeIf { n -> n >= 0 } ?: 9) }
    }
    val (salesRowsCalculation, salesRowsArithmeticError) = remember(
        selectedReport, cloudMode, inPeriodCloudSalesDocuments, inPeriodSales, cloudSalesDocuments,
        sales, startMillis, endExclusive, startText, startDate, endDate, zone
    ) {
        val calculation = runCatching {
            buildSalesReportRows(
                selectedReport = selectedReport,
                cloudMode = cloudMode,
                inPeriodCloudSalesDocuments = inPeriodCloudSalesDocuments,
                inPeriodSales = inPeriodSales,
                cloudSalesDocuments = cloudSalesDocuments,
                sales = sales,
                startMillis = startMillis,
                endExclusive = endExclusive,
                startText = startText,
                endText = endText,
                startDate = startDate,
                endDate = endDate,
                zone = zone
            )
        }
        calculation to if (calculation.isFailure) {
            "تجاوزت مجاميع تقرير المبيعات نطاق الأرقام الدقيق؛ تم حجب التقرير."
        } else ""
    }
    val (ledgerRowsCalculation, ledgerRowsArithmeticError) = remember(
        selectedReport, ledgerStatementError, cloudLedgerLines, cloudJournals, startDate, endDate,
        startMillis, endExclusive, startText, endText, accountFilter
    ) {
        val calculation = runCatching {
            buildLedgerReportRows(
                selectedReport = selectedReport,
                ledgerStatementError = ledgerStatementError,
                cloudLedgerLines = cloudLedgerLines,
                cloudJournals = cloudJournals,
                startDate = startDate,
                endDate = endDate,
                startMillis = startMillis,
                endExclusive = endExclusive,
                startText = startText,
                endText = endText,
                accountFilter = accountFilter
            )
        }
        calculation to if (calculation.isFailure) {
            "تجاوزت مجاميع أو أرصدة دفتر الأستاذ نطاق الأرقام الدقيق؛ تم حجب التقرير."
        } else ""
    }
    val selectedRowsUnvalidated = buildInventoryReportRows(
        selectedReport = selectedReport,
        cloudMode = cloudMode,
        cloudStockAlerts = cloudStockAlerts,
        cloudGlMappings = cloudGlMappings,
        cloudStockMovements = cloudStockMovements,
        cloudWarehouseInventoryContext = cloudWarehouseInventoryContext,
        stockMovementUserReport = stockMovementUserReport,
        locationInventoryReport = locationInventoryReport,
        products = products,
        stockLow = stockLow,
        startMillis = startMillis,
        endExclusive = endExclusive,
        endText = endText,
        sourceErrorForReport = sourceErrorForReport
    ) ?: ledgerRowsCalculation.getOrNull() ?: buildPartyStatementReportRows(
        selectedReport = selectedReport,
        startText = startText,
        endText = endText,
        customerFilter = customerFilter,
        supplierFilter = supplierFilter,
        cloudSalesDocuments = cloudSalesDocuments,
        cloudPurchases = cloudPurchases,
        cloudLedgerLines = cloudLedgerLines,
        cloudSupplierControlAccounts = cloudSupplierControlAccounts,
        cloudGlMappings = cloudGlMappings,
        cloudCustomerPartyAccounts = cloudCustomerPartyAccounts,
        cloudSupplierPartyAccounts = cloudSupplierPartyAccounts,
        startDate = startDate,
        endDate = endDate,
        startMillis = startMillis,
        endExclusive = endExclusive,
        customerStatementError = customerStatementError,
        supplierStatementError = supplierStatementError
    ) ?: buildOperationalReportRows(
        selectedReport = selectedReport,
        cloudMode = cloudMode,
        cloudPayments = cloudPayments,
        cloudReturns = cloudReturns,
        cloudExpenses = cloudExpenses,
        inPeriodSettlements = inPeriodSettlements,
        inPeriodExpenses = inPeriodExpenses,
        startMillis = startMillis,
        endExclusive = endExclusive,
        startText = startText,
        endText = endText,
        zone = zone
    ) ?: (if (selectedReport == "كشف حركات الموردين" && sourceErrorForReport(selectedReport).isNotBlank()) {
        emptyList()
    } else buildSupplierReportRows(
        selectedReport = selectedReport,
        cloudPurchases = cloudPurchases,
        cloudSupplierPayments = cloudSupplierPayments,
        supplierFilter = supplierFilter,
        startMillis = startMillis,
        endExclusive = endExclusive,
        startText = startText,
        endText = endText,
        supplierObligationInputs = supplierObligationInputs,
        supplierMovementInputs = supplierMovementInputs
    )) ?: salesRowsCalculation.getOrNull() ?: when (selectedReport) {
        "أداء المبيعات حسب المستخدم" -> buildUserSalesPerformanceRows(userSalesPerformanceCalculation, userSalesPerformanceError)
        "اتجاه المبيعات اليومي" -> buildDailySalesTrendRows(dailySalesTrendCalculation, dailySalesTrendError)
        "عمر أرصدة العملاء حسب تاريخ الفاتورة" -> buildCustomerReceivableAgingRows(customerReceivableAgingReport, customerReceivableAgingError, today.toString())
        "التحصيل حسب العميل" -> buildCustomerCollectionsRows(customerCollectionsCalculation, customerCollectionsError, startText, endText)
        "المشتريات حسب المورد" -> buildSupplierPurchaseSummaryRows(supplierPurchaseSummaryCalculation, supplierPurchaseSummaryError, startText, endText)
        "عمر مستحقات الموردين حسب تاريخ الفاتورة" -> buildSupplierLiabilityAgingRows(supplierLiabilityAgingReport, supplierLiabilityAgingError, today.toString())
        "أعمار مستحقات الموردين" -> buildSupplierDueDateAgingRows(supplierDueDateAgingReport, supplierDueDateAgingError, today.toString())
        "التدفقات النقدية والصندوق" -> if (sourceErrorForReport(selectedReport).isNotBlank()) emptyList() else buildCashFlowReportLines(cloudLedgerLines, cloudGlMappings, startDate, endDate, startText, endText)
        "ملخص الضرائب والخصومات" -> taxDiscountSummaryReport.lines
        "الضرائب والخصومات التفصيلية" -> buildDetailedTaxReportRows(
            cloudSalesDocuments = cloudSalesDocuments,
            cloudPurchases = cloudPurchases,
            cloudReturns = cloudReturns,
            startMillis = startMillis,
            endExclusive = endExclusive,
            taxDetailError = taxDetailError
        )
        "ربحية الأصناف بعد المرتجعات" -> buildItemProfitabilityRows(itemProfitabilityCalculation, itemProfitabilityError, startText, endText)
        "تكلفة البضاعة والربح" -> buildCostProfitRows(cloudSalesDocuments, cloudReturns, startMillis, endExclusive, startText, endText, costProfitError)
        else -> emptyList()
    }
    // Invalid dates must never widen a report to the full history.
    val selectedReportSourceError = sourceErrorForReport(selectedReport)
    val movementStatement = selectedReport in setOf("أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات")
    val selectedRows = if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) emptyList() else selectedRowsUnvalidated.filter { row ->
        (movementStatement || rowFilter.isBlank() || row.name.contains(rowFilter.trim(), true) || row.reference.contains(rowFilter.trim(), true)) &&
            (currencyFilter.isBlank() || row.currency.contains(currencyFilter.trim(), true)) &&
            (movementStatement || statusFilter.isBlank() || row.status.contains(statusFilter.trim(), true))
    }
    val currencyTotalCalculation = remember(
        dateError, cloudMode, cloudLoading, salesDocumentsLoadError,
        inPeriodCloudSalesDocuments, inPeriodSales
    ) {
        runCatching {
            if (dateError || (cloudMode && (cloudLoading || salesDocumentsLoadError.isNotBlank()))) {
                emptyMap()
            } else {
                val amounts = if (cloudMode) {
                    inPeriodCloudSalesDocuments.map { it.currencyCode to it.totalMinor }
                } else {
                    inPeriodSales.map { it.currencyCode to it.total.toLong() }
                }
                calculateReportCurrencyTotals(amounts)
            }
        }
    }
    val currencyTotal = currencyTotalCalculation.getOrDefault(emptyMap())
    val currencyTotalError = if (currencyTotalCalculation.isFailure) {
        "تجاوز إجمالي المبيعات نطاق الأرقام الدقيق؛ تم إخفاء ملخص العملات."
    } else ""


    return ReportsCenterData(
        dateError = dateError,
        cloudLoading = cloudLoading,
        cloudLoadError = cloudLoadError,
        salesDocumentsLoadError = salesDocumentsLoadError,
        supplierPaymentsLoadError = supplierPaymentsLoadError,
        stockMovementsLoadError = stockMovementsLoadError,
        stockAlertsLoadError = stockAlertsLoadError,
        locationInventoryLoadError = locationInventoryLoadError,
        inPeriodSales = inPeriodSales,
        inPeriodCloudSalesDocuments = inPeriodCloudSalesDocuments,
        categories = categories,
        filteredCatalog = filteredCatalog,
        sourceErrorForReport = sourceErrorForReport,
        selectedRows = selectedRows,
        selectedReportSourceError = selectedReportSourceError,
        currencyTotal = currencyTotal,
        currencyTotalError = currencyTotalError
    )
}
