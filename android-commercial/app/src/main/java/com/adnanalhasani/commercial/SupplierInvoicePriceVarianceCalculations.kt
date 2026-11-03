package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode

internal data class SupplierInvoicePriceVarianceLineInput(
    val quantity: String,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long,
    val purchaseOrderUnitCostMinor: Long?
)

internal data class SupplierInvoicePriceVarianceInput(
    val currencyCode: String,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val freightMinor: Long,
    val totalMinor: Long,
    val lines: List<SupplierInvoicePriceVarianceLineInput>
)

internal data class SupplierInvoicePriceVarianceReportTotals(
    val expectedSubtotalMinor: Long,
    val invoiceSubtotalMinor: Long,
    val varianceMinor: Long
)

/** Match the database accrual trigger: round the sum of quantity × recorded PO unit cost once. */
internal fun calculateSupplierInvoicePriceVariance(
    invoice: SupplierInvoicePriceVarianceInput
): SupplierInvoicePriceVarianceReportTotals {
    require(invoice.currencyCode.matches(Regex("^[A-Z]{3}$"))) { "عملة فاتورة المورد غير صالحة." }
    require(invoice.subtotalMinor >= 0L && invoice.taxMinor >= 0L && invoice.freightMinor >= 0L) {
        "تحتوي فاتورة المورد على مبلغ سالب غير صالح."
    }
    require(invoice.lines.isNotEmpty()) { "لا توجد تفاصيل بنود محفوظة لفاتورة المورد." }
    var expectedExact = BigDecimal.ZERO
    var invoiceLineTotal = 0L
    invoice.lines.forEach { line ->
        val quantity = line.quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("توجد كمية غير صالحة في بنود فاتورة المورد.")
        require(quantity.signum() > 0) { "توجد كمية صفرية أو سالبة في فاتورة المورد." }
        val baseline = line.purchaseOrderUnitCostMinor
            ?: throw IllegalArgumentException("تعذر ربط سعر أساس أمر الشراء بأحد بنود فاتورة المورد.")
        require(baseline >= 0L && line.unitPriceMinor >= 0L && line.lineTotalMinor >= 0L) {
            "توجد قيمة سعر أو إجمالي بند غير صالحة."
        }
        expectedExact = expectedExact.add(quantity.multiply(BigDecimal.valueOf(baseline)))
        invoiceLineTotal = Math.addExact(invoiceLineTotal, line.lineTotalMinor)
    }
    require(invoiceLineTotal == invoice.subtotalMinor) {
        "مجموع بنود فاتورة المورد لا يطابق الإجمالي الفرعي المحفوظ؛ تم حجب فرق السعر."
    }
    val expected = expectedExact.setScale(0, RoundingMode.HALF_UP).longValueExact()
    val headerTotal = Math.addExact(Math.addExact(invoice.subtotalMinor, invoice.taxMinor), invoice.freightMinor)
    require(headerTotal == invoice.totalMinor) {
        "إجمالي فاتورة المورد لا يطابق البنود والضريبة والشحن؛ تم حجب فرق السعر."
    }
    return SupplierInvoicePriceVarianceReportTotals(
        expectedSubtotalMinor = expected,
        invoiceSubtotalMinor = invoice.subtotalMinor,
        varianceMinor = Math.subtractExact(invoice.subtotalMinor, expected)
    )
}

