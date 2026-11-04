from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/InventoryRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ReportsCenterScreen.kt"
PROJECTION = ROOT / "supabase/migrations/20261031070000_inventory_warehouse_context_projection.sql"


class ReportsWarehouseInventoryContextContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.projection = PROJECTION.read_text(encoding="utf-8")

    def test_report_reads_existing_read_only_warehouse_projection(self):
        loader = self.repository.split(
            "internal fun loadCommercialCloudWarehouseInventoryContext", 1
        )[1].split("internal fun loadCommercialCloudWarehouseInventoryContext", 1)[1]
        self.assertIn("commercial_inventory_warehouse_context", loader)
        self.assertIn("organization_id=eq.$orgId", loader)
        self.assertIn("offering.activity_id=eq.$activityId", loader)
        self.assertIn("commercialCloudPagedGet(path, access)", loader)
        self.assertNotIn("commercialCloudRequest(", loader)
        self.assertIn("WITH (security_invoker = true)", self.projection)
        self.assertIn("GRANT SELECT", self.projection)

    def test_mapping_states_and_counts_are_validated_fail_closed(self):
        loader = self.repository.split(
            "internal fun loadCommercialCloudWarehouseInventoryContext", 1
        )[1].split("internal fun loadCommercialCloudWarehouseInventoryContext", 1)[1]
        for marker in (
            'setOf("mapped", "unconfigured", "ambiguous")',
            'status == "mapped" && mappedCount == 1',
            'status == "unconfigured" && mappedCount == 0',
            'status == "ambiguous" && mappedCount > 1',
            "warehouseId == null",
            "تم حجب التقرير",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, loader)

    def test_quantities_use_bigdecimal_and_are_not_aggregated_across_units(self):
        loader = self.repository.split(
            "internal fun loadCommercialCloudWarehouseInventoryContext", 1
        )[1].split("internal fun loadCommercialCloudWarehouseInventoryContext", 1)[1]
        self.assertIn("val quantityOnHand: BigDecimal", self.repository)
        self.assertIn("val quantityReserved: BigDecimal", self.repository)
        self.assertIn("val availableQuantity: BigDecimal", self.repository)
        self.assertIn("BigDecimal(row.optString(field))", loader)
        self.assertIn("available == onHand.subtract(reserved).max(BigDecimal.ZERO)", loader)
        loader_doc = self.repository.split(
            "internal fun loadCommercialCloudWarehouseInventoryContext", 1
        )[0]
        self.assertIn("aggregates unlike units", loader_doc)

    def test_reports_center_loads_and_displays_the_mapping_readiness_report(self):
        for marker in (
            "loadCommercialCloudWarehouseInventoryContext(context)",
            "warehouseInventoryContextLoadError",
            'ReportCatalogItem("حالة ربط أرصدة المخزون بالمستودعات"',
            '"حالة ربط أرصدة المخزون بالمستودعات" -> cloudWarehouseInventoryContext.map',
            '"حالة ربط أرصدة المخزون بالمستودعات" -> warehouseInventoryContextLoadError',
            "مربوط بمستودع واحد",
            'item.mappingStatus == "mapped" && item.warehouseState == "inactive"',
            "مرتبط بمستودع غير نشط",
            "مرتبط بمستودع مؤرشف",
            "حالة المستودع غير معروفة",
            "حالة المستودع:",
            "غير مهيأ",
            "ملتبس · أكثر من مستودع",
            "item.availableQuantity.stripTrailingZeros().toPlainString()",
        ):
            with self.subTest(marker=marker):
                self.assertIn(marker, self.screen)

    def test_report_does_not_claim_to_post_or_move_stock(self):
        report = self.screen.split(
            '"حالة ربط أرصدة المخزون بالمستودعات" -> cloudWarehouseInventoryContext.map', 1
        )[1].split("else -> emptyList()", 1)[0]
        self.assertNotIn("commercialCloudRequest(", report)
        self.assertIn("لا يجمع وحدات مختلفة ولا يغيّر الرصيد أو الحركات", self.screen)


if __name__ == "__main__":
    unittest.main()
