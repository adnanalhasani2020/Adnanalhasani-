package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportSalesTaxDiscountValidationTest {
    @Test
    fun rejectsNegativeDiscount() {
        assertInvalid(sale(discount = -1L))
    }

    @Test
    fun rejectsDiscountGreaterThanSubtotal() {
        assertInvalid(sale(subtotal = 100L, discount = 101L))
    }

    @Test
    fun rejectsNegativeRecordedTax() {
        assertInvalid(sale(tax = -1L))
    }

    @Test
    fun rejectsInvalidCurrency() {
        assertInvalid(sale(currency = ""))
    }

    @Test
    fun preservesCloudAndSalesSourceErrorPrecedence() {
        assertEquals(
            "تقارير خصومات المبيعات والضرائب المسجلة تتطلب فواتير السحابة.",
            validate(cloudMode = false, salesDocumentsLoadError = "sales unavailable")
        )
        assertEquals(
            "sales unavailable",
            validate(salesDocumentsLoadError = "sales unavailable")
        )
    }

    @Test
    fun ignoresInvalidAmountsOutsideSelectedPeriod() {
        assertEquals(
            "",
            validate(sales = listOf(sale(createdAt = "2025-12-31", discount = -1L)))
        )
    }

    @Test
    fun rejectsCompletedInvoiceWithInvalidDateBecauseItsPeriodCannotBeDetermined() {
        assertEquals(
            "توجد فاتورة بيع مكتملة بتاريخ غير صالح؛ تم حجب تقرير الخصومات والضرائب المسجلة.",
            validate(sales = listOf(sale(createdAt = "not-a-date")))
        )
    }

    private fun assertInvalid(document: CommercialCloudSaleDocument) {
        assertEquals(
            "توجد فاتورة بيع مكتملة بخصم أو ضريبة أو عملة غير صالحة؛ تم حجب تقرير الخصومات والضرائب المسجلة.",
            validate(sales = listOf(document))
        )
    }

    private fun validate(
        cloudMode: Boolean = true,
        salesDocumentsLoadError: String = "",
        sales: List<CommercialCloudSaleDocument> = listOf(sale())
    ) = reportSalesTaxDiscountValidationError(
        cloudMode = cloudMode,
        salesDocumentsLoadError = salesDocumentsLoadError,
        cloudSalesDocuments = sales,
        startMillis = reportDateMillis("2026-01-01")!!,
        endExclusive = reportDateMillis("2026-02-01")!!
    )

    private fun sale(
        createdAt: String = "2026-01-10T10:00:00Z",
        subtotal: Long = 100L,
        discount: Long = 10L,
        tax: Long = 5L,
        currency: String = "YER"
    ) = CommercialCloudSaleDocument(
        id = "sale-1",
        customerId = "customer-1",
        userId = "user-1",
        receiptNumber = "R-1",
        customerName = "Customer",
        customerPhone = "",
        customerEmail = "",
        totalMinor = subtotal - discount + tax,
        subtotalMinor = subtotal,
        discountMinor = discount,
        taxMinor = tax,
        saleStatus = "completed",
        paymentStatus = "paid",
        createdAt = createdAt,
        currencyCode = currency,
        lines = emptyList(),
        payments = emptyList(),
        returns = emptyList()
    )
}
