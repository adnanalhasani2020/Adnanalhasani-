from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261025100000_commercial_checkout_response_paid_balance.sql"


class CommercialCheckoutResponseContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_invoice_head_and_line_snapshots_are_written_atomically(self):
        self.assertIn("INSERT INTO public.commercial_sales(", self.sql)
        self.assertIn("INSERT INTO public.commercial_sale_items(", self.sql)
        self.assertIn("product_name_snapshot,quantity,unit_price_minor,line_total_minor", self.sql)

    def test_stock_balance_and_movement_are_written_in_checkout_transaction(self):
        self.assertIn("UPDATE public.commercial_inventory_balances SET quantity_on_hand=quantity_on_hand-v_qty", self.sql)
        self.assertIn("INSERT INTO public.commercial_stock_movements(", self.sql)
        self.assertIn("quantity_delta", self.sql)
        self.assertIn("'sale',-v_qty", self.sql)

    def test_initial_payment_is_created_only_when_positive(self):
        self.assertIn("IF v_initial_payment > 0 THEN", self.sql)
        self.assertIn("INSERT INTO public.commercial_payments(", self.sql)
        self.assertIn("'succeeded','cash'", self.sql)
        self.assertIn("'paid_minor',(SELECT coalesce(sum(p.amount_minor),0)::bigint", self.sql)

    def test_new_and_replayed_responses_return_confirmed_paid_and_outstanding(self):
        self.assertGreaterEqual(self.sql.count("'paid_minor'"), 2)
        self.assertGreaterEqual(self.sql.count("'outstanding_minor'"), 2)
        self.assertIn("p.status='succeeded'", self.sql)
        self.assertIn("greatest(v_total::numeric-", self.sql)

    def test_replay_uses_existing_sale_and_rejects_changed_payload(self):
        self.assertIn("idempotency_key_reused_with_different_request", self.sql)
        self.assertIn("'idempotent_replay',true", self.sql)
        self.assertIn("'idempotent_replay',false", self.sql)


if __name__ == "__main__":
    unittest.main()
