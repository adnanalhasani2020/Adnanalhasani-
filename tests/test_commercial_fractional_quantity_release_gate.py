from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "supabase/tests/database/commercial_financial_release_gate_test.sql"


class CommercialFractionalQuantityReleaseGateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = GATE.read_text(encoding="utf-8")

    def test_pgtap_plan_matches_assertion_count(self):
        match = re.search(r"SELECT\\s+plan\\((\\d+)\\);", self.sql, re.IGNORECASE)
        self.assertIsNotNone(match)
        assertions = len(re.findall(r"SELECT\\s+(?:ok|is)\\s*\\(", self.sql, re.IGNORECASE))
        self.assertEqual(int(match.group(1)), assertions)

    def test_sale_quantity_contract_keeps_base_units_integer(self):
        self.assertIn("(column_name = 'quantity' AND data_type = 'integer')", self.sql)
        self.assertIn("(column_name = 'selling_quantity' AND data_type = 'numeric')", self.sql)
        self.assertIn("(column_name = 'quantity_in_stock_units' AND data_type = 'bigint')", self.sql)
        self.assertNotIn("column_name = 'quantity'\\n     AND data_type = 'numeric'", self.sql)

    def test_checkout_release_gate_checks_exact_conversion(self):
        self.assertIn("v_base_qty := (v_line->>'quantity')::numeric * v_unit.quantity_in_stock", self.sql)
        self.assertIn("v_base_qty <> trunc(v_base_qty)", self.sql)

    def test_return_release_gate_uses_original_invoice_unit_snapshot(self):
        self.assertIn("v_sale_item.selling_unit_code IS DISTINCT FROM v_unit_code", self.sql)
        self.assertIn("v_sale_item.quantity_in_stock_units::numeric / v_sale_item.selling_quantity", self.sql)
        self.assertIn("FROM public.commercial_offering_sell_units", self.sql)
        self.assertIn("= 0,", self.sql)


if __name__ == "__main__":
    unittest.main()
