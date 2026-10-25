package com.adnanalhasani.commercial

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Kotlin domain port of src/agent_core/supplier_invoice_application.py.
 *
 * This class validates invoice facts only. Persisting the invoice must be implemented by an
 * authenticated, transactional backend; the Android client must not write invoice, inventory,
 * payment, or journal tables directly.
 */
data class SupplierInvoiceLine(
    val purchaseLineId: UUID,
    val quantityUnits: Long,
    val unitPriceMinor: Long
)

data class SupplierInvoiceLineRecord(
    val purchaseLineId: UUID,
    val quantityUnits: Long,
    val unitPriceMinor: Long,
    val lineTotalMinor: Long
)

data class SupplierInvoicePriceVariance(
    val purchaseLineId: UUID,
    val quantityUnits: Long,
    val expectedUnitPriceMinor: Long,
    val invoicedUnitPriceMinor: Long,
    val unitVarianceMinor: Long,
    val totalVarianceMinor: Long,
    val currencyCode: String
)

data class SupplierInvoiceRecord(
    val id: UUID,
    val purchaseId: UUID,
    val supplierRef: String,
    val invoiceNumber: String,
    val currencyCode: String,
    val subtotalMinor: Long,
    val taxMinor: Long,
    val freightMinor: Long,
    val totalMinor: Long,
    val state: String,
    val recordedAt: Instant,
    val recordedByRef: UUID,
    val versionNo: Int,
    val lines: List<SupplierInvoiceLineRecord>
)

data class SupplierPurchaseLine(
    val id: UUID,
    val quantityOrdered: Long,
    val quantityReceived: Long,
    val unitCostMinor: Long?,
    val currencyCode: String?
)

data class SupplierPurchaseSnapshot(
    val id: UUID,
    val supplierRef: String,
    val state: String,
    val lines: List<SupplierPurchaseLine>
)

data class SupplierInvoiceRequest(
    val purchaseId: UUID,
    val actorId: UUID,
    val invoiceNumber: String,
    val currencyCode: String,
    val lines: List<SupplierInvoiceLine>,
    val idempotencyKey: String,
    val taxMinor: Long = 0,
    val freightMinor: Long = 0,
    val recordedAt: Instant = Instant.now()
)

class SupplierInvoiceValidationException(message: String) : IllegalArgumentException(message)

/**
 * Pure Kotlin equivalent of the validation/calculation rules in the Python application.
 * It deliberately does not create supplier liabilities, payments, stock movements or GL entries.
 */
class SupplierInvoiceApplication {
    companion object {
        private const val MAX_INTEGER: Long = Long.MAX_VALUE
        private val CURRENCY_PATTERN = Regex("^[A-Z]{3}$")
        private val ALLOWED_PURCHASE_STATES = setOf("ordered", "partially_received", "received")
    }

    fun prepareInvoice(
        request: SupplierInvoiceRequest,
        purchase: SupplierPurchaseSnapshot,
        previouslyInvoicedByLine: Map<UUID, Long>,
        invoiceId: UUID = UUID.randomUUID()
    ): SupplierInvoiceRecord {
        if (request.purchaseId != purchase.id) {
            invalid("Supplier invoice references a different Purchase")
        }
        if (request.invoiceNumber.isBlank()) invalid("Supplier invoice number is required")
        val number = request.invoiceNumber.trim()
        if (!CURRENCY_PATTERN.matches(request.currencyCode)) {
            invalid("Currency code must be a canonical three-letter uppercase code")
        }
        amount(request.taxMinor, "tax_minor")
        amount(request.freightMinor, "freight_minor")
        if (request.idempotencyKey.isBlank() || request.idempotencyKey.trim().length > 200) {
            invalid("idempotency_key must be a non-empty string of at most 200 characters")
        }
        if (request.lines.isEmpty()) invalid("Supplier invoice requires at least one line")
        if (purchase.state !in ALLOWED_PURCHASE_STATES) {
            invalid("Supplier invoice requires an ordered Purchase")
        }

        val purchaseLines = purchase.lines.associateBy { it.id }
        val seen = HashSet<UUID>()
        var subtotal = 0L
        val normalizedLines = request.lines.map { line ->
            if (!seen.add(line.purchaseLineId)) {
                invalid("Supplier invoice cannot repeat a purchase line")
            }
            if (line.quantityUnits <= 0) invalid("Invoiced quantity must be a positive integer")
            amount(line.unitPriceMinor, "unit_price_minor")
            val baseline = purchaseLines[line.purchaseLineId]
                ?: invalid("Supplier invoice line does not belong to this Purchase")
            if (baseline.quantityReceived < 0 || baseline.quantityReceived > baseline.quantityOrdered) {
                invalid("Persisted Purchase receipt quantity is invalid")
            }
            if (line.quantityUnits > baseline.quantityReceived) {
                invalid("Supplier invoice quantity exceeds received quantity for its Purchase line")
            }
            if (baseline.unitCostMinor != null && baseline.currencyCode != request.currencyCode) {
                invalid("Supplier invoice currency differs from the Purchase line currency")
            }
            val alreadyInvoiced = previouslyInvoicedByLine[line.purchaseLineId] ?: 0L
            if (alreadyInvoiced < 0 || alreadyInvoiced > baseline.quantityReceived ||
                line.quantityUnits > baseline.quantityReceived - alreadyInvoiced
            ) {
                invalid("Supplier invoice quantity exceeds uninvoiced received quantity")
            }
            val lineTotal = multiplyExact(line.quantityUnits, line.unitPriceMinor,
                "Supplier invoice line total exceeds integer limits")
            subtotal = addExact(subtotal, lineTotal, "Supplier invoice subtotal exceeds integer limits")
            SupplierInvoiceLineRecord(line.purchaseLineId, line.quantityUnits, line.unitPriceMinor, lineTotal)
        }
        val withTax = addExact(subtotal, request.taxMinor, "Supplier invoice total exceeds integer limits")
        val total = addExact(withTax, request.freightMinor, "Supplier invoice total exceeds integer limits")

        return SupplierInvoiceRecord(
            id = invoiceId,
            purchaseId = purchase.id,
            supplierRef = purchase.supplierRef,
            invoiceNumber = number,
            currencyCode = request.currencyCode,
            subtotalMinor = subtotal,
            taxMinor = request.taxMinor,
            freightMinor = request.freightMinor,
            totalMinor = total,
            state = "recorded",
            recordedAt = request.recordedAt,
            recordedByRef = request.actorId,
            versionNo = 1,
            lines = normalizedLines
        )
    }

    fun priceVariances(
        invoice: SupplierInvoiceRecord,
        purchase: SupplierPurchaseSnapshot
    ): List<SupplierInvoicePriceVariance> {
        if (invoice.purchaseId != purchase.id) {
            invalid("Supplier invoice references a different Purchase")
        }
        val purchaseLines = purchase.lines.associateBy { it.id }
        return invoice.lines.mapNotNull { line ->
            val baseline = purchaseLines[line.purchaseLineId]
                ?: invalid("Supplier invoice line references a missing Purchase line")
            val expectedPrice = baseline.unitCostMinor ?: return@mapNotNull null
            if (baseline.currencyCode != invoice.currencyCode) {
                invalid("Cannot compare supplier invoice price across currencies")
            }
            val delta = try {
                Math.subtractExact(line.unitPriceMinor, expectedPrice)
            } catch (_: ArithmeticException) {
                invalid("Supplier invoice unit price variance exceeds integer limits")
            }
            val totalDelta = try {
                Math.multiplyExact(delta, line.quantityUnits)
            } catch (_: ArithmeticException) {
                invalid("Supplier invoice price variance exceeds integer limits")
            }
            SupplierInvoicePriceVariance(
                purchaseLineId = line.purchaseLineId,
                quantityUnits = line.quantityUnits,
                expectedUnitPriceMinor = expectedPrice,
                invoicedUnitPriceMinor = line.unitPriceMinor,
                unitVarianceMinor = delta,
                totalVarianceMinor = totalDelta,
                currencyCode = invoice.currencyCode
            )
        }
    }

    /**
     * Stable request digest for idempotency checks at the persistence boundary.
     * A backend must compute and verify its own fingerprint inside the same transaction.
     */
    fun requestFingerprint(request: SupplierInvoiceRequest): String {
        val canonical = buildString {
            appendField("record_supplier_invoice")
            appendField(request.purchaseId.toString())
            appendField(request.actorId.toString())
            appendField(request.invoiceNumber.trim())
            appendField(request.currencyCode)
            appendField(request.taxMinor.toString())
            appendField(request.freightMinor.toString())
            request.lines.forEach { line ->
                appendField(line.purchaseLineId.toString())
                appendField(line.quantityUnits.toString())
                appendField(line.unitPriceMinor.toString())
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append(';')
    }

    private fun amount(value: Long, label: String): Long {
        if (value < 0 || value > MAX_INTEGER) invalid("$label must be a non-negative integer")
        return value
    }

    private fun addExact(left: Long, right: Long, message: String): Long =
        try { Math.addExact(left, right) } catch (_: ArithmeticException) { invalid(message) }

    private fun multiplyExact(left: Long, right: Long, message: String): Long =
        try { Math.multiplyExact(left, right) } catch (_: ArithmeticException) { invalid(message) }

    private fun invalid(message: String): Nothing = throw SupplierInvoiceValidationException(message)
}
