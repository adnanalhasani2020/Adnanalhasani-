package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogInvoiceMappingMapperTest {
    @Test
    fun mapsLocationWarehousesUnmappedLinesAndCandidateOfferings() {
        val parsed = parseCatalogInvoiceMapping(
            """{
                "location_id":"location-1",
                "warehouses":[
                    {"id":"warehouse-1","name":"Main","branch_location_id":"location-1"}
                ],
                "lines":[
                    {"id":"line-1","line_number":2,"description":"Rice 5kg","quantity":"3","unit_code":"BAG"}
                ],
                "offerings":[
                    {"id":"offering-1","global_item_id":"global-1","name":"Rice","unit_code":"KG"}
                ]
            }"""
        )

        assertEquals("location-1", parsed.locationId)
        assertEquals("warehouse-1", parsed.warehouses.single().id)
        assertEquals("Main", parsed.warehouses.single().name)
        assertEquals("location-1", parsed.warehouses.single().branchId)
        assertEquals("line-1", parsed.lines.single().id)
        assertEquals(2, parsed.lines.single().lineNumber)
        assertEquals("Rice 5kg", parsed.lines.single().description)
        assertEquals("3", parsed.lines.single().quantity)
        assertEquals("BAG", parsed.lines.single().unitCode)
        assertEquals("offering-1", parsed.offerings.single().id)
        assertEquals("global-1", parsed.offerings.single().globalItemId)
        assertEquals("Rice", parsed.offerings.single().name)
        assertEquals("KG", parsed.offerings.single().unitCode)
    }

    @Test
    fun emptyWarehousesAreAllowedAndEmptyRowsRemainEmpty() {
        val parsed = parseCatalogInvoiceMapping(
            """{
                "location_id":"location-2",
                "lines":[],
                "offerings":[]
            }"""
        )

        assertEquals("location-2", parsed.locationId)
        assertTrue(parsed.warehouses.isEmpty())
        assertTrue(parsed.lines.isEmpty())
        assertTrue(parsed.offerings.isEmpty())
    }
}
