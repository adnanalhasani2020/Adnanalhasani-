from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025200000_commercial_mixed_sell_unit_checkout_fix.sql"


class CommercialMixedSellUnitDatabaseContractTests(unittest.TestCase):
    def test_base_checkout_receives_one_aggregated_line_per_offering(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_checkout_sale_multi_base_units(", sql)
        self.assertIn("GROUP BY e->>'offering_id'", sql)
        self.assertIn("'quantity',q.total_base", sql)

    def test_invoice_projection_keeps_different_sell_units_as_distinct_rows(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("GROUP BY e->>'offering_id',e->>'unit_code'", sql)
        self.assertIn("selling_unit_code=v_expected_code", sql)
        self.assertIn("INSERT INTO public.commercial_sale_items(", sql)
        self.assertIn("selling_unit_name=v_unit_line->>'unit_name'", sql)
        self.assertIn("quantity_in_stock_units=v_expected_base", sql)

    def test_replay_is_idempotent_and_rejects_inconsistent_existing_rows(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("IF v_all_match THEN", sql)
        self.assertIn("sale_item_sell_unit_projection_mismatch", sql)
        self.assertIn("sale_item_base_quantity_mismatch", sql)

    def test_stock_and_financial_checkout_remains_delegated_to_audited_base_rpc(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("public.commercial_checkout_sale_multi_base_units(", sql)
        self.assertIn("v_base_lines,p_location_id,v_effective_key", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_checkout_sale_multi", sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_checkout_sale_multi", sql)


if __name__ == "__main__":
    unittest.main()
