from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "supabase/tests/database/commercial_financial_release_gate_test.sql"
INTEGER_MIGRATION = ROOT / "supabase/migrations/20261105123000_commercial_integer_quantity_enforcement.sql"


class CommercialFractionalQuantityReleaseGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = GATE.read_text(encoding="utf-8")
        cls.migration = INTEGER_MIGRATION.read_text(encoding="utf-8")

    def test_pgtap_plan_matches_assertion_count(self):
        match = re.search(r"SELECT\s+plan\((\d+)\);", self.sql, re.IGNORECASE)
        self.assertIsNotNone(match)
        assertions = len(re.findall(r"SELECT\s+(?:ok|is)\s*\(", self.sql, re.IGNORECASE))
        self.assertEqual(int(match.group(1)), assertions)

    def test_sale_quantity_contract_keeps_integer_quantity_invariant(self):
        self.assertIn("(column_name = 'quantity' AND data_type = 'integer')", self.sql)
        self.assertIn("(column_name = 'selling_quantity' AND data_type = 'numeric')", self.sql)
        self.assertIn("(column_name = 'quantity_in_stock_units' AND data_type = 'bigint')", self.sql)
        self.assertIn("commercial_sale_items_selling_quantity_whole_qty_ck", self.sql)
        self.assertNotIn("column_name = 'quantity' AND data_type = 'numeric'", self.sql)

    def test_checkout_release_gate_checks_integer_input_and_exact_conversion(self):
        self.assertIn("coalesce(v_line->>", self.sql)
        self.assertIn("v_base_qty := (v_line->>'quantity')::numeric * v_unit.quantity_in_stock", self.migration)
        checkout_rpc = self.migration.split(
            "CREATE OR REPLACE FUNCTION public.commercial_checkout_sale_multi(", 1
        )[1].split("$function$;", 1)[0]
        self.assertIn("v_base_qty <> trunc(v_base_qty)", checkout_rpc)
        self.assertIn("!~ '^(0|[1-9][0-9]{0,5})$'", checkout_rpc)

    def test_return_release_gate_uses_original_invoice_unit_snapshot(self):
        self.assertIn("v_sale_item.selling_unit_code IS DISTINCT FROM v_unit_code", self.sql)
        self.assertIn("v_sale_item.quantity_in_stock_units::numeric / v_sale_item.selling_quantity", self.sql)
        self.assertIn("= 0,", self.sql)
        return_rpc = self.migration.split(
            "CREATE OR REPLACE FUNCTION public.commercial_request_sale_return(", 1
        )[1].split("$function$;", 1)[0]
        self.assertNotIn("FROM public.commercial_offering_sell_units", return_rpc)
        self.assertIn("v_qty <> trunc(v_qty)", return_rpc)


if __name__ == "__main__":
    unittest.main()
