package com.adnanalhasani.commercial

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency

internal fun reportCurrencyScale(code: String): Int =
    runCatching { Currency.getInstance(code).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)

internal fun reportMoney(amount: Long, currency: String, cloudMinorUnits: Boolean): String {
    val value = if (cloudMinorUnits) java.math.BigDecimal.valueOf(amount).movePointLeft(reportCurrencyScale(currency))
    else java.math.BigDecimal.valueOf(amount)
    return value.setScale(if (cloudMinorUnits) reportCurrencyScale(currency) else 0, java.math.RoundingMode.HALF_UP)
        .toPlainString() + " " + currency
}

internal fun reportDateMillis(value: String): Long? =
    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
