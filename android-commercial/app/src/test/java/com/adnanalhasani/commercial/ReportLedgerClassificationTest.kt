package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportLedgerClassificationTest {
    @Test
    fun normalizesAccountTypesAndClassifiesStatementSections() {
        assertEquals("asset", reportStatementClass(line(type = "Assets")))
        assertEquals("liability", reportStatementClass(line(type = "LIABILITIES")))
        assertEquals("equity", reportStatementClass(line(type = "equity")))
        assertEquals("revenue", reportStatementClass(line(type = "Income")))
        assertEquals("contra_revenue", reportStatementClass(line(type = "revenue", normal = "debit")))
        assertEquals("contra_revenue", reportStatementClass(line(type = "revenue", name = "مردودات المبيعات")))
        assertEquals("cogs", reportStatementClass(line(type = "cost-of-goods-sold")))
        assertEquals("expense", reportStatementClass(line(type = "expenses", name = "إيجار")))
        assertEquals("unknown", reportStatementClass(line(type = "other")))
    }

    @Test
    fun recognizesCostAccountsStoredUnderExpenseType() {
        assertEquals("cogs", reportStatementClass(line(type = "expense", name = "تكلفة البضاعة المباعة")))
        assertEquals("cogs", reportStatementClass(line(type = "expense", name = "Cost of goods sold")))
        assertEquals("cogs", reportStatementClass(line(type = "expense", code = "5000")))
    }

    @Test
    fun calculatesNormalDebitAndCreditBalancesByAccountClass() {
        val lines = listOf(
            line(type = "asset", debit = 1500L, credit = 200L),
            line(type = "revenue", debit = 100L, credit = 900L),
            line(type = "expense", debit = 300L, credit = 50L),
            line(type = "liability", debit = 80L, credit = 500L)
        )
        assertEquals(1300L, reportAccountNet(lines, "asset"))
        assertEquals(800L, reportAccountNet(lines, "revenue"))
        assertEquals(250L, reportAccountNet(lines, "expense"))
        assertEquals(420L, reportAccountNet(lines, "liability"))
    }

    private fun line(
        type: String,
        normal: String = "credit",
        name: String = "حساب",
        code: String = "1000",
        debit: Long = 0L,
        credit: Long = 0L
    ) = CommercialCloudJournalLine(
        entryId = "entry",
        entryNumber = "1",
        entryDate = "2026-11-04",
        entryDescription = "",
        sourceType = "manual",
        sourceId = "",
        lineNumber = 1,
        accountId = "account",
        accountCode = code,
        accountName = name,
        accountType = type,
        normalBalance = normal,
        partyAccountId = "",
        debitMinor = debit,
        creditMinor = credit,
        currencyCode = "YER"
    )
}
