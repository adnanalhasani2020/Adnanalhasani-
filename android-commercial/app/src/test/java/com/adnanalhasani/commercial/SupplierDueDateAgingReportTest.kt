package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierDueDateAgingReportTest {
    private fun invoice(
        id: String,
        invoiceDate: String = "2026-09-01",
        dueDate: String = "2026-10-01",
        currency: String = "YER",
        due: Long = 1_000,
        paid: Long = 200,
        supplierId: String = "supplier-1"
    ) = SupplierLiabilityAgingInput(
        invoiceId = id,
        invoiceNumber = "INV-$id",
        supplierId = supplierId,
        supplierName = "المورد",
        invoiceDate = invoiceDate,
        dueDate = dueDate,
        currencyCode = currency,
        amountDueMinor = due,
        amountPaidMinor = paid
    )

    @Test
    fun groupsCurrentBalancesByContractualOverdueBucketsAndCurrency() {
        val rows = calculateSupplierDueDateAging(
            listOf(
                invoice("not-due", dueDate = "2026-11-10"),
                invoice("today", dueDate = "2026-11-02"),
                invoice("30", dueDate = "2026-10-03"),
                invoice("60", dueDate = "2026-09-03"),
                invoice("90", dueDate = "2026-08-04"),
                invoice("old", dueDate = "2026-07-01"),
                invoice("sar", dueDate = "2026-10-01", currency = "SAR")
            ),
            "2026-11-02"
        )
        assertEquals(6, rows.size)
        assertEquals(800L, rows.single { it.bucket == "غير مستحق" }.balanceMinor)
        assertEquals(800L, rows.single { it.bucket == "مستحق اليوم" }.balanceMinor)
        assertEquals(800L, rows.single { it.bucket == "متأخر 1–30 يومًا" }.balanceMinor)
        assertEquals(800L, rows.single { it.bucket == "متأخر 31–60 يومًا" }.balanceMinor)
        assertEquals(800L, rows.single { it.bucket == "متأخر 61–90 يومًا" }.balanceMinor)
        assertEquals(800L, rows.single { it.bucket == "متأخر أكثر من 90 يومًا" }.balanceMinor)
    }

    @Test
    fun excludesPaidInvoicesButBlocksOpenInvoicesWithoutVerifiedDates() {
        assertEquals(
            emptyList<SupplierLiabilityAgingSummary>(),
            calculateSupplierDueDateAging(listOf(invoice("paid", due = 100, paid = 100)), "2026-11-02")
        )
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("missing", dueDate = "")), "2026-11-02")
        }
    }

    @Test
    fun rejectsInvalidDatesAmountsCurrenciesAndDuplicateInvoices() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("future", invoiceDate = "2026-11-03")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("reverse", invoiceDate = "2026-10-10", dueDate = "2026-10-09")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("bad", currency = "YER1")), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("bad-amount", due = 100, paid = 101)), "2026-11-02")
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierDueDateAging(listOf(invoice("dup"), invoice("dup")), "2026-11-02")
        }
    }

    @Test
    fun rejectsOverflowWhenSummingSupplierBalances() {
        assertThrows(ArithmeticException::class.java) {
            calculateSupplierDueDateAging(
                listOf(
                    invoice("a", due = Long.MAX_VALUE, paid = 0),
                    invoice("b", due = 1, paid = 0)
                ),
                "2026-11-02"
            )
        }
    }
}
