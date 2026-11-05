from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106110000_commercial_inventory_storage_reservation_guard.sql"


class CommercialInventoryStorageReservationGuardTests(unittest.TestCase):
    def test_allocation_cannot_assign_reserved_stock_to_a_storage_location(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("coalesce(v_balance.quantity_reserved, 0) < 0", sql)
        self.assertIn("coalesce(v_balance.quantity_reserved, 0) > v_balance.quantity_on_hand", sql)
        self.assertIn(
            "v_allocated + p_quantity > v_balance.quantity_on_hand - coalesce(v_balance.quantity_reserved, 0)",
            sql,
        )
        self.assertIn("inventory_storage_allocation_exceeds_unallocated_stock", sql)
        self.assertNotIn("UPDATE public.commercial_inventory_balances", sql)


if __name__ == "__main__":
    unittest.main()
