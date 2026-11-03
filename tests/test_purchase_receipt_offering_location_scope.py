from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261103040000_purchase_receipt_offering_location_scope.sql"


class PurchaseReceiptOfferingLocationScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_purchase_order_line_offering_must_match_receiving_branch(self):
        self.assertIn("commercial_purchase_order_item_receiving_location_guard", self.sql)
        self.assertIn("commercial_guard_purchase_order_item_receiving_location", self.sql)
        self.assertIn("v_offering_location IS DISTINCT FROM v_receive_location::text", self.sql)
        self.assertIn("purchase_offering_receiving_location_mismatch", self.sql)
        self.assertIn("organization_id = NEW.organization_id\\n  FOR SHARE", self.sql)

    def test_receiving_branch_cannot_change_away_from_linked_offerings(self):
        self.assertIn("commercial_purchase_order_receiving_location_change_guard", self.sql)
        self.assertIn("commercial_guard_purchase_order_receiving_location_change", self.sql)
        self.assertIn("purchase_order_receiving_location_conflicts_with_linked_offerings", self.sql)
        self.assertIn("o.location_ref IS DISTINCT FROM NEW.receive_location_id::text", self.sql)

    def test_posted_receipt_line_matches_receipt_order_offering_and_inventory_receipt(self):
        self.assertIn("commercial_purchase_receipt_item_location_scope_guard", self.sql)
        self.assertIn("commercial_guard_purchase_receipt_item_location_scope", self.sql)
        for marker in (
            "v_order_location IS DISTINCT FROM v_receipt_location",
            "v_line_offering IS DISTINCT FROM NEW.offering_id",
            "v_inventory_organization IS DISTINCT FROM NEW.organization_id",
            "v_inventory_location IS DISTINCT FROM v_receipt_location",
            "v_inventory_offering IS DISTINCT FROM NEW.offering_id",
            "v_offering_location IS DISTINCT FROM v_receipt_location::text",
            "v_activity_organization IS DISTINCT FROM NEW.organization_id",
            "purchase-receipt:' || NEW.receipt_id::text || ':' || NEW.purchase_order_item_id::text",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.sql)

    def test_triggers_are_transactional_guards_not_stock_mutations(self):
        self.assertIn("BEFORE INSERT OR UPDATE OF organization_id, receipt_id, purchase_order_id", self.sql)
        self.assertIn("FROM PUBLIC, anon, authenticated", self.sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", self.sql)
        self.assertNotIn("DELETE FROM", self.sql)


if __name__ == "__main__":
    unittest.main()
