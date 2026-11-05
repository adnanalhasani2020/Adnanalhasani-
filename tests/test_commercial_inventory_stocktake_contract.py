from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106120000_commercial_inventory_stocktake_workflow.sql"
FIXTURE = ROOT / "supabase/tests/fixtures/commercial_inventory_stocktake_schema_fixture.sql"
SMOKE = ROOT / "supabase/tests/database/commercial_inventory_stocktake_smoke.sql"
WORKFLOW = ROOT / ".github/workflows/inventory-stocktake-smoke.yml"


class CommercialInventoryStocktakeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.fixture = FIXTURE.read_text(encoding="utf-8")
        cls.smoke = SMOKE.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_document_lifecycle_is_explicit_and_separated(self):
        for status in ("draft", "submitted", "approved", "posted", "reversed"):
            self.assertIn(f"'{status}'", self.sql)
        self.assertIn("inventory_stocktake_self_approval_forbidden", self.sql)
        self.assertIn("created_by=v_uid", self.sql)

    def test_posting_is_atomic_and_requires_fresh_snapshot(self):
        self.assertIn("inventory_stocktake_stale_snapshot", self.sql)
        self.assertIn("inventory_stocktake_below_reserved_quantity", self.sql)
        self.assertIn("inventory_stocktake_cost_layer_reconciliation_failed", self.sql)
        self.assertIn("commercial_stock_movements", self.sql)
        self.assertIn("'adjustment'", self.sql)

    def test_cost_layers_are_consumed_and_restored(self):
        self.assertIn("commercial_inventory_stocktake_cost_allocations", self.sql)
        self.assertIn("inventory_stocktake_positive_variance_cost_required", self.sql)
        self.assertIn("inventory_stocktake_reversal_adjustment_layer_consumed", self.sql)
        self.assertIn("previous_unit_cost_minor", self.sql)

    def test_rpc_only_writes_and_authenticated_execution(self):
        self.assertIn("REVOKE ALL ON public.commercial_inventory_stocktakes", self.sql)
        self.assertIn("GRANT SELECT ON public.commercial_inventory_stocktakes", self.sql)
        self.assertIn("TO authenticated", self.sql)
        self.assertIn("FROM PUBLIC,anon", self.sql)
        self.assertIn("has_table_privilege('authenticated','public.commercial_inventory_stocktakes','INSERT')", self.smoke)

    def test_database_smoke_workflow_is_wired(self):
        self.assertIn("commercial_inventory_stocktake_schema_fixture.sql", self.workflow)
        self.assertIn("commercial_inventory_stocktake_smoke.sql", self.workflow)
        self.assertIn("postgres:17", self.workflow)
        self.assertIn("stocktake_post_retry_not_idempotent", self.smoke)
        self.assertIn("stocktake_reverse_retry_not_idempotent", self.smoke)


if __name__ == "__main__":
    unittest.main()
