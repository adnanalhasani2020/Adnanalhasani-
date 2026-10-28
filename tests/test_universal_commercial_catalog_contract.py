from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
ANDROID = ROOT / "android-commercial" / "app" / "src" / "main" / "java" / "com" / "adnanalhasani" / "commercial"


class UniversalCommercialCatalogContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = (MIGRATIONS / "20261023231027_universal_commercial_catalog.sql").read_text(encoding="utf-8")
        cls.seed = (MIGRATIONS / "20261023231040_seed_universal_catalog_templates.sql").read_text(encoding="utf-8")
        cls.discovery = (MIGRATIONS / "20261023231045_catalog_discovery_rpc.sql").read_text(encoding="utf-8")
        cls.management = (MIGRATIONS / "20261023231049_catalog_management_rpc.sql").read_text(encoding="utf-8")
        cls.update_rpc = (MIGRATIONS / "20261023231054_catalog_update_rpc.sql").read_text(encoding="utf-8")
        cls.android = (ANDROID / "CatalogScreen.kt").read_text(encoding="utf-8")
        cls.main = (ANDROID / "MainActivity.kt").read_text(encoding="utf-8")
        cls.edit_dialog = (ANDROID / "CatalogProductEditDialog.kt").read_text(encoding="utf-8")

    def test_catalog_supports_multi_select_import_without_forcing_prices(self):
        self.assertIn("selectedCatalogItemIds", self.android)
        self.assertIn("موافق — إضافة دون تسعير", self.android)
        self.assertIn("إنشاء صنف جديد", self.android)
        self.assertIn("items.filter { it.id in selectedCatalogItemIds }.forEach(onAddToInventory)", self.android)
        self.assertIn("if (cloudMode)", self.android)
        self.assertIn("ربط هذه القوالب بمخزون المؤسسة السحابي غير متاح", self.android)

    def test_extends_existing_masters_without_parallel_product_or_unit_masters(self):
        self.assertIn("ALTER TABLE public.commercial_global_catalog_items", self.schema)
        self.assertIn("ALTER TABLE public.commercial_global_catalog_categories", self.schema)
        self.assertIn("ALTER TABLE public.commercial_global_units", self.management)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_catalog_products", self.schema)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_catalog_categories", self.schema)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_catalog_units", self.schema)

    def test_unbounded_hierarchy_and_organization_ownership_guards_exist(self):
        self.assertIn("commercial_catalog_category_cycle_guard", self.schema)
        self.assertIn("commercial_catalog_item_category_owner_guard", self.schema)
        self.assertIn("owner_organization_id", self.schema)
        self.assertIn("commercial_catalog_member_of", self.schema)
        self.assertIn("DROP POLICY IF EXISTS commercial_global_catalog_read_active", self.schema)
        self.assertIn("commercial_global_catalog_public_read", self.schema)
        self.assertIn("commercial_global_entity_translations_public_read", self.schema)

    def test_product_units_and_external_images_are_supported(self):
        self.assertIn("commercial_catalog_product_units", self.schema)
        self.assertIn("quantity_in_base", self.schema)
        self.assertIn("ux_commercial_catalog_product_unit_barcode", self.schema)
        self.assertIn("image_url", self.schema)
        self.assertIn("image_url_must_be_https", self.management)
        self.assertNotIn("storage.objects", self.schema + self.management)

    def test_catalog_discovery_returns_barcode_units_and_activity_recommendations(self):
        for field in ("barcode text", "unit_code text", "available_units jsonb", "recommendation_score integer"):
            self.assertIn(field, self.discovery)
        self.assertIn("commercial_catalog_discover_products", self.android)
        self.assertIn("p_sector_code", self.discovery)
        self.assertIn("p_business_level_code", self.discovery)
        self.assertIn("commercial_catalog_product_regions", self.discovery)
        self.assertIn("material_composition jsonb", self.discovery)
        self.assertIn("rulings jsonb", self.discovery)
        self.assertIn("localized_details jsonb", self.discovery)
        self.assertIn("model_number text", self.discovery)
        self.assertIn("commercial_catalog_product_rulings", self.discovery)

    def test_transactional_authoring_covers_metadata_translations_and_rulings(self):
        self.assertIn("commercial_catalog_create_product", self.management)
        self.assertIn("u.dimension='count'", self.management)
        self.assertIn("product_requires_one_default_base_unit", self.management)
        self.assertIn("commercial_catalog_create_category", self.management)
        self.assertIn("commercial_catalog_create_unit", self.management)
        self.assertIn("v_code := 'O'", self.management)
        self.assertIn("commercial_global_entity_translations", self.management)
        self.assertIn("ingredients", self.management)
        self.assertIn("material_composition", self.management)
        self.assertIn("commercial_catalog_product_rulings", self.management)
        self.assertIn("commercial_catalog_product_recipes", self.management)
        self.assertIn("commercial_catalog_recipe_ingredients", self.management)
        self.assertIn("human_authored", self.management)
        self.assertIn("religion_code", self.management)
        self.assertIn("commercial_catalog_review_ruling", self.management)
        self.assertIn("commercial_catalog_update_product_details", self.update_rpc)
        self.assertIn("owner_organization_id=p_organization_id", self.update_rpc)
        self.assertIn("reviewRuling", self.android)
        self.assertIn("CatalogProductEditDialog", self.android)
        self.assertIn("تعديل بيانات الصنف", self.android)
        self.assertIn("product_unit_dimension_mismatch", self.management)

    def test_cross_account_transfer_requires_destination_acceptance(self):
        self.assertIn("commercial_catalog_send_transfer", self.management)
        self.assertIn("commercial_catalog_accept_transfer", self.management)
        self.assertIn("commercial_catalog_reject_transfer", self.management)
        self.assertIn("'pending'", self.management)
        self.assertIn("global_item_ids", self.management)
        self.assertIn("commercial_catalog_accept_transfer", self.android)

    def test_android_import_preserves_catalog_identity_without_claiming_cloud_sync(self):
        for field in ("catalogCode", "barcode", "unitCode", "imageUrl", "manufacturer", "countryOfManufacture"):
            self.assertIn(field, self.main)
        self.assertIn("إضافة إلى المخزون المحلي", self.android)
        self.assertIn("quantityIsWholeBaseUnits", self.main)
        self.assertIn("baseQuantitySold", self.main)
        self.assertIn("unitVariantsJson", self.main)
        self.assertIn("commercial_catalog_create_product", self.android)
        self.assertIn("commercial_catalog_send_transfer", self.android)

    def test_product_editor_round_trips_structured_json_arrays(self):
        self.assertIn("val structured: Any?", self.edit_dialog)
        self.assertIn("runCatching { JSONObject(line) }", self.edit_dialog)
        self.assertIn("runCatching { JSONArray(line) }", self.edit_dialog)
        self.assertIn("put(structured ?: line)", self.edit_dialog)

    def test_cross_industry_starter_templates_are_bilingual_and_unbranded(self):
        self.assertIn("PHONE-SMARTPHONE", self.seed)
        self.assertIn("AUTO-OIL-FILTER", self.seed)
        self.assertIn("MED-GLOVES-NITRILE", self.seed)
        self.assertIn("HOTEL-TOWEL", self.seed)
        self.assertIn("MANUF-BOLT", self.seed)
        self.assertIn("catalog_template", self.seed)
        self.assertIn("commercial_global_entity_translations", self.seed)
        self.assertIn("commercial_catalog_product_units", self.seed)
        self.assertIn("commercial_catalog_product_business_levels", self.seed)


if __name__ == "__main__":
    unittest.main()
