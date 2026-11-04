package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

private fun cloudDebtScale(currency: String): Int =
    runCatching { Currency.getInstance(currency).defaultFractionDigits.coerceIn(0, 6) }.getOrDefault(0)

internal fun cloudDebtInput(amountMinor: Long, currency: String): String =
    BigDecimal.valueOf(amountMinor).movePointLeft(cloudDebtScale(currency))
        .stripTrailingZeros().toPlainString()

internal fun parseMerchantPaymentRequestAmount(value: String, currency: String): Long? = runCatching {
    val amount = value.trim().replace(',', '.').toBigDecimal()
    require(amount > BigDecimal.ZERO)
    amount.movePointRight(cloudDebtScale(currency))
        .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
}.getOrNull()
