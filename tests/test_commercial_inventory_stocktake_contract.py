from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"
MIGRATION = ROOT / "supabase/migrations/20261106120000_commercial_inventory_stocktake_workflow.sql"
FIXTURE = ROOT / "supabase/tests/fixtures/commercial_inventory_stocktake_schema_fixture.sql"
SMOKE = ROOT / "supabase/tests/database/commercial_inventory_stocktake_smoke.sql"
WORKFLOW = ROOT / ".github/workflows/inventory-stocktake-smoke.yml"


class CommercialInventoryStocktakeContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.fixture = FIXTURE.read_text(encoding="utf-8")
        cls.smoke = SMOKE.read_text(encoding="utf-8")
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.repository = (ANDROID / "InventoryStocktakeRepository.kt").read_text(encoding="utf-8")
        cls.screen = (ANDROID / "CommercialInventoryStocktakeScreen.kt").read_text(encoding="utf-8")
        cls.inventory_screen = (ANDROID / "LocalInventoryScreen.kt").read_text(encoding="utf-8")
        cls.store_app = (ANDROID / "StoreApp.kt").read_text(encoding="utf-8")

    def test_document_lifecycle_and_independent_approval_are_server_enforced(self):
        for status in ("draft", "submitted", "approved", "posted", "reversed"):
            self.assertIn(f"'{status}'", self.sql)
        self.assertIn("inventory_stocktake_self_approval_forbidden", self.sql)
        self.assertIn("approved_by <> submitted_by", self.sql)
        self.assertIn("inventory_stocktake_stale_snapshot", self.sql)
        self.assertIn("inventory_stocktake_below_reserved_quantity", self.sql)

    def test_cost_ledger_is_reconciled_and_positive_variance_needs_explicit_cost(self):
        self.assertIn("inventory_stocktake_cost_layer_reconciliation_failed", self.sql)
        self.assertIn("inventory_stocktake_weighted_average_layer_cost_mismatch", self.sql)
        self.assertIn("inventory_stocktake_positive_variance_cost_required", self.sql)
        self.assertIn("commercial_inventory_stocktake_cost_allocations", self.sql)
        self.assertIn("inventory_stocktake_reversal_adjustment_layer_consumed", self.sql)
        self.assertIn("inventory_revision bigint NOT NULL DEFAULT 0", self.sql)
        self.assertIn("posted_balance_revision", self.sql)
        self.assertIn("inventory_stocktake_reversal_balance_changed", self.sql)
        self.assertIn("gl_posting_enabled boolean NOT NULL DEFAULT false", self.sql)
        self.assertIn("inventory_stocktake_accounting_integration_not_ready", self.sql)
        self.assertIn("quantity_reserved", self.sql)

    def test_writes_are_rpc_only_and_smoke_test_covers_reversal_and_stale_count(self):
        self.assertIn("REVOKE ALL ON public.commercial_inventory_stocktakes", self.sql)
        self.assertIn("GRANT SELECT ON public.commercial_inventory_stocktakes", self.sql)
        self.assertIn("FROM PUBLIC,anon", self.sql)
        self.assertIn("has_table_privilege('authenticated','public.commercial_inventory_stocktakes','INSERT')", self.smoke)
        self.assertIn("stale_stocktake_snapshot_was_posted", self.smoke)
        self.assertIn("stocktake_submitter_self_approval_was_allowed", self.smoke)
        self.assertIn("positive_variance_reversal_cost_mismatch", self.smoke)
        self.assertIn("fifo_layer_order_invalid", self.smoke)
        self.assertIn("lifo_layer_order_invalid", self.smoke)
        self.assertIn("stocktake_cost_layer_mismatch_was_posted", self.smoke)
        self.assertIn("stocktake_reversal_after_later_balance_revision_was_allowed", self.smoke)
        self.assertIn("active_zero_balance_offering_not_snapshotted", self.smoke)
        self.assertIn("postgres:17", self.workflow)

    def test_android_repository_uses_guarded_rpc_and_stable_retry_keys(self):
        for rpc in (
            "commercial_create_inventory_stocktake",
            "commercial_set_inventory_stocktake_counts",
            "commercial_submit_inventory_stocktake",
            "commercial_approve_inventory_stocktake",
            "commercial_post_inventory_stocktake",
            "commercial_reverse_inventory_stocktake",
        ):
            self.assertIn(rpc, self.repository)
        self.assertIn("stocktakeActionKey", self.repository)
        self.assertIn("commit())", self.repository)
        self.assertIn("requireStocktakeMatchesCurrentContext", self.repository)
        self.assertIn("stocktakeCreateKey(context, current)", self.repository)
        self.assertIn("تغيرت المؤسسة أو النشاط أو الفرع أثناء تجهيز الطلب", self.repository)
        self.assertIn("تعذر مسح مفتاح إعادة المحاولة المحلي", self.repository)
        self.assertNotIn('SupabaseClient.request("/rest/v1/commercial_inventory_balances"', self.repository)

    def test_stocktake_screen_is_reachable_from_cloud_inventory(self):
        self.assertIn("onStocktake: (() -> Unit)? = null", self.inventory_screen)
        self.assertIn("if (onStocktake != null)", self.inventory_screen)
        self.assertIn("onStocktake = if (cloudMode)", self.store_app)
        self.assertIn("stocktakePage -> CommercialInventoryStocktakeScreen", self.store_app)
        self.assertIn("حفظ وإرسال للاعتماد", self.screen)
        self.assertIn("ترحيل الفروقات", self.screen)
        self.assertIn("الترحيل معطل حتى اكتمال القيود المحاسبية", self.screen)
        self.assertIn("العكس معطل حتى اكتمال القيود المحاسبية", self.screen)
        self.assertIn("enabled = false", self.screen)
        self.assertIn("schemaReady = true", self.screen)
        self.assertIn("enabled = schemaReady && !loading && !saving", self.screen)


if __name__ == "__main__":
    unittest.main()
