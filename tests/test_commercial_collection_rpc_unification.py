from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
DEBT_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt"
SALES_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSalesScreen.kt"
LEGACY_MIGRATION = ROOT / "supabase/migrations/20261024210000_commercial_sale_collection_rpc.sql"
UNIFICATION_MIGRATION = ROOT / "supabase/migrations/20261025120000_unify_commercial_collection_rpc.sql"
CANONICAL_MIGRATION = ROOT / "supabase/migrations/20261024220000_commercial_customer_collection_rpc.sql"


class CommercialCollectionRpcUnificationTests(unittest.TestCase):
    def test_debt_screen_uses_only_gl_backed_customer_payment_path(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        screen = DEBT_SCREEN.read_text(encoding="utf-8")
        sales_screen = SALES_SCREEN.read_text(encoding="utf-8")
        self.assertIn("recordCommercialCloudCustomerPayment(context", screen)
        self.assertIn("/rest/v1/rpc/commercial_record_customer_payment", repository)
        self.assertNotIn("recordCommercialCloudSaleCollection", repository)
        self.assertNotIn("/rest/v1/rpc/commercial_record_sale_collection", repository)
        self.assertIn('recordCommercialCloudCustomerPayment(context, target.id, amount, "cash")', sales_screen)
        self.assertNotIn("recordCommercialCloudSaleCollection", sales_screen)

    def test_legacy_rpc_is_a_compatibility_shim_to_atomic_gl_path(self):
        legacy = LEGACY_MIGRATION.read_text(encoding="utf-8")
        unified = UNIFICATION_MIGRATION.read_text(encoding="utf-8")
        canonical = CANONICAL_MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_private.commercial_record_sale_collection", legacy)
        self.assertIn("public.commercial_record_customer_payment(", unified)
        self.assertIn("sale_return_reconciliation_required", unified)
        self.assertIn("commercial_gl_post_payment(v_payment.id)", canonical)
        self.assertIn("GRANT EXECUTE ON FUNCTION commercial_private.commercial_record_sale_collection", unified)

    def test_customer_receipt_is_numbered_shared_and_currency_minor_units_are_formatted(self):
        repository = REPOSITORY.read_text(encoding="utf-8")
        screen = DEBT_SCREEN.read_text(encoding="utf-8")
        migration = (ROOT / "supabase/migrations/20261026183000_commercial_customer_receipt_numbers.sql").read_text(encoding="utf-8")
        self.assertIn("commercial_payments_org_receipt_number_uidx", migration)
        self.assertIn("commercial_assign_payment_receipt_number", migration)
        self.assertIn("loadCommercialCloudCustomerReceipt", repository)
        self.assertIn("receipt_number", repository)
        self.assertIn("Currency.getInstance(currency).defaultFractionDigits", screen)
        self.assertIn("Intent.ACTION_SEND", screen)
        self.assertIn("سند قبض رقم RC-$receiptNumber", screen)

    def test_idempotent_replay_precedes_return_refund_gate(self):
        unified = UNIFICATION_MIGRATION.read_text(encoding="utf-8")
        replay = unified.index("WHERE p.organization_id = v_org AND p.idempotency_key = p_idempotency_key")
        return_gate = unified.index("sale_return_reconciliation_required")
        self.assertLess(replay, return_gate)


if __name__ == "__main__":
    unittest.main()
