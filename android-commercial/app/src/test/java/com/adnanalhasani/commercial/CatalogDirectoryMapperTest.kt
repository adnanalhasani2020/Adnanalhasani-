package com.adnanalhasani.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDirectoryMapperTest {
    @Test
    fun mapsCategoriesUnitsTranslationsAndActivityHierarchy() {
        val parsed = parseCatalogDirectory(
            """{
                "categories":[
                    {"code":"FOOD","parent_code":null,"name_ar":"غذاء","name_en":"Food","description_ar":"وصف","description_en":"Description"},
                    {"code":"DRINK","parent_code":"FOOD","name_ar":"مشروبات","name_en":"Drinks","description_ar":null,"description_en":null}
                ],
                "units":[
                    {"code":"KG","name_ar":"كيلوغرام","name_en":"Kilogram","dimension":"mass","base_unit_code":null,"factor_to_base":"1","owner_organization_id":null}
                ],
                "localUnits":[{"code":"EA"},{"code":"BOX"},{"code":""}],
                "categoryTranslations":[{"entity_key":"FOOD","translated_value":"Food localized"}],
                "categoryDescriptionTranslations":[{"entity_key":"FOOD","translated_value":"Localized description"}],
                "unitTranslations":[{"entity_key":"KG","translated_value":"كجم"}],
                "activityDirectory":[
                    {"code":"RETAIL","parent_code":null,"node_kind":"sector","path_ar":"تجارة","path_en":"Trade","default_business_level_code":null,"depth":0,"is_selectable":false},
                    {"code":"GROCERY","parent_code":"RETAIL","node_kind":"activity","name_ar":"بقالة","name_en":"Grocery","default_business_level_code":"grocery","depth":1,"is_selectable":true},
                    {"code":"","parent_code":"RETAIL","node_kind":"activity"}
                ]
            }"""
        )

        assertEquals(setOf("ea", "box"), parsed.localUnitCodes)
        assertEquals(2, parsed.activityNodes.size)
        assertEquals("RETAIL", parsed.activityNodes.first().code)
        assertEquals("Trade", parsed.activityNodes.first().pathEn)
        assertEquals("GROCERY", parsed.activityNodes.last().code)
        assertEquals("Food localized", parsed.categories.first().localizedName)
        assertEquals("Localized description", parsed.categories.first().localizedDescription)
        assertEquals("", parsed.categories.last().parentCode)
        assertEquals("KG", parsed.units.single().code)
        assertEquals("كجم", parsed.units.single().localizedName)
        assertEquals("1", parsed.units.single().factor)
        assertEquals("", parsed.units.single().owner)
    }

    @Test
    fun emptyOptionalCollectionsProduceEmptyDirectoryLists() {
        val parsed = parseCatalogDirectory(
            """{
                "categories":[],
                "units":[],
                "categoryTranslations":[],
                "categoryDescriptionTranslations":[],
                "unitTranslations":[]
            }"""
        )

        assertTrue(parsed.localUnitCodes.isEmpty())
        assertTrue(parsed.activityNodes.isEmpty())
        assertTrue(parsed.categories.isEmpty())
        assertTrue(parsed.units.isEmpty())
    }

    @Test
    fun missingRequiredCategoryArrayRemainsAParsingError() {
        val result = runCatching {
            parseCatalogDirectory("""{"units":[],"categoryTranslations":[],"categoryDescriptionTranslations":[],"unitTranslations":[]}""")
        }

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }
}
