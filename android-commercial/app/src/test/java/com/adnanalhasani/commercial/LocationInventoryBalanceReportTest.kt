package com.adnanalhasani.commercial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocationInventoryBalanceReportTest {
    private fun balance(
        location: String = "loc-a",
        locationName: String = "المستودع أ",
        offering: String,
        unit: String = "حبة",
        onHand: String,
        reserved: String = "0",
        type: String = "warehouse"
    ) = LocationInventoryBalanceInput(
        locationId = location,
        locationName = locationName,
        locationType = type,
        offeringId = offering,
        unitName = unit,
        quantityOnHand = BigDecimal(onHand),
        quantityReserved = BigDecimal(reserved)
    )

    @Test
    fun groupsByLocationAndUnitAndKeepsReservedAndAvailableSeparate() {
        val rows = calculateLocationInventoryBalanceTotals(
            listOf(
                balance(offering = "item-1", onHand = "10", reserved = "2"),
                balance(offering = "item-2", onHand = "5", reserved = "1"),
                balance(offering = "item-3", unit = "كجم", onHand = "4", reserved = "0"),
                balance(location = "loc-b", locationName = "الفرع ب", offering = "item-1", onHand = "7", reserved = "9")
            )
        )
        assertEquals(3, rows.size)
        val each = rows.single { it.locationId == "loc-a" && it.unitName == "حبة" }
        assertEquals(2, each.offeringCount)
        assertEquals(BigDecimal("15"), each.quantityOnHand)
        assertEquals(BigDecimal("3"), each.quantityReserved)
        assertEquals(BigDecimal("12"), each.quantityAvailable)
        val kg = rows.single { it.locationId == "loc-a" && it.unitName == "كجم" }
        assertEquals(BigDecimal("4"), kg.quantityAvailable)
        val overReserved = rows.single { it.locationId == "loc-b" }
        assertEquals(BigDecimal("-2"), overReserved.quantityAvailable)
    }

    @Test
    fun rejectsDuplicateLocationOfferingAndNegativeReservedQuantity() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateLocationInventoryBalanceTotals(
                listOf(balance(offering = "duplicate", onHand = "1"), balance(offering = "duplicate", onHand = "2"))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateLocationInventoryBalanceTotals(listOf(balance(offering = "bad", onHand = "1", reserved = "-1")))
        }
    }
}
