from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MasterDataScreen.kt"


class MasterDataHierarchyVisibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")

    def test_category_and_department_parent_are_visible(self):
        self.assertIn('"التصنيف الأب: $parentLabel"', self.screen)
        self.assertIn('"القسم الأب: $parentLabel"', self.screen)

    def test_root_and_child_levels_are_identified(self):
        self.assertIn('"المستوى: ${if (parentLabel == null) "جذري" else "فرعي"}"', self.screen)

    def test_storage_location_keeps_warehouse_and_parent_context(self):
        self.assertIn('"المستودج: $warehouseLabel"', self.screen) if '"المستودج: $warehouseLabel"' in self.screen else self.assertIn('"المستودع: $warehouseLabel"', self.screen)
        self.assertIn('"الموقع الأب: $parentLabel"', self.screen)


if __name__ == "__main__":
    unittest.main()
