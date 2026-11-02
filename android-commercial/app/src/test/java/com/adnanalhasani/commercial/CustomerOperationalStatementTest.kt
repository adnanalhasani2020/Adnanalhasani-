package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CustomerOperationalStatementTest {
    private fun sale(
        total: Long = 1_000,
        currency: String = "YER",
        payments: List<CustomerStatementPaymentInput> = emptyList(),
        returns: List<CustomerStatementReturnInput> = emptyList()
    ) = CustomerStatementInvoiceInput(
        customerId = "customer-1",
        receiptNumber = "R-1",
        customerName = "عميل",
        totalMinor = total,
        currencyCode = currency,
        createdAt = "2026-11-01T10:00:00Z",
        payments = payments,
        returns = returns
    )

    @Test
    fun balancesInvoicePaymentCreditNoteAndExecutedRefundAsSeparateMovements() {
        val movements = buildCustomerOperationalStatementMovements(
            listOf(
                sale(
                    payments = listOf(CustomerStatementPaymentInput("payment-1", 600, "succeeded", "cash", "2026-11-01T11:00:00Z")),
                    returns = listOf(CustomerStatementReturnInput(
                        "return-1", "completed", "refunded", "restocked", 200, 200,
                        "2026-11-01T12:00:00Z", "2026-11-01T13:00:00Z"
                    ))
                )
            )
        )
        assertEquals(listOf(1_000L, -600L, -200L, 200L), movements.map { it.amount })
        assertEquals(400L, sumReportAmountsExactly(movements.map { it.amount }))
    }

    @Test
    fun blocksCompletedReturnsWithoutApprovedAmountOrValidUpdateDate() {
        assertThrows(IllegalArgumentException::class.java) {
            buildCustomerOperationalStatementMovements(
                listOf(sale(returns = listOf(
                    CommercialCloudReturnSummary("return-1", "completed", "pending", "pending", 200, null, "2026-11-01T12:00:00Z", "2026-11-01T13:00:00Z")
                )))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCustomerOperationalStatementMovements(
                listOf(sale(returns = listOf(
                    CommercialCloudReturnSummary("return-1", "completed", "pending", "pending", 200, 200, "2026-11-01T12:00:00Z", "")
                )))
            )
        }
    }

    @Test
    fun blocksInvalidPaymentTimestampAndCurrencyInsteadOfUsingInvoiceDateOrUnknownCurrency() {
        assertThrows(IllegalArgumentException::class.java) {
            buildCustomerOperationalStatementMovements(
                listOf(sale(payments = listOf(
                    CommercialCloudPaymentSummary("payment-1", 500, "succeeded", "cash", "")
                )))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildCustomerOperationalStatementMovements(listOf(sale(currency = "YER1")))
        }
    }

    @Test
    fun blocksRefundMarkedAsExecutedBeforeReturnCompletion() {
        assertThrows(IllegalArgumentException::class.java) {
            buildCustomerOperationalStatementMovements(
                listOf(sale(returns = listOf(
                    CommercialCloudReturnSummary("return-1", "approved", "refunded", "pending", 200, 200, "2026-11-01T12:00:00Z", "2026-11-01T13:00:00Z")
                )))
            )
        }
    }
}
