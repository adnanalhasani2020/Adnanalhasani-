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
        assertEquals(listOf("EA", "BOX", "HALF"), units.map { it.code })
        assertEquals(listOf(1L, 12L, 0L), units.map { it.quantityInBase })
        assertTrue(units.filter { it.code != "HALF" }.all { it.quantityInBase > 0L })
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
        assertEquals(listOf("HALF", "EA"), units.map { it.code })
        assertEquals(0L, units.first { it.code == "HALF" }.quantityInBase)
        assertEquals(1L, units.first { it.code == "EA" }.quantityInBase)
    }
}
