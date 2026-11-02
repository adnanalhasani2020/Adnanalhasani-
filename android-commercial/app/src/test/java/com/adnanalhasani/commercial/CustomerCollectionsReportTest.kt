package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

class CustomerCollectionsReportTest {
    private fun payment(
        id: String,
        customerId: String = "customer-1",
        customerName: String = "عميل أ",
        currency: String = "YER",
        amount: Long = 100
    ) = CustomerCollectionPaymentInput(id, customerId, customerName, currency, amount)

    @Test
    fun groupsSuccessfulPaymentsByCustomerAndCurrency() {
        val rows = calculateCustomerCollectionsByCustomer(listOf(
            payment("p1", amount = 100),
            payment("p2", amount = 250),
            payment("p3", customerId = "customer-2", customerName = "عميل ب", amount = 80),
            payment("p4", currency = "SAR", amount = 40)
        ))
        assertEquals(3, rows.size)
        val customer = rows.single { it.customerId == "customer-1" && it.currencyCode == "YER" }
        assertEquals(2, customer.paymentCount)
        assertEquals(350L, customer.totalMinor)
        assertEquals(40L, rows.single { it.currencyCode == "SAR" }.totalMinor)
    }

    @Test
    fun keepsAnonymousCustomersSeparateBySavedDisplayName() {
        val rows = calculateCustomerCollectionsByCustomer(listOf(
            payment("p1", customerId = "", customerName = "نقدي أ", amount = 100),
            payment("p2", customerId = "", customerName = "نقدي ب", amount = 200)
        ))
        assertEquals(2, rows.size)
    }

    @Test
    fun rejectsDuplicatePaymentIdsInvalidAmountsAndOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerCollectionsByCustomer(listOf(payment("same"), payment("same")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            calculateCustomerCollectionsByCustomer(listOf(payment("zero", amount = 0)))
        }
        assertThrows(ArithmeticException::class.java) {
            calculateCustomerCollectionsByCustomer(listOf(
                payment("large-1", amount = Long.MAX_VALUE),
                payment("large-2", amount = 1)
            ))
        }
    }
}
