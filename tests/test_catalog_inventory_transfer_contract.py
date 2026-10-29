from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CatalogInventoryTransferContract(unittest.TestCase):
    def test_item_setup_uses_catalog_units_and_existing_warehouse(self):
        sql = (ROOT / "supabase/migrations/20261029040000_catalog_setup_privacy_discovery.sql").read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_product_units", sql)
        self.assertIn("warehouse_not_in_branch", sql)
        self.assertIn("'pricing_required',true", sql)
        self.assertIn("'stock_created',false", sql)
        self.assertIn("commercial_catalog_item_offerings", sql)

    def test_market_data_is_cohort_gated_and_aggregate_only(self):
        sql = (ROOT / "supabase/migrations/20261029040000_catalog_setup_privacy_discovery.sql").read_text(encoding="utf-8")
        self.assertIn("percentile_cont(0.5)", sql)
        self.assertIn("HAVING count(DISTINCT e.organization_id)>=5", sql)
        self.assertIn("GROUP BY m.global_item_id,s.organization_id", sql)
        self.assertIn("p_offset LIMIT p_limit", sql)
        self.assertNotIn("seller_phone", sql)
        self.assertNotIn("seller_email", sql)

    def test_transfer_requires_destination_approval_and_never_receives_stock(self):
        sql = (ROOT / "supabase/migrations/20261029050000_invoice_to_purchase_approval.sql").read_text(encoding="utf-8")
        self.assertIn("status text NOT NULL DEFAULT 'pending'", sql)
        self.assertIn("recipient_approval_forbidden", sql)
        self.assertIn("'draft'", sql)
        self.assertIn("'stock_received',false", sql)
        self.assertIn("commercial_purchase_order_items", sql)

if __name__ == "__main__":
    unittest.main()
