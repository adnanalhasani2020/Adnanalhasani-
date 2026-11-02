package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TaxDiscountSummaryReportTest {
    private fun row(id: String, currency: String = "YER", kind: String, amount: Long) =
        TaxDiscountSummaryInput(id, currency, kind, amount)

    @Test
    fun groupsRecordedAmountsByCurrencyAndKeepsCategoriesSeparate() {
        val totals = calculateTaxDiscountSummaryTotals(
            listOf(
                row("s1", kind = "sales_discount", amount = 500),
                row("s1", kind = "sales_tax", amount = 150),
                row("s2", kind = "sales_tax", amount = 250),
                row("p1", kind = "purchase_tax", amount = 300),
                row("r1", kind = "sales_return", amount = 700),
                row("s3", currency = "USD", kind = "sales_discount", amount = 125)
            )
        )
        assertEquals(listOf("USD", "YER"), totals.map { it.currencyCode })
        val yer = totals.single { it.currencyCode == "YER" }
        assertEquals(500L, yer.salesDiscountMinor)
        assertEquals(1, yer.salesDiscountDocumentCount)
        assertEquals(400L, yer.salesTaxMinor)
        assertEquals(2, yer.salesTaxDocumentCount)
        assertEquals(300L, yer.purchaseTaxMinor)
        assertEquals(1, yer.purchaseTaxDocumentCount)
        assertEquals(700L, yer.salesReturnMinor)
        assertEquals(1, yer.salesReturnCount)
        assertEquals(125L, totals.single { it.currencyCode == "USD" }.salesDiscountMinor)
    }

    @Test
    fun rejectsDuplicateDocumentsWithinCategoryAndInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateTaxDiscountSummaryTotals(listOf(
                row("same", kind = "sales_tax", amount = 10),
                row("same", kind = "sales_tax", amount = 20)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateTaxDiscountSummaryTotals(listOf(row("bad-currency", currency = "US", kind = "sales_tax", amount = 10)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateTaxDiscountSummaryTotals(listOf(row("negative", kind = "purchase_tax", amount = -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateTaxDiscountSummaryTotals(listOf(row("unknown", kind = "tax_net", amount = 10)))
        }
    }

    @Test
    fun rejectsTotalsThatOverflowLongInsteadOfWrapping() {
        assertThrows(ArithmeticException::class.java) {
            calculateTaxDiscountSummaryTotals(listOf(
                row("one", kind = "sales_tax", amount = Long.MAX_VALUE),
                row("two", kind = "sales_tax", amount = 1)
            ))
        }
    }
}
