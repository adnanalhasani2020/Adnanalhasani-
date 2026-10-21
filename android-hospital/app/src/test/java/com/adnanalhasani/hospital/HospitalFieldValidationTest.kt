package com.adnanalhasani.hospital

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HospitalFieldValidationTest {
    @Test
    fun normalizesInvoiceNumbersWithoutChangingInternalCharacters() {
        assertEquals("INV-001", HospitalFieldValidation.normalizeInvoiceNumber("  INV-001  "))
        assertEquals("INV 001", HospitalFieldValidation.normalizeInvoiceNumber("INV 001"))
        assertEquals("", HospitalFieldValidation.normalizeInvoiceNumber("   "))
    }

    @Test
    fun parsesInventoryQuantitiesWithoutSilentZeroFallback() {
        assertEquals(0.0, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("0"), 0.0)
        assertEquals(12.5, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull(" 12.5 "), 0.0)
        assertEquals(0.25, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("0.25"), 0.0)
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull(""))
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("abc"))
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("-1"))
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("NaN"))
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("Infinity"))
        assertEquals(null, HospitalFieldValidation.toNonNegativeFiniteDoubleOrNull("1e3"))
    }

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
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("1e2"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("1E2"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("1e-2"))
        assertFalse(HospitalFieldValidation.isValidMoneyAmount("999999999999999999999999"))
    }

    @Test
    fun acceptsOnlyTraceableNewLabSpecimensInInitialState() {
        val valid = LabSpecimenEntity(
            labOrderId = 1,
            specimenCode = "  LAB-001  ",
            specimenType = " Blood "
        )
        assertTrue(isValidLabSpecimenRegistration(valid, " lab-tech "))

        assertFalse(isValidLabSpecimenRegistration(valid.copy(specimenCode = "  "), "lab-tech"))
        assertFalse(isValidLabSpecimenRegistration(valid.copy(specimenType = ""), "lab-tech"))
        assertFalse(isValidLabSpecimenRegistration(valid.copy(status = "completed"), "lab-tech"))
        assertFalse(isValidLabSpecimenRegistration(valid, "  "))
    }

    @Test
    fun guardsLaboratorySpecimenTransitionsAndTerminalStates() {
        assertTrue(isAllowedLabSpecimenTransition("collected", "collected", "received", "tech"))
        assertTrue(isAllowedLabSpecimenTransition("received", "received", "processing", "tech"))
        assertTrue(isAllowedLabSpecimenTransition("processing", "processing", "completed", "tech"))
        assertTrue(isAllowedLabSpecimenTransition("collected", "collected", "processing", "tech", requireReceipt = false))
        assertTrue(isAllowedLabSpecimenTransition("received", "received", "rejected", "tech", rejectionReason = "sample mismatch"))

        assertFalse(isAllowedLabSpecimenTransition("collected", "received", "processing", "tech"))
        assertFalse(isAllowedLabSpecimenTransition("collected", "collected", "processing", "tech"))
        assertFalse(isAllowedLabSpecimenTransition("collected", "collected", "rejected", "tech"))
        assertFalse(isAllowedLabSpecimenTransition("processing", "processing", "completed", " "))
        assertFalse(isAllowedLabSpecimenTransition("completed", "completed", "processing", "tech"))
        assertFalse(isAllowedLabSpecimenTransition("rejected", "rejected", "received", "tech"))
        assertFalse(isAllowedLabSpecimenTransition("received", "received", "unknown", "tech"))
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
