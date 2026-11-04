package com.adnanalhasani.commercial

import java.util.Locale

internal data class CustomerStatementPaymentInput(
    val id: String,
    val amountMinor: Long,
    val status: String,
    val createdAt: String
)
internal data class CustomerStatementReturnInput(
    val id: String,
    val status: String,
    val refundStatus: String,
    val approvedRefundMinor: Long?,
    val updatedAt: String
)
internal data class CustomerStatementInvoiceInput(
    val customerId: String,
    val receiptNumber: String,
    val customerName: String,
    val totalMinor: Long,
    val currencyCode: String,
    val createdAt: String,
    val payments: List<CustomerStatementPaymentInput>,
    val returns: List<CustomerStatementReturnInput>
)
internal data class ReportCustomerMovement(
    val date: String,
    val customerId: String,
    val customer: String,
    val currency: String,
    val reference: String,
    val amount: Long,
    val description: String
)

internal fun buildCustomerOperationalStatementMovements(
    sales: List<CustomerStatementInvoiceInput>
): List<ReportCustomerMovement> = buildList {
    sales.filter { it.customerId.isNotBlank() }.forEach { doc ->
        require(doc.totalMinor >= 0L && doc.currencyCode.matches(Regex("^[A-Z]{3}$"))) {
            "فاتورة عميل بمبلغ أو عملة غير صالحة."
        }
        require(reportDateMillis(doc.createdAt) != null) { "تاريخ فاتورة العميل غير صالح." }
        val customerName = doc.customerName.ifBlank { "عميل غير محدد" }
        add(ReportCustomerMovement(
            doc.createdAt.take(10), doc.customerId, customerName, doc.currencyCode,
            "فاتورة #${doc.receiptNumber}", doc.totalMinor, "مدين · فاتورة مكتملة"
        ))
        doc.payments.filter {
            it.status.lowercase(Locale.ROOT) in setOf("succeeded", "partially_refunded", "refunded")
        }.forEach { payment ->
            require(payment.amountMinor > 0L && reportDateMillis(payment.createdAt) != null) {
                "دفعة عميل ناجحة بلا مبلغ موجب أو تاريخ تسجيل صالح."
            }
            add(ReportCustomerMovement(
                payment.createdAt.take(10), doc.customerId, customerName, doc.currencyCode,
                "تحصيل ${payment.id.ifBlank { doc.receiptNumber }}",
                Math.negateExact(payment.amountMinor),
                "دائن · تحصيل ${payment.status}"
            ))
        }
        doc.returns.filter {
            it.status.equals("completed", true) || it.refundStatus.equals("refunded", true)
        }.forEach { saleReturn ->
            require(saleReturn.status.equals("completed", true)) {
                "تم تسجيل استرداد لمرتجع غير مكتمل؛ يلزم تسوية حالة المرتجع."
            }
            val approved = saleReturn.approvedRefundMinor
                ?: throw IllegalArgumentException("مرتجع مكتمل بلا مبلغ اعتماد محفوظ.")
            require(approved >= 0L && reportDateMillis(saleReturn.updatedAt) != null) {
                "مرتجع مكتمل بمبلغ أو تاريخ تحديث غير صالح."
            }
            if (approved > 0L) {
                add(ReportCustomerMovement(
                    saleReturn.updatedAt.take(10), doc.customerId, customerName, doc.currencyCode,
                    "مرتجع ${saleReturn.id}", Math.negateExact(approved), "دائن · مرتجع معتمد"
                ))
                if (saleReturn.refundStatus.equals("refunded", true)) {
                    add(ReportCustomerMovement(
                        saleReturn.updatedAt.take(10), doc.customerId, customerName, doc.currencyCode,
                        "استرداد ${saleReturn.id}", approved, "مدين · استرداد منفذ"
                    ))
                }
            }
        }
    }
}
