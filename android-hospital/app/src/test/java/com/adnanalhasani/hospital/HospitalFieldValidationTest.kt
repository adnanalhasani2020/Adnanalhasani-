package com.adnanalhasani.hospital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HospitalFieldValidationTest {
    @Test
    fun acceptsValidDatesIncludingLeapDay() {
        assertTrue(HospitalFieldValidation.isValidDate("2024-02-29"))
        assertTrue(HospitalFieldValidation.isValidDate("2026-10-19"))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertFalse(HospitalFieldValidation.isValidDate("2026-02-29"))
        assertFalse(HospitalFieldValidation.isValidDate("2026-02-31"))
        assertFalse(HospitalFieldValidation.isValidDate("2026-13-01"))
    }

    @Test
    fun validatesDateAndTimeTogether() {
        assertTrue(HospitalFieldValidation.isValidDateTime("2026-10-19 23:59"))
        assertFalse(HospitalFieldValidation.isValidDateTime("2026-10-19 24:00"))
        assertFalse(HospitalFieldValidation.isValidDateTime("2026-02-31 10:30"))
    }

    @Test
    fun convertsMoneyExactlyToMinorUnits() {
        assertEquals(0L, HospitalFieldValidation.toMinorUnitsOrNull("0"))
        assertEquals(123L, HospitalFieldValidation.toMinorUnitsOrNull("1.23"))
        assertEquals(123L, HospitalFieldValidation.toMinorUnitsOrNull("1.2300"))
        assertEquals(100L, HospitalFieldValidation.toMinorUnitsOrNull("01.00"))
        assertTrue(HospitalFieldValidation.isValidMoneyAmount("999999999999.99"))
    }

    @Test
    fun rejectsInvalidMoneyPrecisionNegativesAndOverflow() {
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("-0.01"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("1.234"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("abc"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("999999999999999999999999"))
    }

    @Test
    fun validatesMonthAndTimeBoundaries() {
        assertTrue(HospitalFieldValidation.isValidMonth("2026-10"))
        assertFalse(HospitalFieldValidation.isValidMonth("2026-13"))
        assertTrue(HospitalFieldValidation.isValidTime("00:00"))
        assertTrue(HospitalFieldValidation.isValidTime("23:59"))
        assertFalse(HospitalFieldValidation.isValidTime("25:00"))
        assertFalse(HospitalFieldValidation.isValidTime("12:60"))
    }

    @Test
    fun rejectsNonCanonicalDateTimeMonthAndTimeFormats() {
        assertFalse(HospitalFieldValidation.isValidDate("2026-2-03"))
        assertFalse(HospitalFieldValidation.isValidDateTime("2026-10-19 9:05"))
        assertFalse(HospitalFieldValidation.isValidMonth("2026-2"))
        assertFalse(HospitalFieldValidation.isValidTime("9:05"))
        assertFalse(HospitalFieldValidation.isValidTime(" 09:05 "))
    }

    @Test
    fun trimsMoneyInputButRejectsBlankAndSubMinorPrecision() {
        assertEquals(120L, HospitalFieldValidation.toMinorUnitsOrNull(" 1.20 "))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount(" "))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("0.001"))
    }
}
