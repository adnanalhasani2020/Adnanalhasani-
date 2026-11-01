from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261101030000_inventory_receipt_ledger_semantics.sql"
REPORTS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
INTEGER_RECEIVING = ROOT / "supabase/migrations/20261026250000_commercial_integer_purchase_receiving.sql"


class InventoryReceiptLedgerSemanticsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.reports = REPORTS.read_text(encoding="utf-8")

    def test_historical_receipts_are_reclassified_by_stable_source_reference(self):
        self.assertIn("SET movement_type='receipt'", self.sql)
        self.assertIn("WHERE movement_type='adjustment'", self.sql)
        self.assertIn("AND source_ref LIKE 'receipt:%'", self.sql)

    def test_new_receipts_write_receipt_movement(self):
        self.assertIn("VALUES(p_offering_id,'receipt',p_quantity,'receipt:'", self.sql)
        self.assertNotIn("VALUES(p_offering_id,'adjustment',p_quantity,'receipt:'", self.sql)

    def test_purchase_order_receiving_preserves_fractional_quantities(self):
        marker = "CREATE OR REPLACE FUNCTION commercial_private.commercial_receive_purchase_order("
        self.assertIn(marker, self.sql)
        receipt_rpc = self.sql.split(marker, 1)[1].split("$function$;", 1)[0]
        self.assertIn("scale(v_qty)>6", receipt_rpc)
        self.assertNotIn("v_qty<>trunc(v_qty)", receipt_rpc)
        self.assertIn("v_qty>999999999999.999999", receipt_rpc)
        self.assertIn("public.commercial_receive_inventory_stock", receipt_rpc)
        integer_migration = INTEGER_RECEIVING.read_text(encoding="utf-8")
        self.assertIn("REVOKE ALL ON FUNCTION commercial_private.commercial_receive_purchase_order", integer_migration)
        self.assertIn("FROM PUBLIC,anon,authenticated", integer_migration)

    def test_receipt_rpc_preserves_fractional_quantity_support(self):
        self.assertIn("scale(p_quantity)>6", self.sql)
        self.assertNotIn("p_quantity<>trunc(p_quantity)", self.sql)
        self.assertIn("numeric(20,6)", self.sql)

    def test_receipt_rpc_keeps_existing_transaction_and_access_contract(self):
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_receive_inventory_stock", self.sql)
        self.assertIn("pg_advisory_xact_lock", self.sql)
        self.assertIn("commercial_inventory_receipts", self.sql)
        self.assertIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_receive_inventory_stock", self.sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_receive_inventory_stock", self.sql)

    def test_reports_localize_receipt_movement_type(self):
        self.assertIn('"receipt" -> "توريد / استلام"', self.reports)
        self.assertIn('"adjustment" -> "تسوية مخزون"', self.reports)

    def test_reclassification_does_not_rewrite_balances_or_receipt_history(self):
        prefix = self.sql.split("CREATE OR REPLACE FUNCTION", 1)[0]
        self.assertNotIn("UPDATE public.commercial_inventory_balances", prefix)
        self.assertNotIn("DELETE FROM public.commercial_inventory_receipts", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_stock_movements", self.sql)


if __name__ == "__main__":
    unittest.main()
