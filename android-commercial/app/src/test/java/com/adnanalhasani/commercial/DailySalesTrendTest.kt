package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DailySalesTrendTest {
    private fun invoice(id: String, date: String, currency: String = "YER", amount: Long = 100) =
        DailySalesInvoiceInput(id, date, currency, amount)

    @Test
    fun groupsDailySalesByDateAndCurrencyWithoutMixingCurrencies() {
        val rows = calculateDailySalesTrend(listOf(
            invoice("1", "2026-11-01", amount = 100),
            invoice("2", "2026-11-01", amount = 250),
            invoice("3", "2026-11-01", currency = "SAR", amount = 50),
            invoice("4", "2026-11-02", amount = 300)
        ))
        assertEquals(3, rows.size)
        val firstDayYer = rows.single { it.date == "2026-11-01" && it.currencyCode == "YER" }
        assertEquals(2, firstDayYer.invoiceCount)
        assertEquals(350L, firstDayYer.totalMinor)
        assertEquals(50L, rows.single { it.currencyCode == "SAR" }.totalMinor)
    }

    @Test
    fun rejectsInvalidDatesDuplicateInvoicesAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateDailySalesTrend(listOf(invoice("bad", "2026-13-40")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateDailySalesTrend(listOf(invoice("same", "2026-11-01"), invoice("same", "2026-11-02")))
        }
        assertThrows(ArithmeticException::class.java) {
            calculateDailySalesTrend(listOf(
                invoice("large-1", "2026-11-01", amount = Long.MAX_VALUE),
                invoice("large-2", "2026-11-01", amount = 1)
            ))
        }
    }
}
