from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/MasterDataScreen.kt"
MIGRATION = ROOT / "supabase/migrations/20261024001000_commercial_warehouse_category_department_foundation.sql"


class DepartmentWarehouseAssignmentContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = REPOSITORY.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.migration = MIGRATION.read_text(encoding="utf-8")

    def test_assignment_reads_are_organization_scoped(self):
        function = self.repository.split("internal fun loadCommercialDepartmentWarehouseAssignments", 1)[1].split(
            "internal fun assignCommercialDepartmentToWarehouse", 1
        )[0]
        self.assertIn("commercial_department_warehouse_assignments", function)
        self.assertIn("organization_id=eq.$orgId", function)
        self.assertIn('"GET", token = access', function)

    def test_assignment_writes_use_existing_table_and_tenant_scope(self):
        function = self.repository.split("internal fun assignCommercialDepartmentToWarehouse", 1)[1].split(
            "internal data class CommercialCloudStockAlert", 1
        )[0]
        self.assertIn('.put("organization_id", orgId)', function)
        self.assertIn('.put("department_id", departmentId)', function)
        self.assertIn('.put("warehouse_id", warehouseId)', function)
        self.assertIn("commercial_department_warehouse_assignments", function)
        self.assertIn("existingRows.length() > 0", function)
        self.assertIn("department_warehouse_scope_mismatch", self.migration)

    def test_ui_exposes_assignment_and_prevents_empty_selection(self):
        self.assertIn("ربط الأقسام بالمستودعات", self.screen)
        self.assertIn("assignCommercialDepartmentToWarehouse(context, departmentId, warehouseId)", self.screen)
        self.assertIn("chosenDepartment != null && chosenWarehouse != null", self.screen)
        self.assertIn("لا تتغير أرصدة المخزون", self.screen)
        self.assertIn('loadCommercialMasterData(context, "commercial_departments").filter { it.state == "active" }', self.screen)
        self.assertIn('loadCommercialMasterData(context, "commercial_warehouses").filter { it.state == "active" }', self.screen)

    def test_no_new_assignment_table_or_stock_mutation_is_introduced(self):
        self.assertNotIn("CREATE TABLE", self.repository)
        self.assertNotIn("commercial_inventory_balances", self.repository.split(
            "internal fun assignCommercialDepartmentToWarehouse", 1
        )[1].split("internal data class CommercialCloudStockAlert", 1)[0])


if __name__ == "__main__":
    unittest.main()
