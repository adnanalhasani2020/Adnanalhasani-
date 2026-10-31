from pathlib import Path
import unittest


class InvoicePurchaseLineIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.migration = Path("supabase/migrations/20261031020000_invoice_purchase_line_integrity.sql").read_text(encoding="utf-8")

    def test_invoice_lines_keep_a_persisted_stable_sequence(self):
        self.assertIn("ADD COLUMN IF NOT EXISTS line_number integer", self.migration)
        self.assertIn("PARTITION BY request_id ORDER BY id", self.migration)
        self.assertIn("row_number() OVER(ORDER BY si.created_at,si.id)::integer", self.migration)
        self.assertIn("ORDER BY line_number", self.migration)

    def test_approval_does_not_guess_catalog_offerings(self):
        self.assertIn("v_item.line_number,NULL", self.migration)
        self.assertIn("catalog_mapping_required',true", self.migration)
        self.assertNotIn("LIMIT 1", self.migration.split("CREATE OR REPLACE FUNCTION public.commercial_review_invoice_purchase_request", 1)[1].split("-- Map by", 1)[0])

    def test_mapping_is_scoped_to_the_receiving_location_and_preserves_stock(self):
        mapping = self.migration.split("CREATE OR REPLACE FUNCTION public.commercial_invoice_purchase_map_known_items", 1)[1]
        self.assertIn("m.location_id=v_po.receive_location_id", mapping)
        self.assertIn("o.location_ref=v_po.receive_location_id::text", mapping)
        self.assertIn("poi.line_number=c.line_number", mapping)
        self.assertIn("'stock_changed',false", mapping)
        self.assertIn("poi.quantity_received=0", mapping)


if __name__ == "__main__":
    unittest.main()
