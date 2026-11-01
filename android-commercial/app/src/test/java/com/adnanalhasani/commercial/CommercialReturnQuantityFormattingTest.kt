package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class CommercialReturnQuantityFormattingTest {
    @Test
    fun preservesFractionalSellingQuantity() {
        assertEquals("1.25", formatCommercialReturnSoldQuantity("1.2500", 1))
        assertEquals("0.125", formatCommercialReturnSoldQuantity("0.125", 1))
    }

    @Test
    fun normalizesWholeQuantitiesWithoutDecimalNoise() {
        assertEquals("3", formatCommercialReturnSoldQuantity("3.000", 1))
    }

    @Test
    fun fallsBackForMissingInvalidOrNonPositiveValues() {
        assertEquals("4", formatCommercialReturnSoldQuantity(null, 4))
        assertEquals("4", formatCommercialReturnSoldQuantity("invalid", 4))
        assertEquals("4", formatCommercialReturnSoldQuantity("0", 4))
        assertEquals("0", formatCommercialReturnSoldQuantity("-2", 0))
    }
}
