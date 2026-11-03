from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
ORDER_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseOrderEntryDialog.kt"
PURCHASES_SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"
RECEIPT_DIALOG = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PurchaseReceiptEntryDialog.kt"


class PurchaseOrderOfferingLocationPickerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.dialog = ORDER_DIALOG.read_text(encoding="utf-8")
        cls.screen = PURCHASES_SCREEN.read_text(encoding="utf-8")
        cls.receipt = RECEIPT_DIALOG.read_text(encoding="utf-8")

    def test_repository_loads_offering_location_ref_and_purchase_location_name(self):
        self.assertIn("location_ref,product:commercial_products(name,state),activity:commercial_activities(organization_id,state)", self.repository)
        self.assertIn('val locationRef = row.optString("location_ref")', self.repository)
        self.assertIn("locationRef.isNotBlank()", self.repository)
        self.assertIn("val locationRef: String", self.repository)
        self.assertIn("receive_location:commercial_organization_locations!commercial_purchase_orders_receive_location_id_fkey(name)", self.repository)
        self.assertIn('receiveLocationName = row.optJSONObject("receive_location")?.optString("name")', self.repository)

    def test_order_dialog_only_offers_items_for_selected_receiving_location(self):
        self.assertIn("it.locationRef == locationId", self.dialog)
        self.assertIn("available?.offerings?.firstOrNull { it.id == line.offeringId }?.locationRef == locationId", self.dialog)
        self.assertIn("val mismatchedLocationLines = lines.count", self.dialog)
        self.assertIn("تغيّر موقع الاستلام أو توجد بنود من موقع آخر", self.dialog)
        self.assertIn("لا توجد عناصر مخزون نشطة مرتبطة بموقع الاستلام المحدد", self.dialog)

    def test_currency_selection_recalculates_for_new_location(self):
        self.assertIn("?.takeIf { it.locationRef == locationId }?.currencyCode", self.dialog)
        self.assertIn("it.locationRef == locationId && it.currencyCode == selectedCurrency", self.dialog)

    def test_purchase_and_receipt_screens_show_location_name_not_raw_uuid(self):
        self.assertIn('order.receiveLocationName.ifBlank { "غير محدد" }', self.screen)
        self.assertIn('order.receiveLocationName.ifBlank { "غير محدد" }', self.receipt)

    def test_closed_purchase_orders_have_an_arabic_status_label(self):
        self.assertIn('"closed" -> "مغلق"', self.screen)
        self.assertIn('"closed" -> CloudPurchasesMuted', self.screen)


if __name__ == "__main__":
    unittest.main()
