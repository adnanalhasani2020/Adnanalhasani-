package com.adnanalhasani.commercial

import java.util.Locale

/** Builds detailed tax/discount rows from the original sales, supplier invoices, and completed returns. */
internal fun buildDetailedTaxReportRows(
    cloudSalesDocuments: List<CommercialCloudSaleDocument>,
    cloudPurchases: List<CommercialCloudPurchaseDocument>,
    cloudReturns: List<CommercialCloudSaleReturn>,
    startMillis: Long,
    endExclusive: Long,
    taxDetailError: String
): List<ReportLine> =
    if (taxDetailError.isNotBlank()) emptyList() else buildList {

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
