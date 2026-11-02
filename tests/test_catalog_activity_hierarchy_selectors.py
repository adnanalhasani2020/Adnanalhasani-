from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"


class CatalogActivityHierarchySelectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCREEN.read_text(encoding="utf-8")

    def test_directory_nodes_keep_parent_kind_and_selectability(self):
        self.assertIn("data class CatalogActivityNode", self.source)
        self.assertIn('node.optString("parent_code")', self.source)
        self.assertIn('node.optString("node_kind")', self.source)
        self.assertIn('node.optBoolean("is_selectable",false)', self.source)
        self.assertIn('node.optString("default_business_level_code")', self.source)

    def test_sector_filter_walks_parent_chain_and_prevents_cycles(self):
        self.assertIn("catalogNodeBelongsToSector", self.source)
        self.assertIn("nodes.associateBy { it.code }", self.source)
        self.assertIn("visited.add(current.code)", self.source)
        self.assertIn("current = byCode[current.parentCode]", self.source)

    def test_sector_activity_and_level_selectors_use_directory_values(self):
        self.assertIn('it.nodeKind.equals("sector", true)', self.source)
        self.assertIn("activityOptions = remember(activityNodes, sector)", self.source)
        self.assertIn("businessLevelOptions = remember(activityOptions)", self.source)
        self.assertIn("sectorMenuExpanded", self.source)
        self.assertIn("businessLevelMenuExpanded", self.source)
        self.assertIn("activityMenuExpanded", self.source)
        self.assertNotRegex(self.source, r"OutlinedTextField\\(sector\\s*,")
        self.assertNotRegex(self.source, r"OutlinedTextField\\(businessLevel\\s*,")

    def test_initial_activity_restores_its_sector_and_default_level(self):
        self.assertIn("catalogSectorForNode(initialActivityCode.uppercase(), activityNodes)", self.source)
        self.assertIn("businessLevel = it", self.source)

    def test_rpc_filters_keep_existing_parameter_contract(self):
        self.assertIn('.put("p_activity_node_code",selectedActivityCode.trim().uppercase()', self.source)
        self.assertIn('.put("p_sector_code",sector.trim()', self.source)
        self.assertIn('.put("p_business_level_code",businessLevel.trim()', self.source)

    def test_legacy_activity_triple_representation_is_removed(self):
        self.assertNotIn("List<Triple<String, String, String>>", self.source)
        self.assertNotIn("node.first", self.source)
        self.assertNotIn("node.second", self.source)
        self.assertNotIn("node.third", self.source)


if __name__ == "__main__":
    unittest.main()
