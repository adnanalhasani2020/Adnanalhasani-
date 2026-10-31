from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031010000_catalog_nearby_private_item_visibility.sql"


class NearbyCatalogPrivacyContractTests(unittest.TestCase):
    def test_security_definer_nearby_rpc_reapplies_catalog_item_visibility(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("LANGUAGE plpgsql SECURITY DEFINER", sql)
        self.assertIn("p.owner_organization_id IS NULL", sql)
        self.assertIn("commercial_catalog_member_of(p.owner_organization_id", sql)
        self.assertIn("oi.organization_id=p_organization_id AND oi.global_item_id=p.id", sql)
        self.assertIn("REVOKE ALL ON FUNCTION public.commercial_catalog_nearby_popularity", sql)
        self.assertIn("TO authenticated", sql)

    def test_nearby_rpc_returns_aggregates_not_merchant_identity(self):
        sql = MIGRATION.read_text(encoding="utf-8")
        self.assertIn("count(DISTINCT organization_id)", sql)
        self.assertIn("HAVING count(DISTINCT organization_id)>=5", sql)
        self.assertNotIn("merchant_name", sql)
        self.assertNotIn("merchant_email", sql)
        self.assertNotIn("merchant_phone", sql)


if __name__ == "__main__":
    unittest.main()
