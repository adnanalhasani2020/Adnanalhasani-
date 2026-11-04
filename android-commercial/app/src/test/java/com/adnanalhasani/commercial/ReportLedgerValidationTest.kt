package com.adnanalhasani.commercial

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportLedgerValidationTest {
    private val glMappings = setOf(
        "accounts_receivable", "sales_revenue", "sales_returns", "sales_discounts",
        "sales_tax_payable", "inventory", "cost_of_goods_sold"
    )
    private val supplierRoles = setOf(
        "inventory_asset", "accounts_payable", "goods_received_not_invoiced",
        "purchase_price_variance", "purchase_tax_freight_expense"
    )

    @Test
    fun blocksFinancialStatementsOutsideCloudMode() {
        assertEquals(
            "القوائم المالية تتطلب دفتر الأستاذ السحابي.",
            validate(cloudMode = false)
        )
    }

    @Test
    fun preservesSourceErrorPrecedence() {
        assertEquals(
            "ledger unavailable",
            validate(ledgerLinesLoadError = "ledger unavailable", glMappingsLoadError = "mapping unavailable")
        )
        assertEquals(
            "mapping unavailable",
            validate(glMappingsLoadError = "mapping unavailable")
        )
        assertEquals(
            "supplier accounts unavailable",
            validate(supplierControlAccountsLoadError = "supplier accounts unavailable")
        )
    }

    @Test
    fun rejectsMissingMappingsAndSupplierRoles() {
        val missingMapping = validate(glMappingKeys = glMappings - "inventory")
        assertEquals(true, missingMapping.contains("inventory"))

        val missingRole = validate(supplierAccountRoles = supplierRoles - "accounts_payable")
        assertEquals(true, missingRole.contains("accounts_payable"))
    }

    @Test
    fun rejectsInvalidDatesAndEmptyLedgerWindow() {
        assertEquals(
            "توجد قيود مرحلة بتاريخ غير صالح؛ لا يمكن استبعادها بأمان من القوائم المالية.",
            validate(ledgerLines = listOf(line(date = "not-a-date")))
        )
        assertEquals(
            "لا توجد قيود مرحلة ضمن الفترة؛ لا يمكن اعتماد القوائم المالية.",
            validate(ledgerLines = emptyList())
        )
    }

    @Test
    fun rejectsUnbalancedUnknownAndInvalidCurrencyLedgerLines() {
        assertEquals(
            "توجد قيود مرحلة غير متزنة حسب العملة؛ تم حجب القوائم.",
            validate(ledgerLines = listOf(line(debit = 100L, credit = 0L)))
        )
        assertEquals(
            "توجد حسابات بأنواع غير معروفة؛ يلزم تصنيف دليل الحسابات قبل اعتماد القوائم.",
            validate(ledgerLines = listOf(line(type = "unmapped", debit = 100L, credit = 100L)))
        )
        assertEquals(
            "توجد سطور أستاذ بلا رمز عملة ISO صالح؛ تم حجب القوائم لمنع خلط العملات.",
            validate(ledgerLines = listOf(line(currency = "XXX1", debit = 100L, credit = 100L)))
        )
    }

    @Test
    fun rejectsBalancedLedgerTotalsThatOverflowLong() {
        val error = validate(
            ledgerLines = listOf(
                line(entryId = "large-entry", debit = Long.MAX_VALUE),
                line(entryId = "large-entry", debit = 1L),
                line(entryId = "large-entry", type = "revenue", credit = Long.MAX_VALUE),
                line(entryId = "large-entry", type = "revenue", credit = 1L)
            )
        )

        assertEquals(
            "تجاوزت مجاميع المدين أو الدائن نطاق الأرقام الدقيق؛ تم حجب القوائم.",
            error
        )
    }

    @Test
    fun acceptsBalancedKnownLedgerLinesWithCompleteMappings() {
        assertEquals(
            "",
            validate(
                ledgerLines = listOf(
                    line(entryId = "entry-a", debit = 1250L, credit = 0L),
                    line(entryId = "entry-a", type = "revenue", debit = 0L, credit = 1250L)
                )
            )
        )
    }

    private fun validate(
        cloudMode: Boolean = true,
        ledgerLines: List<CommercialCloudJournalLine> = listOf(
            line(debit = 100L, credit = 100L)
        ),
        endDate: LocalDate? = LocalDate.parse("2026-11-04"),
        ledgerLinesLoadError: String = "",
        glMappingsLoadError: String = "",
        glMappingKeys: Set<String> = glMappings,
        supplierControlAccountsLoadError: String = "",
        supplierAccountRoles: Set<String> = supplierRoles
    ) = reportLedgerStatementValidationError(
        cloudMode = cloudMode,
        ledgerLines = ledgerLines,
        endDate = endDate,
        ledgerLinesLoadError = ledgerLinesLoadError,
        glMappingsLoadError = glMappingsLoadError,
        glMappingKeys = glMappingKeys,
        supplierControlAccountsLoadError = supplierControlAccountsLoadError,
        supplierAccountRoles = supplierAccountRoles
    )

    private fun line(
        entryId: String = "entry",
        date: String = "2026-11-04",
        type: String = "asset",
        currency: String = "YER",
        debit: Long = 0L,
        credit: Long = 0L
    ) = CommercialCloudJournalLine(
        entryId = entryId,
        entryNumber = "1",
        entryDate = date,
        entryDescription = "",
        sourceType = "manual",
        sourceId = "",
        lineNumber = 1,
        accountId = "account",
        accountCode = "1000",
        accountName = "حساب",
        accountType = type,
        normalBalance = "debit",
        partyAccountId = "",
        debitMinor = debit,
        creditMinor = credit,
        currencyCode = currency
    )
}
