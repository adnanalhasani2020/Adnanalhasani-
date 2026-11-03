package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.ZoneId
import java.util.Currency
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


internal data class ItemProfitSaleLineInput(
    val saleItemId: String,
    val saleId: String,
    val productName: String,
    val currencyCode: String,
    val saleTaxMinor: Long,
    val saleDiscountMinor: Long,
    val quantity: String,
    val lineTotalMinor: Long,
    val costTotalMinor: Long?,
    val inSelectedPeriod: Boolean
)

internal data class ItemProfitReturnLineInput(
    val saleItemId: String,
    val quantity: Int,
    val refundAmountMinor: Long,
    val refundAmountPresent: Boolean,
    val restockRequested: Boolean,
    val restockFlagPresent: Boolean,
    val restockedQuantity: Int,
    val restockedQuantityPresent: Boolean,
    val saleItemQuantityInStockUnits: Long
)

internal data class ItemProfitReturnInput(
    val returnId: String,
    val saleId: String,
    val currencyCode: String,
    val approvedRefundMinor: Long?,
    val items: List<ItemProfitReturnLineInput>
)

internal data class ItemProfitabilityTotals(
    val productName: String,
    val currencyCode: String,
    val soldQuantity: BigDecimal,
    val returnedQuantity: Int,
    val salesMinor: Long,
    val refundMinor: Long,
    val netRevenueMinor: Long,
    val soldCostMinor: Long,
    val returnedCostMinor: Long,
    val netCostMinor: Long,
    val grossProfitMinor: Long,
    val saleLineCount: Int
)

/**
 * Item-level gross profitability for the selected period.
 * Completed returns are assigned to their original sale item and currency.
 * Returns can refer to sales from earlier periods, so all sale lines are supplied;
 * only lines marked inSelectedPeriod contribute sales and COGS.
 */
internal fun calculateItemProfitability(
    saleLines: List<ItemProfitSaleLineInput>,
    returns: List<ItemProfitReturnInput>
): List<ItemProfitabilityTotals> {
    val saleById = linkedMapOf<String, ItemProfitSaleLineInput>()
    saleLines.forEach { line ->
        require(line.saleItemId.isNotBlank() && saleById.put(line.saleItemId, line) == null) {
            "معرف بند البيع مفقود أو مكرر؛ تعذر ربط المرتجعات بالصنف الصحيح."
        }
        require(line.saleId.isNotBlank() && line.productName.isNotBlank() &&
            line.currencyCode.matches(Regex("^[A-Z]{3}$")) &&
            line.saleTaxMinor >= 0L && line.saleDiscountMinor >= 0L) {
            "اسم الصنف أو عملة بند البيع غير صالحة."
        }
        val quantity = line.quantity.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("توجد كمية بيع غير صالحة في تقرير ربحية الأصناف.")
        require(quantity.signum() > 0 && line.lineTotalMinor >= 0L) {
            "توجد كمية أو قيمة بيع غير صالحة في تقرير ربحية الأصناف."
        }
        if (line.inSelectedPeriod) {
            require(line.saleDiscountMinor == 0L) {
                "توجد خصومات إجمالية على فواتير البيع بلا توزيع محفوظ على البنود؛ تم حجب ربحية الأصناف."
            }
            require(line.costTotalMinor != null && line.costTotalMinor >= 0L) {
                "توجد تكلفة تاريخية مفقودة لأحد بنود البيع في الفترة؛ تم حجب ربحية الأصناف."
            }
        }
    }

    val returnIds = mutableSetOf<String>()
    val refundedBySaleItem = mutableMapOf<String, Long>()
    val restockedBySaleItem = mutableMapOf<String, Long>()
    val returnedQuantityBySaleItem = mutableMapOf<String, BigDecimal>()
    returns.forEach { saleReturn ->
        require(saleReturn.returnId.isNotBlank() && returnIds.add(saleReturn.returnId)) {
            "معرف مرتجع مفقود أو مكرر في تقرير ربحية الأصناف."
        }
        require(saleReturn.saleId.isNotBlank() && saleReturn.currencyCode.matches(Regex("^[A-Z]{3}$"))) {
            "عملة المرتجع غير صالحة في تقرير ربحية الأصناف."
        }
        val approved = saleReturn.approvedRefundMinor
            ?: throw IllegalArgumentException("يوجد مرتجع مكتمل بلا مبلغ اعتماد محفوظ؛ تم حجب ربحية الأصناف.")
        require(approved >= 0L && saleReturn.items.isNotEmpty()) {
            "بيانات المرتجع المكتمل غير مكتملة أو تحتوي مبلغًا سالبًا."
        }
        val itemRefundTotal = sumReportAmountsExactly(saleReturn.items.map { it.refundAmountMinor })
        require(itemRefundTotal == approved) {
            "مجموع مبالغ بنود المرتجع لا يطابق مبلغ الاعتماد؛ تم حجب ربحية الأصناف."
        }
        saleReturn.items.forEach { item ->
            val saleLine = saleById[item.saleItemId]
                ?: throw IllegalArgumentException("تعذر ربط بند مرتجع ببند البيع الأصلي.")
            require(saleLine.saleId == saleReturn.saleId &&
                saleLine.currencyCode == saleReturn.currencyCode &&
                item.refundAmountPresent && item.restockFlagPresent && item.restockedQuantityPresent &&
                item.refundAmountMinor >= 0L && item.quantity > 0) {
                "مرجع البيع أو عملة أو كمية أو مبلغ/حالة إعادة تخزين بند المرتجع غير مكتملة."
            }
            require(item.restockedQuantity >= 0 &&
                (item.restockedQuantity == 0 || (item.restockFlagPresent && item.restockRequested))) {
                "حالة إعادة التخزين لا تتطابق مع الكمية المسجلة؛ تم حجب ربحية الأصناف."
            }
            require(saleLine.saleTaxMinor == 0L) {
                "توجد مرتجعات لفواتير ضريبية ولا توجد حصة ضريبية مفصلة لكل بند؛ تم حجب ربحية الأصناف."
            }
            require(saleLine.saleDiscountMinor == 0L) {
                "يرتبط المرتجع بفاتورة لها خصم إجمالي غير موزع على البنود؛ تم حجب ربحية الأصناف."
            }
            val cumulativeRefund = Math.addExact(refundedBySaleItem[item.saleItemId] ?: 0L, item.refundAmountMinor)
            require(cumulativeRefund <= saleLine.lineTotalMinor) {
                "إجمالي مبالغ المرتجعات يتجاوز قيمة بند البيع الأصلي؛ تم حجب ربحية الأصناف."
            }
            refundedBySaleItem[item.saleItemId] = cumulativeRefund
            val originalQuantity = saleLine.quantity.toBigDecimal()
            val cumulativeReturnedQuantity = (returnedQuantityBySaleItem[item.saleItemId] ?: BigDecimal.ZERO)
                .add(BigDecimal.valueOf(item.quantity.toLong()))
            require(cumulativeReturnedQuantity <= originalQuantity) {
                "إجمالي كمية المرتجعات يتجاوز كمية بند البيع الأصلي."
            }
            returnedQuantityBySaleItem[item.saleItemId] = cumulativeReturnedQuantity
            val cumulativeRestocked = Math.addExact(
                restockedBySaleItem[item.saleItemId] ?: 0L, item.restockedQuantity.toLong()
            )
            if (item.restockedQuantity > 0) {
                require(item.saleItemQuantityInStockUnits > 0L &&
                    cumulativeRestocked <= item.saleItemQuantityInStockUnits) {
                    "إجمالي الكمية المعاد تخزينها يتجاوز كمية وحدات المخزون الأصلية."
                }
            }
            restockedBySaleItem[item.saleItemId] = cumulativeRestocked
            // Validate each returned-cost slice now; totals are recomputed per return line below.
            calculateRestockedCostMinor(
                saleLine.costTotalMinor,
                item.restockedQuantity,
                item.saleItemQuantityInStockUnits
            )
        }
    }

    val includedSales = saleLines.filter { it.inSelectedPeriod }
    val productKeys = (includedSales.map { it.productName to it.currencyCode } +
        returns.flatMap { saleReturn ->
            saleReturn.items.map { item ->
                val saleLine = saleById.getValue(item.saleItemId)
                saleLine.productName to saleLine.currencyCode
            }
        }).distinct()
    return productKeys.map { key ->
        val productSales = includedSales.filter { it.productName == key.first && it.currencyCode == key.second }
        val itemIds = saleLines.filter { it.productName == key.first && it.currencyCode == key.second }
            .map { it.saleItemId }.toSet()
        val productReturns = returns.flatMap { it.items }.filter { it.saleItemId in itemIds }
        val salesMinor = sumReportAmountsExactly(productSales.map { it.lineTotalMinor })
        val soldCostMinor = sumReportAmountsExactly(productSales.map { it.costTotalMinor!! })
        val refundMinor = sumReportAmountsExactly(productReturns.map { it.refundAmountMinor })
        val returnedCostMinor = sumReportAmountsExactly(productReturns.map { item ->
            val saleLine = saleById.getValue(item.saleItemId)
            calculateRestockedCostMinor(
                saleLine.costTotalMinor,
                item.restockedQuantity,
                item.saleItemQuantityInStockUnits
            )
        })
        val soldQuantity = productSales.fold(BigDecimal.ZERO) { total, line ->
            total.add(line.quantity.toBigDecimal())
        }
        val returnedQuantity = productReturns.fold(0) { total, item ->
            Math.addExact(total, item.quantity)
        }
        val netRevenue = Math.subtractExact(salesMinor, refundMinor)
        val netCost = Math.subtractExact(soldCostMinor, returnedCostMinor)
        ItemProfitabilityTotals(
            productName = key.first,
            currencyCode = key.second,
            soldQuantity = soldQuantity,
            returnedQuantity = returnedQuantity,
            salesMinor = salesMinor,
            refundMinor = refundMinor,
            netRevenueMinor = netRevenue,
            soldCostMinor = soldCostMinor,
            returnedCostMinor = returnedCostMinor,
            netCostMinor = netCost,
            grossProfitMinor = Math.subtractExact(netRevenue, netCost),
            saleLineCount = productSales.size
        )
    }.sortedWith(compareBy<ItemProfitabilityTotals> { it.currencyCode }.thenByDescending { it.grossProfitMinor }.thenBy { it.productName })
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



internal data class LocationInventoryBalanceInput(
    val locationId: String,
    val locationName: String,
    val locationType: String,
    val offeringId: String,
    val unitName: String,
    val quantityOnHand: java.math.BigDecimal,
    val quantityReserved: java.math.BigDecimal
)

internal data class LocationInventoryBalanceTotal(
    val locationId: String,
    val locationName: String,
    val locationType: String,
    val unitName: String,
    val offeringCount: Int,
    val quantityOnHand: java.math.BigDecimal,
    val quantityReserved: java.math.BigDecimal,
    val quantityAvailable: java.math.BigDecimal
)

/** Aggregates current balances by organization location and unit; never adds unlike units. */
internal fun calculateLocationInventoryBalanceTotals(
    balances: List<LocationInventoryBalanceInput>
): List<LocationInventoryBalanceTotal> {
    val seen = mutableSetOf<Pair<String, String>>()
    balances.forEach { balance ->
        require(balance.locationId.isNotBlank() && balance.offeringId.isNotBlank() &&
            seen.add(balance.locationId to balance.offeringId)) {
            "يوجد رصيد مخزون مفقود المعرف أو مكرر للموقع والصنف؛ تم حجب التقرير."
        }
        require(balance.locationName.isNotBlank() && balance.unitName.isNotBlank() &&
            balance.quantityReserved.signum() >= 0) {
            "توجد بيانات موقع أو وحدة أو كمية محجوزة غير صالحة؛ تم حجب التقرير."
        }
    }
    return balances.groupBy { balance ->
        Triple(balanceLocationKey(balance.locationId), balance.unitName.trim(), balance.locationType.trim())
    }.map { (key, rows) ->
        val onHand = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityOnHand) }
        val reserved = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityReserved) }
        LocationInventoryBalanceTotal(
            locationId = key.first,
            locationName = rows.first().locationName.trim(),
            locationType = key.third,
            unitName = key.second,
            offeringCount = rows.size,
            quantityOnHand = onHand,
            quantityReserved = reserved,
            quantityAvailable = onHand.subtract(reserved)
        )
    }.sortedWith(compareBy<LocationInventoryBalanceTotal> { it.locationName }
        .thenBy { it.locationType }.thenBy { it.unitName })
}

private fun balanceLocationKey(locationId: String): String = locationId.trim()

internal data class StockMovementUserInput(
    val movementId: String,
    val userId: String,
    val movementType: String,
    val unitName: String,
    val quantityDelta: java.math.BigDecimal
)

internal data class StockMovementUserTotal(
    val userId: String,
    val movementType: String,
    val unitName: String,
    val movementCount: Int,
    val quantityDelta: java.math.BigDecimal
)

/** Groups persisted stock movements by their recorded actor, movement type, and unit. */
internal fun calculateStockMovementUserTotals(
    movements: List<StockMovementUserInput>
): List<StockMovementUserTotal> {
    val seen = mutableSetOf<String>()
    movements.forEach { movement ->
        require(movement.movementId.isNotBlank() && seen.add(movement.movementId)) {
            "معرف حركة مخزون مفقود أو مكرر في تقرير المستخدمين."
        }
        require(movement.movementType.isNotBlank() && movement.quantityDelta.compareTo(java.math.BigDecimal.ZERO) != 0) {
            "توجد حركة مخزون بنوع أو كمية غير صالحة؛ تم حجب تقرير المستخدمين."
        }
    }
    return movements.groupBy {
        Triple(
            movementActorLabel(it.userId),
            it.movementType.trim(),
            it.unitName.trim().ifBlank { "وحدة غير محددة" }
        )
    }.map { (key, rows) ->
        StockMovementUserTotal(
            userId = key.first,
            movementType = key.second,
            unitName = key.third,
            movementCount = rows.size,
            quantityDelta = rows.fold(java.math.BigDecimal.ZERO) { total, row -> total.add(row.quantityDelta) }
        )
    }.sortedWith(
        compareBy<StockMovementUserTotal> { it.movementType }
            .thenBy { it.userId }
            .thenBy { it.unitName }
    )
}

private fun movementActorLabel(userId: String): String = userId.trim().ifBlank { "غير منسوب" }

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

private fun cloudDebtScale(currency: String): Int =
    runCatching { Currency.getInstance(currency).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)

internal fun cloudDebtInput(amountMinor: Long, currency: String): String =
    java.math.BigDecimal.valueOf(amountMinor).movePointLeft(cloudDebtScale(currency))
        .stripTrailingZeros().toPlainString()

internal fun parseMerchantPaymentRequestAmount(value: String, currency: String): Long? = runCatching {
    val amount = value.trim().replace(',', '.').toBigDecimal()
    require(amount > java.math.BigDecimal.ZERO)
    amount.movePointRight(cloudDebtScale(currency))
        .setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact()
}.getOrNull()

