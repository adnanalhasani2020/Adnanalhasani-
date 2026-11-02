from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261102050000_inventory_cost_ledger_manager_read_scope.sql"


class InventoryCostLedgerReadScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_cost_detail_policies_require_active_organization_managers(self):
        tables = (
            "commercial_inventory_cost_layers",
            "commercial_inventory_cost_allocations",
            "commercial_inventory_cost_reversals",
            "commercial_inventory_return_cost_allocations",
        )
        for table in tables:
            with self.subTest(table=table):
                self.assertIn(f"ON public.{table}", self.sql)
                self.assertIn(f"m.organization_id = {table}.organization_id", self.sql)
                self.assertIn("m.user_id = (SELECT auth.uid())", self.sql)
                self.assertIn("m.state = 'active'", self.sql)
                self.assertIn("m.role IN ('owner', 'admin', 'manager')", self.sql)

    def test_old_member_wide_policies_are_replaced(self):
        self.assertEqual(self.sql.count("DROP POLICY IF EXISTS"), 4)
        self.assertEqual(self.sql.count("CREATE POLICY"), 4)
        self.assertNotIn("role IN ('owner', 'admin', 'manager', 'staff')", self.sql)

    def test_migration_does_not_change_stock_or_cost_records(self):
        for forbidden in ("INSERT INTO public.", "UPDATE public.", "DELETE FROM public."):
            self.assertNotIn(forbidden, self.sql)
        self.assertIn("FOR SELECT TO authenticated", self.sql)
        self.assertIn("ALTER VIEW public.commercial_inventory_cost_audit_report", self.sql)
        self.assertIn("SET (security_invoker = true)", self.sql)


if __name__ == "__main__":
    unittest.main()
