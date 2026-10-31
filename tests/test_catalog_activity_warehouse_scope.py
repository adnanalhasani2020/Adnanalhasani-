from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031050000_catalog_activity_warehouse_scope.sql"


class CatalogActivityWarehouseScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_activity_scoped_warehouse_must_match_catalog_setup_activity(self):
        self.assertIn("commercial_organization_catalog_item_setup", self.sql)
        self.assertIn("commercial_warehouses", self.sql)
        self.assertIn("w.activity_id", self.sql)
        self.assertIn("catalog_setup_warehouse_activity_mismatch", self.sql)
        self.assertIn("business_activity_node_id", self.sql)
        self.assertIn("v_setup_node_code text;", self.sql)
        self.assertIn("SELECT n.code INTO v_setup_node_code", self.sql)
        self.assertIn("commercial_catalog_setup_activity_scope", self.sql)

    def test_linked_offering_must_match_configured_activity_and_branch(self):
        self.assertIn("commercial_catalog_item_offerings", self.sql)
        self.assertIn("catalog_offering_activity_node_mismatch", self.sql)
        self.assertIn("catalog_offering_location_or_organization_mismatch", self.sql)
        self.assertIn("v_offering_location_ref IS DISTINCT FROM NEW.location_id::text", self.sql)
        self.assertIn("commercial_catalog_offering_activity_scope", self.sql)
        self.assertIn("AND NOT EXISTS (", self.sql)
        self.assertNotIn("ORDER BY s.updated_at DESC", self.sql)

    def test_validation_is_additive_and_never_posts_stock_or_prices(self):
        self.assertIn("CREATE OR REPLACE FUNCTION", self.sql)
        self.assertIn("BEFORE INSERT OR UPDATE", self.sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", self.sql)
        self.assertNotIn("UPDATE public.commercial_offerings", self.sql)
        self.assertIn("REVOKE ALL ON FUNCTION", self.sql)


if __name__ == "__main__":
    unittest.main()
