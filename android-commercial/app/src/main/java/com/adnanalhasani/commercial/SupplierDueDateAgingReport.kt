package com.adnanalhasani.commercial

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class SupplierDueDateAgingReportState(
    val rows: List<SupplierLiabilityAgingSummary>,
    val error: String
)

internal fun calculateSupplierDueDateAging(
    invoices: List<SupplierLiabilityAgingInput>,
    asOfDate: String
): List<SupplierLiabilityAgingSummary> {
    val asOf = LocalDate.parse(asOfDate)
    val seen = mutableSetOf<String>()
    val open = invoices.mapNotNull { invoice ->
        require(invoice.invoiceId.isNotBlank() && seen.add(invoice.invoiceId)) {
            "معرف فاتورة مورد مفقود أو مكرر في تقرير الاستحقاق."
        }
        require(
            invoice.supplierId.isNotBlank() &&
                invoice.supplierName.isNotBlank() &&
                invoice.currencyCode.matches(Regex("^[A-Z]{3}$")) &&
                invoice.amountDueMinor >= 0L &&
                invoice.amountPaidMinor >= 0L &&
                invoice.amountPaidMinor <= invoice.amountDueMinor
        ) { "بيانات التزام المورد أو العملة أو المبالغ غير صالحة؛ تم حجب تقرير الاستحقاق." }

        val balance = Math.subtractExact(invoice.amountDueMinor, invoice.amountPaidMinor)
        if (balance == 0L) return@mapNotNull null

        require(invoice.invoiceDate.isNotBlank() && invoice.dueDate.isNotBlank()) {
            "توجد فاتورة مورد مفتوحة بلا تاريخ فاتورة أو استحقاق محفوظ؛ راجع المستند الأصلي قبل إصدار التقرير."
        }
        val invoiceDate = LocalDate.parse(invoice.invoiceDate.take(10))
        val dueDate = LocalDate.parse(invoice.dueDate.take(10))
        require(!invoiceDate.isAfter(asOf) && !dueDate.isBefore(invoiceDate)) {
            "تاريخ فاتورة المورد أو استحقاقها غير صالح؛ تم حجب تقرير الاستحقاق."
        }
        val overdueDays = ChronoUnit.DAYS.between(dueDate, asOf)
        val bucket = when {
            overdueDays < 0L -> "غير مستحق"
            overdueDays == 0L -> "مستحق اليوم"
            overdueDays <= 30L -> "متأخر 1–30 يومًا"
            overdueDays <= 60L -> "متأخر 31–60 يومًا"
            overdueDays <= 90L -> "متأخر 61–90 يومًا"
            else -> "متأخر أكثر من 90 يومًا"
        }
        Triple(invoice, bucket, balance)
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

internal fun buildSupplierDueDateAgingReport(
    purchases: List<CommercialCloudPurchaseDocument>,
    asOfDate: String,
    cloudMode: Boolean,
    purchasesLoadError: String,
    invoiceIntegrityError: String
): SupplierDueDateAgingReportState {
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
                invoiceDate = invoice.invoiceDate,
                dueDate = invoice.dueDate,
                currencyCode = obligation.currencyCode,
                amountDueMinor = obligation.amountDueMinor,
                amountPaidMinor = obligation.amountPaidMinor
            )
        }
    }
    val result = runCatching { calculateSupplierDueDateAging(inputs, asOfDate) }
    val error = when {
        !cloudMode -> "أعمار مستحقات الموردين حسب الاستحقاق تتطلب مصادر السحابة."
        purchasesLoadError.isNotBlank() -> purchasesLoadError
        invoiceIntegrityError.isNotBlank() -> invoiceIntegrityError
        result.isFailure -> result.exceptionOrNull()?.message.orEmpty()
        else -> ""
    }
    return SupplierDueDateAgingReportState(result.getOrDefault(emptyList()), error)
}
