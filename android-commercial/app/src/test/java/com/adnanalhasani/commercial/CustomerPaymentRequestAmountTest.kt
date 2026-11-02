package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomerPaymentRequestAmountTest {
    @Test
    fun parsesMerchantAmountInCurrencyMajorUnits() {
        assertEquals(1250L, parseMerchantPaymentRequestAmount("12.50", "USD"))
        assertEquals(1250L, parseMerchantPaymentRequestAmount("12,50", "USD"))
        assertEquals(500L, parseMerchantPaymentRequestAmount("500", "YER"))
        assertNull(parseMerchantPaymentRequestAmount("0.00", "USD"))
    }

    @Test
    fun rejectsPrecisionThatCannotBeRepresentedInCurrencyMinorUnits() {
        assertNull(parseMerchantPaymentRequestAmount("12.501", "USD"))
        assertNull(parseMerchantPaymentRequestAmount("1.1", "JPY"))
        assertNull(parseMerchantPaymentRequestAmount("-1", "USD"))
        assertNull(parseMerchantPaymentRequestAmount("abc", "USD"))
    }
}
