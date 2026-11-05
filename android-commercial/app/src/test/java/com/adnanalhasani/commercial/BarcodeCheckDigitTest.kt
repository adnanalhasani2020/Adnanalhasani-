package com.adnanalhasani.commercial

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeCheckDigitTest {
    @Test fun acceptsKnownValidEan13() {
        assertTrue(isValidGtinCheckDigit("4006381333931"))
    }

    @Test fun acceptsKnownValidUpcA() {
        assertTrue(isValidGtinCheckDigit("036000291452"))
    }

    @Test fun rejectsInvalidCheckDigit() {
        assertFalse(isValidGtinCheckDigit("4006381333932"))
    }

    @Test fun rejectsUnsupportedLengthAndNonNumericValues() {
        assertFalse(isValidGtinCheckDigit("1234567"))
        assertFalse(isValidGtinCheckDigit("1234567A"))
    }
}
