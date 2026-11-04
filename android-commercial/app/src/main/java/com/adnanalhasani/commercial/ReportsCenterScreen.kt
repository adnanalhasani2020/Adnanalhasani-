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
    var supplierFilter by uiState.supplierFilter
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
    val taxDetailError = when {
        !cloudMode -> "التقرير الضريبي التفصيلي يتطلب مصادر السحابة."
        salesDocumentsLoadError.isNotBlank() -> salesDocumentsLoadError
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        returnsLoadError.isNotBlank() -> returnsLoadError
        cloudSalesDocuments.any { doc ->
            doc.saleStatus.equals("completed", true) && reportDateMillis(doc.createdAt) == null
        } -> "توجد فاتورة بيع مكتملة بتاريخ غير صالح؛ تم حجب ملخص الضرائب والخصومات."
        cloudPurchases.any { purchase ->
            purchase.supplierInvoices.any { invoice ->
                invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                    reportDateMillis(invoice.recordedAt) == null
            }
        } -> "توجد فاتورة مورد غير ملغاة بتاريخ غير صالح؛ تم حجب ملخص الضرائب والخصومات."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) && reportDateMillis(saleReturn.completedAt) == null
        } -> "يوجد مرتجع مكتمل بتاريخ غير صالح؛ تم حجب ملخص الضرائب والخصومات."
        cloudSalesDocuments.any { doc ->
            doc.saleStatus.equals("completed", true) &&
                reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (doc.subtotalMinor < 0L || doc.discountMinor < 0L || doc.discountMinor > doc.subtotalMinor ||
                    doc.taxMinor < 0L || !doc.currencyCode.matches(Regex("^[A-Z]{3}$")))
        } -> "توجد فاتورة بيع مكتملة بخصم أو ضريبة أو عملة غير صالحة؛ تم حجب تفاصيل الضرائب والخصومات."
        cloudPurchases.any { purchase ->
            purchase.supplierInvoices.any { invoice ->
                invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed") &&
                    reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                    (invoice.subtotalMinor < 0L || invoice.taxMinor < 0L || invoice.freightMinor < 0L ||
                        !invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) || invoice.currencyCode != purchase.currencyCode)
            }
        } -> "توجد فاتورة مورد بمبالغ أو عملة غير متطابقة مع أمر الشراء؛ تم حجب تفاصيل الضرائب والخصومات."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                (saleReturn.approvedRefundMinor == null || saleReturn.approvedRefundMinor < 0L ||
                    !saleReturn.currencyCode.matches(Regex("^[A-Z]{3}$")))
        } -> "يوجد مرتجع مكتمل بلا مبلغ اعتماد أو عملة صالحة؛ تم حجب تفاصيل الضرائب والخصومات."
        cloudReturns.any { saleReturn ->
            saleReturn.status.equals("completed", true) &&
                reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                cloudSalesDocuments.none { sale -> sale.id == saleReturn.saleId &&
                    sale.saleStatus.equals("completed", true) && sale.currencyCode == saleReturn.currencyCode }
        } -> "تعذر مطابقة عملة المرتجع مع فاتورة البيع الأصلية؛ تم حجب تفاصيل الضرائب والخصومات."
        else -> ""
    }
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
    val customerStatementError = when {
        !cloudMode -> "كشف العميل الكامل يتطلب دفتر الأستاذ السحابي."
        ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
        cloudLedgerLines.isEmpty() -> "لا توجد قيود مرحلة في دفتر الأستاذ؛ لا يمكن إنشاء كشف حساب محاسبي."
        customerPartyAccountsLoadError.isNotBlank() -> customerPartyAccountsLoadError
        cloudCustomerPartyAccounts.isEmpty() -> "لا توجد روابط لحسابات مراقبة العملاء في دليل الحسابات."
        cloudGlMappings.none { it.mappingKey == "accounts_receivable" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } -> "حساب مراقبة العملاء غير مربوط بعملة صالحة في دليل الحسابات."
        cloudCustomerPartyAccounts.any { link -> cloudGlMappings.none { it.mappingKey == "accounts_receivable" && it.currencyCode == link.currencyCode } } -> "توجد عملة عميل بلا حساب مراقبة عملاء مطابق؛ تعذر اعتماد الكشف."
        cloudLedgerLines.any { line ->
            line.accountId in cloudGlMappings.filter { it.mappingKey == "accounts_receivable" }.map { it.ledgerAccountId }.toSet() &&
                (line.partyAccountId.isBlank() || cloudCustomerPartyAccounts.none { link ->
                    link.partyAccountId == line.partyAccountId && link.currencyCode == line.currencyCode
                })
        } -> "توجد حركات على حساب مراقبة العملاء بلا ربط صالح بعميل وعملة مطابقين؛ تعذر اعتماد الكشف الكامل."
        else -> ""
    }
    val supplierStatementError = when {
        !cloudMode -> "كشف المورد الكامل يتطلب دفتر الأستاذ السحابي."
        ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
        cloudLedgerLines.isEmpty() -> "لا توجد قيود مرحلة في دفتر الأستاذ؛ لا يمكن إنشاء كشف حساب محاسبي."
        supplierPartyAccountsLoadError.isNotBlank() -> supplierPartyAccountsLoadError
        supplierControlAccountsLoadError.isNotBlank() -> supplierControlAccountsLoadError
        cloudSupplierPartyAccounts.isEmpty() -> "لا توجد روابط لحسابات مراقبة الموردين في دليل الحسابات."
        cloudSupplierControlAccounts.none { it.accountRole == "accounts_payable" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } -> "حساب مراقبة الموردين غير مربوط بعملة صالحة في دليل الحسابات."
        cloudSupplierPartyAccounts.any { link -> cloudSupplierControlAccounts.none { it.accountRole == "accounts_payable" && it.currencyCode == link.currencyCode } } -> "توجد عملة مورد بلا حساب مراقبة موردين مطابق؛ تعذر اعتماد الكشف."
        cloudLedgerLines.any { line ->
            line.accountId in cloudSupplierControlAccounts.filter { it.accountRole == "accounts_payable" }.map { it.ledgerAccountId }.toSet() &&
                (line.partyAccountId.isBlank() || cloudSupplierPartyAccounts.none { link ->
                    link.partyAccountId == line.partyAccountId && link.currencyCode == line.currencyCode
                })
        } -> "توجد حركات على حساب مراقبة الموردين بلا ربط صالح بمورد وعملة مطابقين؛ تعذر اعتماد الكشف الكامل."
        else -> ""
    }
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
        "أداء المبيعات حسب المستخدم" -> if (userSalesPerformanceError.isNotBlank()) emptyList()
            else userSalesPerformanceCalculation.getOrThrow().map { row ->
                val actorLabel = if (row.userId == "غير منسوب") "مبيعات غير منسوبة لمستخدم"
                    else "مستخدم ${row.userId}"
                ReportLine(
                    "-",
                    row.userId,
                    actorLabel,
                    row.totalMinor,
                    row.currencyCode,
                    "${row.invoiceCount} فاتورة مكتملة · متوسط الفاتورة ${reportMoney(row.averageInvoiceMinor, row.currencyCode, true)}"
                )
            }
        "اتجاه المبيعات اليومي" -> if (dailySalesTrendError.isNotBlank()) emptyList() else {
            dailySalesTrendCalculation.getOrThrow().map { day ->
                ReportLine(
                    day.date,
                    "${day.invoiceCount} فاتورة",
                    "إجمالي مبيعات اليوم",
                    day.totalMinor,
                    day.currencyCode,
                    "إجمالي الفواتير المكتملة في هذا اليوم · العملة: ${day.currencyCode}"
                )
            }
        }
        "عمر أرصدة العملاء حسب تاريخ الفاتورة" -> if (customerReceivableAgingError.isNotBlank()) emptyList() else {
            customerReceivableAgingReport.rows.map { aging ->
                ReportLine(
                    today.toString(),
                    aging.bucket,
                    aging.customerName,
                    aging.balanceMinor,
                    aging.currencyCode,
                    "${aging.invoiceCount} فاتورة مفتوحة · الرصيد الحالي بعد المرتجعات والاستردادات · العمر من تاريخ الفاتورة وليس الاستحقاق"
                )
            }
        }
        "التحصيل حسب العميل" -> if (customerCollectionsError.isNotBlank()) emptyList() else {
            customerCollectionsCalculation.getOrThrow().map { customer ->
                ReportLine(
                    "${startText} — ${endText}",
                    "${customer.paymentCount} دفعة",
                    customer.customerName,
                    customer.totalMinor,
                    customer.currencyCode,
                    "دفعات ناجحة فقط · ${if (customer.customerId.isBlank()) "لا يوجد معرف عميل محفوظ" else "معرف العميل: ${customer.customerId}"}"
                )
            }
        }
        "التحصيل حسب طريقة الدفع" -> cloudPayments.filter { row ->
            val timestamp = reportDateMillis(row.createdAt)
            timestamp != null && timestamp >= startMillis && timestamp < endExclusive &&
                row.status.equals("succeeded", true)
        }.groupBy { it.paymentMethodCode.ifBlank { "غير محددة" } to it.currencyCode }.map { (key, rows) ->
            val total = rows.fold(0L) { sum, row -> Math.addExact(sum, row.amountMinor) }
            ReportLine(
                "${startText} — ${endText}",
                "تجميع",
                key.first,
                total,
                key.second,
                "عدد الدفعات: ${rows.size} · دفعات ناجحة فقط · المصدر: commercial_payments"
            )
        }.sortedWith(compareBy<ReportLine> { it.currency }.thenBy { it.name })
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
        "المشتريات حسب المورد" -> if (supplierPurchaseSummaryError.isNotBlank()) emptyList() else {
            supplierPurchaseSummaryCalculation.getOrThrow().map { summary ->
                ReportLine(
                    "${startText} — ${endText}",
                    "${summary.invoiceCount} فاتورة",
                    summary.supplierName,
                    summary.totalMinor,
                    summary.currencyCode,
                    "إجمالي فواتير المورد غير الملغاة · معرف المورد: ${summary.supplierId}"
                )
            }
        }
        "عمر مستحقات الموردين حسب تاريخ الفاتورة" -> if (supplierLiabilityAgingError.isNotBlank()) emptyList() else {
            supplierLiabilityAgingReport.rows.map { aging ->
                ReportLine(
                    today.toString(),
                    aging.bucket,
                    aging.supplierName,
                    aging.balanceMinor,
                    aging.currencyCode,
                    "${aging.invoiceCount} فاتورة مفتوحة · الرصيد الحالي حتى اليوم · العمر من تاريخ تسجيل الفاتورة وليس من تاريخ استحقاق تعاقدي"
                )
            }
        }
        "أعمار مستحقات الموردين" -> if (supplierDueDateAgingError.isNotBlank()) emptyList()
            else supplierDueDateAgingReport.toReportLines(today.toString())
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
        "التدفقات النقدية والصندوق" -> if (sourceErrorForReport(selectedReport).isNotBlank()) emptyList() else buildCashFlowReportLines(cloudLedgerLines, cloudGlMappings, startDate, endDate, startText, endText)
        "ملخص الضرائب والخصومات" -> taxDiscountSummaryReport.lines
        "الضرائب والخصومات التفصيلية" -> if (taxDetailError.isNotBlank()) emptyList() else buildList {
            cloudSalesDocuments.filter { doc ->
                doc.saleStatus.equals("completed", true) &&
                    reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }.forEach { doc ->
                if (doc.discountMinor != 0L) add(ReportLine(doc.createdAt.take(10), "فاتورة بيع #${doc.receiptNumber}", doc.customerName, -doc.discountMinor, doc.currencyCode,
                    "خصم مسجل على الفاتورة · مصدر commercial_sales.discount_minor"))
                if (doc.taxMinor != 0L) add(ReportLine(doc.createdAt.take(10), "فاتورة بيع #${doc.receiptNumber}", doc.customerName, doc.taxMinor, doc.currencyCode,
                    "ضريبة مسجلة قبل تسوية المرتجعات · مصدر commercial_sales.tax_minor"))
            }
            cloudPurchases.forEach { purchase ->
                purchase.supplierInvoices.filter { invoice ->
                    reportDateMillis(invoice.recordedAt)?.let { it >= startMillis && it < endExclusive } == true &&
                        invoice.state.lowercase(Locale.ROOT) !in setOf("cancelled", "canceled", "void", "reversed")
                }.forEach { invoice ->
                    if (invoice.taxMinor != 0L) add(ReportLine(invoice.recordedAt.take(10), "فاتورة شراء #${invoice.invoiceNumber.ifBlank { invoice.id }}", purchase.supplierName, invoice.taxMinor, invoice.currencyCode.ifBlank { purchase.currencyCode },
                        "ضريبة شراء مسجلة · commercial_supplier_invoices.tax_minor"))
                }
            }
            cloudReturns.filter { saleReturn ->
                saleReturn.status.equals("completed", true) &&
                    reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
            }.forEach { saleReturn ->
                add(ReportLine(saleReturn.completedAt.take(10), "مرتجع #${saleReturn.id}", saleReturn.customerName, -saleReturn.approvedRefundMinor!!, saleReturn.currencyCode,
                    "قيمة مرتجع معتمد؛ لا تفصل حصة الضريبة لأن مصدر المرتجع لا يخزنها منفصلة"))
            }
        }.sortedByDescending { it.date }
        "ربحية الأصناف بعد المرتجعات" -> if (itemProfitabilityError.isNotBlank()) emptyList() else {
            itemProfitabilityCalculation.getOrThrow().map { item ->
                ReportLine(
                    "${startText} — ${endText}",
                    "ربحية صنف",
                    item.productName,
                    item.grossProfitMinor,
                    item.currencyCode,
                    "الإيراد: ${reportMoney(item.salesMinor, item.currencyCode, true)} · المرتجعات المعتمدة: ${reportMoney(item.refundMinor, item.currencyCode, true)} · صافي الإيراد: ${reportMoney(item.netRevenueMinor, item.currencyCode, true)} · تكلفة البيع: ${reportMoney(item.soldCostMinor, item.currencyCode, true)} · تكلفة المرتجع المعاد تخزينه: ${reportMoney(item.returnedCostMinor, item.currencyCode, true)} · صافي التكلفة: ${reportMoney(item.netCostMinor, item.currencyCode, true)} · عدد بنود البيع: ${item.saleLineCount}",
                    displayAmount = "الكمية المباعة: ${item.soldQuantity.stripTrailingZeros().toPlainString()} · الكمية المرتجعة: ${item.returnedQuantity}"
                )
            }
        }
        "تكلفة البضاعة والربح" -> if (costProfitError.isNotBlank()) emptyList() else {
            val periodSales = cloudSalesDocuments.filter { doc ->
                doc.saleStatus.equals("completed", true) &&
                    reportDateMillis(doc.createdAt)?.let { it >= startMillis && it < endExclusive } == true
            }
            val periodReturns = cloudReturns.filter { saleReturn ->
                saleReturn.status.equals("completed", true) &&
                    reportDateMillis(saleReturn.completedAt)?.let { it >= startMillis && it < endExclusive } == true
            }
            buildList {
                calculateCostProfitReportTotals(periodSales.map { it.toCostProfitInput() }, periodReturns.map { it.toCostProfitInput() }).forEach { totals ->
                    val currency = totals.currencyCode
                    add(ReportLine("${startText} — ${endText}", "إيرادات", "إجمالي المبيعات بعد الخصومات", totals.grossSalesMinor, currency,
                        "إجمالي ${totals.invoiceCount} فاتورة مكتملة؛ الإيراد لا يتضمن الضريبة"))
                    if (totals.returnRevenueMinor != 0L) add(ReportLine("${startText} — ${endText}", "مرتجعات", "مبالغ المرتجعات المعتمدة", -totals.returnRevenueMinor, currency,
                        "مرتجعات مكتملة ضمن الفترة فقط؛ تم التحقق من ربط الفاتورة والعملة"))
                    add(ReportLine("${startText} — ${endText}", "صافي الإيرادات", "صافي الإيرادات بعد المرتجعات", totals.netRevenueMinor, currency,
                        "من الفواتير المكتملة ناقص الخصومات والمرتجعات المعتمدة"))
                    add(ReportLine("${startText} — ${endText}", "تكلفة البضاعة", "تكلفة البنود المباعة المحفوظة", -totals.soldCostMinor, currency,
                        "لقطات cost_total_minor المسجلة عند البيع؛ لا تستخدم تكلفة الشراء الحالية"))
                    if (totals.returnedCostMinor != 0L) add(ReportLine("${startText} — ${endText}", "عكس تكلفة المرتجع", "تكلفة الكمية التي أعيد تخزينها", totals.returnedCostMinor, currency,
                        "محسوبة من تكلفة بند البيع التاريخية والكمية المعاد تخزينها"))
                    add(ReportLine("${startText} — ${endText}", "تكلفة صافية", "تكلفة البضاعة بعد المرتجعات المعاد تخزينها", -totals.netCostMinor, currency,
                        "تكلفة البيع ناقص تكلفة المرتجعات التي أعيد تخزينها"))
                    add(ReportLine("${startText} — ${endText}", "مجمل الربح", "مجمل الربح بعد الخصومات والمرتجعات", totals.grossProfitMinor, currency,
                        "إيرادات صافية ناقص تكلفة تاريخية صافية؛ لا يمثل صافي الربح بعد المصروفات التشغيلية"))
                }
            }
        }
        "ملخص المصروفات حسب التصنيف وطريقة الصرف" -> cloudExpenses.filter { expense ->
            expense.canceledAt.isBlank() &&
                reportDateMillis(expense.recordedAt)?.let { it >= startMillis && it < endExclusive } == true
        }.groupBy { Triple(it.category, it.paymentMethod.ifBlank { "غير محددة" }, it.currencyCode) }
            .map { (key, rows) ->
                val total = rows.fold(0L) { sum, expense -> Math.addExact(sum, expense.amountMinor) }
                ReportLine(
                    "${startText} — ${endText}",
                    "تجميع",
                    key.first,
                    total,
                    key.third,
                    "طريقة الصرف: ${key.second} · عدد السندات: ${rows.size} · سندات غير ملغاة فقط"
                )
            }.sortedWith(compareBy<ReportLine> { it.currency }.thenBy { it.name })
        "المصروفات التشغيلية" -> (if (cloudMode) cloudExpenses.filter { it.canceledAt.isBlank() && (reportDateMillis(it.recordedAt)?.let { ts -> ts >= startMillis && ts < endExclusive } == true) }.map {
            ReportLine(it.recordedAt.take(10), it.id, it.category, it.amountMinor, it.currencyCode, it.paymentMethod)
        } else inPeriodExpenses.map {
            ReportLine(Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate().toString(), "مصروف #${it.id}", it.category, it.amount.toLong(), "YER", it.note)
        })
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
    val exportLaunchers = rememberReportsExportLaunchers(
        context = context,
        dateError = dateError,
        cloudMode = cloudMode,
        cloudLoading = cloudLoading,
        selectedReportSourceError = selectedReportSourceError,
        exportRows = exportRows,
        onError = { error = it }
    )
    val csvLauncher = exportLaunchers.csv
    val xlsxLauncher = exportLaunchers.xlsx
    val pdfLauncher = exportLaunchers.pdf

    val currencyTotal = if (dateError || (cloudMode && (cloudLoading || salesDocumentsLoadError.isNotBlank()))) emptyMap() else if (cloudMode) inPeriodCloudSalesDocuments.groupBy { it.currencyCode.ifBlank { "غير محددة" } }.mapValues { (_, rows) -> rows.sumOf { it.totalMinor } }
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
        if (selectedReport in setOf("كشف حركات الموردين", "أرصدة الموردين والمستحقات")) item {
            OutlinedTextField(
                value = supplierFilter,
                onValueChange = { supplierFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم المورد") },
                supportingText = { Text(if (selectedReport == "أرصدة الموردين والمستحقات") "يعرض حركات حساب مراقبة المورد من القيود المرحلة." else "الكشف التشغيلي الجزئي يعتمد على الفواتير والدفعات المحملة.") }
            )
        }
        if (selectedReport in setOf("أرصدة العملاء وكشف الحساب", "الرصيد المحاسبي الكامل للعملاء")) item {
            OutlinedTextField(
                value = customerFilter,
                onValueChange = { customerFilter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("اسم العميل أو معرفه") },
                supportingText = { Text(if (selectedReport == "الرصيد المحاسبي الكامل للعملاء") "يصفّي حركات حساب مراقبة العميل في دفتر الأستاذ المرحل." else "يصفّي الكشف التشغيلي حسب العميل والحركات المحملة.") }
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("تصفية نتائج التقرير", fontWeight = FontWeight.SemiBold, color = ReportInk)
                if (selectedReport !in setOf("أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات")) {
                    OutlinedTextField(rowFilter, { rowFilter = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("اسم أو مرجع المستند") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(currencyFilter, { currencyFilter = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("العملة") })
                    if (selectedReport !in setOf("أرصدة العملاء وكشف الحساب", "كشف حركات الموردين", "الرصيد المحاسبي الكامل للعملاء", "أرصدة الموردين والمستحقات")) {
                        OutlinedTextField(statusFilter, { statusFilter = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("الحالة") })
                    }
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
                            add(listOf("الفترة", if (exportTitle == "التقييم المالي الحالي للمخزون") "لقطة حالية للموقع المختار؛ ليست تاريخية" else if (exportTitle == "رصيد المخزون حسب الموقع" || exportTitle == "حالة ربط أرصدة المخزون بالمستودعات") "لقطة حالية لجميع مواقع المؤسسة؛ ليست تاريخية" else startText + " — " + endText))
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
                            add(listOf("الفترة", if (exportTitle == "التقييم المالي الحالي للمخزون") "لقطة حالية للموقع المختار؛ ليست تاريخية" else if (exportTitle == "رصيد المخزون حسب الموقع" || exportTitle == "حالة ربط أرصدة المخزون بالمستودعات") "لقطة حالية لجميع مواقع المؤسسة؛ ليست تاريخية" else startText + " — " + endText))
                            add(listOf("مصدر البيانات", if (cloudMode) "السحابة" else "محلي على الجهاز"))
                            add(listOf("تاريخ التصدير", java.time.LocalDateTime.now().toString()))
                            add(listOf("التاريخ", "المرجع", "الاسم", "المبلغ/الكمية", "العملة", "الحالة"))
                            selectedRows.forEach { row -> add(listOf(row.date, row.reference, row.name, reportLineAmount(row, cloudMode), row.currency, row.status)) }
                        }
                        xlsxLauncher.launch("mutajri-report-${selectedReport.hashCode()}.xlsx")
                    }) { Icon(Icons.Default.TableChart, null); Spacer(Modifier.width(4.dp)); Text("Excel XLSX") }
                    OutlinedButton(onClick = {
                        if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) {
                            error = "لا يمكن تصدير التقرير: تحقق من الفترة واكتمال مصادر هذا التقرير."
                        } else {
                            exportTitle = selectedReport
                            exportRows = buildList {
                                add(listOf("التقرير", exportTitle))
                                add(listOf("الفترة", if (exportTitle == "التقييم المالي الحالي للمخزون") "لقطة حالية للموقع المختار؛ ليست تاريخية" else if (exportTitle == "رصيد المخزون حسب الموقع" || exportTitle == "حالة ربط أرصدة المخزون بالمستودعات") "لقطة حالية لجميع مواقع المؤسسة؛ ليست تاريخية" else startText + " — " + endText))
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
                        if (dateError || (cloudMode && (cloudLoading || selectedReportSourceError.isNotBlank()))) {
                            error = "لا يمكن طباعة التقرير: تحقق من الفترة واكتمال مصادر هذا التقرير."
                        } else {
                            exportTitle = selectedReport
                            exportRows = buildList {
                                add(listOf("التقرير", exportTitle))
                                add(listOf("الفترة", if (exportTitle == "التقييم المالي الحالي للمخزون") "لقطة حالية للموقع المختار؛ ليست تاريخية" else if (exportTitle == "رصيد المخزون حسب الموقع" || exportTitle == "حالة ربط أرصدة المخزون بالمستودعات") "لقطة حالية لجميع مواقع المؤسسة؛ ليست تاريخية" else startText + " — " + endText))
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
                        Text(reportLineAmount(row, cloudMode), color = ReportGreen, fontWeight = FontWeight.Bold)
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
