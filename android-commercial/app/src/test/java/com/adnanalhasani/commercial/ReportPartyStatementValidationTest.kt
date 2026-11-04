package com.adnanalhasani.commercial

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportPartyStatementValidationTest {
    private val start = LocalDate.parse("2026-01-02")
    private val end = LocalDate.parse("2026-01-31")
    private val customerLink = CommercialCloudPartyAccountLink("customer-1", "party-1", "YER")
    private val supplierLink = CommercialCloudPartyAccountLink("supplier-1", "party-1", "YER")

    @Test
    fun rejectsCustomerOpeningBalanceOverflow() {
        val error = customerError(
            lines = listOf(
                line(date = "2026-01-01", debit = Long.MAX_VALUE),
                line(date = "2026-01-01", debit = 1L)
            )
        )
        assertEquals(
            "تجاوزت حركات أو أرصدة كشف العملاء نطاق الأرقام الدقيق؛ تم حجب الكشف.",
            error
        )
    }

    @Test
    fun rejectsSupplierOpeningBalanceOverflow() {
        val error = supplierError(
            lines = listOf(
                line(date = "2026-01-01", credit = Long.MAX_VALUE),
                line(date = "2026-01-01", credit = 1L)
            )
        )
        assertEquals(
            "تجاوزت حركات أو أرصدة كشف الموردين نطاق الأرقام الدقيق؛ تم حجب الكشف.",
            error
        )
    }

    @Test
    fun preservesPartyStatementSourceErrorPrecedence() {
        assertEquals(
            "ledger unavailable",
            customerError(ledgerLinesLoadError = "ledger unavailable")
        )
        assertEquals(
            "ledger unavailable",
            supplierError(ledgerLinesLoadError = "ledger unavailable")
        )
    }

    private fun customerError(
        ledgerLinesLoadError: String = "",
        lines: List<CommercialCloudJournalLine> = listOf(line(debit = 100L))
    ) = reportCustomerStatementValidationError(
        cloudMode = true,
        ledgerLines = lines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = "",
        partyAccounts = listOf(customerLink),
        glMappings = listOf(CommercialCloudGlAccountMapping("accounts_receivable", "control-account", "YER")),
        startDate = start,
        endDate = end
    )

    private fun supplierError(
        ledgerLinesLoadError: String = "",
        lines: List<CommercialCloudJournalLine> = listOf(line(credit = 100L))
    ) = reportSupplierStatementValidationError(
        cloudMode = true,
        ledgerLines = lines,
        ledgerLinesLoadError = ledgerLinesLoadError,
        partyAccountsLoadError = "",
        controlAccountsLoadError = "",
        partyAccounts = listOf(supplierLink),
        supplierControlAccounts = listOf(CommercialCloudSupplierControlAccount("accounts_payable", "control-account", "YER")),
        startDate = start,
        endDate = end
    )

    private fun line(
        date: String = "2026-01-10",
        debit: Long = 0L,
        credit: Long = 0L
    ) = CommercialCloudJournalLine(
        entryId = "entry",
        entryNumber = "1",
        entryDate = date,
        entryDescription = "",
        sourceType = "test",
        sourceId = "source",
        lineNumber = 1,
        accountId = "control-account",
        accountCode = "200",
        accountName = "Control",
        accountType = "liability",
        normalBalance = "credit",
        partyAccountId = "party-1",
        debitMinor = debit,
        creditMinor = credit,
        currencyCode = "YER"
    )
}
