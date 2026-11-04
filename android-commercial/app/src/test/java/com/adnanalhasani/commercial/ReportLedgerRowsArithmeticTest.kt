package com.adnanalhasani.commercial

import java.time.LocalDate
import org.junit.Test

class ReportLedgerRowsArithmeticTest {
    @Test
    fun rejectsTrialBalanceDebitTotalOverflow() {
        expectArithmeticFailure {
            buildLedgerReportRows(
                selectedReport = "ميزان المراجعة حسب الحساب",
                ledgerStatementError = "",
                cloudLedgerLines = listOf(
                    line(debit = Long.MAX_VALUE),
                    line(debit = 1L)
                ),
                cloudJournals = emptyList(),
                startDate = LocalDate.parse("2026-01-01"),
                endDate = LocalDate.parse("2026-01-31"),
                startMillis = 0L,
                endExclusive = Long.MAX_VALUE,
                startText = "2026-01-01",
                endText = "2026-01-31",
                accountFilter = ""
            )
        }
    }

    @Test
    fun rejectsIncomeStatementAccountNetOverflow() {
        expectArithmeticFailure {
            buildLedgerReportRows(
                selectedReport = "قائمة الدخل والميزانية العمومية",
                ledgerStatementError = "",
                cloudLedgerLines = listOf(
                    line(type = "revenue", credit = Long.MAX_VALUE),
                    line(type = "revenue", credit = 1L)
                ),
                cloudJournals = emptyList(),
                startDate = LocalDate.parse("2026-01-01"),
                endDate = LocalDate.parse("2026-01-31"),
                startMillis = 0L,
                endExclusive = Long.MAX_VALUE,
                startText = "2026-01-01",
                endText = "2026-01-31",
                accountFilter = ""
            )
        }
    }

    @Test
    fun rejectsSingleLedgerLineDebitCreditDifferenceOverflow() {
        expectArithmeticFailure {
            reportAccountNet(
                listOf(line(type = "asset", debit = Long.MAX_VALUE, credit = -1L)),
                "asset"
            )
        }
    }

    private fun expectArithmeticFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected exact ledger arithmetic to reject overflow")
        } catch (_: ArithmeticException) {
            // Expected: reports data preflight converts this into a fail-closed error.
        }
    }

    private fun line(
        type: String = "asset",
        debit: Long = 0L,
        credit: Long = 0L
    ) = CommercialCloudJournalLine(
        entryId = "entry",
        entryNumber = "1",
        entryDate = "2026-01-10",
        entryDescription = "",
        sourceType = "test",
        sourceId = "source",
        lineNumber = 1,
        accountId = "account",
        accountCode = "100",
        accountName = "Account",
        accountType = type,
        normalBalance = "debit",
        partyAccountId = "",
        debitMinor = debit,
        creditMinor = credit,
        currencyCode = "YER"
    )
}
