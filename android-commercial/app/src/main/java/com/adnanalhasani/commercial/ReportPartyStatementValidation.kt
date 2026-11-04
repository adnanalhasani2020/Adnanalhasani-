package com.adnanalhasani.commercial

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
    glMappings: List<CommercialCloudGlAccountMapping>
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
    else -> ""
}

internal fun reportSupplierStatementValidationError(
    cloudMode: Boolean,
    ledgerLines: List<CommercialCloudJournalLine>,
    ledgerLinesLoadError: String,
    partyAccountsLoadError: String,
    controlAccountsLoadError: String,
    partyAccounts: List<CommercialCloudPartyAccountLink>,
    supplierControlAccounts: List<CommercialCloudSupplierControlAccount>
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
    else -> ""
}
