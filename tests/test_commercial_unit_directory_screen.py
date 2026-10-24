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

    def test_unit_directory_fetches_all_pages_in_stable_order(self):
        function = self.repository.split("internal fun loadCommercialUnitsOfMeasure", 1)[1].split(
            "private fun commercialMasterDataAccess", 1
        )[0]
        self.assertIn("commercialCloudPagedGet(", function)
        self.assertIn("order=dimension.asc,code.asc", function)
        pager = self.repository.split("private fun commercialCloudPagedGet", 1)[1].split(
            "private fun commercialMasterDataAccess", 1
        )[0]
        self.assertIn("pageSize: Int = 500", pager)
        self.assertIn("limit=$pageSize&offset=$offset", pager)
        self.assertIn("if (page.length() < pageSize) break", pager)

    def test_master_data_and_department_links_are_paginated(self):
        master_data = self.repository.split("internal fun loadCommercialMasterData", 1)[1].split(
            "internal fun assignCommercialCloudOfferingCategory", 1
        )[0]
        assignments = self.repository.split("internal fun loadCommercialDepartmentWarehouseAssignments", 1)[1].split(
            "internal fun assignCommercialDepartmentToWarehouse", 1
        )[0]
        self.assertIn("commercialCloudPagedGet(", master_data)
        self.assertIn("order=code.asc,id.asc", master_data)
        self.assertIn("commercialCloudPagedGet(", assignments)
        self.assertIn("order=department_id.asc,warehouse_id.asc", assignments)

    def test_sales_and_inventory_alerts_use_complete_stable_pagination(self):
        sales = self.repository.split("internal fun loadCommercialCloudSales", 1)[1].split(
            "internal fun loadCommercialCloudSaleDocuments", 1
        )[0]
        alerts = self.repository.split("internal fun loadCommercialCloudStockAlerts", 1)[1].split(
            "internal data class CommercialCloudRecord", 1
        )[0]
        self.assertIn("commercialCloudPagedGet(path, access)", sales)
        self.assertIn("order=created_at.desc,id.asc", sales)
        self.assertIn("commercialCloudPagedGet(query, access)", alerts)
        self.assertIn("order=updated_at.desc,offering_id.asc", alerts)


if __name__ == "__main__":
    unittest.main()
