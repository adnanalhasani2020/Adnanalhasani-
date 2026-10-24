from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialUnitDirectoryScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


class CommercialUnitDirectoryScreenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")

    def test_screen_is_reachable_from_more_menu_and_back_navigation(self):
        self.assertIn('"دليل وحدات القياس" -> onUnitsDirectory()', self.main)
        self.assertIn('cloudUnitsPage -> CommercialUnitDirectoryScreen', self.main)
        self.assertIn('onBack = { cloudUnitsPage = false }', self.main)

    def test_screen_reads_existing_active_unit_directory(self):
        self.assertIn('loadCommercialUnitsOfMeasure(context)', self.screen)
        self.assertIn('commercial_unit_of_measure?select=code,display_name,dimension,decimal_places&is_active=eq.true', self.repository)

    def test_screen_has_search_filter_refresh_and_error_state(self):
        self.assertIn('value = query,', self.screen)
        self.assertIn('selectedDimension', self.screen)
        self.assertIn('onClick = { refreshToken++ }', self.screen)
        self.assertIn('تعذر تحميل دليل وحدات القياس', self.screen)

    def test_screen_does_not_write_or_claim_item_conversion_factors(self):
        self.assertNotIn('commercialCloudRequest(', self.screen)
        self.assertIn('معاملًا خاصًا بكل صنف', self.screen)


if __name__ == "__main__":
    unittest.main()
