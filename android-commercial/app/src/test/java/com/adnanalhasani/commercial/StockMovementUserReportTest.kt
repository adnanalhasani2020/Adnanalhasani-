package com.adnanalhasani.commercial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StockMovementUserReportTest {
    private fun movement(
        id: String,
        userId: String = "user-a",
        type: String = "sale",
        unit: String = "حبة",
        quantity: String = "-1"
    ) = StockMovementUserInput(id, userId, type, unit, BigDecimal(quantity))

    @Test
    fun groupsByRecordedActorTypeAndUnitWithoutMixingUnits() {
        val rows = calculateStockMovementUserTotals(
            listOf(
                movement("1", quantity = "-2"),
                movement("2", quantity = "-3"),
                movement("3", userId = "user-b", quantity = "-1"),
                movement("4", unit = "كجم", quantity = "-4"),
                movement("5", userId = "", quantity = "7", type = "receipt")
            )
        )
        assertEquals(4, rows.size)
        val userAEach = rows.single { it.userId == "user-a" && it.unitName == "حبة" }
        assertEquals(2, userAEach.movementCount)
        assertEquals(BigDecimal("-5"), userAEach.quantityDelta)
        assertEquals(BigDecimal("-4"), rows.single { it.unitName == "كجم" }.quantityDelta)
        assertEquals("غير منسوب", rows.single { it.userId == "غير منسوب" }.userId)
    }

    @Test
    fun rejectsDuplicateIdsBlankTypesAndZeroQuantities() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateStockMovementUserTotals(listOf(movement("same"), movement("same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateStockMovementUserTotals(listOf(movement("bad-type", type = " ")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateStockMovementUserTotals(listOf(movement("zero", quantity = "0")))
        }
    }
}
