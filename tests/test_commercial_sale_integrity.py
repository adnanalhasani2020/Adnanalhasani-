from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
HELPER = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialSaleIntegrity.kt"
DEBT_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudDebtScreen.kt"
COLLECTION_MIGRATION = ROOT / "supabase/migrations/20261024220000_commercial_customer_collection_rpc.sql"


class CommercialSaleIntegrityContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.helper = HELPER.read_text(encoding="utf-8")
        cls.debt_screen = DEBT_SCREEN.read_text(encoding="utf-8")
        cls.migration = COLLECTION_MIGRATION.read_text(encoding="utf-8")

    def test_repeated_product_quantities_are_aggregated_with_overflow_guard(self):
        self.assertIn("internal fun aggregateSaleQuantities", self.helper)
        self.assertIn("totals[productId] = Math.addExact(totals[productId] ?: 0, quantity)", self.helper)
        self.assertIn("aggregateSaleQuantities(listOf(currentProduct.cloudOfferingId to baseQuantity))", self.main)
        self.assertIn("validateAvailableStock(requested", self.main)

    def test_cloud_credit_sale_uses_selected_customer_and_server_rpc(self):
        self.assertIn("loadCommercialCloudCustomers(context)", self.main)
        self.assertIn("cloudCustomers.forEach { item ->", self.main)
        self.assertIn('label = { Text("العميل الآجل من قاعدة البيانات") }', self.main)
        self.assertIn("cloudCustomers.none { it.id == customer }", self.main)
        self.assertIn("اختر عميلًا سحابيًا للبيع الآجل.", self.main)
        self.assertIn("checkoutCommercialCloudSaleOnAccount(context", self.main)
        self.assertIn("/rest/v1/rpc/commercial_checkout_sale_on_account", self.repository)
        self.assertIn("p_customer_id", self.repository)
        self.assertIn("getOrCreateCommercialCloudCreditSaleKey", self.main)

    def test_unresolved_sale_retry_keys_cannot_be_replaced(self):
        self.assertIn("عملية بيع معلّقة لم تُحسم بعد", self.repository)
        self.assertIn("عملية بيع آجل معلّقة", self.repository)

    def test_partial_return_uses_original_sale_item_ids_and_persistent_retry_key(self):
        self.assertIn('put("sale_item_id", saleItemId)', self.repository)
        self.assertIn("commercial_cloud_pending_return_v1", self.repository)
        self.assertIn("request_fingerprint", self.repository)
        self.assertIn("commercial_request_sale_return", self.repository)

    def test_obsolete_sale_collection_rpc_path_is_removed(self):
        self.assertNotIn("getOrCreateCommercialCloudCollectionKey", self.repository)
        self.assertNotIn("clearCommercialCloudCollectionKey", self.repository)
        self.assertNotIn("recordCommercialCloudSaleCollection", self.repository)
        self.assertNotIn("/rest/v1/rpc/commercial_record_sale_collection", self.repository)

    def test_cloud_debt_screen_uses_server_collection_rpc_and_partial_balances(self):
        self.assertIn("loadCommercialCloudReceivables(context)", self.debt_screen)
        self.assertIn("recordCommercialCloudCustomerPayment(context", self.debt_screen)
        self.assertIn("/rest/v1/rpc/commercial_record_customer_payment", self.repository)
        self.assertIn("sum", self.repository)
        self.assertIn("payment_status", self.repository)

    def test_collection_rpc_is_authenticated_idempotent_and_posts_gl(self):
        for marker in (
            "SECURITY DEFINER",
            "authentication_required",
            "idempotency_key_reused_with_different_request",
            "commercial_gl_post_payment(v_payment.id)",
            "REVOKE ALL ON FUNCTION public.commercial_record_customer_payment",
            "TO authenticated",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.migration)


if __name__ == "__main__":
    unittest.main()
