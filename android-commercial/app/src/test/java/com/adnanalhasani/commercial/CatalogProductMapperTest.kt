package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogProductMapperTest {
    @Test
    fun mapsProductDetailsUnitsAndRulingsWithoutDroppingRawFields() {
        val products = parseCatalogProducts(
            """[{
                "product_id":"p-1",
                "catalog_code":"CAT-1",
                "product_name":"سكر",
                "short_description":"سكر أبيض",
                "category_code":"FOOD",
                "category_name":"أغذية",
                "brand":"Brand",
                "manufacturer_name":"Maker",
                "country_of_manufacture":"YE",
                "image_url":"https://example.test/sugar.png",
                "is_prescription_only":false,
                "recommendation_score":8,
                "barcode":"123",
                "unit_code":"KG",
                "available_units":[{"unit_code":"BAG","unit_role":"sale","quantity_in_base":"5","barcode":"456","package_name":"كيس","image_url":"unit.png","is_default":true,"unit_name":"كيس","sku":"SKU-1","net_quantity":"5","net_unit_code":"KG"}],
                "created_by":"user-1",
                "created_at":"2026-11-04T10:00:00Z",
                "attributes_schema":{"color":"white"},
                "material_composition":["sugar"],
                "ingredients":["sugar"],
                "recipe":{"instructions":"خلط","ingredients":[{"ingredient_name":"سكر","quantity":"1","unit_code":"KG"}]},
                "specifications":{"grade":"A"},
                "allergens":["none"],
                "storage_instructions":"جاف",
                "usage_instructions":"غذائي",
                "warnings":"لا شيء",
                "shelf_life_days":365,
                "requires_expiry_date":true,
                "is_batch_tracked":true,
                "is_serial_tracked":false,
                "owner_organization_id":"org-1",
                "name_ar":"سكر",
                "name_en":"Sugar",
                "item_type":"product",
                "barcode_gtin":"000123",
                "rulings":[{"ruling_id":"r-1","ruling_domain":"food","authority_code":"AUTH","outcome":"allowed","confidence":"high"}]
            }]""",
            importedBy = "user-import"
        )

        val product = products.single()
        assertEquals("p-1", product.id)
        assertEquals("Brand", product.brand)
        assertEquals("user-import", product.importedBy)
        assertEquals("BAG", product.availableUnits.single().unitCode)
        assertEquals("5", product.availableUnits.single().quantityInBase)
        assertEquals("r-1", product.rulings.single().id)
        assertEquals("allowed", product.rulings.single().outcome)
        assertEquals("[\"sugar\"]", product.rawIngredients)
        assertEquals("{\"grade\":\"A\"}", product.rawSpecifications)
        assertTrue(product.requiresExpiryDate)
        assertTrue(product.isBatchTracked)
    }

    @Test
    fun localizedDetailsOverrideDisplayFieldsAndRawValuesRemainAvailable() {
        val product = parseCatalogProducts(
            """[{
                "product_id":"p-2",
                "product_name":"Original",
                "brand":"Original brand",
                "manufacturer_name":"Original maker",
                "localized_details":{
                    "brand":"Localized brand",
                    "manufacturer_name":"Localized maker",
                    "model_number":"M-2",
                    "part_number":"P-2",
                    "image_alt":"Alt"
                }
            }]""",
            importedBy = "user-2"
        ).single()

        assertEquals("Localized brand", product.brand)
        assertEquals("Localized maker", product.manufacturer)
        assertEquals("Original brand", product.rawBrand)
        assertEquals("Original maker", product.rawManufacturer)
        assertEquals("M-2", product.modelNumber)
        assertEquals("P-2", product.partNumber)
        assertEquals("Alt", product.imageAlt)
    }

    @Test
    fun emptyPageReturnsNoProducts() {
        assertTrue(parseCatalogProducts("[]", importedBy = "user").isEmpty())
    }
}
