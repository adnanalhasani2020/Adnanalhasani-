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
    ) = CommercialCloudSaleDocument(
        id = id,
        customerId = "customer-1",
        receiptNumber = id,
        customerName = "عميل",
        customerPhone = "",
        customerEmail = "",
        totalMinor = subtotal,
        subtotalMinor = subtotal,
        discountMinor = discount,
        taxMinor = 0,
        saleStatus = "completed",
        paymentStatus = "paid",
        createdAt = "2026-11-02T10:00:00Z",
        currencyCode = currency,
        lines = listOf(CommercialCloudSaleLine("صنف", "2", 500, subtotal, cost, null)),
        payments = emptyList(),
        returns = emptyList()
    )

    private fun saleReturn(
        id: String = "return-1",
        approvedRefund: Long? = 100,
        restocked: Int = 1,
        originalQuantity: Long = 2,
        saleCost: Long? = 400,
        currency: String = "YER"
    ) = CommercialCloudSaleReturn(
        id = id,
        saleId = "sale-1",
        receiptNumber = "sale-1",
        customerName = "عميل",
        reasonCode = "customer_request",
        reasonNote = "",
        status = "completed",
        refundStatus = "completed",
        restockStatus = "completed",
        currencyCode = currency,
        requestedRefundMinor = approvedRefund ?: 0,
        approvedRefundMinor = approvedRefund,
        createdAt = "2026-11-02T11:00:00Z",
        completedAt = "2026-11-02T11:00:00Z",
        decisionNote = "",
        items = listOf(
            CommercialCloudReturnItem(
                id = "return-line-1",
                saleItemId = "sale-line-1",
                productName = "صنف",
                quantity = 1,
                refundAmountMinor = approvedRefund ?: 0,
                restock = restocked > 0,
                restockedQuantity = restocked,
                conditionCode = "resalable",
                note = "",
                saleItemQuantity = originalQuantity.toInt(),
                saleItemQuantityInStockUnits = originalQuantity,
                saleItemCostTotalMinor = saleCost,
                saleItemUnitCostMinor = null
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
