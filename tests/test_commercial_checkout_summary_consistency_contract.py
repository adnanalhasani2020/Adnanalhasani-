from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025161000_commercial_checkout_summary_consistency.sql"
SOURCE_OF_TRUTH = ROOT / "docs/commerce/operational-invoice-source-of-truth.md"
TABLE_MAPPING = ROOT / "schema/reconciliation/table-mapping.json"
MULTILINE_CHECKOUT = ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql"


class CommercialCheckoutSummaryConsistencyContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.checkout = MULTILINE_CHECKOUT.read_text(encoding="utf-8")
        cls.decision = SOURCE_OF_TRUTH.read_text(encoding="utf-8")
        cls.mapping = TABLE_MAPPING.read_text(encoding="utf-8")

    def test_reuses_existing_governed_checkout_and_sale_records(self):
        self.assertIn("commercial_private.commercial_checkout_sale_multi(", self.sql)
        self.assertIn("public.commercial_sales", self.sql)
        self.assertIn("public.commercial_sale_items", self.sql)
        self.assertNotIn("CREATE TABLE", self.sql.upper())
        self.assertNotIn("INSERT INTO public.commercial_documents", self.sql)

    def test_returns_payment_and_remaining_totals_from_persisted_payments(self):
        self.assertIn("'paid_minor', v_paid", self.sql)
        self.assertIn("'outstanding_minor', greatest(v_total - v_paid, 0)", self.sql)
        self.assertIn("p.status = 'succeeded'", self.sql)
        self.assertIn("'payment_recorded', v_paid > 0", self.sql)

    def test_returns_persisted_line_price_snapshots(self):
        for field in ("offering_id", "product_name_snapshot", "quantity", "unit_price_minor", "line_total_minor"):
            self.assertIn(field, self.sql)
        self.assertIn("'lines', v_lines", self.sql)

    def test_preserves_authenticated_rpc_boundary(self):
        self.assertIn("IF auth.uid() IS NULL", self.sql)
        self.assertIn("s.user_id = auth.uid()", self.sql)
        self.assertIn("TO authenticated", self.sql)
        self.assertIn("FROM PUBLIC, anon", self.sql)

    def test_replay_and_atomic_effects_are_owned_by_canonical_checkout(self):
        self.assertIn("idempotency_key", self.checkout)
        self.assertIn("idempotency_key_reused_with_different_request", self.checkout)
        self.assertIn("UPDATE public.commercial_inventory_balances SET quantity_on_hand=quantity_on_hand-v_qty", self.checkout)
        self.assertIn("INSERT INTO public.commercial_stock_movements", self.checkout)
        self.assertIn("INSERT INTO public.commercial_payments", self.checkout)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_payments", self.sql)

    def test_documents_model_avoids_duplicate_invoice_ledger(self):
        self.assertIn("commercial_sales", self.decision)
        self.assertIn("commercial_sale_items", self.decision)
        self.assertIn("commercial_payments", self.decision)
        self.assertIn("commercial_documents", self.decision)
        self.assertIn("لا تُنشأ فيه نسخة أخرى", self.decision)
        self.assertIn('"status": "partial_overlap"', self.mapping)
        self.assertIn("commercial_sale_items", self.mapping)

    def test_migration_dependencies_are_explicitly_gated_by_live_validation(self):
        self.assertIn("commercial_checkout_sale_multi", self.sql)
        self.assertIn("commercial_private.commercial_checkout_sale_multi(", self.checkout)
        self.assertIn("لا يُسمح بتطبيق ترحيل", self.decision)


if __name__ == "__main__":
    unittest.main()
