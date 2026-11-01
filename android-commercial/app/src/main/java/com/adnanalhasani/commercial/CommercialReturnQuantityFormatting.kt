package com.adnanalhasani.commercial

internal fun formatCommercialReturnSoldQuantity(value: String?, fallback: Int): String =
    runCatching {
        value?.let { java.math.BigDecimal(it) }
            ?.takeIf { it.signum() > 0 }
            ?.stripTrailingZeros()
            ?.toPlainString()
    }.getOrNull() ?: fallback.coerceAtLeast(0).toString()
