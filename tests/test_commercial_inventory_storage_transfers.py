from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106100000_commercial_inventory_storage_transfers.sql"


class CommercialInventoryStorageTransferTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_storage_subledger_is_additive_and_keeps_aggregate_balance_authoritative(self):
        self.assertIn("commercial_inventory_storage_balances", self.sql)
        self.assertIn("commercial_inventory_balances", self.sql)
        self.assertIn("inventory_storage_allocation_exceeds_unallocated_stock", self.sql)
        self.assertIn("Aggregate commercial_inventory_balances remains authoritative", self.sql)

    def test_allocation_and_transfer_are_atomic_idempotent_and_audited(self):
        for rpc in (
            "commercial_allocate_inventory_to_storage_location",
            "commercial_transfer_inventory_storage_location",
        ):
            self.assertIn(f"CREATE OR REPLACE FUNCTION public.{rpc}", self.sql)
            self.assertIn("pg_advisory_xact_lock", self.sql)
            self.assertIn("idempotency_key_reused_with_different_request", self.sql)
            self.assertIn("commercial_audit_events", self.sql)
        self.assertIn("UNIQUE (organization_id, idempotency_key)", self.sql)

    def test_transfer_requires_same_warehouse_and_does_not_change_branch_balance(self):
        self.assertIn("source_storage_location_id,destination_storage_location_id", self.sql)
        self.assertIn("sl.warehouse_id = p_warehouse_id", self.sql)
        self.assertIn("inventory_storage_insufficient", self.sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", self.sql)
        self.assertNotIn("DELETE FROM public.commercial_inventory_storage_movements", self.sql)

    def test_storage_writes_are_rpc_only_and_reads_are_member_scoped(self):
        self.assertIn("ENABLE ROW LEVEL SECURITY", self.sql)
        self.assertIn("commercial_organization_members", self.sql)
        self.assertIn("REVOKE ALL ON public.commercial_inventory_storage_balances", self.sql)
        self.assertIn("GRANT SELECT ON public.commercial_inventory_storage_balances", self.sql)
        self.assertIn("GRANT EXECUTE ON FUNCTION public.commercial_transfer_inventory_storage_location", self.sql)


if __name__ == "__main__":
    unittest.main()
