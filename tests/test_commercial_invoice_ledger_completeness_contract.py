from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
CHECKOUT = ROOT / "supabase/migrations/20261024200000_commercial_multiline_cash_checkout.sql"
SUMMARY = ROOT / "supabase/migrations/20261025161000_commercial_checkout_summary_consistency.sql"


class CommercialInvoiceLedgerCompletenessContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.checkout = CHECKOUT.read_text(encoding="utf-8")
        cls.summary = SUMMARY.read_text(encoding="utf-8")

    def test_invoice_identity_and_customer_are_returned(self):
        for field in ("'invoice_id'", "'invoice_number'", "'customer_id'", "'receipt_number'"):
            self.assertIn(field, self.checkout)
        self.assertIn("'invoice_id', v_sale", self.summary)
        self.assertIn("'invoice_number', v_receipt", self.summary)
        self.assertIn("'customer_id', v_customer", self.summary)

    def test_cost_basis_and_stock_movement_are_part_of_atomic_checkout(self):
        self.assertIn("b.unit_cost_minor INTO v_on_hand,v_reserved,v_unit_cost", self.checkout)
        self.assertIn("unit_cost_minor,cost_total_minor", self.checkout)
        self.assertIn("commercial_stock_movements", self.checkout)
        self.assertIn("inventory_cost_basis_missing_sale_blocked", self.checkout)

    def test_invoice_posts_once_to_customer_subledger_and_general_ledger(self):
        self.assertIn("commercial_private.commercial_customer_party_account", self.checkout)
        self.assertIn("commercial_private.commercial_gl_post_entry", self.checkout)
        self.assertIn("'sale:'||v_sale::text", self.checkout)
        self.assertIn("'party_account_id',v_party", self.checkout)
        self.assertIn("mapping_key='accounts_receivable'", self.checkout)
        self.assertIn("'customer_party_account_id', v_party", self.summary)
        self.assertIn("'accounts_receivable_account_id', v_ar", self.summary)

    def test_payment_trigger_sees_open_receivable_and_retry_is_idempotent(self):
        self.assertIn("v_payment_status := CASE WHEN v_initial_payment > 0 THEN 'partially_paid' ELSE 'pending' END", self.checkout)
        self.assertIn("pg_advisory_xact_lock(hashtext(v_uid::text), hashtext(p_idempotency_key))", self.checkout)
        self.assertIn("idempotent_replay',true", self.checkout)
        self.assertIn("initial_paid_minor", self.summary)
        self.assertIn("later_collected_minor", self.summary)
        self.assertIn("greatest(v_total - v_paid, 0)", self.summary)

    def test_initial_and_later_collections_are_read_from_succeeded_payments(self):
        self.assertIn("p.status = 'succeeded'", self.summary)
        self.assertIn("p.idempotency_key = 'sale:'||v_sale::text||':initial'", self.summary)
        self.assertIn("p.idempotency_key IS DISTINCT FROM 'sale:'||v_sale::text||':initial'", self.summary)


if __name__ == "__main__":
    unittest.main()
