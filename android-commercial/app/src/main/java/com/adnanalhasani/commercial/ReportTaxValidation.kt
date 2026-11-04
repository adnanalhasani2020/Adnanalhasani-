package com.adnanalhasani.commercial

import java.util.Locale

/** Validates the source records used by the detailed recorded-tax/discount report. */
internal fun reportTaxDetailValidationError(
    cloudMode: Boolean,
    salesDocumentsLoadError: String,
    purchasesLoadError: String,
    returnsLoadError: String,
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    cloudPurchases: List<CommercialCloudPurchaseDocument>,
    cloudReturns: List<CommercialCloudSaleReturn>,
    startMillis: Long,
    endExclusive: Long
): String = when {
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
