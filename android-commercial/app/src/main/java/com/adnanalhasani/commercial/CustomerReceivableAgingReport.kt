package com.adnanalhasani.commercial

internal data class CustomerReceivableAgingReportState(
    val rows: List<CustomerReceivableAgingSummary>,
    val error: String
)

internal fun buildCustomerReceivableAgingReport(
    receivables: List<CommercialCloudReceivable>,
    asOfDate: String,
    cloudMode: Boolean,
    loadError: String
): CustomerReceivableAgingReportState {
    val inputs = receivables.map { receivable ->
        CustomerReceivableAgingInput(
            saleId = receivable.saleId,
            receiptNumber = receivable.receiptNumber,
            customerId = receivable.customerId,
            customerName = receivable.customerName,
            invoiceDate = receivable.createdAt,
            currencyCode = receivable.currencyCode,
            balanceMinor = receivable.dueMinor
        )
    }
    val result = runCatching { calculateCustomerReceivableAging(inputs, asOfDate) }
    val error = when {
        !cloudMode -> "عمر أرصدة العملاء يتطلب مصادر السحابة."
        loadError.isNotBlank() -> loadError
        receivables.any { it.createdAt.isBlank() || it.currencyCode.isBlank() || it.dueMinor <= 0L } ->
            "توجد فاتورة عميل بتاريخ أو عملة أو رصيد غير صالح؛ تم حجب تقرير الأعمار."
        result.isFailure -> result.exceptionOrNull()?.message.orEmpty()
        else -> ""
    }
    return CustomerReceivableAgingReportState(result.getOrDefault(emptyList()), error)
}
