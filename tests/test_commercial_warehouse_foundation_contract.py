from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase" / "migrations" / "20261024001000_commercial_warehouse_category_department_foundation.sql"


class CommercialWarehouseFoundationContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_additive_model_reuses_existing_organization_and_product_masters(self):
        for table in (
            "commercial_warehouses",
            "commercial_storage_locations",
            "commercial_product_categories",
            "commercial_departments",
            "commercial_offering_categories",
            "commercial_department_warehouse_assignments",
        ):
            self.assertIn(f"CREATE TABLE IF NOT EXISTS public.{table}", self.sql)
        self.assertIn("REFERENCES public.commercial_organization_locations(id)", self.sql)
        self.assertIn("REFERENCES public.commercial_offerings(id)", self.sql)
        self.assertIn("activity_id is optional", self.sql)
        self.assertNotIn("CHECK (activity_id IS NULL OR activity_id IS NOT NULL)", self.sql)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_products", self.sql)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_global_catalog_items", self.sql)

    def test_nested_trees_reject_cycles_and_cross_organization_parents(self):
        self.assertIn("storage_location_hierarchy_cycle", self.sql)
        self.assertIn("category_hierarchy_cycle", self.sql)
        self.assertIn("department_hierarchy_cycle", self.sql)
        self.assertIn("category_parent_scope_mismatch", self.sql)
        self.assertIn("department_parent_scope_mismatch", self.sql)
        self.assertIn("storage_parent_scope_mismatch", self.sql)

    def test_tenant_policies_and_relationship_guards_are_defined(self):
        self.assertIn("commercial_organization_members", self.sql)
        self.assertIn("m.role IN (''owner'',''admin'',''manager'')", self.sql)
        self.assertIn("offering_category_scope_mismatch", self.sql)
        self.assertIn("department_warehouse_scope_mismatch", self.sql)
        self.assertIn("FROM anon", self.sql)

    def test_master_data_label_falls_back_to_english_when_arabic_is_missing(self):
        repository = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt").read_text(encoding="utf-8")
        self.assertIn('row.optString("name_ar")', repository)
        self.assertIn('row.optString("name_en")', repository)
        self.assertIn('ifBlank { row.optString("name_en")', repository)

    def test_does_not_change_existing_stock_balances_or_checkout(self):
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_inventory_balances", self.sql)
        self.assertNotIn("CREATE OR REPLACE FUNCTION public.commercial_checkout_sale", self.sql)
        self.assertIn("stock posting is intentionally not wired in this additive phase", self.sql)


if __name__ == "__main__":
    unittest.main()
