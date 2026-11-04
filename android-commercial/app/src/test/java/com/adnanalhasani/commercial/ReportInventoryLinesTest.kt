package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportInventoryLinesTest {
    @Test
    fun locationReportMapsExactAvailableAndReservedQuantities() {
        val state = buildLocationInventoryReportState(
            listOf(
                CommercialCloudLocationInventoryBalance(
                    locationId = "location-1",
                    locationName = "المستودع الرئيسي",
                    locationType = "warehouse",
                    offeringId = "offering-1",
                    productName = "صنف",
                    unitName = "حبة",
                    quantityOnHand = BigDecimal("10.25"),
                    quantityReserved = BigDecimal("2.25")
                )
            ),
            loadError = ""
        )

        assertEquals("", state.error)
        assertEquals(1, state.lines.size)
        assertEquals("متاح 8 حبة · إجمالي 10.25 حبة", state.lines.single().displayAmount)
        assertTrue(state.lines.single().status.contains("محجوز 2.25 حبة"))
    }

    @Test
    fun stockMovementReportFiltersByPeriodAndPreservesFractionalQuantity() {
        val state = buildStockMovementUserReportState(
            movements = listOf(
                movement(id = "movement-1", date = "2026-11-02T10:00:00Z", quantity = "-1.25"),
                movement(id = "movement-2", date = "2026-10-31T10:00:00Z", quantity = "-9")
            ),
            startDate = LocalDate.parse("2026-11-01"),
            endDate = LocalDate.parse("2026-11-03"),
            cloudMode = true,
            loadError = ""
        )

        assertEquals("", state.error)
        assertEquals(1, state.lines.size)
        assertEquals("-1.25 حبة", state.lines.single().displayAmount)
        assertTrue(state.lines.single().status.contains("عدد الحركات: 1"))
    }

    @Test
    fun stockMovementReportFailsClosedForInvalidDatesAndLocalMode() {
        val invalidDate = buildStockMovementUserReportState(
            movements = listOf(movement(id = "bad-date", date = "not-a-date", quantity = "1")),
            startDate = LocalDate.parse("2026-11-01"),
            endDate = LocalDate.parse("2026-11-03"),
            cloudMode = true,
            loadError = ""
        )
        assertTrue(invalidDate.lines.isEmpty())
        assertTrue(invalidDate.error.isNotBlank())

        val local = buildStockMovementUserReportState(
            movements = emptyList(),
            startDate = null,
            endDate = null,
            cloudMode = false,
            loadError = ""
        )
        assertTrue(local.lines.isEmpty())
        assertTrue(local.error.contains("السحابي"))
    }

    @Test
    fun locationReportReturnsSourceErrorWithoutPartialRows() {
        val state = buildLocationInventoryReportState(emptyList(), "تعذر تحميل الأرصدة")
        assertTrue(state.lines.isEmpty())
        assertEquals("تعذر تحميل الأرصدة", state.error)
        assertFalse(state.error.isBlank())
    }

    private fun movement(id: String, date: String, quantity: String) =
        CommercialCloudStockMovement(
            id = id,
            offeringId = "offering-1",
            productName = "صنف",
            unitName = "حبة",
            movementType = "sale",
            quantityDelta = BigDecimal(quantity),
            sourceReference = "",
            saleId = "",
            saleReceiptNumber = "",
            saleStatus = "",
            createdBy = "user-1",
            createdAt = date
        )
}
