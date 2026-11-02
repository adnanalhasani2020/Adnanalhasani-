package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierLiabilityAgingReportTest {
    private fun invoice(
        id: String,
        supplier: String = "supplier-1",
        supplierName: String = "المورد أ",
        date: String = "2026-10-20",
        currency: String = "YER",
        due: Long = 1_000,
        paid: Long = 200
    ) = SupplierLiabilityAgingInput(
        invoiceId = id,
        invoiceNumber = "INV-$id",
        supplierId = supplier,
        supplierName = supplierName,
        invoiceDate = date,
        currencyCode = currency,
        amountDueMinor = due,
        amountPaidMinor = paid
    )

    @Test
    fun groupsOpenBalancesBySupplierCurrencyAndInvoiceAge() {
        val rows = calculateSupplierLiabilityAging(
            listOf(
                invoice("a", due = 1_000, paid = 200),
                invoice("b", date = "2026-09-20", due = 500, paid = 100),
                invoice("c", date = "2026-08-01", due = 700, paid = 200),
                invoice("d", currency = "SAR", due = 300, paid = 0),
                invoice("e", supplier = "supplier-2", supplierName = "المورد ب", due = 900, paid = 0),
                invoice("paid", due = 100, paid = 100)
            ),
            "2026-11-02"
        )
        assertEquals(5, rows.size)
        assertEquals(800L, rows.single { it.bucket == "0–30 يومًا" && it.currencyCode == "YER" && it.supplierId == "supplier-1" }.balanceMinor)
        assertEquals(400L, rows.single { it.bucket == "31–60 يومًا" }.balanceMinor)
        assertEquals(500L, rows.single { it.bucket == "أكثر من 90 يومًا" }.balanceMinor)
        assertTrue(rows.none { it.balanceMinor <= 0L })
    }

    @Test
    fun rejectsDuplicateIdsInvalidAmountsCurrenciesDatesAndFutureInvoices() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierLiabilityAging(listOf(invoice("x"), invoice("x")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierLiabilityAging(listOf(invoice("x", due = 100, paid = 101)), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierLiabilityAging(listOf(invoice("x", currency = "YER1")), "2026-11-02")
        }
        assertThrows(java.time.format.DateTimeParseException::class.java) {
            calculateSupplierLiabilityAging(listOf(invoice("x", date = "bad-date")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierLiabilityAging(listOf(invoice("x", date = "2026-11-03")), "2026-11-02")
        }
    }

    @Test
    fun rejectsOverflowWhenSummingSupplierBalances() {
        assertThrows(ArithmeticException::class.java) {
            calculateSupplierLiabilityAging(
                listOf(
                    invoice("a", due = Long.MAX_VALUE, paid = 0),
                    invoice("b", due = Long.MAX_VALUE, paid = 0)
                ),
                "2026-11-02"
            )
        }
    }
}
