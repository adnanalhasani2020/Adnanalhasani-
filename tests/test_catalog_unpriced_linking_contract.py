from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class CatalogUnpricedLinkingContractTests(unittest.TestCase):
    def test_rpc_is_authenticated_scoped_bounded_and_idempotent(self):
        sql = (ROOT / "supabase/migrations/20261029020000_catalog_link_unpriced_items.sql").read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_link_unpriced_items", sql)
        self.assertIn("commercial_catalog_member_of", sql)
        self.assertIn("ARRAY['owner','admin','manager']", sql)
        self.assertIn("cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100", sql)
        self.assertIn("ON CONFLICT (organization_id, global_item_id) DO NOTHING", sql)
        self.assertIn("FROM PUBLIC, anon", sql)
        self.assertIn("TO authenticated", sql)
        self.assertIn("SET search_path = pg_catalog, public", sql)
        self.assertIn("array_position(p_global_item_ids, NULL)", sql)
        self.assertIn("duplicate_catalog_item_selection", sql)
        self.assertIn("p.owner_organization_id IS NULL OR p.owner_organization_id = p_organization_id", sql)

    def test_new_links_are_explicitly_unpriced_and_unavailable(self):
        sql = (ROOT / "supabase/migrations/20261029020000_catalog_link_unpriced_items.sql").read_text(encoding="utf-8")
        self.assertIn("pricing_status IN ('unpriced','configured','review_required')", sql)
        self.assertIn("jsonb_build_object('linked_without_pricing', true, 'pricing_required', true", sql)
        self.assertIn("false,", sql)
        self.assertIn("'unpriced'", sql)

    def test_linking_does_not_create_sellable_offerings_or_stock(self):
        sql = (ROOT / "supabase/migrations/20261029020000_catalog_link_unpriced_items.sql").read_text(encoding="utf-8")
        # Linking into the organization directory is intentionally not stock initialization.
        self.assertNotIn("INSERT INTO public.commercial_offerings", sql)
        self.assertNotIn("INSERT INTO public.commercial_inventory_balances", sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", sql)
        self.assertIn("'is_available', false", sql)
        self.assertIn("'pricing_status', 'unpriced'", sql)

    def test_android_bulk_action_uses_rpc_and_limits_selection(self):
        screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        self.assertIn("selectedCatalogIds", screen)
        self.assertIn("commercial_catalog_link_unpriced_items", screen)
        self.assertIn("selectedCatalogIds.size<=100", screen)
        self.assertIn("p_global_item_ids", screen)
        self.assertIn("غير متاحة للبيع", screen)

if __name__ == "__main__":
    unittest.main()
