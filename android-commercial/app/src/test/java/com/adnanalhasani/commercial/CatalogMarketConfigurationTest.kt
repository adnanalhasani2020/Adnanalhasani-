package com.adnanalhasani.commercial

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMarketConfigurationTest {
    @Test
    fun buildsPayloadUsingOneAuthoritativeBaseAndDefaultSaleUnit() {
        val units = JSONArray(
            """
            [
              {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA","is_default":true},
              {"product_id":"item-1","unit_role":"sale","quantity_in_base":"12","unit_code":"BOX","is_default":true},
              {"product_id":"item-1","unit_role":"sale","quantity_in_base":"6","unit_code":"HALF_BOX","is_default":false}
            ]
            """.trimIndent()
        )

        val payload = buildCatalogConfigurationItems(listOf("item-1"), units)
        val row = payload.getJSONObject(0)
        assertEquals(1, payload.length())
        assertEquals("item-1", row.getString("global_item_id"))
        assertEquals("EA", row.getString("base_unit_code"))
        assertEquals("BOX", row.getString("sale_unit_code"))
    }

    @Test
    fun fallsBackToBaseUnitWhenNoSaleUnitExists() {
        val units = JSONArray(
            """[{"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA"}]"""
        )

        val row = buildCatalogConfigurationItems(listOf("item-1"), units).getJSONObject(0)
        assertEquals("EA", row.getString("base_unit_code"))
        assertEquals("EA", row.getString("sale_unit_code"))
    }

    @Test
    fun rejectsMissingOrAmbiguousBaseUnit() {
        val missing = runCatching {
            buildCatalogConfigurationItems(listOf("item-1"), JSONArray())
        }
        val ambiguous = runCatching {
            buildCatalogConfigurationItems(
                listOf("item-1"),
                JSONArray(
                    """[
                      {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA"},
                      {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"PCS"}
                    ]"""
                )
            )
        }

        assertTrue(missing.isFailure)
        assertTrue(ambiguous.isFailure)
    }

    @Test
    fun rejectsAmbiguousSaleUnitsAndInvalidConversionFactors() {
        val ambiguous = runCatching {
            buildCatalogConfigurationItems(
                listOf("item-1"),
                JSONArray(
                    """[
                      {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA"},
                      {"product_id":"item-1","unit_role":"sale","quantity_in_base":"12","unit_code":"BOX"},
                      {"product_id":"item-1","unit_role":"sale","quantity_in_base":"6","unit_code":"PACK"}
                    ]"""
                )
            )
        }
        val invalidFactor = runCatching {
            buildCatalogConfigurationItems(
                listOf("item-1"),
                JSONArray(
                    """[
                      {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA"},
                      {"product_id":"item-1","unit_role":"sale","quantity_in_base":"0","unit_code":"BOX","is_default":true}
                    ]"""
                )
            )
        }

        assertFalse(ambiguous.isSuccess)
        assertFalse(invalidFactor.isSuccess)
    }
    @Test
    fun rejectsFractionalCatalogUnitConversionFactor() {
        val result = runCatching {
            buildCatalogConfigurationItems(
                listOf("item-1"),
                JSONArray(
                    """[
                      {"product_id":"item-1","unit_role":"base","quantity_in_base":"1","unit_code":"EA"},
                      {"product_id":"item-1","unit_role":"sale","quantity_in_base":"1.5","unit_code":"BOX","is_default":true}
                    ]"""
                )
            )
        }
        assertTrue(result.isFailure)
    }

}
