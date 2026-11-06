from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudStockAlertsScreen.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/InventoryRepository.kt"
CATALOG_REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogRepository.kt"


class CommercialCloudStockAlertsContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MainActivity.kt").read_text(encoding="utf-8") + (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt").read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.catalog_repository = CATALOG_REPOSITORY.read_text(encoding="utf-8")

    def test_screen_is_reachable_from_more_menu(self):
        self.assertIn('"تنبيهات المخزون السحابي" -> onCloudStockAlerts()', self.main)
        self.assertIn("cloudStockAlertsPage -> CommercialCloudStockAlertsScreen", self.main)
        self.assertIn('"تنبيهات المخزون السحابي"', self.main)

    def test_screen_uses_existing_cloud_inventory_projection(self):
        self.assertIn("loadCommercialCloudStockAlerts(context)", self.screen)
        self.assertIn("internal fun loadCommercialCloudStockAlerts", self.repository)
        self.assertIn("commercial_list_inventory_batches", self.repository)
        self.assertIn("availableQuantity = (sellableByOffering", self.repository)
        self.assertIn("subtract(reserved).max(BigDecimal.ZERO)", self.repository)
        self.assertIn("parseWholeInventoryQuantity(row.optString(\"quantity_on_hand\", \"0\"), \"quantity_on_hand\")", self.repository)
        self.assertIn("reorderValue.stripTrailingZeros().scale() <= 0", self.catalog_repository)
        self.assertIn("reorderValue.intValueExact()", self.catalog_repository)
        self.assertIn("organization_id=eq.$orgId", self.repository)
        self.assertIn("location_id=eq.$locationId", self.repository)
        self.assertIn("offering.activity_id=eq.$activityId", self.repository)

    def test_low_stock_and_out_of_stock_filters_are_visible(self):
        for label in ("تحت حد الطلب", "نفد المتاح", "حد إعادة الطلب", "المتاح للبيع"):
            with self.subTest(label=label):
                self.assertIn(label, self.screen)

    def test_screen_is_read_only_and_has_recovery_states(self):
        self.assertIn("لا تعدّل الأرصدة ولا تنشئ حركات مخزون", self.screen)
        self.assertIn("إعادة المحاولة", self.screen)
        self.assertIn("تعذر تحميل المخزون", self.screen)


if __name__ == "__main__":
    unittest.main()
