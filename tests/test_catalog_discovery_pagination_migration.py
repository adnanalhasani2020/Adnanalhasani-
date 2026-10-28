from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CatalogDiscoveryPaginationMigrationTests(unittest.TestCase):
    def test_page_rpc_reuses_existing_discovery_contract_and_has_bounded_paging(self):
        sql = (ROOT / "supabase/migrations/20261029010000_catalog_discovery_pagination.sql").read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_discover_products_page", sql)
        self.assertIn("p_offset integer DEFAULT 0", sql)
        self.assertIn("LIMIT greatest(1,least(COALESCE(p_limit,50),100))", sql)
        self.assertIn("OFFSET greatest(0,COALESCE(p_offset,0))", sql)
        self.assertIn("commercial_catalog_member_of", sql)
        self.assertIn("FROM PUBLIC, anon", sql)
        self.assertIn("TO authenticated", sql)

    def test_paged_rpc_preserves_sector_level_region_and_unit_filters(self):
        sql = (ROOT / "supabase/migrations/20261029010000_catalog_discovery_pagination.sql").read_text(encoding="utf-8")
        for marker in ("commercial_catalog_product_sectors", "commercial_catalog_product_business_levels", "commercial_catalog_product_regions", "available_units jsonb", "ORDER BY 12 DESC"):
            self.assertIn(marker, sql)

if __name__ == "__main__":
    unittest.main()
