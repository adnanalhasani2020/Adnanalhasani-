package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserSalesPerformanceTest {
    private fun invoice(
        id: String,
        userId: String = "user-a",
        currency: String = "YER",
        total: Long = 100L
    ) = UserSalesPerformanceInvoiceInput(id, userId, currency, total)

    @Test
    fun groupsCompletedSalesByActorAndCurrency() {
        val rows = calculateUserSalesPerformance(
            listOf(
                invoice("1", total = 100),
                invoice("2", total = 300),
                invoice("3", userId = "user-b", total = 200),
                invoice("4", currency = "SAR", total = 500),
                invoice("5", userId = "", total = 50)
            )
        )
        assertEquals(4, rows.size)
        val userA = rows.single { it.userId == "user-a" && it.currencyCode == "YER" }
        assertEquals(2, userA.invoiceCount)
        assertEquals(400L, userA.totalMinor)
        assertEquals(200L, userA.averageInvoiceMinor)
        assertEquals(50L, rows.single { it.userId == "غير منسوب" }.totalMinor)
    }

    @Test
    fun rejectsDuplicateInvoicesInvalidCurrenciesAndNegativeTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateUserSalesPerformance(listOf(invoice("same"), invoice("same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateUserSalesPerformance(listOf(invoice("bad-currency", currency = "YER1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateUserSalesPerformance(listOf(invoice("negative", total = -1)))
        }
    }

    @Test
    fun rejectsOverflowInsteadOfWrappingTotals() {
        assertThrows(ArithmeticException::class.java) {
            calculateUserSalesPerformance(
                listOf(invoice("large", total = Long.MAX_VALUE), invoice("overflow", total = 1))
            )
        }
    }
}
