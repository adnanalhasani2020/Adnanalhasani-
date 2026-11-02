package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierPurchaseSummaryTest {
    private fun invoice(
        id: String,
        supplier: String = "supplier-1",
        currency: String = "YER",
        amount: Long = 1_000
    ) = SupplierPurchaseInvoiceInput(id, supplier, "مورد أ", currency, amount)

    @Test
    fun groupsSupplierInvoicesBySupplierAndCurrency() {
        val rows = calculateSupplierPurchaseSummary(listOf(
            invoice("inv-1", amount = 1_000),
            invoice("inv-2", amount = 2_000),
            invoice("inv-3", supplier = "supplier-2", amount = 700),
            invoice("inv-4", currency = "SAR", amount = 300)
        ))
        assertEquals(3, rows.size)
        val yer = rows.single { it.supplierId == "supplier-1" && it.currencyCode == "YER" }
        assertEquals(2, yer.invoiceCount)
        assertEquals(3_000L, yer.totalMinor)
        assertEquals(300L, rows.single { it.currencyCode == "SAR" }.totalMinor)
    }

    @Test
    fun rejectsDuplicateInvoicesInvalidCurrencyAndNegativeTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierPurchaseSummary(listOf(invoice("same"), invoice("same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierPurchaseSummary(listOf(invoice("bad-currency", currency = "YER1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierPurchaseSummary(listOf(invoice("negative", amount = -1)))
        }
    }

    @Test
    fun rejectsOverflowInsteadOfReturningPartialSupplierTotal() {
        assertThrows(ArithmeticException::class.java) {
            calculateSupplierPurchaseSummary(listOf(
                invoice("large-1", amount = Long.MAX_VALUE),
                invoice("large-2", amount = 1)
            ))
        }
    }
}
