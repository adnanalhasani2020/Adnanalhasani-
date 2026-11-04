package com.adnanalhasani.commercial

import java.time.LocalDate

/**
 * Validates source links required by full customer and supplier control-account statements.
 * Row assembly remains in ReportPartyStatementRows.
 */
internal fun reportCustomerStatementValidationError(
    cloudMode: Boolean,
    ledgerLines: List<CommercialCloudJournalLine>,
    ledgerLinesLoadError: String,
    partyAccountsLoadError: String,
    partyAccounts: List<CommercialCloudPartyAccountLink>,
    glMappings: List<CommercialCloudGlAccountMapping>,
    startDate: LocalDate?,
    endDate: LocalDate?
): String = when {
    !cloudMode -> "كشف العميل الكامل يتطلب دفتر الأستاذ السحابي."
    ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
    ledgerLines.isEmpty() -> "لا توجد قيود مرحلة في دفتر الأستاذ؛ لا يمكن إنشاء كشف حساب محاسبي."
    partyAccountsLoadError.isNotBlank() -> partyAccountsLoadError
    partyAccounts.isEmpty() -> "لا توجد روابط لحسابات مراقبة العملاء في دليل الحسابات."
    glMappings.none { it.mappingKey == "accounts_receivable" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } ->
        "حساب مراقبة العملاء غير مربوط بعملة صالحة في دليل الحسابات."
    partyAccounts.any { link ->
        glMappings.none { it.mappingKey == "accounts_receivable" && it.currencyCode == link.currencyCode }
    } -> "توجد عملة عميل بلا حساب مراقبة عملاء مطابق؛ تعذر اعتماد الكشف."
    ledgerLines.any { line ->
        line.accountId in glMappings.filter { it.mappingKey == "accounts_receivable" }.map { it.ledgerAccountId }.toSet() &&
            (line.partyAccountId.isBlank() || partyAccounts.none { link ->
                link.partyAccountId == line.partyAccountId && link.currencyCode == line.currencyCode
            })
    } -> "توجد حركات على حساب مراقبة العملاء بلا ربط صالح بعميل وعملة مطابقين؛ تعذر اعتماد الكشف الكامل."
    !reportPartyStatementArithmeticIsExact(
        ledgerLines = ledgerLines,
        partyAccounts = partyAccounts,
        controlAccountIdsByCurrency = glMappings.filter { it.mappingKey == "accounts_receivable" }
            .groupBy({ it.currencyCode }, { it.ledgerAccountId }).mapValues { it.value.toSet() },
        startDate = startDate,
        endDate = endDate,
        customerSide = true
    ) -> "تجاوزت حركات أو أرصدة كشف العملاء نطاق الأرقام الدقيق؛ تم حجب الكشف."
    else -> ""
}

internal fun reportSupplierStatementValidationError(
    cloudMode: Boolean,
    ledgerLines: List<CommercialCloudJournalLine>,
    ledgerLinesLoadError: String,
    partyAccountsLoadError: String,
    controlAccountsLoadError: String,
    partyAccounts: List<CommercialCloudPartyAccountLink>,
    supplierControlAccounts: List<CommercialCloudSupplierControlAccount>,
    startDate: LocalDate?,
    endDate: LocalDate?
): String = when {
    !cloudMode -> "كشف المورد الكامل يتطلب دفتر الأستاذ السحابي."
    ledgerLinesLoadError.isNotBlank() -> ledgerLinesLoadError
    ledgerLines.isEmpty() -> "لا توجد قيود مرحلة في دفتر الأستاذ؛ لا يمكن إنشاء كشف حساب محاسبي."
    partyAccountsLoadError.isNotBlank() -> partyAccountsLoadError
    controlAccountsLoadError.isNotBlank() -> controlAccountsLoadError
    partyAccounts.isEmpty() -> "لا توجد روابط لحسابات مراقبة الموردين في دليل الحسابات."
    supplierControlAccounts.none { it.accountRole == "accounts_payable" && it.currencyCode.matches(Regex("^[A-Z]{3}$")) } ->
        "حساب مراقبة الموردين غير مربوط بعملة صالحة في دليل الحسابات."
    partyAccounts.any { link ->
        supplierControlAccounts.none { it.accountRole == "accounts_payable" && it.currencyCode == link.currencyCode }
    } -> "توجد عملة مورد بلا حساب مراقبة موردين مطابق؛ تعذر اعتماد الكشف."
    ledgerLines.any { line ->
        line.accountId in supplierControlAccounts.filter { it.accountRole == "accounts_payable" }.map { it.ledgerAccountId }.toSet() &&
            (line.partyAccountId.isBlank() || partyAccounts.none { link ->
                link.partyAccountId == line.partyAccountId && link.currencyCode == line.currencyCode
            })
    } -> "توجد حركات على حساب مراقبة الموردين بلا ربط صالح بمورد وعملة مطابقين؛ تعذر اعتماد الكشف الكامل."
    !reportPartyStatementArithmeticIsExact(
        ledgerLines = ledgerLines,
        partyAccounts = partyAccounts,
        controlAccountIdsByCurrency = supplierControlAccounts.filter { it.accountRole == "accounts_payable" }
            .groupBy({ it.currencyCode }, { it.ledgerAccountId }).mapValues { it.value.toSet() },
        startDate = startDate,
        endDate = endDate,
        customerSide = false
    ) -> "تجاوزت حركات أو أرصدة كشف الموردين نطاق الأرقام الدقيق؛ تم حجب الكشف."
    else -> ""
}


/** Preflights the same opening and running-balance arithmetic used by party statement rows. */
private fun reportPartyStatementArithmeticIsExact(
    ledgerLines: List<CommercialCloudJournalLine>,
    partyAccounts: List<CommercialCloudPartyAccountLink>,
    controlAccountIdsByCurrency: Map<String, Set<String>>,
    startDate: LocalDate?,
    endDate: LocalDate?,
    customerSide: Boolean
): Boolean = runCatching {
    partyAccounts.groupBy { it.partyId to it.currencyCode }.forEach { (party, links) ->
        val partyAccountIds = links.map { it.partyAccountId }.toSet()
        val controlAccountIds = controlAccountIdsByCurrency[party.second].orEmpty()
        val movements = ledgerLines.filter {
            it.partyAccountId in partyAccountIds &&
                it.accountId in controlAccountIds &&
                it.currencyCode == party.second
        }.mapNotNull { line ->
            val date = runCatching { LocalDate.parse(line.entryDate.take(10)) }.getOrNull()
                ?: return@mapNotNull null
            val amount = if (customerSide) {
                Math.subtractExact(line.debitMinor, line.creditMinor)
            } else {
                Math.subtractExact(line.creditMinor, line.debitMinor)
            }
            Triple(date, line.entryNumber, amount)
        }.sortedWith(compareBy<Triple<LocalDate, String, Long>> { it.first }.thenBy { it.second })

        val opening = movements.filter { it.first < (startDate ?: LocalDate.MIN) }
            .fold(0L) { total, movement -> Math.addExact(total, movement.third) }
        var balance = opening
        movements.filter {
            startDate != null && endDate != null &&
                !it.first.isBefore(startDate) && !it.first.isAfter(endDate)
        }.forEach { movement ->
            balance = Math.addExact(balance, movement.third)
        }
    }
}.isSuccess
