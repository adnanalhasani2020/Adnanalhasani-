package com.adnanalhasani.commercial

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CommercialFractionalSaleQuantityTest {
    @Test
    fun acceptsOnlyPositiveWholeSellingQuantitiesIncludingLocalizedDigits() {
        assertEquals(BigDecimal("1"), parseCommercialCheckoutQuantity("1"))
        assertEquals(BigDecimal("12"), parseCommercialCheckoutQuantity("١٢"))
        assertEquals(BigDecimal("100000"), parseCommercialCheckoutQuantity("100000"))
        listOf("0", "-1", "1.5", "0.25", "2.75", "1,5", "١٫٥", ".5", "1e2", "abc").forEach {
            assertNull("Must reject quantity: $it", parseCommercialCheckoutQuantity(it))
        }
    }

    @Test
    fun aggregatesOnlyWholeQuantitiesWithinTheSameSellingUnit() {
        val lines = aggregateCommercialCheckoutLines(
            listOf(
                CommercialCloudCheckoutLine("offering-1", BigDecimal("2"), "carton"),
                CommercialCloudCheckoutLine("offering-1", BigDecimal("3"), "carton"),
                CommercialCloudCheckoutLine("offering-1", BigDecimal("1"), "each")
            )
        )
        assertEquals(2, lines.size)
        assertEquals(BigDecimal("5"), lines.first { it.unitCode == "carton" }.quantity)
        assertEquals(BigDecimal("1"), lines.first { it.unitCode == "each" }.quantity)
        try {
            aggregateCommercialCheckoutLines(
                listOf(CommercialCloudCheckoutLine("offering-1", BigDecimal("1.5"), "each"))
            )
            fail("Fractional quantities must be rejected.")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun convertsWholeSellingUnitsWithIntegerFactorsAndNeverRounds() {
        assertEquals(java.math.BigInteger("24"), BigDecimal("2").multiply(BigDecimal("12")).toBigIntegerExact())
        assertEquals(java.math.BigInteger("1000"), BigDecimal("1").multiply(BigDecimal("1000")).toBigIntegerExact())
    }

    @Test
    fun returnMaximumUsesWholeOriginalSellingUnitsOnly() {
        assertEquals(BigDecimal("2"), maxCommercialReturnQuantity(24L, 12L))
        assertEquals(BigDecimal.ZERO, maxCommercialReturnQuantity(5L, 12L))
        assertEquals(BigDecimal.ZERO, maxCommercialReturnQuantity(0L, 12L))
    }

    @Test
    fun returnConversionFactorComesFromTheImmutableInvoiceSnapshot() {
        assertEquals(12L, commercialSavedSellingUnitFactor(24L, BigDecimal("2")))
        assertNull(commercialSavedSellingUnitFactor(5L, BigDecimal("2")))
        assertNull(commercialSavedSellingUnitFactor(24L, BigDecimal("1.5")))
    }

    @Test
    fun customerPortalLineTotalsRejectFractionalQuantitiesButKeepMoneyPrecision() {
        assertEquals(246L, commercialPortalLineTotalMinor(BigDecimal("2"), 123L))
        assertNull(commercialPortalLineTotalMinor(BigDecimal("0.5"), 123L))
        assertNull(commercialPortalLineTotalMinor(BigDecimal.ZERO, 100L))
        assertNull(commercialPortalLineTotalMinor(BigDecimal("-1"), 100L))
        assertNull(commercialPortalLineTotalMinor(BigDecimal.ONE, -1L))
    }

    @Test
    fun sumsCustomerPortalLineTotalsWithoutOverflowOrMissingValues() {
        assertEquals(300L, sumCommercialPortalLineTotalsMinor(listOf(100L, 200L)))
        assertNull(sumCommercialPortalLineTotalsMinor(emptyList()))
        assertNull(sumCommercialPortalLineTotalsMinor(listOf(100L, null)))
        assertNull(sumCommercialPortalLineTotalsMinor(listOf(Long.MAX_VALUE, 1L)))
    }

    @Test
    fun formatsOperationalAmountsWithoutChangingFinancialPrecision() {
        assertEquals("1,234.56 USD", formatCommercialOperationalAmount(123456L, "USD", 2))
        assertEquals("1.234 KWD", formatCommercialOperationalAmount(1234L, "KWD", 3))
        assertEquals("12.34 YER", formatCommercialOperationalAmount(1234L, "YER", 2))
        assertEquals("1,234 JPY", formatCommercialOperationalAmount(1234L, "JPY", 0))
    }
}
