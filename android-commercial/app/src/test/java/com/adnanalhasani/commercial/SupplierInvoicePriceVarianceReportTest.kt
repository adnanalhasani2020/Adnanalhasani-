package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierInvoicePriceVarianceReportTest {
    private fun invoice(
        lines: List<CommercialCloudSupplierInvoiceLineSummary>,
        subtotal: Long,
        tax: Long = 25,
        freight: Long = 10,
        total: Long = subtotal + tax + freight,
        currency: String = "YER"
    ) = CommercialCloudSupplierInvoiceSummary(
        id = "invoice-1",
        invoiceNumber = "INV-1",
        totalMinor = total,
        subtotalMinor = subtotal,
        taxMinor = tax,
        freightMinor = freight,
        currencyCode = currency,
        recordedAt = "2026-11-02T10:00:00Z",
        state = "recorded",
        lines = lines,
        obligation = null
    )

    @Test
    fun computesVarianceUsingTheSameSingleAggregateRoundingAsThePostingTrigger() {
        val report = calculateSupplierInvoicePriceVariance(
            invoice(
                lines = listOf(
                    CommercialCloudSupplierInvoiceLineSummary("l1", "po1", "1.5", 120, 180, "A", 100),
                    CommercialCloudSupplierInvoiceLineSummary("l2", "po2", "2.5", 208, 520, "B", 200)
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
                    listOf(CommercialCloudSupplierInvoiceLineSummary("l1", "po1", "1", 100, 100, "A", null)),
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
                    listOf(CommercialCloudSupplierInvoiceLineSummary("l1", "po1", "1", 100, 99, "A", 100)),
                    subtotal = 100,
                    tax = 0,
                    freight = 0
                )
            )
        }
    }

    @Test
    fun rejectsHeaderTotalMismatchAndInvalidCurrency() {
        val validLine = listOf(CommercialCloudSupplierInvoiceLineSummary("l1", "po1", "1", 100, 100, "A", 100))
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
                        CommercialCloudSupplierInvoiceLineSummary("l1", "po1", "1", 1, Long.MAX_VALUE, "A", Long.MAX_VALUE),
                        CommercialCloudSupplierInvoiceLineSummary("l2", "po2", "1", 1, 1, "B", 1)
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
