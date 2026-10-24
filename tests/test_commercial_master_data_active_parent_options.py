from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MasterDataScreen.kt"


class ActiveMasterDataParentOptionsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")

    def test_storage_locations_only_offer_active_warehouses(self):
        self.assertIn('warehouseRows.filter { it.state == "active" }.forEach { warehouse -> DropdownMenuItem', self.screen)

    def test_hierarchy_parent_choices_are_active_and_location_scoped(self):
        self.assertIn('rows.filter { it.warehouseId == warehouseId && it.state == "active" }', self.screen)
        self.assertIn('else rows.filter { it.state == "active" }', self.screen)

    def test_root_level_remains_available(self):
        self.assertIn('Text("جذر رئيسي (بلا أب)")', self.screen)


if __name__ == "__main__":
    unittest.main()
