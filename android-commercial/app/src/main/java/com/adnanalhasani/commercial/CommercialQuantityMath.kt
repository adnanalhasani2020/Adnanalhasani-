package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency
import java.util.Locale

internal data class CommercialCloudCheckoutLine(
    val offeringId: String,
    val quantity: BigDecimal,
    val unitCode: String = "each"
)

/** Render inventory quantities without losing stored decimal precision. */
internal fun formatCommercialStockQuantity(value: BigDecimal): String =
    value.stripTrailingZeros().toPlainString()

/** Compare the exact available quantity to the configured reorder threshold. */
internal fun isCommercialStockLow(value: BigDecimal, threshold: Int): Boolean =
    value <= BigDecimal.valueOf(threshold.toLong())

/** Parse a positive whole selling quantity. Fractional quantities are rejected at the domain boundary. */
internal fun parseCommercialCheckoutQuantity(value: String): BigDecimal? {
    val normalized = value.trim()
    val ascii = buildString {
        normalized.forEach { ch ->
            val digit = Character.digit(ch, 10)
            if (digit >= 0) append(('0'.code + digit).toChar()) else append(ch)
        }
    }
    if (!Regex("^[0-9]{1,6}$").matches(ascii)) return null
    return runCatching { BigDecimal(ascii) }
        .getOrNull()
        ?.takeIf { it.signum() > 0 && it <= BigDecimal("100000") }
}

/** Merge only lines with the same offering and selling unit before checkout. */
internal fun aggregateCommercialCheckoutLines(
    lines: List<CommercialCloudCheckoutLine>
): List<CommercialCloudCheckoutLine> {
    require(lines.isNotEmpty() && lines.size <= 200) { "أضف صنفًا واحدًا على الأقل إلى الفاتورة." }
    val totals = linkedMapOf<Pair<String, String>, BigDecimal>()
    lines.forEach { line ->
        val quantity = line.quantity.stripTrailingZeros()
        require(line.offeringId.isNotBlank() && quantity.signum() > 0 &&
            quantity <= BigDecimal("100000") && quantity.stripTrailingZeros().scale() <= 0 && line.unitCode.isNotBlank()) {
            "تحقق من الأصناف والكميات ووحدة البيع؛ الكمية يجب أن تكون عددًا صحيحًا موجبًا."
        }
        val key = line.offeringId to line.unitCode
        val total = (totals[key] ?: BigDecimal.ZERO).add(quantity)
        require(total <= BigDecimal("100000")) { "إجمالي كمية الصنف بوحدة البيع نفسها يتجاوز الحد المسموح." }
        totals[key] = total.stripTrailingZeros()
    }
    return totals.entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))
        .map { (key, quantity) -> CommercialCloudCheckoutLine(key.first, quantity, key.second) }
}

internal fun maxCommercialReturnQuantity(remainingBaseUnits: Long, factor: Long): BigDecimal {
    if (remainingBaseUnits <= 0L || factor <= 0L) return BigDecimal.ZERO
    return BigDecimal.valueOf(remainingBaseUnits / factor)
}

/** Recover the exact conversion factor saved by a completed sale line. */
internal fun commercialSavedSellingUnitFactor(baseQuantity: Long, sellingQuantity: BigDecimal): Long? {
    if (baseQuantity <= 0L || sellingQuantity.signum() <= 0 || sellingQuantity.stripTrailingZeros().scale() > 0) return null
    return runCatching {
        BigDecimal.valueOf(baseQuantity)
            .divide(sellingQuantity)
            .toBigIntegerExact()
            .longValueExact()
    }.getOrNull()?.takeIf { it > 0L }
}

/** Format a server amount_minor value using the currency's ISO minor-unit scale. */
internal fun formatCommercialOperationalAmount(amountMinor: Long, currencyCode: String): String {
    val code = currencyCode.trim().uppercase(Locale.ROOT)
    val fractionDigits = runCatching { Currency.getInstance(code).defaultFractionDigits }.getOrDefault(-1)
    return formatCommercialOperationalAmount(amountMinor, code, fractionDigits)
}

/** Pure formatter overload so currency arithmetic can be tested on the JVM without Android runtime stubs. */
internal fun formatCommercialOperationalAmount(amountMinor: Long, currencyCode: String, fractionDigits: Int): String {
    val code = currencyCode.trim().uppercase(Locale.ROOT)
    if (fractionDigits !in 0..6) return amountMinor.toString() + " وحدة نقدية صغرى" + if (code.isBlank()) "" else " • $code"
    val amountText = BigDecimal.valueOf(amountMinor).movePointLeft(fractionDigits).toPlainString()
    val negative = amountText.startsWith("-")
    val unsigned = amountText.removePrefix("-")
    val parts = unsigned.split(".", limit = 2)
    val groupedInteger = parts[0].reversed().chunked(3).joinToString(",").reversed()
    val formatted = (if (negative) "-" else "") + groupedInteger +
        if (fractionDigits > 0) "." + parts.getOrElse(1) { "0".repeat(fractionDigits) } else ""
    return formatted + " " + code
}


/** Calculate a customer-portal line total in minor units without binary floating-point drift. */
internal fun commercialPortalLineTotalMinor(quantity: BigDecimal, priceMinor: Long): Long? {
    if (quantity.signum() <= 0 || priceMinor < 0) return null
    return runCatching {
        quantity.multiply(BigDecimal.valueOf(priceMinor))
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }.getOrNull()
}

/** Sum validated customer-portal line totals, rejecting missing values and Long overflow. */
internal fun sumCommercialPortalLineTotalsMinor(values: List<Long?>): Long? {
    if (values.isEmpty() || values.any { it == null }) return null
    return runCatching {
        values.fold(BigInteger.ZERO) { total, value -> total.add(BigInteger.valueOf(value!!)) }
            .longValueExact()
    }.getOrNull()
}
