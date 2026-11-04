package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportCurrencyTotalsTest {
    @Test
    fun groupsSummaryAmountsByCurrencyWithoutMixingCurrencies() {
        assertEquals(
            mapOf("USD" to 350L, "YER" to 700L),
            calculateReportCurrencyTotals(
                listOf(
                    "YER" to 250L,
                    "USD" to 100L,
                    "YER" to 450L,
                    "USD" to 250L
                )
            )
        )
    }

    @Test
    fun preservesFallbackCurrencyLabel() {
        assertEquals(
            mapOf("غير محددة" to 75L),
            calculateReportCurrencyTotals(listOf("" to 25L, "غير محددة" to 50L))
        )
    }

    @Test
    fun rejectsCurrencyTotalOverflowInsteadOfWrapping() {
        try {
            calculateReportCurrencyTotals(
                listOf("YER" to Long.MAX_VALUE, "YER" to 1L)
            )
            throw AssertionError("Expected exact aggregation to reject overflow")
        } catch (_: ArithmeticException) {
            // Expected: callers hide the summary and expose a source-level error.
        }
    }
}
