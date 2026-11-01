from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261101010000_catalog_activity_filtered_discovery.sql"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"

class CatalogActivityFilteredDiscoveryTests(unittest.TestCase):
    def test_rpc_adds_activity_filter_without_rebuilding_catalog(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("p_activity_node_code text", sql)
        self.assertIn("commercial_organization_catalog_item_activities", sql)
        self.assertIn("item_activity.organization_id = p_organization_id", sql)
        self.assertIn("item_activity.global_item_id = p.id", sql)
        self.assertIn("activity.code = upper(btrim(p_activity_node_code))", sql)
        self.assertIn("activity.status = 'active'", sql)
        self.assertIn("activity.is_selectable", sql)
        self.assertIn("existing_activity.organization_id = p_organization_id", sql)
        self.assertIn("not-yet-classified catalog items discoverable", sql)
        self.assertNotIn("CREATE TABLE", sql)
        self.assertNotIn("DROP TABLE", sql)

    def test_rpc_keeps_membership_and_authenticated_execution_guards(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("commercial_catalog_member_of", sql)
        self.assertIn("ARRAY['owner','admin','manager','staff']", sql)
        self.assertIn("REVOKE ALL ON FUNCTION", sql)
        self.assertIn("FROM PUBLIC, anon", sql)
        self.assertIn("TO authenticated", sql)

    def test_android_sends_selected_activity_to_discovery(self):
        screen = SCREEN.read_text(encoding="utf-8")
        payload_start = screen.index('val payload = JSONObject().put("p_organization_id",orgId)')
        payload_end = screen.index('catalogHttp("/rest/v1/rpc/commercial_catalog_discover_products_page"', payload_start)
        payload = screen[payload_start:payload_end]
        self.assertIn('"p_activity_node_code",selectedActivityCode.trim().uppercase().ifBlank { JSONObject.NULL }', payload)

if __name__ == "__main__":
    unittest.main()
