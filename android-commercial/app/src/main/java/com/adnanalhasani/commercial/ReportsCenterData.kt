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
    val currencyTotal: Map<String, Long>
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
    val endExclusive = endDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE

    val ledgerStatementError = reportLedgerStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = cloudLedgerLines,
        endDate = endDate,
        ledgerLinesLoadError = ledgerLinesLoadError,
        glMappingsLoadError = glMappingsLoadError,
        glMappingKeys = cloudGlMappings.map { it.mappingKey }.toSet(),
        supplierControlAccountsLoadError = supplierControlAccountsLoadError,
        supplierAccountRoles = cloudSupplierControlAccounts.map { it.accountRole }.toSet()
    )
    val expenseReportError = when {
        !cloudMode -> expensesLoadError
        expensesLoadError.isNotBlank() -> expensesLoadError
        cloudExpenses.any { expense ->
            expense.canceledAt.isBlank() &&
                reportDateMillis(expense.recordedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (expense.amountMinor <= 0L || !expense.currencyCode.matches(Regex("^[A-Z]{3}$")))
        } -> "توجد مصروفات مسجلة بمبلغ أو عملة غير صالحة؛ تم حجب التقرير بدل افتراض عملة أو إجمالي غير موثوق."
        else -> ""
    }
    val paymentMethodAnalysisError = when {
        !cloudMode -> "تحليل التحصيل حسب طريقة الدفع يتطلب سجل الدفعات السحابي."
        paymentsLoadError.isNotBlank() -> paymentsLoadError
        cloudPayments.any { row ->
            row.status.equals("succeeded", true) &&
                reportDateMillis(row.createdAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (row.amountMinor <= 0L || !row.currencyCode.matches(Regex("^[A-Z]{3}$")))
        } -> "توجد دفعة ناجحة بمبلغ أو عملة غير صالحة؛ تم حجب التجميع حتى لا يظهر إجمالي ناقص."
        else -> ""
    }
    val supplierInvoiceVarianceError = when {
        !cloudMode -> "فروقات أسعار فواتير الموردين تتطلب فواتير السحابة وبنودها المحفوظة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        cloudPurchases.any { purchase ->
            purchase.supplierInvoices.filter { invoice ->
                invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                    reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
            }.any { invoice ->
                invoice.currencyCode != purchase.currencyCode ||
                    runCatching { calculateSupplierInvoicePriceVariance(invoice.toPriceVarianceInput()) }.isFailure
            }
        } ->
            "تعذر مطابقة أحد فواتير المورد مع بنودها وأسعار أمر الشراء؛ لم يتم عرض إجمالي جزئي أو فرق سعر مضلل."
        else -> ""
    }
    val activeSupplierInvoiceCandidates = cloudPurchases.flatMap { purchase ->
        purchase.supplierInvoices.filter { invoice ->
            invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
        }.map { invoice -> purchase to invoice }
    }
    val supplierObligationCandidates = activeSupplierInvoiceCandidates.filter { (_, invoice) ->
        invoice.obligation != null &&
            invoice.obligation.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
    }
    val supplierObligationInputs = supplierObligationCandidates.mapNotNull { (purchase, invoice) ->
        val timestamp = reportDateMillis(invoice.recordedAt) ?: return@mapNotNull null
        if (timestamp >= endExclusive) return@mapNotNull null
        val obligation = invoice.obligation ?: return@mapNotNull null
        SupplierObligationInput(
            supplierId = purchase.supplierId,
            supplierName = purchase.supplierName.ifBlank { "مورد غير محدد" },
            purchaseOrderCurrencyCode = purchase.currencyCode,
            invoiceCurrencyCode = invoice.currencyCode,
            obligationCurrencyCode = obligation.currencyCode,
            amountDueMinor = obligation.amountDueMinor,
            amountPaidMinor = obligation.amountPaidMinor
        )
    }
    val supplierInvoiceDetailError = when {
        !cloudMode -> "تفاصيل فواتير الشراء تتطلب مصادر السحابة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        activeSupplierInvoiceCandidates.any { (_, invoice) -> reportDateMillis(invoice.recordedAt) == null } ->
            "توجد فاتورة مورد بتاريخ تسجيل غير صالح؛ تعذر تحديد الفترة بدقة."
        activeSupplierInvoiceCandidates.any { (purchase, invoice) ->
            val obligation = invoice.obligation
            invoice.totalMinor < 0L || !purchase.currencyCode.matches(Regex("^[A-Z]{3}$")) ||
                invoice.currencyCode != purchase.currencyCode ||
                (obligation != null && (obligation.currencyCode != purchase.currencyCode ||
                    obligation.amountDueMinor < 0L || obligation.amountPaidMinor < 0L))
        } -> "توجد فاتورة أو مستحق مورد بمبلغ أو عملة غير متطابقة؛ تم حجب التقرير."
        else -> ""
    }
    val supplierPurchaseSummaryCalculation = remember(cloudPurchases, startMillis, endExclusive, cloudMode) {
        runCatching {
            val invoices = cloudPurchases.flatMap { purchase ->
                purchase.supplierInvoices.filter { invoice ->
                    invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                        reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
                }.map { invoice ->
                    SupplierPurchaseInvoiceInput(
                        invoiceId = invoice.id,
                        supplierId = purchase.supplierId,
                        supplierName = purchase.supplierName,
                        currencyCode = invoice.currencyCode,
                        totalMinor = invoice.totalMinor
                    )
                }
            }
            calculateSupplierPurchaseSummary(invoices)
        }
    }
    val supplierPurchaseSummaryError = when {
        !cloudMode -> "ملخص المشتريات حسب المورد يتطلب فواتير السحابة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        supplierInvoiceDetailError.isNotBlank() -> supplierInvoiceDetailError
        activeSupplierInvoiceCandidates.any { (_, invoice) ->
            invoice.subtotalMinor < 0L || invoice.taxMinor < 0L || invoice.freightMinor < 0L ||
                invoice.lines.isEmpty() ||
                invoice.lines.any { line ->
                    line.lineTotalMinor < 0L || line.unitPriceMinor < 0L ||
                        line.quantity.toBigDecimalOrNull()?.signum() != 1
                } ||
                runCatching { sumReportAmountsExactly(invoice.lines.map { it.lineTotalMinor }) }.getOrNull() != invoice.subtotalMinor ||
                runCatching {
                    Math.addExact(Math.addExact(invoice.subtotalMinor, invoice.taxMinor), invoice.freightMinor)
                }.getOrNull() != invoice.totalMinor
        } -> "توجد فاتورة مورد لا تتطابق بنودها أو إجمالياتها مع الضريبة والشحن؛ تم حجب ملخص الموردين."
        else -> supplierPurchaseSummaryCalculation.exceptionOrNull()?.message.orEmpty()
    }
    val supplierObligationsError = when {
        !cloudMode -> "مستحقات فواتير الموردين تتطلب مصادر السحابة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        supplierObligationCandidates.any { (_, invoice) -> reportDateMillis(invoice.recordedAt) == null } ->
            "توجد فاتورة التزام مورد بتاريخ غير صالح؛ لا يمكن حساب الرصيد حتى نهاية الفترة."
        runCatching { calculateSupplierObligationTotals(supplierObligationInputs) }.isFailure ->
            "تعذر مطابقة عملات الالتزامات أو تجاوزت مجاميعها نطاق الأرقام الدقيق؛ تم حجب المستحقات."
        else -> ""
    }
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
    val taxDiscountSummaryReport = remember(
        cloudSalesDocuments, cloudPurchases, cloudReturns, startMillis, endExclusive, startText, endText, taxDetailError
    ) {
        buildTaxDiscountSummaryReportState(
            cloudSalesDocuments, cloudPurchases, cloudReturns, startMillis, endExclusive, startText, endText, taxDetailError
        )
    }
    val taxDiscountSummaryError = taxDiscountSummaryReport.error
    val costProfitError = when {
        !cloudMode -> "تكلفة الربح التاريخية تتطلب سجلات تكلفة السحابة."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        returnsLoadError.isNotBlank() -> returnsLoadError
        cloudSalesDocuments.any { doc ->
            doc.saleStatus.equals("completed", true) &&
                reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (doc.lines.isEmpty() || doc.lines.any { it.costTotalMinor == null || it.costTotalMinor < 0L })
        } -> "توجد بنود مبيعات في الفترة بلا تكلفة تاريخية مسجلة؛ لا يمكن اعتماد الربح."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (saleReturn.approvedRefundMinor == null ||
                    saleReturn.items.any { it.restockedQuantity > 0 && (it.saleItemCostTotalMinor == null || it.saleItemQuantityInStockUnits <= 0L) })
        } -> "توجد مرتجعات مكتملة تفتقد مبلغ اعتماد أو تكلفة البند التاريخية اللازمة لتسوية الربح."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                cloudSalesDocuments.none { it.id == saleReturn.saleId && it.currencyCode == saleReturn.currencyCode }
        } -> "تعذر مطابقة عملة أو فاتورة أحد المرتجعات مع سجل البيع الأصلي؛ تم حجب الربح."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                cloudSalesDocuments.any { it.id == saleReturn.saleId && it.taxMinor > 0L }
        } -> "توجد مرتجعات لفواتير ضريبية؛ لم تُفصل حصة الضريبة في مبلغ الاسترداد، لذلك حُجب صافي الربح."
        runCatching {
            val periodSales = cloudSalesDocuments.filter { doc ->
                doc.saleStatus.equals("completed", true) &&
                    reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }
            val periodReturns = cloudReturns.filter { saleReturn ->
                saleReturn.status.equals("completed", true) &&
                    reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
            }
            calculateCostProfitReportTotals(periodSales.map { it.toCostProfitInput() }, periodReturns.map { it.toCostProfitInput() })
        }.isFailure -> "تجاوزت مجاميع المبيعات أو التكلفة أو المرتجعات نطاق الأرقام الدقيق، أو تعذر تسويتها؛ تم حجب الربح بدل إظهار إجمالي ملتف أو ناقص."
        else -> ""
    }
    val customerOperationalStatementError = when {
        !cloudMode -> "كشف العميل التشغيلي يتطلب فواتير السحابة والتحصيلات والمرتجعات."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        runCatching {
            val movements = buildCustomerOperationalStatementMovements(cloudSalesDocuments.toCustomerStatementInputs())
            movements.groupBy { it.customerId to it.currency }.values.forEach { customerMoves ->
                val ordered = customerMoves.sortedWith(compareBy<ReportCustomerMovement> { it.date }.thenBy { it.reference })
                sumReportAmountsExactly(ordered.map { it.amount })
                var balance = 0L
                ordered.forEach { balance = Math.addExact(balance, it.amount) }
            }
        }.isFailure -> "توجد فاتورة أو دفعة أو مرتجع عميل بمبلغ/تاريخ غير صالح، أو تجاوزت مجاميع كشف العميل نطاق الأرقام الدقيق؛ تم حجب الكشف."
        else -> ""
    }
    val customerStatementError = reportCustomerStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = cloudLedgerLines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = customerPartyAccountsLoadError,
        partyAccounts = cloudCustomerPartyAccounts,
        glMappings = cloudGlMappings
    )
    val supplierStatementError = reportSupplierStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = cloudLedgerLines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = supplierPartyAccountsLoadError,
        controlAccountsLoadError = supplierControlAccountsLoadError,
        partyAccounts = cloudSupplierPartyAccounts,
        supplierControlAccounts = cloudSupplierControlAccounts
    )
    val itemProfitabilityCalculation = remember(cloudSalesDocuments, cloudReturns, startMillis, endExclusive, cloudMode) {
        runCatching {
            val allSaleLines = cloudSalesDocuments.filter { it.saleStatus.equals("completed", true) }.flatMap { document ->
                val inPeriod = reportDateMillis(document.createdAt)?.let { it >= startMillis && it < endExclusive } == true
                document.lines.map { line ->
                    ItemProfitSaleLineInput(
                        saleItemId = line.id,
                        saleId = document.id,
                        productName = line.productName,
                        currencyCode = document.currencyCode,
                        saleTaxMinor = document.taxMinor,
                        saleDiscountMinor = document.discountMinor,
                        quantity = line.quantity,
                        lineTotalMinor = line.lineTotalMinor,
                        costTotalMinor = line.costTotalMinor,
                        inSelectedPeriod = inPeriod
                    )
                }
            }
            val periodReturns = cloudReturns.filter { saleReturn ->
                saleReturn.status.equals("completed", true) &&
                    reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
            }.map { saleReturn ->
                ItemProfitReturnInput(
                    returnId = saleReturn.id,
                    saleId = saleReturn.saleId,
                    currencyCode = saleReturn.currencyCode,
                    approvedRefundMinor = saleReturn.approvedRefundMinor,
                    items = saleReturn.items.map { item ->
                        ItemProfitReturnLineInput(
                            saleItemId = item.saleItemId,
                            quantity = item.quantity,
                            refundAmountMinor = item.refundAmountMinor,
                            refundAmountPresent = item.refundAmountPresent,
                            restockRequested = item.restock,
                            restockFlagPresent = item.restockFlagPresent,
                            restockedQuantity = item.restockedQuantity,
                            restockedQuantityPresent = item.restockedQuantityPresent,
                            saleItemQuantityInStockUnits = item.saleItemQuantityInStockUnits
                        )
                    }
                )
            }
            calculateItemProfitability(allSaleLines, periodReturns)
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
            val payments = cloudSalesDocuments.flatMap { document ->
                document.payments.filter { payment ->
                    payment.status.equals("succeeded", true) &&
                        reportDateMillis(payment.createdAt)?.let { it >= startMillis && it < endExclusive } == true
                }.map { payment ->
                    CustomerCollectionPaymentInput(
                        paymentId = payment.id,
                        customerId = document.customerId,
                        customerName = document.customerName,
                        currencyCode = payment.currencyCode,
                        amountMinor = payment.amountMinor
                    )
                }
            }
            calculateCustomerCollectionsByCustomer(payments)
        }
    }
    val customerCollectionsError = when {
        !cloudMode -> "التحصيل حسب العميل يتطلب سجلات الدفعات السحابية."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        cloudSalesDocuments.any { document ->
            document.payments.any { payment ->
                payment.status.equals("succeeded", true) &&
                    (reportDateMillis(payment.createdAt) == null || payment.amountMinor <= 0L ||
                        !payment.currencyCode.matches(Regex("^[A-Z]{3}$")))
            }
        } -> "توجد دفعة ناجحة بتاريخ أو مبلغ أو عملة غير صالحة؛ تم حجب تجميع التحصيل حسب العميل."
        else -> customerCollectionsCalculation.exceptionOrNull()?.message.orEmpty()
    }
    val userSalesPerformanceCalculation = remember(cloudSalesDocuments, startMillis, endExclusive, cloudMode) {
        runCatching {
            calculateUserSalesPerformance(cloudSalesDocuments.filter { document ->
                document.saleStatus.equals("completed", true) &&
                    reportDateMillis(document.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }.map { document ->
                UserSalesPerformanceInvoiceInput(
                    invoiceId = document.id,
                    userId = document.userId,
                    currencyCode = document.currencyCode,
                    totalMinor = document.totalMinor
                )
            })
        }
    }
    val userSalesPerformanceError = when {
        !cloudMode -> "أداء المبيعات حسب المستخدم يتطلب سجلات السحابة."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        cloudSalesDocuments.any { document ->
            document.saleStatus.equals("completed", true) &&
                (reportDateMillis(document.createdAt) == null ||
                    !document.currencyCode.matches(Regex("^[A-Z]{3}$")) || document.totalMinor < 0L)
        } -> "توجد فاتورة مكتملة بتاريخ أو عملة أو مبلغ غير صالح؛ تم حجب تقرير أداء المستخدمين."
        else -> userSalesPerformanceCalculation.exceptionOrNull()?.message.orEmpty()
    }


    val locationInventoryReport = remember(cloudLocationInventoryBalances, locationInventoryLoadError) {
        buildLocationInventoryReportState(cloudLocationInventoryBalances, locationInventoryLoadError)
    }
    val stockMovementUserReport = remember(cloudStockMovements, startDate, endDate, cloudMode, stockMovementsLoadError) {
        buildStockMovementUserReportState(cloudStockMovements, startDate, endDate, cloudMode, stockMovementsLoadError)
    }

    val dailySalesTrendCalculation = remember(cloudSalesDocuments, startMillis, endExclusive, cloudMode) {
        runCatching {
            val invoices = cloudSalesDocuments.filter { document ->
                document.saleStatus.equals("completed", true) &&
                    reportDateMillis(document.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }.map { document ->
                DailySalesInvoiceInput(
                    invoiceId = document.id,
                    date = document.createdAt.take(10),
                    currencyCode = document.currencyCode,
                    totalMinor = document.totalMinor
                )
            }
            calculateDailySalesTrend(invoices)
        }
    }
    val dailySalesTrendError = when {
        !cloudMode -> "الاتجاه اليومي للمبيعات يتطلب سجلات السحابة."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        cloudSalesDocuments.any { it.saleStatus.equals("completed", true) &&
            (reportDateMillis(it.createdAt) == null || !it.currencyCode.matches(Regex("^[A-Z]{3}$")) || it.totalMinor < 0L) } ->
            "توجد فاتورة مكتملة بتاريخ أو عملة أو مبلغ غير صالح؛ تم حجب الاتجاه اليومي."
        else -> dailySalesTrendCalculation.exceptionOrNull()?.message.orEmpty()
    }
    val itemProfitabilityError = when {
        !cloudMode -> "ربحية الأصناف بعد المرتجعات تتطلب سجلات السحابة."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        returnsLoadError.isNotBlank() -> returnsLoadError
        cloudSalesDocuments.any { it.saleStatus.equals("completed", true) && reportDateMillis(it.createdAt) == null } ->
            "توجد فاتورة بيع مكتملة بتاريخ غير صالح؛ تعذر تحديد فترة ربحيتها."
        cloudSalesDocuments.filter { it.saleStatus.equals("completed", true) }.any { document ->
            document.subtotalMinor < 0L || document.discountMinor < 0L ||
                document.discountMinor > document.subtotalMinor || document.taxMinor < 0L ||
                !document.currencyCode.matches(Regex("^[A-Z]{3}$")) || document.lines.isEmpty() ||
                document.lines.any { it.lineTotalMinor < 0L } ||
                runCatching { sumReportAmountsExactly(document.lines.map { it.lineTotalMinor }) }.getOrNull() != document.subtotalMinor
        } -> "توجد فاتورة مكتملة لا تتطابق بنودها أو عملتها أو مبالغها مع الإجمالي الفرعي؛ تم حجب ربحية الأصناف."
        cloudReturns.any { it.status.equals("completed", true) && reportDateMillis(it.completedAt) == null } ->
            "يوجد مرتجع مكتمل بتاريخ غير صالح؛ تعذر تحديد فترة أثره على الربحية."
        cloudReturns.any { it.refundStatus.equals("refunded", true) && !it.status.equals("completed", true) } ->
            "يوجد استرداد منفذ لمرتجع غير مكتمل؛ يلزم تسوية حالة المرتجع قبل اعتماد ربحية الأصناف."
        else -> itemProfitabilityCalculation.exceptionOrNull()?.message.orEmpty()
    }
    val sourceErrorForReport: (String) -> String = { title ->
        when (title) {
            "ملخص المبيعات حسب الفترة", "تفاصيل فواتير المبيعات", "تفاصيل بنود فواتير المبيعات",
            "المبيعات حسب الصنف", "الخصومات على فواتير المبيعات", "الضرائب المسجلة على المبيعات",
            "لوحة المؤشرات", "المبيعات حسب العميل", "المبيعات حسب حالة السداد", "متوسط قيمة الفاتورة", "مقارنة الفترات",
            "أرصدة العملاء وكشف الحساب" -> customerOperationalStatementError
            "مرتجعات المبيعات وحالة التسوية" -> returnsLoadError
            "فواتير الشراء والمستحقات المسجلة" -> supplierInvoiceDetailError
            "المشتريات حسب المورد" -> supplierPurchaseSummaryError
            "مستحقات فواتير الموردين" -> supplierObligationsError
            "عمر مستحقات الموردين حسب تاريخ الفاتورة" -> supplierLiabilityAgingError
            "أعمار مستحقات الموردين" -> supplierDueDateAgingError
            "فروقات أسعار فواتير الموردين" -> supplierInvoiceVarianceError
            "كشف حركات الموردين" -> purchasesLoadError.ifBlank { supplierPaymentsLoadError }
            "الدفعات السحابية الناجحة", "سندات القبض والصرف" -> paymentsLoadError
            "التحصيل حسب طريقة الدفع" -> paymentMethodAnalysisError
            "التحصيل حسب العميل" -> customerCollectionsError
            "عمر أرصدة العملاء حسب تاريخ الفاتورة" -> customerReceivableAgingError
            "سجل القيود المحاسبية" -> journalsLoadError
            "ميزان المراجعة حسب الحساب", "حركة دفتر الأستاذ العام", "كشف حساب محاسبي", "تفاصيل القيود المرحلة" -> ledgerLinesLoadError
            "قائمة الدخل والميزانية العمومية" -> ledgerStatementError
            "الضرائب والخصومات التفصيلية" -> taxDetailError
            "ملخص الضرائب والخصومات" -> taxDiscountSummaryError
            "تكلفة البضاعة والربح" -> costProfitError
            "ربحية الأصناف بعد المرتجعات" -> itemProfitabilityError
            "أداء المبيعات حسب المستخدم" -> userSalesPerformanceError
            "اتجاه المبيعات اليومي" -> dailySalesTrendError
            "التقييم المالي الحالي للمخزون" -> when {
                stockAlertsLoadError.isNotBlank() -> stockAlertsLoadError
                glMappingsLoadError.isNotBlank() -> glMappingsLoadError
                cloudGlMappings.none { it.mappingKey == "inventory" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } -> "لا يوجد حساب مخزون مربوط بعملة صالحة في دليل الحسابات."
                cloudStockAlerts.isEmpty() -> "لا توجد أرصدة مخزون محملة للموقع والنشاط الحاليين."
                cloudStockAlerts.any { it.unitCostMinor == null || it.unitCostMinor < 0L || it.quantityOnHand.signum() < 0 } -> "بعض أرصدة المخزون تفتقد تكلفة وحدة مسجلة أو تحتوي كمية غير صالحة."
                runCatching {
                    cloudStockAlerts.fold(java.math.BigDecimal.ZERO) { total, item ->
                        total.add(item.quantityOnHand.multiply(java.math.BigDecimal.valueOf(item.unitCostMinor ?: 0L))
                            .setScale(0, java.math.RoundingMode.HALF_UP))
                    }.longValueExact()
                }.isFailure -> "إجمالي قيمة المخزون يتجاوز نطاق الأرقام المدعوم؛ لم يُعرض تقييم ناقص."
                else -> ""
            }
            "التدفقات النقدية والصندوق" -> when {
                glMappingsLoadError.isNotBlank() -> glMappingsLoadError
                cloudGlMappings.none { it.mappingKey in setOf("cash", "bank", "card_clearing", "other_receipts", "check_clearing", "other_disbursements") && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } -> "لا توجد حسابات نقدية أو بنكية مربوطة بعملة صالحة في دليل الحسابات."
                ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
                cloudLedgerLines.isEmpty() -> "لا توجد قيود مرحلة لحسابات النقد والبنك؛ لا يمكن اعتماد الرصيد."
                cloudLedgerLines.any { runCatching { LocalDate.parse(it.entryDate.take(10)) }.getOrNull() == null } -> "توجد قيود مرحلة بتاريخ غير صالح؛ لا يمكن ضمان الرصيد الافتتاحي."
                cloudLedgerLines.any { !it.currencyCode.matches(Regex("^[A-Z]{3}$")) } -> "توجد قيود نقدية بعملة غير محددة؛ تم حجب كشف الصندوق."
                else -> ""
            }
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
    val inPeriodSales = if (dateError) emptyList() else sales.filter { it.createdAt >= startMillis && it.createdAt < endExclusive && it.status.equals("completed", true) }
    val inPeriodCloudSalesDocuments = if (dateError) emptyList() else cloudSalesDocuments.filter { doc ->
        doc.saleStatus.equals("completed", true) &&
            (reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true)
    }
    val inPeriodSettlements = if (cloudMode) emptyList() else settlements.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val inPeriodExpenses = if (cloudMode) emptyList() else expenses.filter { it.timestamp >= startMillis && it.timestamp < endExclusive }
    val salesByCurrency = inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.toSortedMap()
    val stockLow = products.filter { it.stock <= (it.reorderPoint.takeIf { n -> n >= 0 } ?: 9) }
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
    ) ?: buildLedgerReportRows(
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
    ) ?: buildPartyStatementReportRows(
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
    ) ?: buildSupplierReportRows(
        selectedReport = selectedReport,
        cloudPurchases = cloudPurchases,
        cloudSupplierPayments = cloudSupplierPayments,
        supplierFilter = supplierFilter,
        startMillis = startMillis,
        endExclusive = endExclusive,
        startText = startText,
        endText = endText,
        supplierObligationInputs = supplierObligationInputs
    ) ?: buildSalesReportRows(
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
    ) ?: when (selectedReport) {
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
    val currencyTotal = if (dateError || (cloudMode && (cloudLoading || salesDocumentsLoadError.isNotBlank()))) emptyMap() else if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
        else inPeriodSales.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.total.toLong() } }


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
        currencyTotal = currencyTotal
    )
}
