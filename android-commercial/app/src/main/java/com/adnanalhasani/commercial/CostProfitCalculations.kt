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

