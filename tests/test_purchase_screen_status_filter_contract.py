from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudPurchasesScreen.kt"


class PurchaseScreenStatusFilterContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCREEN.read_text(encoding="utf-8")

    def test_status_filter_covers_purchase_order_lifecycle(self):
        for marker in (
            '"draft" to "مسودة"',
            '"submitted" to "بانتظار الاعتماد"',
            '"approved" to "معتمد"',
            '"partially_received" to "استلام جزئي"',
            '"received" to "مستلم"',
            '"closed" to "مغلق"',
            '"cancelled" to "ملغى"',
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.source)

    def test_status_filter_and_text_search_are_composed(self):
        self.assertIn("selectedStatusFilter.isBlank()", self.source)
        self.assertIn("order.status.equals(selectedStatusFilter, ignoreCase = true)", self.source)
        self.assertIn("order.orderNumber.contains(search, true)", self.source)
        self.assertIn("order.supplierName.contains(search, true)", self.source)
        self.assertIn("order.lines.any { it.description.contains(search, true) }", self.source)

    def test_filter_chips_show_live_counts_and_keep_all_orders_option(self):
        self.assertIn('"" to "الكل"', self.source)
        self.assertIn("orders.groupingBy { it.status.lowercase() }.eachCount()", self.source)
        self.assertIn('FilterChip(', self.source)
        self.assertIn('label = { Text("$label ($count)") }', self.source)
        self.assertIn("enabled = !loading", self.source)

    def test_screen_filter_is_read_only(self):
        self.assertNotIn("commercialCloudRequest(", self.source)
        self.assertNotIn("updateCommercialCloudPurchaseOrder", self.source)
        self.assertNotIn("receiveCommercialCloudPurchaseOrder", self.source)


if __name__ == "__main__":
    unittest.main()
