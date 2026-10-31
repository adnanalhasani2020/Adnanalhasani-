from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031080000_inventory_cost_layers_fifo_lifo_average.sql"


class InventoryCostLayersContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_all_three_costing_methods_are_supported_without_changing_default(self):
        for method in ("fifo", "lifo", "weighted_average"):
            self.assertIn("'" + method + "'", self.sql)
        self.assertIn("v_method := coalesce(v_method,'weighted_average')", self.sql)

    def test_receipts_create_layers_and_weighted_average_revalues_remaining_layers(self):
        self.assertIn("commercial_inventory_receipt_cost_layer", self.sql)
        self.assertIn("AFTER INSERT ON public.commercial_inventory_receipts", self.sql)
        self.assertIn("UPDATE public.commercial_inventory_cost_layers", self.sql)
        self.assertIn("NEW.quantity::numeric*NEW.unit_cost_minor::numeric", self.sql)

    def test_opening_stock_is_seeded_only_when_cost_is_known(self):
        self.assertIn("b.quantity_on_hand > 0", self.sql)
        self.assertIn("b.unit_cost_minor IS NOT NULL", self.sql)
        self.assertIn("'opening-balance:'||b.id::text", self.sql)

    def test_sale_cost_allocator_supports_fifo_lifo_and_average(self):
        self.assertIn("commercial_allocate_sale_item_cost", self.sql)
        self.assertIn("ORDER BY", self.sql)
        self.assertIn("v_method='lifo'", self.sql)
        self.assertIn("quantity_remaining>0", self.sql)
        self.assertIn("inventory_cost_layers_insufficient", self.sql)
        self.assertIn("cost_total_minor=v_total_cost::bigint", self.sql)

    def test_allocation_is_idempotent_and_never_deletes_cost_history(self):
        self.assertIn("idempotent_replay", self.sql)
        self.assertIn("UNIQUE (sale_item_id,cost_layer_id)", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_inventory_cost_layers", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_inventory_cost_allocations", self.sql)

    def test_costing_data_is_organization_scoped_and_rls_protected(self):
        self.assertIn("ENABLE ROW LEVEL SECURITY", self.sql)
        self.assertIn("commercial_organization_members", self.sql)
        self.assertIn("commercial_validate_inventory_costing_policy_scope", self.sql)
        self.assertIn("inventory_costing_policy_location_scope_mismatch", self.sql)
        self.assertIn("commercial_inventory_cost_allocations_member_read", self.sql)
        self.assertIn("REVOKE ALL ON public.commercial_inventory_costing_policies", self.sql)


if __name__ == "__main__":
    unittest.main()
