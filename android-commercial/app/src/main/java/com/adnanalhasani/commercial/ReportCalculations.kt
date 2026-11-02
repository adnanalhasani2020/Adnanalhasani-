package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

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

internal data class CostProfitSaleLineInput(val costTotalMinor: Long?)
internal data class CostProfitSaleInput(
    val currencyCode: String,
    val subtotalMinor: Long,
    val discountMinor: Long,
    val lines: List<CostProfitSaleLineInput>
)
internal data class CostProfitReturnItemInput(
    val saleItemCostTotalMinor: Long?,
    val restockedQuantity: Int,
    val saleItemQuantityInStockUnits: Long
)
internal data class CostProfitReturnInput(
    val currencyCode: String,
    val approvedRefundMinor: Long?,
    val items: List<CostProfitReturnItemInput>
)

internal data class CostProfitReportTotals(
    val currencyCode: String,
    val grossSalesMinor: Long,
    val returnRevenueMinor: Long,
    val netRevenueMinor: Long,
    val soldCostMinor: Long,
    val returnedCostMinor: Long,
    val netCostMinor: Long,
    val grossProfitMinor: Long,
    val invoiceCount: Int
)

internal fun sumReportAmountsExactly(values: Iterable<Long>): Long =
    values.fold(0L) { total, value -> Math.addExact(total, value) }

internal fun calculateRestockedCostMinor(
    saleItemCostTotalMinor: Long?,
    restockedQuantity: Int,
    saleItemQuantityInStockUnits: Long
): Long {
    require(restockedQuantity >= 0) { "كمية إعادة التخزين لا يمكن أن تكون سالبة." }
    if (restockedQuantity == 0) return 0L
    val cost = saleItemCostTotalMinor
        ?: throw IllegalArgumentException("تكلفة بند البيع التاريخية غير متاحة للكمية المعادة للمخزون.")
    require(cost >= 0L && saleItemQuantityInStockUnits > 0L &&
        restockedQuantity.toLong() <= saleItemQuantityInStockUnits) {
        "كمية أو تكلفة إعادة التخزين غير صالحة."
    }
    return BigDecimal.valueOf(cost)
        .multiply(BigDecimal.valueOf(restockedQuantity.toLong()))
        .divide(BigDecimal.valueOf(saleItemQuantityInStockUnits), 0, RoundingMode.HALF_UP)
        .longValueExact()
}

internal fun calculateCostProfitReportTotals(
    periodSales: List<CostProfitSaleInput>,
    periodReturns: List<CostProfitReturnInput>
): List<CostProfitReportTotals> {
    val currencies = (periodSales.map { it.currencyCode } + periodReturns.map { it.currencyCode })
    require(currencies.all { it.matches(Regex("^[A-Z]{3}$")) }) {
        "توجد مبيعات أو مرتجعات بعملة غير صالحة."
    }
    return currencies.distinct().sorted().map { currency ->
        val salesRows = periodSales.filter { it.currencyCode == currency }
        val returnRows = periodReturns.filter { it.currencyCode == currency }
        val grossSales = sumReportAmountsExactly(salesRows.map { sale ->
            require(sale.subtotalMinor >= 0L && sale.discountMinor >= 0L && sale.discountMinor <= sale.subtotalMinor) {
                "خصم الفاتورة يتجاوز إجماليها الفرعي أو يحتوي مبلغًا غير صالح."
            }
            Math.subtractExact(sale.subtotalMinor, sale.discountMinor)
        })
        val returnRevenue = sumReportAmountsExactly(returnRows.map { saleReturn ->
            saleReturn.approvedRefundMinor?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("مبلغ المرتجع المعتمد غير متاح أو غير صالح.")
        })
        val soldCost = sumReportAmountsExactly(salesRows.flatMap { sale ->
            require(sale.lines.isNotEmpty()) { "توجد فاتورة مكتملة بلا بنود." }
            sale.lines.map { line ->
                line.costTotalMinor?.takeIf { it >= 0L }
                    ?: throw IllegalArgumentException("تكلفة بند البيع التاريخية غير متاحة أو غير صالحة.")
            }
        })
        val returnedCost = sumReportAmountsExactly(returnRows.flatMap { saleReturn ->
            saleReturn.items.map { item ->
                calculateRestockedCostMinor(
                    item.saleItemCostTotalMinor,
                    item.restockedQuantity,
                    item.saleItemQuantityInStockUnits
                )
            }
        })
        // Returns in this period may refer to sales from an earlier period;
        // negative net revenue or net COGS is therefore possible and must not be
        // silently clamped or treated as invalid.
        val netRevenue = Math.subtractExact(grossSales, returnRevenue)
        val netCost = Math.subtractExact(soldCost, returnedCost)
        CostProfitReportTotals(
            currencyCode = currency,
            grossSalesMinor = grossSales,
            returnRevenueMinor = returnRevenue,
            netRevenueMinor = netRevenue,
            soldCostMinor = soldCost,
            returnedCostMinor = returnedCost,
            netCostMinor = netCost,
            grossProfitMinor = Math.subtractExact(netRevenue, netCost),
            invoiceCount = salesRows.size
        )
    }
}

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

private fun reportCalculationDateMillis(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching {
            LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()

internal fun buildCustomerOperationalStatementMovements(
    sales: List<CustomerStatementInvoiceInput>
): List<ReportCustomerMovement> = buildList {
    sales.filter { it.customerId.isNotBlank() }.forEach { doc ->
        require(doc.totalMinor >= 0L && doc.currencyCode.matches(Regex("^[A-Z]{3}$"))) {
            "فاتورة عميل بمبلغ أو عملة غير صالحة."
        }
        require(reportCalculationDateMillis(doc.createdAt) != null) { "تاريخ فاتورة العميل غير صالح." }
        val customerName = doc.customerName.ifBlank { "عميل غير محدد" }
        add(ReportCustomerMovement(
            doc.createdAt.take(10), doc.customerId, customerName, doc.currencyCode,
            "فاتورة #${doc.receiptNumber}", doc.totalMinor, "مدين · فاتورة مكتملة"
        ))
        doc.payments.filter {
            it.status.lowercase(Locale.ROOT) in setOf("succeeded", "partially_refunded", "refunded")
        }.forEach { payment ->
            require(payment.amountMinor > 0L && reportCalculationDateMillis(payment.createdAt) != null) {
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
            require(approved >= 0L && reportCalculationDateMillis(saleReturn.updatedAt) != null) {
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
