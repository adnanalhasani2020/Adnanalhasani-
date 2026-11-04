from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
SECTIONS = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreenSections.kt"


class CatalogScreenSectionsOwnershipTests(unittest.TestCase):
    def test_category_and_unit_editors_have_separate_presentation_owners(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        self.assertIn('"category" -> CatalogCategorySection(', screen)
        self.assertIn('"unit" -> CatalogUnitSection(', screen)
        self.assertIn("internal fun CatalogCategorySection(", sections)
        self.assertIn("internal fun CatalogUnitSection(", sections)
        self.assertIn("onCreateCategory: () -> Unit", sections)
        self.assertIn("onCreateUnit: () -> Unit", sections)

    def test_transfer_presentation_is_separate_and_keeps_actions_callback_driven(self):
        screen = SCREEN.read_text(encoding="utf-8")
        sections = SECTIONS.read_text(encoding="utf-8")
        self.assertIn('"transfer" -> CatalogTransferSection(', screen)
        self.assertIn("internal fun CatalogTransferSection(", sections)
        self.assertIn("onRespondToTransfer: (String, Boolean) -> Unit", sections)
        self.assertIn("onLoadPendingTransfers: () -> Unit", sections)


if __name__ == "__main__":
    unittest.main()
