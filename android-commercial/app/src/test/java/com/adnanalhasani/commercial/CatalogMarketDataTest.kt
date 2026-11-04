package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMarketDataTest {
    @Test
    fun parsesBranchAndWarehouseOptionsAndKeepsMissingCoordinatesEmpty() {
        val context = parseCatalogMarketContext(
            """{"branches":[{"id":"b1","name":"فرع صنعاء","latitude":15.3,"longitude":44.2},{"id":"b2","name":"فرع بلا موقع","latitude":null,"longitude":null}],"warehouses":[{"id":"w1","name":"المستودع الرئيسي","branch_location_id":"b1"}]}"""
        )

        assertEquals(2, context.branches.size)
        assertEquals("15.3", context.branches[0].latitude)
        assertEquals("", context.branches[1].latitude)
        assertEquals("", context.branches[1].longitude)
        assertEquals("w1", context.warehouses.single().id)
        assertEquals("b1", context.warehouses.single().branchId)
    }

    @Test
    fun parsesNearbyRowsWithoutAddingMerchantIdentity() {
        val items = parseNearbyCatalogItems(
            """[{"global_item_id":"item-1","product_name":"سكر","popularity_band":"high","merchant_count_band":"5-9"}]"""
        )

        assertEquals(
            NearbyCatalogItem("item-1", "سكر", "high", "5-9"),
            items.single()
        )
    }

    @Test
    fun formatsAggregatedPriceHintsAndReturnsEmptyMapForNoRows() {
        val hints = parseCatalogMarketPriceHints(
            """[{"global_item_id":"item-1","currency_code":"YER","suggested_median_minor":1200,"lower_quartile_minor":1000,"upper_quartile_minor":1400,"merchant_count_band":"5-9"}]"""
        )

        assertEquals("YER • وسيط 1200 • نطاق ربعي 1000–1400 • تجار 5-9", hints["item-1"])
        assertTrue(parseCatalogMarketPriceHints("[]").isEmpty())
    }
}
