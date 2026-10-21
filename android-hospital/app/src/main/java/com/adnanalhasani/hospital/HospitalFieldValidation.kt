package com.adnanalhasani.hospital

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode
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

    /**
     * Converts a non-negative amount to minor units without silently truncating
     * fractions or overflowing Long. Trailing zeroes beyond two decimals are OK.
     */
    fun toMinorUnitsOrNull(raw: String): Long? = runCatching {
        val normalized = raw.trim()
        require(!normalized.contains('e', ignoreCase = true)) { "Scientific notation is not supported for money" }
        val amount = BigDecimal(normalized)
        require(amount.signum() >= 0) { "Amount must not be negative" }
        require(amount.stripTrailingZeros().scale() <= 2) { "Amount supports at most two decimal places" }
        amount.setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()

    fun isValidMoneyAmount(raw: String): Boolean = toMinorUnitsOrNull(raw) != null
}
