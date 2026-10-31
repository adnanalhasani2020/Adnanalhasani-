from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
SCHEMA = ROOT / "supabase/migrations/20261023231027_universal_commercial_catalog.sql"
SETUP = ROOT / "supabase/migrations/20261029040000_catalog_setup_privacy_discovery.sql"


class NearbyCatalogInventorySetupContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SCREEN.read_text(encoding="utf-8")
        cls.schema = SCHEMA.read_text(encoding="utf-8")
        cls.setup = SETUP.read_text(encoding="utf-8")

    def test_nearby_selected_items_fetch_authoritative_units_instead_of_requiring_catalog_page_cache(self):
        self.assertIn("val selectedIds=selectedCatalogIds.toList()", self.screen)
        self.assertIn("commercial_catalog_product_units?select=product_id,unit_code,unit_role,quantity_in_base,is_default", self.screen)
        self.assertIn('product_id=$filter', self.screen)
        self.assertIn('grouped.getOrPut(row.optString("product_id"))', self.screen)
        self.assertIn('for(id in selectedIds)', self.screen)
        self.assertIn('selectedCatalogIds.size<=100', self.screen)

    def test_unit_selection_rejects_missing_or_ambiguous_conversion_data(self):
        self.assertIn("if(baseCandidates.size!=1)", self.screen)
        self.assertIn("defaultSaleCandidates.size>1", self.screen)
        self.assertIn("val defaultSaleCandidates=saleCandidates.filter { it.optBoolean(\"is_default\",false) }", self.screen)
        self.assertIn("defaultSaleCandidates.singleOrNull() ?: saleCandidates.singleOrNull() ?: base", self.screen)
        self.assertNotIn("val defaultCandidates=units.filter { it.optBoolean(\"is_default\",false) }", self.screen)
        self.assertIn("saleFactor<=java.math.BigDecimal.ZERO", self.screen)
        self.assertIn('put("base_unit_code",base.optString("unit_code"))', self.screen)
        self.assertIn('put("sale_unit_code",sale.optString("unit_code"))', self.screen)

    def test_nearby_results_keep_scroll_pagination_radius_expansion_and_selection(self):
        self.assertIn("var nearbyHasMore by remember", self.screen)
        self.assertIn("var nearbyLoadGeneration by remember", self.screen)
        self.assertIn("val nearbyListState = rememberLazyListState()", self.screen)
        self.assertIn("fun startNearbyCatalog()", self.screen)
        self.assertIn("nearbyHasMore=rows.length()==30", self.screen)
        self.assertIn("snapshotFlow", self.screen)
        self.assertIn("if(nearbyHasMore) loadNearbyCatalog(false) else loadNearbyCatalog(true)", self.screen)
        self.assertIn("LazyColumn(state=nearbyListState", self.screen)
        self.assertIn("FilterChip(", self.screen)
        self.assertIn("selectedCatalogIds.size<100", self.screen)
        self.assertIn("nearbyHasMore=true; nearbyLoadGeneration++", self.screen)

    def test_configure_rpc_remains_authoritative_and_does_not_create_stock_or_price(self):
        self.assertIn('commercial_catalog_configure_items', self.screen)
        self.assertIn('p_branch_location_id', self.screen)
        self.assertIn('p_warehouse_id', self.screen)
        self.assertIn("'pricing_required',true", self.setup)
        self.assertIn("'stock_created',false", self.setup)
        self.assertIn("quantity_in_base>0", self.setup)

    def test_product_unit_read_policy_inherits_global_catalog_item_visibility(self):
        self.assertIn("'commercial_catalog_product_units'", self.schema)
        self.assertIn("ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY", self.schema)
        self.assertIn("p.owner_organization_id IS NULL", self.schema)
        self.assertIn("commercial_catalog_member_of(p.owner_organization_id", self.schema)


if __name__ == "__main__":
    unittest.main()
