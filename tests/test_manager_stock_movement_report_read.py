from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261102060000_manager_stock_movement_report_read.sql"


class ManagerStockMovementReadScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_read_is_scoped_through_unique_offering_organization_link(self):
        self.assertIn("commercial_catalog_item_offerings cio", self.sql)
        self.assertIn("cio.offering_id = commercial_stock_movements.offering_id", self.sql)
        self.assertIn("m.organization_id = cio.organization_id", self.sql)
        self.assertIn("m.user_id = (SELECT auth.uid())", self.sql)
        self.assertIn("m.state = 'active'", self.sql)
        self.assertIn("m.role IN ('owner', 'admin', 'manager')", self.sql)

    def test_existing_own_row_policy_is_not_removed_and_no_write_access_added(self):
        self.assertNotIn('DROP POLICY IF EXISTS "users read own stock movements"', self.sql)
        self.assertEqual(self.sql.count("CREATE POLICY"), 1)
        self.assertIn("FOR SELECT TO authenticated", self.sql)
        for forbidden in ("FOR INSERT", "FOR UPDATE", "FOR DELETE", "GRANT ", "REVOKE "):
            self.assertNotIn(forbidden, self.sql)


if __name__ == "__main__":
    unittest.main()
