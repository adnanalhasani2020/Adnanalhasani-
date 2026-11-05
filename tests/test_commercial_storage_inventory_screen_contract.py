from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
SCREEN = ANDROID / "CommercialStorageInventoryScreen.kt"
MASTER_SCREEN = ANDROID / "MasterDataScreen.kt"
STORE_APP = ANDROID / "StoreApp.kt"
MASTER_REPOSITORY = ANDROID / "MasterDataRepository.kt"


class CommercialStorageInventoryScreenContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.master_screen = MASTER_SCREEN.read_text(encoding="utf-8")
        cls.store_app = STORE_APP.read_text(encoding="utf-8")
        cls.repository = MASTER_REPOSITORY.read_text(encoding="utf-8")

    def test_storage_inventory_screen_is_reachable_from_master_data(self):
        self.assertIn("onStorageInventory: () -> Unit", self.master_screen)
        self.assertIn("إدارة أرصدة مواقع التخزين", self.master_screen)
        self.assertIn("storageInventoryPage -> CommercialStorageInventoryScreen", self.store_app)

    def test_screen_uses_guarded_rpc_wrappers_not_direct_table_writes(self):
        self.assertIn("allocateCommercialInventoryToStorageLocation(", self.screen)
        self.assertIn("transferCommercialInventoryBetweenStorageLocations(", self.screen)
        self.assertNotIn('SupabaseClient.request("/rest/v1/commercial_inventory_storage_balances"', self.screen)
        self.assertNotIn('SupabaseClient.request("/rest/v1/commercial_inventory_storage_movements"', self.screen)

    def test_pending_operations_reuse_persisted_idempotency_key(self):
        self.assertIn("persistPendingStorageInventoryAction(context, action)", self.screen)
        self.assertIn("loadPendingStorageInventoryAction(context)", self.screen)
        self.assertIn("إعادة المحاولة بنفس الطلب", self.screen)
        self.assertIn("isDefinitiveStorageInventoryRejection(detail)", self.screen)
        self.assertIn("inventory_management_permission_required", self.screen)
        self.assertIn("idempotency_key_reused_with_different_request", self.screen)
        self.assertIn("persistPendingStorageInventoryAction(context, action)", self.screen)
        self.assertIn("if (!persistPendingStorageInventoryAction(context, action))", self.screen)
        self.assertIn("return editor.commit()", self.screen)
        self.assertIn('require(it == "allocation" || it == "transfer")', self.screen)
        self.assertIn("state,product:commercial_products", self.repository)
        self.assertIn('offering.optString("state") != "active"', self.repository)
        self.assertIn("idempotencyKey: String =", self.repository)
        self.assertIn('.put("p_idempotency_key", idempotencyKey)', self.repository)

    def test_allocation_uses_precise_unreserved_quantity(self):
        self.assertIn("loadCommercialStorageAllocationProducts(context)", self.screen)
        self.assertIn("schemaReady = true", self.screen)
        self.assertIn("enabled = schemaReady && !loading && !saving", self.screen)
        self.assertIn("ميزة أرصدة مواقع التخزين غير منشورة", self.screen)
        self.assertIn("quantity_on_hand,quantity_reserved", self.repository)
        self.assertIn("availableQuantity = onHand.subtract(reserved)", self.repository)
        self.assertIn("java.math.BigDecimal", self.repository)
        self.assertIn("branch_location_id", self.repository)
        self.assertIn("it.branchLocationId == branchId", self.screen)
        self.assertIn("it.warehouseId in warehouseIds", self.screen)


if __name__ == "__main__":
    unittest.main()
