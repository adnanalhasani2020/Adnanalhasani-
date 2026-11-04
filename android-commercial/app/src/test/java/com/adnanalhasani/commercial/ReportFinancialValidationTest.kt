package com.adnanalhasani.commercial

import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportFinancialValidationTest {
    private val mapping = CommercialCloudGlAccountMapping(
        mappingKey = "cash",
        ledgerAccountId = "cash-account",
        currencyCode = "YER"
    )

    @Test
    fun preservesCashFlowSourceErrorPrecedence() {
        assertEquals(
            "mapping unavailable",
            cashError(glMappingsLoadError = "mapping unavailable", ledgerLinesLoadError = "ledger unavailable")
        )
        assertEquals(
            "لا توجد حسابات نقدية أو بنكية مربوطة بعملة صالحة في دليل الحسابات.",
            cashError(glMappings = emptyList(), ledgerLinesLoadError = "ledger unavailable")
        )
        assertEquals(
            "ledger unavailable",
            cashError(ledgerLinesLoadError = "ledger unavailable")
        )
    }

    @Test
    fun rejectsOpeningBalanceOverflow() {
        val error = cashError(
            lines = listOf(
                line(date = "2026-01-01", debit = Long.MAX_VALUE),
                line(date = "2026-01-01", debit = Long.MAX_VALUE)
            ),
            startDate = LocalDate.parse("2026-01-02")
        )

        assertEquals(
            "تجاوزت حركات النقد أو أرصدته الافتتاحية/الختامية نطاق الأرقام الدقيق؛ تم حجب كشف الصندوق.",
            error
        )
    }

    @Test
    fun rejectsCreditMovementMagnitudeOverflow() {
        val error = cashError(
            lines = listOf(
                line(date = "2026-01-02", credit = Long.MAX_VALUE),
                line(date = "2026-01-02", credit = Long.MAX_VALUE)
            )
        )

        assertEquals(
            "تجاوزت حركات النقد أو أرصدته الافتتاحية/الختامية نطاق الأرقام الدقيق؛ تم حجب كشف الصندوق.",
            error
        )
    }

    @Test
    fun rejectsCurrentInventoryValuationOverflow() {
        val alerts = listOf(
            stockAlert(cost = Long.MAX_VALUE),
            stockAlert(cost = 1L)
        )
        assertEquals(
            "إجمالي قيمة المخزون يتجاوز نطاق الأرقام المدعوم؛ لم يُعرض تقييم ناقص.",
            reportCurrentInventoryValuationValidationError(
                stockAlertsLoadError = "",
                glMappingsLoadError = "",
                cloudGlMappings = listOf(
                    CommercialCloudGlAccountMapping("inventory", "inventory-account", "YER")
                ),
                cloudStockAlerts = alerts
            )
        )
    }

    private fun cashError(
        glMappingsLoadError: String = "",
        glMappings: List<CommercialCloudGlAccountMapping> = listOf(mapping),
        ledgerLinesLoadError: String = "",
        lines: List<CommercialCloudJournalLine> = listOf(line()),
        startDate: LocalDate? = LocalDate.parse("2026-01-01")
    ) = reportCashFlowValidationError(
        glMappingsLoadError = glMappingsLoadError,
        cloudGlMappings = glMappings,
        ledgerLinesLoadError = ledgerLinesLoadError,
        cloudLedgerLines = lines,
        startDate = startDate,
        endDate = LocalDate.parse("2026-01-31")
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
        accountId = "cash-account",
        accountCode = "101",
        accountName = "Cash",
        accountType = "asset",
        normalBalance = "debit",
        partyAccountId = "",
        debitMinor = debit,
        creditMinor = credit,
        currencyCode = "YER"
    )

    private fun stockAlert(cost: Long) = CommercialCloudStockAlert(
        offeringId = "offering",
        productName = "Product",
        categoryName = "",
        unitName = "unit",
        availableQuantity = 1.0,
        reorderPoint = 0.0,
        quantityOnHand = BigDecimal.ONE,
        unitCostMinor = cost
    )
}
