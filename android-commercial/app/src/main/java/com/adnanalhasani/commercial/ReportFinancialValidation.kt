package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

private val reportCashGlMappingKeys = setOf(
    "cash", "bank", "card_clearing", "other_receipts", "check_clearing", "other_disbursements"
)

/** Validates current inventory valuation sources and exact representability before rows are built. */
internal fun reportCurrentInventoryValuationValidationError(
    stockAlertsLoadError: String,
    glMappingsLoadError: String,
    cloudGlMappings: List<CommercialCloudGlAccountMapping>,
    cloudStockAlerts: List<CommercialCloudStockAlert>
): String = when {
    stockAlertsLoadError.isNotBlank() -> stockAlertsLoadError
    glMappingsLoadError.isNotBlank() -> glMappingsLoadError
    cloudGlMappings.none { it.mappingKey == "inventory" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } ->
        "لا يوجد حساب مخزون مربوط بعملة صالحة في دليل الحسابات."
    cloudStockAlerts.isEmpty() -> "لا توجد أرصدة مخزون محملة للموقع والنشاط الحاليين."
    cloudStockAlerts.any { it.unitCostMinor == null || it.unitCostMinor < 0L || it.quantityOnHand.signum() < 0 } ->
        "بعض أرصدة المخزون تفتقد تكلفة وحدة مسجلة أو تحتوي كمية غير صالحة."
    runCatching {
        cloudStockAlerts.fold(BigDecimal.ZERO) { total, item ->
            total.add(
                item.quantityOnHand.multiply(BigDecimal.valueOf(item.unitCostMinor ?: 0L))
                    .setScale(0, RoundingMode.HALF_UP)
            )
        }.longValueExact()
    }.isFailure -> "إجمالي قيمة المخزون يتجاوز نطاق الأرقام المدعوم؛ لم يُعرض تقييم ناقص."
    else -> ""
}

/**
 * Preserves cash-flow source readiness precedence and preflights every exact
 * arithmetic operation performed by the cash-flow row builder.
 */
internal fun reportCashFlowValidationError(
    glMappingsLoadError: String,
    cloudGlMappings: List<CommercialCloudGlAccountMapping>,
    ledgerLinesLoadError: String,
    cloudLedgerLines: List<CommercialCloudJournalLine>,
    startDate: LocalDate?,
    endDate: LocalDate?
): String {
    val cashAccountIds = cloudGlMappings
        .filter { it.mappingKey in reportCashGlMappingKeys }
        .map { it.ledgerAccountId }
        .toSet()
    return when {
        glMappingsLoadError.isNotBlank() -> glMappingsLoadError
        cloudGlMappings.none {
            it.mappingKey in reportCashGlMappingKeys && it.currencyCode.matches(Regex("^[A-Z]{3}$"))
        } -> "لا توجد حسابات نقدية أو بنكية مربوطة بعملة صالحة في دليل الحسابات."
        ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
        cloudLedgerLines.isEmpty() -> "لا توجد قيود مرحلة لحسابات النقد والبنك؛ لا يمكن اعتماد الرصيد."
        cloudLedgerLines.any { runCatching { LocalDate.parse(it.entryDate.take(10)) }.getOrNull() == null } ->
            "توجد قيود مرحلة بتاريخ غير صالح؛ لا يمكن ضمان الرصيد الافتتاحي."
        cloudLedgerLines.any { !it.currencyCode.matches(Regex("^[A-Z]{3}$")) } ->
            "توجد قيود نقدية بعملة غير محددة؛ تم حجب كشف الصندوق."
        runCatching {
            val movements = cloudLedgerLines.filter { it.accountId in cashAccountIds }.map { line ->
                val date = LocalDate.parse(line.entryDate.take(10))
                Triple(line, date, Math.subtractExact(line.debitMinor, line.creditMinor))
            }
            movements.map { it.first.currencyCode }.distinct().forEach { currency ->
                movements.filter { it.first.currencyCode == currency }
                    .groupBy { it.first.accountId to it.first.accountCode }
                    .values.forEach { accountMoves ->
                        val orderedAccountMoves = accountMoves.sortedWith(
                            compareBy<Triple<CommercialCloudJournalLine, LocalDate, Long>> { it.second }
                                .thenBy { it.first.entryNumber }
                                .thenBy { it.first.lineNumber }
                        )
                        val opening = orderedAccountMoves.filter { it.second < (startDate ?: LocalDate.MIN) }
                            .fold(0L) { total, move -> Math.addExact(total, move.third) }
                        var balance = opening
                        val periodMoves = orderedAccountMoves.filter {
                            startDate != null && endDate != null &&
                                !it.second.isBefore(startDate) && !it.second.isAfter(endDate)
                        }
                        periodMoves.forEach { balance = Math.addExact(balance, it.third) }
                        periodMoves.filter { it.third > 0L }
                            .fold(0L) { total, move -> Math.addExact(total, move.third) }
                        periodMoves.filter { it.third < 0L }
                            .fold(0L) { total, move -> Math.addExact(total, Math.negateExact(move.third)) }
                    }
            }
        }.isFailure -> "تجاوزت حركات النقد أو أرصدته الافتتاحية/الختامية نطاق الأرقام الدقيق؛ تم حجب كشف الصندوق."
        else -> ""
    }
}
