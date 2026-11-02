package com.adnanalhasani.commercial

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
