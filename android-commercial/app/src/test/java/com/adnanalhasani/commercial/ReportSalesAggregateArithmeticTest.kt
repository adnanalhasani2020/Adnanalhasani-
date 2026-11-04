package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSalesAggregateArithmeticTest {
    @Test
    fun sumsSalesAmountsExactly() {
        assertEquals(350L, sumReportAmountsExactly(listOf(100L, 250L)))
    }

    @Test
    fun rejectsSalesAmountOverflow() {
        try {
            sumReportAmountsExactly(listOf(Long.MAX_VALUE, 1L))
            throw AssertionError("Expected exact sales aggregation to reject overflow")
        } catch (_: ArithmeticException) {
            // Expected: report preflight converts this into a fail-closed report error.
        }
    }

    @Test
    fun rejectsSalesQuantityCountOverflow() {
        try {
            sumReportCountsExactly(listOf(Int.MAX_VALUE, 1))
            throw AssertionError("Expected exact quantity aggregation to reject overflow")
        } catch (_: ArithmeticException) {
            // Expected: report preflight converts this into a fail-closed report error.
        }
    }
}
