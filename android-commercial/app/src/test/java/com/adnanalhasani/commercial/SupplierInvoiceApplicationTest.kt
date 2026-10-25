package com.adnanalhasani.commercial

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SupplierInvoiceApplicationTest {
    private val app = SupplierInvoiceApplication()
    private val purchaseId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val lineId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val actorId = UUID.fromString("30000000-0000-0000-0000-000000000003")

    private fun purchase(
        received: Long = 5,
        ordered: Long = 5,
        unitCost: Long? = 1_000,
        currency: String? = "YER",
        state: String = "received"
    ) = SupplierPurchaseSnapshot(
        id = purchaseId,
        supplierRef = "supplier-1",
        state = state,
        lines = listOf(SupplierPurchaseLine(lineId, ordered, received, unitCost, currency))
    )

    private fun request(
        quantity: Long = 2,
        price: Long = 1_250,
        tax: Long = 100,
        freight: Long = 50
    ) = SupplierInvoiceRequest(
        purchaseId = purchaseId,
        actorId = actorId,
        invoiceNumber = "  INV-001  ",
        currencyCode = "YER",
        lines = listOf(SupplierInvoiceLine(lineId, quantity, price)),
        idempotencyKey = "invoice-attempt-001",
        taxMinor = tax,
        freightMinor = freight,
        recordedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    @Test
    fun preparesInvoiceAndCalculatesTotals() {
        val result = app.prepareInvoice(
            request(), purchase(), mapOf(lineId to 1L),
            UUID.fromString("40000000-0000-0000-0000-000000000004")
        )

        assertEquals("INV-001", result.invoiceNumber)
        assertEquals(2_500L, result.subtotalMinor)
        assertEquals(100L, result.taxMinor)
        assertEquals(50L, result.freightMinor)
        assertEquals(2_650L, result.totalMinor)
        assertEquals(1L, result.versionNo.toLong())
        assertEquals(2L, result.lines.single().quantityUnits)
    }

    @Test
    fun rejectsQuantityAboveReceivedAndUninvoicedBalance() {
        assertThrows(SupplierInvoiceValidationException::class.java) {
            app.prepareInvoice(request(quantity = 5), purchase(received = 4), emptyMap())
        }
        assertThrows(SupplierInvoiceValidationException::class.java) {
            app.prepareInvoice(request(quantity = 2), purchase(received = 5), mapOf(lineId to 4L))
        }
    }

    @Test
    fun rejectsTotalsThatOverflowLong() {
        assertThrows(SupplierInvoiceValidationException::class.java) {
            app.prepareInvoice(
                request(quantity = 2, price = Long.MAX_VALUE, tax = 0, freight = 0),
                purchase(unitCost = null, currency = null),
                emptyMap()
            )
        }
    }

    @Test
    fun reportsPriceVarianceAgainstPurchasePrice() {
        val invoice = app.prepareInvoice(request(), purchase(), emptyMap())
        val variance = app.priceVariances(invoice, purchase()).single()

        assertEquals(250L, variance.unitVarianceMinor)
        assertEquals(500L, variance.totalVarianceMinor)
    }

    @Test
    fun rejectsUnitPriceVarianceOverflow() {
        val invoice = app.prepareInvoice(
            request(quantity = 1, price = Long.MAX_VALUE, tax = 0, freight = 0),
            purchase(received = 1, ordered = 1, unitCost = 1),
            emptyMap()
        )
        assertThrows(SupplierInvoiceValidationException::class.java) {
            app.priceVariances(invoice, purchase(received = 1, ordered = 1, unitCost = Long.MIN_VALUE))
        }
    }

    @Test
    fun requestFingerprintIsStableAndChangesWithInvoicePayload() {
        val original = request()
        assertEquals(app.requestFingerprint(original), app.requestFingerprint(original))
        assertNotEquals(app.requestFingerprint(original), app.requestFingerprint(request(price = 1_251)))
    }
}
