from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261101020000_catalog_pricing_status_sync.sql"


class CatalogPricingStatusSyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")

    def test_status_is_derived_from_real_active_positive_price_offerings(self):
        self.assertIn("commercial_catalog_item_offerings", self.sql)
        self.assertIn("commercial_offerings", self.sql)
        self.assertIn("o.state = 'active'", self.sql)
        self.assertIn("o.price_minor > 0", self.sql)
        self.assertIn("THEN 'configured' ELSE 'unpriced' END", self.sql)

    def test_mapping_and_price_changes_refresh_status(self):
        self.assertIn("AFTER INSERT OR UPDATE OR DELETE", self.sql)
        self.assertIn("AFTER UPDATE OF price_minor, state", self.sql)
        self.assertIn("commercial_catalog_refresh_item_pricing_status", self.sql)
        self.assertIn("commercial_catalog_sync_pricing_status_from_item_offering", self.sql)
        self.assertIn("commercial_catalog_sync_pricing_status_from_offering", self.sql)

    def test_sync_never_publishes_or_mutates_stock(self):
        self.assertNotIn("is_available =", self.sql)
        self.assertNotIn("INSERT INTO public.commercial_offerings", self.sql)
        self.assertNotIn("UPDATE public.commercial_offerings", self.sql)
        self.assertNotIn("commercial_inventory_balances", self.sql)
        self.assertNotIn("commercial_stock_movements", self.sql)

    def test_review_required_is_preserved_and_access_is_restricted(self):
        self.assertIn("oi.pricing_status IN ('unpriced', 'configured')", self.sql)
        self.assertIn("FROM PUBLIC, anon, authenticated", self.sql)
        self.assertIn("SET search_path = pg_catalog, public", self.sql)

    def test_backfill_repairs_only_unpriced_rows_with_real_priced_offerings(self):
        self.assertIn("WHERE oi.pricing_status = 'unpriced'", self.sql)
        self.assertIn("pricing_status = 'configured'", self.sql)
        self.assertIn("Keep is_available unchanged", self.sql)


if __name__ == "__main__":
    unittest.main()
