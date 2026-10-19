package com.adnanalhasani.hospital

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
    fun validatesMonthAndTimeBoundaries() {
        assertTrue(HospitalFieldValidation.isValidMonth("2026-10"))
        assertFalse(HospitalFieldValidation.isValidMonth("2026-13"))
        assertTrue(HospitalFieldValidation.isValidTime("00:00"))
        assertTrue(HospitalFieldValidation.isValidTime("23:59"))
        assertFalse(HospitalFieldValidation.isValidTime("25:00"))
        assertFalse(HospitalFieldValidation.isValidTime("12:60"))
    }
}
