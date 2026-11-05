package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSaleUnitIntegerQuantityTest {
    @Test
    fun acceptsWholeUnitFactorsAndRejectsFractionalFactors() {
        val product = Product(
            name = "منتج",
            category = "عام",
            stock = 24,
            price = "10",
            unitCode = "EA",
            unitVariantsJson = """[
                {"unitCode":"EA","role":"base","quantityInBase":"1","isDefault":true},
                {"unitCode":"BOX","role":"sale","quantityInBase":"12","isDefault":false},
                {"unitCode":"HALF","role":"sale","quantityInBase":"1.5","isDefault":false}
            ]"""
        )
        val units = localSaleUnits(product)
        assertEquals(listOf("EA", "BOX"), units.map { it.code })
        assertEquals(listOf(1L, 12L), units.map { it.quantityInBase })
        assertTrue(units.all { it.quantityInBase > 0L })
    }

    @Test
    fun rejectsFractionalOnlyConfigurationAndFallsBackToTheBaseUnit() {
        val product = Product(
            name = "منتج",
            category = "عام",
            stock = 24,
            price = "10",
            unitCode = "EA",
            unitVariantsJson = """[
                {"unitCode":"HALF","role":"sale","quantityInBase":"0.5","isDefault":true}
            ]"""
        )
        val units = localSaleUnits(product)
        assertEquals(1, units.size)
        assertEquals("EA", units.single().code)
        assertEquals(1L, units.single().quantityInBase)
    }
}
