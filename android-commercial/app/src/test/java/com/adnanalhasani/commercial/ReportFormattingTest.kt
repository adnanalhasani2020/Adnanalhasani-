package com.adnanalhasani.commercial

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReportFormattingTest {
    @Test
    fun formatsCloudMinorUnitsUsingCurrencyFractionDigits() {
        assertEquals("123.45 USD", reportMoney(12345L, "USD", true))
        assertEquals("123 JPY", reportMoney(123L, "JPY", true))
    }

    @Test
    fun preservesLocalWholeUnitFormatting() {
        assertEquals("12345 YER", reportMoney(12345L, "YER", false))
    }

    @Test
    fun parsesIsoInstantAndDateOnlyValues() {
        assertEquals(
            Instant.parse("2026-11-04T10:15:30Z").toEpochMilli(),
            reportDateMillis("2026-11-04T10:15:30Z")
        )
        val expected = LocalDate.parse("2026-11-04")
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals(expected, reportDateMillis("2026-11-04"))
        assertEquals(null, reportDateMillis("not-a-date"))
    }

    @Test
    fun maximumEndDateUsesUnboundedExclusiveTimestampWithoutOverflow() {
        assertEquals(Long.MAX_VALUE, reportEndExclusiveMillis(LocalDate.MAX, ZoneId.of("UTC")))
        assertEquals(Long.MAX_VALUE, reportEndExclusiveMillis(null, ZoneId.of("UTC")))
    }

    @Test
    fun ordinaryEndDateIncludesTheEntireSelectedDay() {
        val date = LocalDate.parse("2026-11-04")
        val expected = date.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(expected, reportEndExclusiveMillis(date, ZoneId.of("UTC")))
    }

    @Test
    fun invalidCurrencyUsesZeroFractionDigitsWithoutCrashing() {
        assertEquals("123 INVALID", reportMoney(123L, "INVALID", true))
    }
}
