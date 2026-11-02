package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomerReceivableAgingReportTest {
    private fun invoice(
        id: String,
        customer: String = "customer-1",
        name: String = "العميل أ",
        date: String = "2026-10-20",
        currency: String = "YER",
        balance: Long = 800
    ) = CustomerReceivableAgingInput(
        saleId = id,
        receiptNumber = "R-$id",
        customerId = customer,
        customerName = name,
        invoiceDate = date,
        currencyCode = currency,
        balanceMinor = balance
    )

    @Test
    fun groupsOpenReturnAdjustedBalancesByCustomerCurrencyAndInvoiceAge() {
        val rows = calculateCustomerReceivableAging(
            listOf(
                invoice("a", balance = 800),
                invoice("b", date = "2026-09-20", balance = 400),
                invoice("c", date = "2026-08-01", balance = 500),
                invoice("d", currency = "SAR", balance = 300),
                invoice("e", customer = "customer-2", name = "العميل ب", balance = 900)
            ),
            "2026-11-02"
        )
        assertEquals(5, rows.size)
        assertEquals(800L, rows.single { it.bucket == "0–30 يومًا" && it.currencyCode == "YER" && it.customerId == "customer-1" }.balanceMinor)
        assertEquals(400L, rows.single { it.bucket == "31–60 يومًا" }.balanceMinor)
        assertEquals(500L, rows.single { it.bucket == "أكثر من 90 يومًا" }.balanceMinor)
    }

    @Test
    fun rejectsDuplicateInvoicesInvalidBalanceCurrencyAndDates() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerReceivableAging(listOf(invoice("x"), invoice("x")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerReceivableAging(listOf(invoice("x", balance = 0)), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerReceivableAging(listOf(invoice("x", currency = "YER1")), "2026-11-02")
        }
        assertThrows(java.time.format.DateTimeParseException::class.java) {
            calculateCustomerReceivableAging(listOf(invoice("x", date = "bad-date")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerReceivableAging(listOf(invoice("x", date = "2026-11-03")), "2026-11-02")
        }
    }

    @Test
    fun rejectsOverflowWhenAggregatingReceivableBalances() {
        assertThrows(ArithmeticException::class.java) {
            calculateCustomerReceivableAging(
                listOf(invoice("a", balance = Long.MAX_VALUE), invoice("b", balance = 1)),
                "2026-11-02"
            )
        }
    }
}
