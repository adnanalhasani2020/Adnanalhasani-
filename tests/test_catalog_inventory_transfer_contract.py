from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CatalogInventoryTransferContract(unittest.TestCase):
    def test_setup_and_market_controls_are_connected(self):
        sql=(ROOT/"supabase/migrations/20261029040000_catalog_setup_privacy_discovery.sql").read_text(encoding="utf-8")
        kt=(ROOT/"android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_configure_items",kt)
        self.assertIn("commercial_catalog_nearby_popularity",kt)
        self.assertIn("commercial_catalog_price_suggestions",kt)
        self.assertIn("commercial_request_invoice_as_purchase",kt)
        self.assertIn("reviewInvoicePurchaseRequest",kt)
        self.assertIn("تهيئة المختار للفرع",kt)
        self.assertIn("warehouse_not_in_branch",sql)
        self.assertIn("percentile_cont(0.5)",sql)
        self.assertIn("HAVING count(DISTINCT e.organization_id)>=5",sql)
        self.assertIn("distinctBy { it.id }",kt)

    def test_transfer_requires_recipient_approval_and_does_not_receive_stock(self):
        sql=(ROOT/"supabase/migrations/20261029050000_invoice_to_purchase_approval.sql").read_text(encoding="utf-8")
        self.assertIn("recipient_approval_forbidden",sql)
        self.assertIn("'draft'",sql)
        self.assertIn("'stock_received',false",sql)
        self.assertIn("commercial_purchase_order_items",sql)

if __name__=="__main__":
    unittest.main()


def test_activity_profile_supports_supabase_phone_sms_otp():
    source=(ROOT/"android-commercial/app/src/main/java/com/adnanalhasani/commercial/ActivityProfileScreen.kt").read_text(encoding="utf-8")
    assert '"/auth/v1/otp"' in source
    assert '"/auth/v1/verify"' in source
    assert '.put("create_user", false)' in source
    assert '.put("type", "sms")' in source
    assert 'putString("refresh_token", refresh)' in source
    assert "رمز الهاتف SMS" in source
