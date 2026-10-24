from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MasterDataScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class MasterDataStatusFilterContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")

    def test_status_filter_supports_all_active_and_inactive(self):
        self.assertIn('"all" to "الكل"', self.screen)
        self.assertIn('"active" to "نشط"', self.screen)
        self.assertIn('"inactive" to "غير نشط"', self.screen)
        self.assertIn('statusFilter == "active" && row.state == "active"', self.screen)
        self.assertIn('statusFilter == "inactive" && row.state != "active"', self.screen)

    def test_status_is_visible_and_searchable(self):
        self.assertIn('"الحالة: نشط"', self.screen)
        self.assertIn('"الحالة: غير نشط (', self.screen)
        self.assertIn('row.state.contains(query, ignoreCase = true)', self.screen)
        self.assertIn('state = row.optString("state").ifBlank { "active" }', self.repository)

    def test_filter_is_read_only_and_does_not_change_master_data(self):
        self.assertNotIn('commercialCloudRequest("/rest/v1/$table", "PATCH"', self.screen)
        self.assertNotIn('commercialCloudRequest("/rest/v1/$table", "DELETE"', self.screen)


if __name__ == "__main__":
    unittest.main()
