package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogWorkflowMappersTest {
    @Test
    fun mapsInvoicePurchaseRequestsAndOptionalPurchaseOrderId() {
        val requests = parseInvoicePurchaseRequests(
            """[
                {
                    "id":"request-1",
                    "source_organization_id":"org-a",
                    "destination_organization_id":"org-b",
                    "source_invoice_number":"INV-10",
                    "currency_code":"YER",
                    "total_minor":12500,
                    "status":"pending",
                    "destination_purchase_order_id":null,
                    "created_at":"2026-11-04T09:00:00Z"
                },
                {
                    "id":"request-2",
                    "source_organization_id":"org-c",
                    "destination_organization_id":"org-b",
                    "source_invoice_number":"INV-11",
                    "currency_code":"SAR",
                    "total_minor":250,
                    "status":"accepted",
                    "destination_purchase_order_id":"po-2",
                    "created_at":"2026-11-04T10:00:00Z"
                }
            ]"""
        )

        assertEquals(2, requests.size)
        assertEquals("INV-10", requests.first().invoiceNumber)
        assertEquals(12500L, requests.first().totalMinor)
        assertEquals("", requests.first().purchaseOrderId)
        assertEquals("po-2", requests.last().purchaseOrderId)
        assertEquals("accepted", requests.last().status)
    }

    @Test
    fun mapsPendingTransfersAndEmptyResponse() {
        val transfers = parsePendingCatalogTransfers(
            """[{
                "id":"transfer-1",
                "source_organization_id":"org-a",
                "destination_organization_id":"org-b",
                "item_count":7,
                "created_at":"2026-11-04T11:00:00Z"
            }]"""
        )

        assertEquals(1, transfers.size)
        assertEquals("transfer-1", transfers.single().id)
        assertEquals("org-a", transfers.single().source)
        assertEquals("org-b", transfers.single().destination)
        assertEquals(7, transfers.single().itemCount)
        assertTrue(parseInvoicePurchaseRequests("[]").isEmpty())
        assertTrue(parsePendingCatalogTransfers("[]").isEmpty())
    }
}
