from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudReturnsScreen.kt"


class ReturnsScreenStatusFilterContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCREEN.read_text(encoding="utf-8")

    def test_return_request_lifecycle_filters_are_available(self):
        for marker in (
            '"" to "الكل"',
            '"requested" to "بانتظار القرار"',
            '"approved" to "موافق عليه"',
            '"completed" to "مكتمل"',
            '"rejected" to "مرفوض"',
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.source)

    def test_status_filter_composes_with_existing_search(self):
        self.assertIn("selectedStatusFilter.isBlank()", self.source)
        self.assertIn("r.status.equals(selectedStatusFilter, ignoreCase = true)", self.source)
        self.assertIn("r.receiptNumber,r.customerName,r.reasonCode,r.status,r.refundStatus,r.restockStatus,r.id", self.source)

    def test_filter_chips_display_live_counts_and_disable_during_actions(self):
        self.assertIn("rows.groupingBy { it.status.lowercase(Locale.ROOT) }.eachCount()", self.source)
        self.assertIn("FilterChip(", self.source)
        self.assertIn('label = { Text("$title ($count)") }', self.source)
        self.assertIn("enabled = !busy", self.source)

    def test_filtering_does_not_mutate_return_records(self):
        self.assertNotIn("commercialCloudRequest(", self.source)
        self.assertNotIn("updateCommercialCloudSaleReturn", self.source)


if __name__ == "__main__":
    unittest.main()
