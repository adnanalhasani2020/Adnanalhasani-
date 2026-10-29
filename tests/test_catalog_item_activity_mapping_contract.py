from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261029030000_catalog_item_activity_mapping.sql"

class CatalogItemActivityMappingContractTests(unittest.TestCase):
    def test_mapping_is_many_to_many_and_integrity_scoped(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_organization_catalog_item_activities", sql)
        self.assertIn("PRIMARY KEY (organization_id, global_item_id, activity_node_id)", sql)
        self.assertIn("REFERENCES public.commercial_organization_catalog_items(organization_id, global_item_id)", sql)
        self.assertIn("REFERENCES public.commercial_global_business_activity_nodes(id)", sql)

    def test_rpc_requires_managerial_access_selectable_activity_and_linked_items(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_member_of", sql)
        self.assertIn("ARRAY['owner','admin','manager']", sql)
        self.assertIn("n.status='active' AND n.is_selectable", sql)
        self.assertIn("all_items_must_be_linked_and_enabled_first", sql)
        self.assertIn("cardinality(p_global_item_ids) NOT BETWEEN 1 AND 100", sql)
        self.assertIn("FROM PUBLIC, anon", sql)
        self.assertIn("TO authenticated", sql)

    def test_android_loads_activity_directory_and_assigns_selected_items(self):
        screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        self.assertIn("commercial_list_business_activity_nodes", screen)
        self.assertIn("activityNodes", screen)
        self.assertIn("commercial_catalog_assign_items_to_activity", screen)
        self.assertIn("p_activity_node_code", screen)
        self.assertIn("p_global_item_ids", screen)

    def test_android_auto_loads_activity_directory_and_uses_default_business_level(self):
        screen = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt").read_text(encoding="utf-8")
        self.assertIn('LaunchedEffect(token, organizationId)', screen)
        self.assertIn('node.optString("default_business_level_code")', screen)
        self.assertIn('if (node.third.isNotBlank()) businessLevel=node.third', screen)
        self.assertIn('commercial_list_business_activity_nodes', screen)

    def test_assignment_is_idempotent_and_does_not_change_prices_or_stock(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("ON CONFLICT (organization_id, global_item_id, activity_node_id) DO NOTHING", sql)
        self.assertNotIn("UPDATE public.commercial_organization_catalog_items", sql)
        self.assertNotIn("INSERT INTO public.commercial_offerings", sql)
        self.assertNotIn("INSERT INTO public.commercial_inventory_balances", sql)
        self.assertNotIn("INSERT INTO public.commercial_stock_movements", sql)

if __name__ == "__main__":
    unittest.main()
