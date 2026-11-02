package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierObligationTotalsTest {
    private fun obligation(
        due: Long,
        paid: Long,
        supplier: String = "المورد",
        supplierId: String = "supplier-1",
        purchaseCurrency: String = "YER",
        invoiceCurrency: String = purchaseCurrency,
        obligationCurrency: String = purchaseCurrency
    ) = SupplierObligationInput(
        supplierId = supplierId,
        supplierName = supplier,
        purchaseOrderCurrencyCode = purchaseCurrency,
        invoiceCurrencyCode = invoiceCurrency,
        obligationCurrencyCode = obligationCurrency,
        amountDueMinor = due,
        amountPaidMinor = paid
    )

    @Test
    fun calculatesOutstandingSupplierBalance() {
        val result = calculateSupplierObligationTotals(listOf(obligation(100, 80))).single()
        assertEquals(100L, result.amountDueMinor)
        assertEquals(80L, result.amountPaidMinor)
        assertEquals(20L, result.balanceMinor)
    }

    @Test
    fun preservesOverpaymentAsNegativeSupplierCredit() {
        val result = calculateSupplierObligationTotals(listOf(obligation(100, 120))).single()
        assertEquals(-20L, result.balanceMinor)
    }

    @Test
    fun rejectsCurrencyMismatchAndNegativeAmounts() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierObligationTotals(listOf(obligation(100, 80, invoiceCurrency = "USD")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierObligationTotals(listOf(obligation(-1, 0)))
        }
    }

    @Test
    fun rejectsOverflowInsteadOfWrappingSupplierTotals() {
        assertThrows(ArithmeticException::class.java) {
            calculateSupplierObligationTotals(
                listOf(
                    obligation(Long.MAX_VALUE, 0, supplier = "A", supplierId = "supplier-a"),
                    obligation(1, 0, supplier = "A", supplierId = "supplier-a")
                )
            )
        }
    }
}
