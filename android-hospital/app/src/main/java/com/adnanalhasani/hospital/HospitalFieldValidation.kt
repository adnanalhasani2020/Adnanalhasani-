package com.adnanalhasani.hospital

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Pure validation helpers kept separate from Compose so temporal edge cases can
 * be covered by fast JVM unit tests.
 */
object HospitalFieldValidation {
    private val dateTimeFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)
    private val monthFormatter = DateTimeFormatter
        .ofPattern("uuuu-MM", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)
    private val timeFormatter = DateTimeFormatter
        .ofPattern("HH:mm", Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun isValidDate(raw: String): Boolean = runCatching {
        LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
    }.isSuccess

    fun isValidDateTime(raw: String): Boolean = runCatching {
        LocalDateTime.parse(raw, dateTimeFormatter)
    }.isSuccess

    fun isValidMonth(raw: String): Boolean = runCatching {
        YearMonth.parse(raw, monthFormatter)
    }.isSuccess

    fun isValidTime(raw: String): Boolean = runCatching {
        LocalTime.parse(raw, timeFormatter)
    }.isSuccess
}
