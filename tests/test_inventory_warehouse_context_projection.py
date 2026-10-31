from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031070000_inventory_warehouse_context_projection.sql"


class InventoryWarehouseContextProjectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_projection_reuses_existing_inventory_and_catalog_links(self):
        self.assertIn("public.commercial_inventory_balances", self.sql)
        self.assertIn("public.commercial_catalog_item_offerings", self.sql)
        self.assertIn("public.commercial_organization_catalog_item_setup", self.sql)
        self.assertIn("public.commercial_warehouses", self.sql)

    def test_only_unambiguous_catalog_setup_is_assigned_to_a_warehouse(self):
        self.assertIn("count(DISTINCT s.warehouse_id)", self.sql)
        self.assertIn("WHEN m.mapped_warehouse_count = 1 THEN 'mapped'", self.sql)
        self.assertIn("WHEN m.mapped_warehouse_count = 0 THEN 'unconfigured'", self.sql)
        self.assertIn("ELSE 'ambiguous'", self.sql)
        self.assertIn("THEN min(s.warehouse_id::text)::uuid", self.sql)

    def test_projection_is_read_only_and_preserves_existing_stock_source_of_truth(self):
        self.assertIn("WITH (security_invoker = true)", self.sql)
        self.assertIn("GRANT SELECT", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_inventory_balances", self.sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", self.sql)
        self.assertIn("does not change stock posting or ledger source of truth", self.sql)


if __name__ == "__main__":
    unittest.main()
