from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase/migrations"
CATALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
SQL_FILE = MIGRATIONS / "20261102020000_invoice_purchase_unit_receiving_context.sql"


class InvoicePurchaseUnitAndReceivingLocationTests(unittest.TestCase):
    def test_invoice_transfer_preserves_original_selling_unit_snapshots(self):
        sql = SQL_FILE.read_text(encoding="utf-8")
        for column in ("selling_unit_code", "selling_unit_name", "selling_quantity", "quantity_in_stock_units",
                       "source_selling_unit_code", "source_selling_unit_name", "source_selling_quantity",
                       "source_quantity_in_stock_units"):
            self.assertIn(column, sql)
        self.assertIn("si.selling_unit_code", sql)
        self.assertIn("si.selling_quantity", sql)
        self.assertIn("v_item.selling_unit_code", sql)
        self.assertIn("v_item.quantity_in_stock_units", sql)

    def test_recipient_can_choose_an_active_receiving_location(self):
        sql = SQL_FILE.read_text(encoding="utf-8")
        kt = CATALOG.read_text(encoding="utf-8")
        self.assertIn("p_receive_location_id uuid DEFAULT NULL", sql)
        self.assertIn("id=p_receive_location_id AND organization_id=v_req.destination_organization_id AND state='active'", sql)
        self.assertIn('"p_receive_location_id"', kt)
        self.assertIn("فرع الاستلام للموافقة الواردة", kt)
        self.assertIn("marketBranches.none { it.id==receiveLocation }", kt)

    def test_existing_quantity_and_stock_receipt_semantics_are_not_changed(self):
        sql = SQL_FILE.read_text(encoding="utf-8")
        self.assertIn("quantity_ordered,quantity_received,unit_cost_minor,", sql)
        self.assertIn("v_item.quantity,0,v_item.unit_price_minor", sql)
        self.assertIn("recipient_receiving_location_not_in_organization", sql)
        self.assertIn("recipient_active_receiving_location_required", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_review_invoice_purchase_request(uuid,text,text,uuid)", sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_review_invoice_purchase_request(uuid,text,text,uuid) TO authenticated", sql)
        self.assertIn("Backward compatibility for older clients", sql)
        self.assertIn("ri.line_number=poi.line_number", sql)


if __name__ == "__main__":
    unittest.main()
