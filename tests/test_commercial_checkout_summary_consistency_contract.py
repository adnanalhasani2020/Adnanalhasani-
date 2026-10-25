from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025161000_commercial_checkout_summary_consistency.sql"


class CommercialCheckoutSummaryConsistencyContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_reuses_existing_governed_checkout_and_sale_records(self):
        self.assertIn("commercial_private.commercial_checkout_sale_multi(", self.sql)
        self.assertIn("public.commercial_sales", self.sql)
        self.assertIn("public.commercial_sale_items", self.sql)
        self.assertNotIn("CREATE TABLE", self.sql.upper())

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

    def test_does_not_claim_tax_invoice_or_create_duplicate_document_ledger(self):
        self.assertIn("does not claim tax-invoice compliance", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_documents", self.sql)


if __name__ == "__main__":
    unittest.main()
