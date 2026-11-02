from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261102040000_invoice_purchase_create_unmapped_item_atomic.sql"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"


class InvoicePurchaseItemCreationAtomicTests(unittest.TestCase):
    def test_new_item_creation_and_purchase_line_link_are_one_server_transaction(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertTrue(sql.lstrip().startswith("BEGIN;"))
        self.assertTrue(sql.rstrip().endswith("COMMIT;"))
        self.assertIn("CREATE OR REPLACE FUNCTION public.commercial_create_and_link_invoice_purchase_item(", sql)
        for marker in (
            "commercial_catalog_create_product(",
            "commercial_catalog_configure_items(",
            "commercial_create_inventory_item_with_category_and_unit(",
            "commercial_catalog_bind_existing_offering(",
            "commercial_link_purchase_order_item_offering(",
            "commercial_invoice_purchase_item_creation_requests",
            "invoice_item_creation_key_reused_with_different_request",
            "purchase_line_offering_already_linked",
            "purchase_line_item_creation_after_receipt_or_invoice_forbidden",
            "warehouse_not_in_purchase_receiving_branch",
            "activity_node_does_not_match_selected_activity",
            "purchase_line_unit_mismatch_requires_unit_review",
            "purchase_line_unit_conversion_mismatch_requires_review",
            "'price_minor', 0",
            "'initial_quantity', 0",
            "'stock_created', false",
            "'reapproval_required', (v_order.status = 'approved')",
            "is_available = false",
            "pricing_status = 'unpriced'",
            "GRANT EXECUTE ON FUNCTION public.commercial_create_and_link_invoice_purchase_item",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, sql)

    def test_creation_requires_explicit_names_category_and_compatible_units(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("nullif(btrim(p_name_ar), '') IS NULL", sql)
        self.assertIn("nullif(btrim(p_name_en), '') IS NULL", sql)
        self.assertIn("active_global_catalog_category_not_found", sql)
        self.assertIn("sale_unit_not_compatible_with_base_unit", sql)
        self.assertIn("code = lower(btrim(p_base_unit_code))", sql)
        self.assertIn("dimension = v_base_dimension", sql)
        self.assertIn("p_local_category_id, lower(btrim(p_base_unit_code))", sql)
        self.assertIn("commercial_global_catalog_items", sql)
        self.assertIn("multiple_exact_catalog_items_require_manual_resolution", sql)

    def test_screen_exposes_explicit_create_flow_without_auto_receipt_or_price(self):
        source = SCREEN.read_text(encoding="utf-8")
        self.assertIn("commercial_create_and_link_invoice_purchase_item", source)
        self.assertIn("إنشاء صنف جديد وربطه بهذا البند", source)
        self.assertIn("اسم الصنف بالعربية", source)
        self.assertIn("اسم الصنف بالإنجليزية", source)
        self.assertIn("مستودع الاستلام", source)
        self.assertIn("commercial_unit_of_measure?select=code", source)
        self.assertIn("invoiceNewIdempotencyKey", source)
        self.assertIn("السعر سيبقى صفرًا بحالة غير مسعّر", source)
        self.assertIn("لن يتغير المخزون حتى تسجيل الاستلام", source)
        self.assertIn("لا ينشأ رصيد مستقل للمستودع", source)
        self.assertIn("purchase_order_id", source)
        self.assertIn("result?.optBoolean(\"reapproval_required\",false)", source)
        self.assertIn("val requiresReapproval=invoiceMappingPurchaseOrderStatus==\"approved\"", source)


if __name__ == "__main__":
    unittest.main()
