from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
ANDROID = ROOT / "android-commercial" / "app" / "src" / "main" / "java" / "com" / "adnanalhasani" / "commercial"


class BusinessActivityTaxonomyContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.taxonomy = (MIGRATIONS / "20261024110000_business_activity_taxonomy.sql").read_text(encoding="utf-8")
        cls.expansion = (MIGRATIONS / "20261024112000_business_activity_taxonomy_expansion.sql").read_text(encoding="utf-8")
        cls.catalog_schema = (MIGRATIONS / "20261024100000_universal_commercial_catalog.sql").read_text(encoding="utf-8")
        cls.profile_screen = (ANDROID / "ActivityProfileScreen.kt").read_text(encoding="utf-8")
        cls.policy_fix = (MIGRATIONS / "20261024113000_catalog_policy_deduplication.sql").read_text(encoding="utf-8")
        cls.integrity_fix = (MIGRATIONS / "20261024114000_catalog_integrity_performance.sql").read_text(encoding="utf-8")
        cls.alias_guard = (MIGRATIONS / "20261024115000_business_activity_alias_global_uniqueness.sql").read_text(encoding="utf-8")

    def test_activity_taxonomy_does_not_duplicate_product_or_unit_masters(self):
        self.assertIn("commercial_global_business_activity_nodes", self.taxonomy)
        self.assertIn("commercial_global_catalog_categories", self.catalog_schema)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_business_activity_categories", self.taxonomy)
        self.assertNotIn("CREATE TABLE IF NOT EXISTS public.commercial_business_activity_products", self.taxonomy)
        self.assertIn("commercial_global_entity_translations", self.taxonomy)
        self.assertIn("'business_activity'", self.taxonomy)

    def test_unbounded_parent_hierarchy_has_cycle_guard_and_stable_codes(self):
        self.assertIn("parent_code text REFERENCES public.commercial_global_business_activity_nodes(code)", self.taxonomy)
        self.assertIn("commercial_business_activity_cycle_guard", self.taxonomy)
        self.assertIn("business_activity_cycle", self.taxonomy)
        self.assertIn("code text NOT NULL UNIQUE", self.taxonomy)

    def test_grocery_path_and_synonyms_are_seeded(self):
        self.assertIn("('TRADE',NULL,'التجارة'", self.taxonomy)
        self.assertIn("('RETAIL','TRADE','تجارة التجزئة'", self.taxonomy)
        self.assertIn("('RETAIL_NON_SPECIALIZED','RETAIL','تجارة التجزئة غير المتخصصة'", self.taxonomy)
        self.assertIn("('GROCERY','RETAIL_NON_SPECIALIZED','بقالة وتموينات'", self.taxonomy)
        self.assertIn("('GROCERY','ar','بقالة')", self.taxonomy)
        self.assertIn("('GROCERY','ar','تموينات')", self.taxonomy)
        self.assertIn("('GROCERY','en','grocery store')", self.taxonomy)

    def test_activity_records_keep_legacy_code_and_gain_normalized_fk(self):
        self.assertIn("ADD COLUMN IF NOT EXISTS business_activity_node_id uuid", self.taxonomy)
        self.assertIn("commercial_activities_business_activity_node_fkey", self.taxonomy)
        self.assertIn("commercial_link_activity_taxonomy", self.taxonomy)
        self.assertIn("NEW.activity_code := v_code", self.taxonomy)

    def test_directory_rpc_returns_breadcrumb_paths_and_selectable_nodes(self):
        self.assertIn("commercial_list_business_activity_nodes", self.taxonomy)
        self.assertIn("'path_ar',path_ar", self.taxonomy)
        self.assertIn("'path_en',path_en", self.taxonomy)
        self.assertIn("WHERE is_selectable", self.taxonomy)

    def test_external_codes_are_explicit_and_seed_provenance_is_not_misrepresented(self):
        self.assertIn("commercial_global_business_activity_codes", self.taxonomy)
        self.assertIn("standard_id", self.taxonomy)
        self.assertIn("external_version", self.taxonomy)
        self.assertIn("source_system='ROUH_ACTIVITY'", self.expansion)
        self.assertIn("ux_business_activity_alias_normalized_active", self.alias_guard)
        self.assertIn("DROP INDEX IF EXISTS public.ux_business_activity_alias_locale_normalized", self.alias_guard)

    def test_catalog_policies_do_not_use_overlapping_for_all_write_policies(self):
        self.assertIn("DROP POLICY IF EXISTS commercial_catalog_items_org_select", self.policy_fix)
        self.assertIn("FOR INSERT TO authenticated", self.policy_fix)
        self.assertIn("FOR UPDATE TO authenticated", self.policy_fix)
        self.assertIn("FOR DELETE TO authenticated", self.policy_fix)
        self.assertNotIn("CREATE POLICY commercial_catalog_product_units_write", self.policy_fix)

    def test_obsolete_one_phase_transfer_is_removed_and_foreign_keys_indexed(self):
        self.assertIn("DROP FUNCTION IF EXISTS public.commercial_catalog_transfer", self.integrity_fix)
        self.assertIn("ix_catalog_recipes_product", self.integrity_fix)
        self.assertIn("ix_catalog_transfers_source", self.integrity_fix)
        self.assertIn("commercial_catalog_product_regions_pkey", self.integrity_fix)

    def test_android_loads_and_selects_the_server_activity_directory(self):
        self.assertIn("commercial_list_business_activity_nodes", self.profile_screen)
        self.assertIn("النشاط التجاري من الدليل *", self.profile_screen)
        self.assertIn("pathAr", self.profile_screen)
        self.assertIn("activityCode.isNotBlank()", self.profile_screen)
        self.assertIn("جارٍ تحميل دليل الأنشطة", self.profile_screen)


if __name__ == "__main__":
    unittest.main()
