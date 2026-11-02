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
        self.assertIn("HAVING count(*)>=5",sql)
        self.assertIn("merchant_prices AS",sql)
        self.assertIn("count(DISTINCT s.id)::numeric transaction_count",sql)
        self.assertIn("distinctBy { it.id }",kt)

    def test_transfer_requires_recipient_approval_and_does_not_receive_stock(self):
        sql=(ROOT/"supabase/migrations/20261029050000_invoice_to_purchase_approval.sql").read_text(encoding="utf-8")
        self.assertIn("recipient_approval_forbidden",sql)
        self.assertIn("'draft'",sql)
        self.assertIn("receive_location_id",sql)
        self.assertIn("recipient_active_receiving_location_required",sql)
        self.assertIn("'stock_received',false",sql)
        self.assertIn("commercial_purchase_order_items",sql)
        mapping=(ROOT/"supabase/migrations/20261029070000_invoice_purchase_catalog_mapping.sql").read_text(encoding="utf-8")
        kt=(ROOT/"android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        self.assertIn("commercial_invoice_purchase_map_known_items",mapping)
        self.assertIn("stock_changed",mapping)
        self.assertIn("recipient_catalog_mapping_forbidden",mapping)
        self.assertIn("ربط الأصناف المعروفة بأمر الشراء",kt)
        self.assertIn("commercial_invoice_purchase_map_known_items",kt)
        self.assertIn("loadInvoicePurchaseUnmappedLines",kt)
        self.assertIn("linkSelectedInvoicePurchaseLine",kt)
        self.assertIn("commercial_link_purchase_order_item_offering",kt)
        self.assertIn("commercial_catalog_item_offerings?select=offering_id,global_item_id",kt)
        self.assertIn("location_id=eq.",kt)
        self.assertIn("commercial_offerings?select=id,location_ref,state",kt)
        self.assertIn("state=eq.active&location_ref=eq.",kt)
        self.assertIn("currency_code=eq.",kt)
        self.assertIn("commercial_products?select=id,state",kt)
        self.assertIn("state=eq.active",kt)
        self.assertIn("يتطلب إعادة اعتماد",kt)
        self.assertIn("commercial_global_catalog_items?select=id,name_ar,name_en,unit_code",kt)
        self.assertIn("offering_id=is.null",kt)
        self.assertIn("quantity_received=eq.0",kt)
        self.assertIn("لا يُنشأ صنف تلقائيًا من وصف الفاتورة",kt)
        self.assertIn("لم يتغير المخزون",kt)

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


def test_catalog_inventory_creation_is_atomic_idempotent_and_explicitly_mapped():
    sql=(ROOT/"supabase/migrations/20261029060000_catalog_create_inventory_atomic.sql").read_text(encoding="utf-8")
    kt=(ROOT/"android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
    main=(ROOT/"android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8")
    assert "commercial_create_inventory_item_with_category_and_unit" in sql
    assert "commercial_catalog_item_offerings" in sql
    assert "commercial_catalog_inventory_creation_requests" in sql
    assert "catalog_creation_key_reused_with_different_request" in sql
    assert "createCommercialCloudCatalogInventoryItem" in main
    assert "findCommercialCloudCatalogOffering" in main
    assert "إنشاء وربط بالمخزون" in kt
