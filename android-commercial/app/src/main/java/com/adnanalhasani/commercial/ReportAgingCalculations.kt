package com.adnanalhasani.commercial

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class SupplierLiabilityAgingReportState(
    val rows: List<SupplierLiabilityAgingSummary>,
    val error: String
)

internal fun buildSupplierLiabilityAgingReport(
    purchases: List<CommercialCloudPurchaseDocument>,
    asOfDate: String,
    cloudMode: Boolean,
    purchasesLoadError: String,
    invoiceIntegrityError: String
): SupplierLiabilityAgingReportState {
    val inputs = purchases.flatMap { purchase ->
        purchase.supplierInvoices.mapNotNull { invoice ->
            if (invoice.state.lowercase() in setOf("cancelled", "canceled", "void", "reversed")) return@mapNotNull null
            val obligation = invoice.obligation ?: return@mapNotNull null
            if (obligation.state.lowercase() in setOf("cancelled", "canceled", "void", "reversed")) return@mapNotNull null
            SupplierLiabilityAgingInput(
                invoiceId = invoice.id,
                invoiceNumber = invoice.invoiceNumber,
                supplierId = purchase.supplierId,
                supplierName = purchase.supplierName.ifBlank { "مورد غير محدد" },
                invoiceDate = invoice.recordedAt,
                currencyCode = obligation.currencyCode,
                amountDueMinor = obligation.amountDueMinor,
                amountPaidMinor = obligation.amountPaidMinor
            )
        }
    }
    val result = runCatching { calculateSupplierLiabilityAging(inputs, asOfDate) }
    val error = when {
        !cloudMode -> "أعمار مستحقات الموردين تتطلب مصادر السحابة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        invoiceIntegrityError.isNotBlank() -> invoiceIntegrityError
        result.isFailure -> result.exceptionOrNull()?.message.orEmpty()
        else -> ""
    }
    return SupplierLiabilityAgingReportState(result.getOrDefault(emptyList()), error)
}

internal data class SupplierLiabilityAgingInput(
    val invoiceId: String,
    val invoiceNumber: String,
    val supplierId: String,
    val supplierName: String,
    val invoiceDate: String,
    val dueDate: String = "",
    val currencyCode: String,
    val amountDueMinor: Long,
    val amountPaidMinor: Long
)

internal data class SupplierLiabilityAgingSummary(
    val supplierId: String,
    val supplierName: String,
    val currencyCode: String,
    val bucket: String,
    val invoiceCount: Int,
    val balanceMinor: Long
)

/**
 * Ages unpaid supplier invoice obligations by invoice-recording date, not by contractual due date.
 * The current supplier-invoice schema stores recorded_at but no supplier invoice_date/due_date.
 */
internal fun calculateSupplierLiabilityAging(
    invoices: List<SupplierLiabilityAgingInput>,
    asOfDate: String
): List<SupplierLiabilityAgingSummary> {
    val asOf = LocalDate.parse(asOfDate)
    val seen = mutableSetOf<String>()
    val open = invoices.mapNotNull { invoice ->
        require(invoice.invoiceId.isNotBlank() && seen.add(invoice.invoiceId)) {
            "معرف فاتورة مورد مفقود أو مكرر في تقرير أعمار المستحقات."
        }
        require(invoice.supplierId.isNotBlank() && invoice.supplierName.isNotBlank() &&
            invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) &&
            invoice.amountDueMinor >= 0L && invoice.amountPaidMinor >= 0L &&
            invoice.amountPaidMinor <= invoice.amountDueMinor) {
            "بيانات التزام المورد أو العملة أو المبالغ غير صالحة؛ تم حجب تقرير الأعمار."
        }
        val date = LocalDate.parse(invoice.invoiceDate.take(10))
        require(!date.isAfter(asOf)) {
            "تاريخ فاتورة المورد أحدث من تاريخ التقرير؛ تم حجب تقرير الأعمار."
        }
        val balance = Math.subtractExact(invoice.amountDueMinor, invoice.amountPaidMinor)
        if (balance == 0L) null else {
            val ageDays = ChronoUnit.DAYS.between(date, asOf)
            val bucket = when {
                ageDays <= 30L -> "0–30 يومًا"
                ageDays <= 60L -> "31–60 يومًا"
                ageDays <= 90L -> "61–90 يومًا"
                else -> "أكثر من 90 يومًا"
            }
            Triple(invoice, bucket, balance)
        }
    }
    return open.groupBy { Triple(it.first.supplierId, it.first.currencyCode, it.second) }
        .map { (key, rows) ->
            SupplierLiabilityAgingSummary(
                supplierId = key.first,
                supplierName = rows.first().first.supplierName,
                currencyCode = key.second,
                bucket = key.third,
                invoiceCount = rows.size,
                balanceMinor = sumReportAmountsExactly(rows.map { it.third })
            )
        }
        .sortedWith(compareBy<SupplierLiabilityAgingSummary> { it.currencyCode }
            .thenBy { it.supplierName }.thenBy { it.bucket })
}


internal data class CustomerReceivableAgingInput(
    val saleId: String,
    val receiptNumber: String,
    val customerId: String,
    val customerName: String,
    val invoiceDate: String,
    val currencyCode: String,
    val balanceMinor: Long
)

internal data class CustomerReceivableAgingSummary(
    val customerId: String,
    val customerName: String,
    val currencyCode: String,
    val bucket: String,
    val invoiceCount: Int,
    val balanceMinor: Long
)

/**
 * Ages open customer receivables by sale-invoice date, not contractual due date.
 * Return credits and executed refunds must already have been reflected by the source loader.
 */
internal fun calculateCustomerReceivableAging(
    invoices: List<CustomerReceivableAgingInput>,
    asOfDate: String
): List<CustomerReceivableAgingSummary> {
    val asOf = LocalDate.parse(asOfDate)
    val seen = mutableSetOf<String>()
    val open = invoices.mapNotNull { invoice ->
        require(invoice.saleId.isNotBlank() && seen.add(invoice.saleId)) {
            "معرف فاتورة عميل مفقود أو مكرر في تقرير أعمار الأرصدة."
        }
        require(invoice.customerId.isNotBlank() && invoice.customerName.isNotBlank() &&
            invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) && invoice.balanceMinor > 0L) {
            "بيانات رصيد العميل أو العملة غير صالحة؛ تم حجب تقرير الأعمار."
        }
        val date = LocalDate.parse(invoice.invoiceDate.take(10))
        require(!date.isAfter(asOf)) {
            "تاريخ فاتورة العميل أحدث من تاريخ التقرير؛ تم حجب تقرير الأعمار."
        }
        val ageDays = ChronoUnit.DAYS.between(date, asOf)
        val bucket = when {
            ageDays <= 30L -> "0–30 يومًا"
            ageDays <= 60L -> "31–60 يومًا"
            ageDays <= 90L -> "61–90 يومًا"
            else -> "أكثر من 90 يومًا"
        }
        Triple(invoice, bucket, invoice.balanceMinor)
    }
    return open.groupBy { Triple(it.first.customerId, it.first.currencyCode, it.second) }
        .map { (key, rows) ->
            CustomerReceivableAgingSummary(
                customerId = key.first,
                customerName = rows.first().first.customerName,
                currencyCode = key.second,
                bucket = key.third,
                invoiceCount = rows.size,
                balanceMinor = sumReportAmountsExactly(rows.map { it.third })
            )
        }
        .sortedWith(compareBy<CustomerReceivableAgingSummary> { it.currencyCode }
            .thenBy { it.customerName }.thenBy { it.bucket })
}
