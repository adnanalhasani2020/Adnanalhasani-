from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031060000_guard_warehouse_catalog_setup_updates.sql"


class WarehouseCatalogSetupUpdateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_warehouse_scope_edits_cannot_break_existing_catalog_setup(self):
        self.assertIn("commercial_catalog_guard_warehouse_update", self.sql)
        self.assertIn("commercial_organization_catalog_item_setup", self.sql)
        self.assertIn("OLD.branch_location_id IS DISTINCT FROM NEW.branch_location_id", self.sql)
        self.assertIn("OLD.activity_id IS DISTINCT FROM NEW.activity_id", self.sql)
        self.assertIn("warehouse_update_would_invalidate_catalog_setup", self.sql)

    def test_activity_mapping_uses_exact_node_or_exact_activity_code(self):
        self.assertIn("a.business_activity_node_id = s.activity_node_id", self.sql)
        self.assertIn("a.activity_code = n.code", self.sql)
        self.assertIn("a.state = 'active'", self.sql)
        self.assertIn("n.status = 'active'", self.sql)
        self.assertIn("n.is_selectable", self.sql)

    def test_guard_is_additive_and_does_not_rewrite_inventory(self):
        self.assertIn("BEFORE UPDATE OF organization_id, branch_location_id, activity_id", self.sql)
        self.assertIn("REVOKE ALL ON FUNCTION", self.sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_organization_catalog_item_setup", self.sql)


if __name__ == "__main__":
    unittest.main()
