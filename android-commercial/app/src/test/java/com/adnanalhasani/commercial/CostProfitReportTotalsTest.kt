package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CostProfitReportTotalsTest {
    private fun sale(
        id: String = "sale-1",
        subtotal: Long = 1_000,
        discount: Long = 100,
        cost: Long? = 500,
        currency: String = "YER"
    ) = CostProfitSaleInput(
        currencyCode = currency,
        subtotalMinor = subtotal,
        discountMinor = discount,
        lines = listOf(CostProfitSaleLineInput(cost))
    )

    private fun saleReturn(
        id: String = "return-1",
        approvedRefund: Long? = 100,
        restocked: Int = 1,
        originalQuantity: Long = 2,
        saleCost: Long? = 400,
        currency: String = "YER"
    ) = CostProfitReturnInput(
        currencyCode = currency,
        approvedRefundMinor = approvedRefund,
        items = listOf(
            CostProfitReturnItemInput(
                saleItemCostTotalMinor = saleCost,
                restockedQuantity = restocked,
                saleItemQuantityInStockUnits = originalQuantity
            )
        )
    )

    @Test
    fun calculatesNetGrossProfitFromSavedCostsAndRestockedReturns() {
        val result = calculateCostProfitReportTotals(
            listOf(sale()),
            listOf(saleReturn())
        ).single()
        assertEquals(900L, result.grossSalesMinor)
        assertEquals(100L, result.returnRevenueMinor)
        assertEquals(800L, result.netRevenueMinor)
        assertEquals(500L, result.soldCostMinor)
        assertEquals(200L, result.returnedCostMinor)
        assertEquals(300L, result.netCostMinor)
        assertEquals(500L, result.grossProfitMinor)
    }

    @Test
    fun allowsReturnsFromEarlierPeriodsToProduceNegativePeriodNetAmounts() {
        val result = calculateCostProfitReportTotals(
            emptyList(),
            listOf(saleReturn(approvedRefund = 1_200, restocked = 1, originalQuantity = 1, saleCost = 500))
        ).single()
        assertEquals(-1_200L, result.netRevenueMinor)
        assertEquals(-500L, result.netCostMinor)
        assertEquals(-700L, result.grossProfitMinor)
    }

    @Test
    fun roundsReturnedCostOnceAndDoesNotDefaultMissingCostToZero() {
        assertEquals(33L, calculateRestockedCostMinor(100, 1, 3))
        assertThrows(IllegalArgumentException::class.java) {
            calculateRestockedCostMinor(null, 1, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateRestockedCostMinor(100, 4, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateRestockedCostMinor(100, -1, 3)
        }
    }

    @Test
    fun rejectsInvalidDiscountsAndCurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateCostProfitReportTotals(listOf(sale(subtotal = 100, discount = 101)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateCostProfitReportTotals(listOf(sale(currency = "YER1")), emptyList())
        }
    }

    @Test
    fun rejectsOverflowInsteadOfWrappingTotals() {
        assertThrows(ArithmeticException::class.java) {
            calculateCostProfitReportTotals(
                listOf(
                    sale(id = "large", subtotal = 0, discount = 0, cost = Long.MAX_VALUE),
                    sale(id = "overflow", subtotal = 0, discount = 0, cost = 1)
                ),
                emptyList()
            )
        }
    }
}
