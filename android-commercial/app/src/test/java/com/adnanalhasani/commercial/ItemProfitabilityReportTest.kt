package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ItemProfitabilityReportTest {
    private fun sale(
        id: String = "sale-item-1",
        quantity: String = "2",
        lineTotal: Long = 2_000,
        cost: Long? = 1_000,
        inPeriod: Boolean = true
    ) = ItemProfitSaleLineInput(id, "صنف أ", "YER", quantity, lineTotal, cost, inPeriod)

    private fun returned(
        id: String = "return-1",
        approved: Long? = 1_000,
        saleItemId: String = "sale-item-1",
        refund: Long = 1_000,
        restocked: Int = 1
    ) = ItemProfitReturnInput(
        returnId = id,
        currencyCode = "YER",
        approvedRefundMinor = approved,
        items = listOf(ItemProfitReturnLineInput(
            saleItemId = saleItemId,
            quantity = 1,
            refundAmountMinor = refund,
            restockedQuantity = restocked,
            saleItemQuantityInStockUnits = 2
        ))
    )

    @Test
    fun computesItemGrossProfitAfterApprovedReturnAndRestockedCost() {
        val row = calculateItemProfitability(listOf(sale()), listOf(returned())).single()
        assertEquals(2_000L, row.salesMinor)
        assertEquals(1_000L, row.refundMinor)
        assertEquals(1_000L, row.netRevenueMinor)
        assertEquals(1_000L, row.soldCostMinor)
        assertEquals(500L, row.returnedCostMinor)
        assertEquals(500L, row.netCostMinor)
        assertEquals(500L, row.grossProfitMinor)
        assertEquals("2", row.soldQuantity.stripTrailingZeros().toPlainString())
        assertEquals(1, row.returnedQuantity)
    }

    @Test
    fun includesReturnForEarlierSaleWithoutCountingEarlierSaleRevenueAgain() {
        val row = calculateItemProfitability(
            listOf(sale(inPeriod = false)),
            listOf(returned())
        ).single()
        assertEquals(0L, row.salesMinor)
        assertEquals(1_000L, row.refundMinor)
        assertEquals(0L, row.soldCostMinor)
        assertEquals(500L, row.returnedCostMinor)
        assertEquals(-500L, row.grossProfitMinor)
    }

    @Test
    fun sumsMultiplePartialReturnsWithoutRepeatingOrLosingCost() {
        val returns = listOf(
            returned(id = "return-1", approved = 500, refund = 500),
            returned(id = "return-2", approved = 500, refund = 500)
        )
        val row = calculateItemProfitability(listOf(sale()), returns).single()
        assertEquals(1_000L, row.refundMinor)
        assertEquals(1_000L, row.returnedCostMinor)
        assertEquals(0L, row.netCostMinor)
        assertEquals(1_000L, row.grossProfitMinor)
    }

    @Test
    fun rejectsMissingHistoricalCostForSelectedPeriod() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateItemProfitability(listOf(sale(cost = null)), emptyList())
        }
    }

    @Test
    fun rejectsReturnAmountThatDoesNotMatchApprovedHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateItemProfitability(listOf(sale()), listOf(returned(approved = 900, refund = 1_000)))
        }
    }

    @Test
    fun rejectsCumulativeRefundAndRestockAboveOriginalSaleLine() {
        val excessiveRefunds = listOf(
            returned(id = "return-1", approved = 1_100, refund = 1_100),
            returned(id = "return-2", approved = 1_100, refund = 1_100)
        )
        assertThrows(IllegalArgumentException::class.java) {
            calculateItemProfitability(listOf(sale()), excessiveRefunds)
        }

        val excessiveRestock = listOf(
            returned(id = "return-1", approved = 500, refund = 500, restocked = 2),
            returned(id = "return-2", approved = 500, refund = 500, restocked = 1)
        )
        assertThrows(IllegalArgumentException::class.java) {
            calculateItemProfitability(listOf(sale()), excessiveRestock)
        }
    }

    @Test
    fun rejectsReturnWithoutOriginalSaleItem() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateItemProfitability(listOf(sale()), listOf(returned(saleItemId = "missing")))
        }
    }
}
