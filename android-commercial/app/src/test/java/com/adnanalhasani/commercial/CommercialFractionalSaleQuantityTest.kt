package com.adnanalhasani.commercial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommercialFractionalSaleQuantityTest {
    @Test
    fun formatsFractionalInventoryAndComparesReorderThresholdPrecisely() {
        assertEquals("0.5", formatCommercialStockQuantity(BigDecimal("0.5000")))
        assertEquals("1.25", formatCommercialStockQuantity(BigDecimal("1.2500")))
        assertEquals("2", formatCommercialStockQuantity(BigDecimal("2.000")))
        assertEquals("0", formatCommercialStockQuantity(BigDecimal.ZERO))

        assertEquals(false, isCommercialStockLow(BigDecimal("9.5"), 9))
        assertEquals(true, isCommercialStockLow(BigDecimal("9.0001"), 10))
        assertEquals(true, isCommercialStockLow(BigDecimal("9"), 9))
        assertEquals(true, isCommercialStockLow(BigDecimal("0.5"), 9))
    }

    @Test
    fun formatsCloudOperationalAmountsUsingTheCurrencyMinorUnitScale() {
        assertEquals("1,234.56 USD", formatCommercialOperationalAmount(123456L, "USD", 2))
        assertEquals("1.234 KWD", formatCommercialOperationalAmount(1234L, "KWD", 3))
        assertEquals("1.2345 CLF", formatCommercialOperationalAmount(12345L, "CLF", 4))
        assertEquals("12.34 YER", formatCommercialOperationalAmount(1234L, "YER", 2))
        assertEquals("1,234 JPY", formatCommercialOperationalAmount(1234L, "JPY", 0))
        assertEquals("1234 وحدة نقدية صغرى", formatCommercialOperationalAmount(1234L, "", -1))
    }

    @Test
    fun parsesDecimalAndLocalizedSellingQuantities() {
        assertEquals(BigDecimal("0.5"), parseCommercialCheckoutQuantity("0.5"))
        assertEquals(BigDecimal("1.25"), parseCommercialCheckoutQuantity("1,25"))
        assertEquals(BigDecimal("0.5"), parseCommercialCheckoutQuantity(".5"))
        assertEquals(BigDecimal("1.25"), parseCommercialCheckoutQuantity("١.٢٥"))
        assertEquals(BigDecimal("0.75"), parseCommercialCheckoutQuantity("٠٫٧٥"))
    }

    @Test
    fun rejectsInvalidOrOverPreciseSellingQuantities() {
        assertNull(parseCommercialCheckoutQuantity("0"))
        assertNull(parseCommercialCheckoutQuantity("-0.5"))
        assertNull(parseCommercialCheckoutQuantity("1.2345"))
        assertNull(parseCommercialCheckoutQuantity("100000.1"))
        assertNull(parseCommercialCheckoutQuantity("1e2"))
        assertNull(parseCommercialCheckoutQuantity("abc"))
    }

    @Test
    fun aggregatesFractionalQuantitiesWithoutFloatingPointRounding() {
        val lines = aggregateCommercialCheckoutLines(
            listOf(
                CommercialCloudCheckoutLine("offering-1", BigDecimal("0.5"), "kg"),
                CommercialCloudCheckoutLine("offering-1", BigDecimal("0.25"), "kg"),
                CommercialCloudCheckoutLine("offering-1", BigDecimal("1"), "each")
            )
        )
        assertEquals(2, lines.size)
        assertEquals(BigDecimal("0.75"), lines.first { it.unitCode == "kg" }.quantity)
        assertEquals(BigDecimal("1"), lines.first { it.unitCode == "each" }.quantity)
    }

    @Test
    fun returnMaximumUsesOnlyQuantitiesThatMapExactlyToBaseUnits() {
        assertEquals(BigDecimal("0.5"), maxCommercialReturnQuantity(500L, 1000L))
        assertEquals(BigDecimal("0.25"), maxCommercialReturnQuantity(5L, 12L))
        assertEquals(BigDecimal.ZERO, maxCommercialReturnQuantity(0L, 1000L))
    }

    @Test
    fun recoversReturnFactorFromTheImmutableSaleLineSnapshot() {
        assertEquals(1000L, commercialSavedSellingUnitFactor(500L, BigDecimal("0.5")))
        assertEquals(12L, commercialSavedSellingUnitFactor(12L, BigDecimal("1")))
        assertNull(commercialSavedSellingUnitFactor(5L, BigDecimal("2")))
        assertNull(commercialSavedSellingUnitFactor(0L, BigDecimal("0.5")))
    }

    @Test
    fun fractionalSellingQuantityMustResolveToWholeBaseStockUnits() {
        assertEquals(
            java.math.BigInteger("500"),
            BigDecimal("0.5").multiply(BigDecimal("1000")).toBigIntegerExact()
        )
        try {
            BigDecimal("0.5").multiply(BigDecimal.ONE).toBigIntegerExact()
            throw AssertionError("A half base unit must not be silently rounded.")
        } catch (_: ArithmeticException) {
            // The checkout must reject fractions that cannot map to whole stock units.
        }
    }
}
