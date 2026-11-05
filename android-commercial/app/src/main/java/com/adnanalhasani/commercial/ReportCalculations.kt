package com.adnanalhasani.commercial

import java.time.LocalDate


internal fun sumReportAmountsExactly(values: Iterable<Long>): Long =
    values.fold(0L) { total, value -> Math.addExact(total, value) }

internal fun sumReportCountsExactly(values: Iterable<Int>): Int =
    values.fold(0) { total, value -> Math.addExact(total, value) }

internal data class SupplierObligationInput(
    val supplierId: String,
    val supplierName: String,
    val purchaseOrderCurrencyCode: String,
    val invoiceCurrencyCode: String,
    val obligationCurrencyCode: String,
    val amountDueMinor: Long,
    val amountPaidMinor: Long
)
internal data class SupplierObligationTotals(
    val supplierId: String,
    val supplierName: String,
    val currencyCode: String,
    val amountDueMinor: Long,
    val amountPaidMinor: Long,
    val balanceMinor: Long
)

internal fun calculateSupplierObligationTotals(
    obligations: List<SupplierObligationInput>
): List<SupplierObligationTotals> {
    obligations.forEach { row ->
        require(row.supplierId.isNotBlank()) { "يوجد التزام مورد بلا معرف مورد محفوظ." }
        require(row.purchaseOrderCurrencyCode.matches(Regex("^[A-Z]{3}$")) &&
            row.invoiceCurrencyCode == row.purchaseOrderCurrencyCode &&
            row.obligationCurrencyCode == row.purchaseOrderCurrencyCode) {
            "عملة التزام المورد لا تطابق عملة أمر الشراء والفاتورة."
        }
        require(row.amountDueMinor >= 0L && row.amountPaidMinor >= 0L) {
            "مبلغ الالتزام أو المسدد للمورد غير صالح."
        }
    }
    return obligations.groupBy { Triple(it.supplierId, it.supplierName, it.purchaseOrderCurrencyCode) }
        .map { (key, rows) ->
            val due = sumReportAmountsExactly(rows.map { it.amountDueMinor })
            val paid = sumReportAmountsExactly(rows.map { it.amountPaidMinor })
            SupplierObligationTotals(
                supplierId = key.first,
                supplierName = key.second,
                currencyCode = key.third,
                amountDueMinor = due,
                amountPaidMinor = paid,
                balanceMinor = Math.subtractExact(due, paid)
            )
        }
        .sortedWith(compareBy<SupplierObligationTotals> { it.currencyCode }.thenBy { it.supplierName }.thenBy { it.supplierId })
}

internal data class SupplierPurchaseInvoiceInput(
    val invoiceId: String,
    val supplierId: String,
    val supplierName: String,
    val currencyCode: String,
    val totalMinor: Long
)

internal data class SupplierPurchaseSummary(
    val supplierId: String,
    val supplierName: String,
    val currencyCode: String,
    val invoiceCount: Int,
    val totalMinor: Long
)

internal fun calculateSupplierPurchaseSummary(
    invoices: List<SupplierPurchaseInvoiceInput>
): List<SupplierPurchaseSummary> {
    val invoiceIds = mutableSetOf<String>()
    invoices.forEach { invoice ->
        require(invoice.invoiceId.isNotBlank() && invoiceIds.add(invoice.invoiceId)) {
            "معرف فاتورة مورد مفقود أو مكرر في ملخص المشتريات."
        }
        require(invoice.supplierId.isNotBlank() && invoice.supplierName.isNotBlank() &&
            invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.totalMinor >= 0L) {
            "بيانات مورد أو مبلغ أو عملة غير صالحة في ملخص المشتريات."
        }
    }
    return invoices.groupBy { Triple(it.supplierId, it.supplierName, it.currencyCode) }
        .map { (key, rows) ->
            SupplierPurchaseSummary(
                supplierId = key.first,
                supplierName = key.second,
                currencyCode = key.third,
                invoiceCount = rows.size,
                totalMinor = sumReportAmountsExactly(rows.map { it.totalMinor })
            )
        }
        .sortedWith(compareBy<SupplierPurchaseSummary> { it.currencyCode }
            .thenByDescending { it.totalMinor }.thenBy { it.supplierName }.thenBy { it.supplierId })
}

internal data class DailySalesInvoiceInput(
    val invoiceId: String,
    val date: String,
    val currencyCode: String,
    val totalMinor: Long
)

internal data class DailySalesTrendTotal(
    val date: String,
    val currencyCode: String,
    val invoiceCount: Int,
    val totalMinor: Long
)

internal fun calculateDailySalesTrend(
    invoices: List<DailySalesInvoiceInput>
): List<DailySalesTrendTotal> {
    val invoiceIds = mutableSetOf<String>()
    invoices.forEach { invoice ->
        require(invoice.invoiceId.isNotBlank() && invoiceIds.add(invoice.invoiceId)) {
            "معرف فاتورة مبيعات مفقود أو مكرر في الاتجاه اليومي."
        }
        require(runCatching { LocalDate.parse(invoice.date) }.isSuccess &&
            invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.totalMinor >= 0L) {
            "تاريخ أو عملة أو مبلغ فاتورة غير صالح في الاتجاه اليومي."
        }
    }
    return invoices.groupBy { it.date to it.currencyCode }
        .map { (key, rows) ->
            DailySalesTrendTotal(
                date = key.first,
                currencyCode = key.second,
                invoiceCount = rows.size,
                totalMinor = sumReportAmountsExactly(rows.map { it.totalMinor })
            )
        }
        .sortedWith(compareBy<DailySalesTrendTotal> { it.date }.thenBy { it.currencyCode })
}

internal data class CustomerCollectionPaymentInput(
    val paymentId: String,
    val customerId: String,
    val customerName: String,
    val currencyCode: String,
    val amountMinor: Long
)

internal data class CustomerCollectionSummary(
    val customerId: String,
    val customerName: String,
    val currencyCode: String,
    val paymentCount: Int,
    val totalMinor: Long
)

internal fun calculateCustomerCollectionsByCustomer(
    payments: List<CustomerCollectionPaymentInput>
): List<CustomerCollectionSummary> {
    val paymentIds = mutableSetOf<String>()
    payments.forEach { payment ->
        require(payment.paymentId.isNotBlank() && paymentIds.add(payment.paymentId)) {
            "معرف دفعة مفقود أو مكرر في تقرير التحصيل حسب العميل."
        }
        require(payment.customerName.isNotBlank() &&
            payment.currencyCode.matches(Regex("^[A-Z]{3}$")) && payment.amountMinor > 0L) {
            "اسم العميل أو عملة أو مبلغ دفعة غير صالح في تقرير التحصيل حسب العميل."
        }
    }
    return payments.groupBy { payment ->
        payment.customerId.ifBlank { "cash:${payment.customerName}" } to payment.currencyCode
    }.map { (key, rows) ->
        CustomerCollectionSummary(
            customerId = rows.first().customerId,
            customerName = rows.first().customerName,
            currencyCode = key.second,
            paymentCount = rows.size,
            totalMinor = sumReportAmountsExactly(rows.map { it.amountMinor })
        )
    }.sortedWith(compareBy<CustomerCollectionSummary> { it.currencyCode }
        .thenByDescending { it.totalMinor }.thenBy { it.customerName }.thenBy { it.customerId })
}

internal data class UserSalesPerformanceInvoiceInput(
    val invoiceId: String,
    val userId: String,
    val currencyCode: String,
    val totalMinor: Long
)

internal data class UserSalesPerformanceTotal(
    val userId: String,
    val currencyCode: String,
    val invoiceCount: Int,
    val totalMinor: Long,
    val averageInvoiceMinor: Long
)

/** Sales performance is attributed only to the persisted sale actor; currencies stay separate. */
internal fun calculateUserSalesPerformance(
    invoices: List<UserSalesPerformanceInvoiceInput>
): List<UserSalesPerformanceTotal> {
    val seen = mutableSetOf<String>()
    invoices.forEach { invoice ->
        require(invoice.invoiceId.isNotBlank() && seen.add(invoice.invoiceId)) {
            "معرف فاتورة مبيعات مفقود أو مكرر في تقرير أداء المستخدمين."
        }
        require(invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.totalMinor >= 0L) {
            "توجد فاتورة بعملة أو مبلغ غير صالح؛ تم حجب تقرير أداء المستخدمين."
        }
    }
    return invoices.groupBy { (it.userId.ifBlank { "غير منسوب" }) to it.currencyCode }
        .map { (key, rows) ->
            val total = sumReportAmountsExactly(rows.map { it.totalMinor })
            UserSalesPerformanceTotal(
                userId = key.first,
                currencyCode = key.second,
                invoiceCount = rows.size,
                totalMinor = total,
                averageInvoiceMinor = if (rows.isEmpty()) 0L else total / rows.size
            )
        }
        .sortedWith(compareBy<UserSalesPerformanceTotal> { it.currencyCode }
            .thenByDescending { it.totalMinor }.thenBy { it.userId })
}

internal data class TaxDiscountSummaryInput(
    val documentId: String,
    val currencyCode: String,
    val kind: String,
    val amountMinor: Long
)

internal data class TaxDiscountSummaryTotal(
    val currencyCode: String,
    val salesDiscountMinor: Long,
    val salesDiscountDocumentCount: Int,
    val salesTaxMinor: Long,
    val salesTaxDocumentCount: Int,
    val purchaseTaxMinor: Long,
    val purchaseTaxDocumentCount: Int,
    val salesReturnMinor: Long,
    val salesReturnCount: Int
)

/**
 * Summarizes only recorded tax, discount, and approved return amounts.
 * It deliberately does not calculate a net tax liability or tax refund.
 */
internal fun calculateTaxDiscountSummaryTotals(
    records: List<TaxDiscountSummaryInput>
): List<TaxDiscountSummaryTotal> {
    val seen = mutableSetOf<String>()
    records.forEach { row ->
        require(row.documentId.isNotBlank() && row.kind in setOf(
            "sales_discount", "sales_tax", "purchase_tax", "sales_return"
        ) && seen.add(row.kind + ":" + row.documentId)) {
            "معرف مستند مفقود أو مكرر في ملخص الضرائب والخصومات."
        }
        require(row.currencyCode.matches(Regex("^[A-Z]{3}$")) && row.amountMinor >= 0L) {
            "توجد قيمة ضريبية أو خصم أو مرتجع بعملة أو مبلغ غير صالح؛ تم حجب الملخص."
        }
    }
    return records.groupBy { it.currencyCode }.map { (currency, rows) ->
        fun amount(kind: String): Long =
            sumReportAmountsExactly(rows.filter { it.kind == kind }.map { it.amountMinor })
        fun count(kind: String): Int = rows.count { it.kind == kind }
        TaxDiscountSummaryTotal(
            currencyCode = currency,
            salesDiscountMinor = amount("sales_discount"),
            salesDiscountDocumentCount = count("sales_discount"),
            salesTaxMinor = amount("sales_tax"),
            salesTaxDocumentCount = count("sales_tax"),
            purchaseTaxMinor = amount("purchase_tax"),
            purchaseTaxDocumentCount = count("purchase_tax"),
            salesReturnMinor = amount("sales_return"),
            salesReturnCount = count("sales_return")
        )
    }.sortedBy { it.currencyCode }
}

/** Aggregates report summary amounts by currency without allowing Long overflow. */
internal fun calculateReportCurrencyTotals(amounts: List<Pair<String, Long>>): Map<String, Long> =
    amounts.groupBy { (currencyCode, _) -> currencyCode.ifBlank { "غير محددة" } }
        .toSortedMap()
        .mapValues { (_, rows) -> sumReportAmountsExactly(rows.map { it.second }) }
