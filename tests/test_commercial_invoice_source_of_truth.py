from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
CHECKOUT = ROOT / "supabase/migrations/20261025100000_commercial_checkout_response_paid_balance.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class CommercialInvoiceSourceOfTruthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.checkout = CHECKOUT.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")

    def test_sale_is_the_single_invoice_record(self):
        self.assertIn("'invoice_id',v_sale", self.checkout)
        self.assertIn("'sale_id',v_sale", self.checkout)
        self.assertIn("INSERT INTO public.commercial_sales(", self.checkout)
        self.assertIn("INSERT INTO public.commercial_sale_items(", self.checkout)
        self.assertNotIn("INSERT INTO public.commercial_documents", self.checkout)

    def test_invoice_history_reads_saved_sale_and_line_records(self):
        projection = self.repository.split(
            "internal fun loadCommercialCloudSaleDocuments", 1
        )[1].split("internal fun loadCommercialCloudSales", 1)[0]
        self.assertIn("/rest/v1/commercial_sales", projection)
        self.assertIn("commercial_sale_items(", projection)
        self.assertIn("receipt_number", projection)
        self.assertIn("total_minor", projection)
        self.assertIn("organization_id=eq.$orgId", projection)
        self.assertIn("activity_id=eq.$activityId", projection)

    def test_idempotent_replay_returns_existing_invoice_before_side_effects(self):
        replay_start = self.checkout.index("IF FOUND THEN")
        replay_end = self.checkout.index("\n  SELECT a.id, a.organization_id INTO v_activity", replay_start)
        replay = self.checkout[replay_start:replay_end]
        side_effects_start = self.checkout.index("INSERT INTO public.commercial_sales(")
        self.assertIn("idempotency_key_reused_with_different_request", replay)
        self.assertIn("'idempotent_replay',true", replay)
        self.assertLess(replay_end, side_effects_start)
        self.assertIn("pg_advisory_xact_lock(hashtext(v_uid::text), hashtext(p_idempotency_key))", self.checkout)

    def test_stock_decrement_and_movement_are_tied_to_the_same_sale(self):
        self.assertIn("UPDATE public.commercial_inventory_balances SET quantity_on_hand=quantity_on_hand-v_qty", self.checkout)
        self.assertIn("INSERT INTO public.commercial_stock_movements(", self.checkout)
        self.assertIn("VALUES(v_offer,v_sale,'sale',-v_qty", self.checkout)
        self.assertIn("'sale:'||v_sale::text||':offering:'||v_offer::text||':location:'||p_location_id::text", self.checkout)

    def test_initial_payment_is_conditional_and_retry_reads_database_totals(self):
        self.assertIn("IF v_initial_payment > 0 THEN", self.checkout)
        self.assertIn("ON CONFLICT(organization_id,idempotency_key) DO NOTHING", self.checkout)
        self.assertGreaterEqual(self.checkout.count("sum(p.amount_minor)"), 4)
        self.assertIn("p.status='succeeded'", self.checkout)
        self.assertIn("'paid_minor'", self.checkout)
        self.assertIn("'outstanding_minor'", self.checkout)


if __name__ == "__main__":
    unittest.main()
