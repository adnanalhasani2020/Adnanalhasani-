package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode

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
        require(quantity.signum() > 0 && quantity.stripTrailingZeros().scale() <= 0 && line.lineTotalMinor >= 0L) {
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
