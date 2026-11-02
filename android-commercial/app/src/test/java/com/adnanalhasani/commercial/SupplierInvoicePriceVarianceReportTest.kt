package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierInvoicePriceVarianceReportTest {
    private fun invoice(
        lines: List<SupplierInvoicePriceVarianceLineInput>,
        subtotal: Long,
        tax: Long = 25,
        freight: Long = 10,
        total: Long = subtotal + tax + freight,
        currency: String = "YER"
    ) = SupplierInvoicePriceVarianceInput(
        currencyCode = currency,
        subtotalMinor = subtotal,
        taxMinor = tax,
        freightMinor = freight,
        totalMinor = total,
        lines = lines
    )

    @Test
    fun computesVarianceUsingTheSameSingleAggregateRoundingAsThePostingTrigger() {
        val report = calculateSupplierInvoicePriceVariance(
            invoice(
                lines = listOf(
                    SupplierInvoicePriceVarianceLineInput("1.5", 120, 180, 100),
                    SupplierInvoicePriceVarianceLineInput("2.5", 208, 520, 200)
                ),
                subtotal = 700
            )
        )
        assertEquals(650L, report.expectedSubtotalMinor)
        assertEquals(700L, report.invoiceSubtotalMinor)
        assertEquals(50L, report.varianceMinor)
    }

    @Test
    fun rejectsMissingPurchasePriceBaseline() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierInvoicePriceVariance(
                invoice(
                    listOf(SupplierInvoicePriceVarianceLineInput("1", 100, 100, null)),
                    subtotal = 100,
                    tax = 0,
                    freight = 0
                )
            )
        }
    }

    @Test
    fun rejectsInvoiceLineSubtotalMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierInvoicePriceVariance(
                invoice(
                    listOf(SupplierInvoicePriceVarianceLineInput("1", 100, 99, 100)),
                    subtotal = 100,
                    tax = 0,
                    freight = 0
                )
            )
        }
    }

    @Test
    fun rejectsHeaderTotalMismatchAndInvalidCurrency() {
        val validLine = listOf(SupplierInvoicePriceVarianceLineInput("1", 100, 100, 100))
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierInvoicePriceVariance(invoice(validLine, subtotal = 100, tax = 0, freight = 0, total = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateSupplierInvoicePriceVariance(invoice(validLine, subtotal = 100, tax = 0, freight = 0, currency = "YER1"))
        }
    }

    @Test
    fun detectsLongOverflowInsteadOfReturningPartialVariance() {
        assertThrows(ArithmeticException::class.java) {
            calculateSupplierInvoicePriceVariance(
                invoice(
                    listOf(
                        SupplierInvoicePriceVarianceLineInput("1", 1, Long.MAX_VALUE, Long.MAX_VALUE),
                        SupplierInvoicePriceVarianceLineInput("1", 1, 1, 1)
                    ),
                    subtotal = Long.MAX_VALUE,
                    tax = 0,
                    freight = 0,
                    total = Long.MAX_VALUE
                )
            )
        }
    }
}
