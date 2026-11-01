from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261101040000_commercial_location_coordinates_rpc.sql"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"


class CommercialLocationCoordinatesContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sql = MIGRATION.read_text(encoding="utf-8")
        cls.screen = SCREEN.read_text(encoding="utf-8")

    def test_coordinate_rpc_is_authenticated_scoped_and_audited(self):
        self.assertLess(
            self.sql.index("m.role IN ('owner','admin','manager')"),
            self.sql.index("SELECT l.* INTO v_location"),
            "membership authorization must happen before revealing location existence",
        )
        for token in (
            "CREATE OR REPLACE FUNCTION public.commercial_update_location_coordinates",
            "SECURITY DEFINER",
            "SET search_path TO 'pg_catalog','public'",
            "auth.uid()",
            "m.state='active'",
            "m.role IN ('owner','admin','manager')",
            "l.organization_id=p_organization_id",
            "l.state='active'",
            "FOR UPDATE",
            "latitude=p_latitude",
            "longitude=p_longitude",
            "organization_location.coordinates_updated",
            "REVOKE ALL ON FUNCTION public.commercial_update_location_coordinates",
            "GRANT EXECUTE ON FUNCTION public.commercial_update_location_coordinates",
        ):
            with self.subTest(token=token):
                self.assertIn(token, self.sql)

    def test_coordinates_are_validated_as_a_pair_and_with_geographic_bounds(self):
        self.assertIn("(p_latitude IS NULL) <> (p_longitude IS NULL)", self.sql)
        self.assertIn("p_latitude < -90 OR p_latitude > 90", self.sql)
        self.assertIn("p_longitude < -180 OR p_longitude > 180", self.sql)
        self.assertIn("coordinates_must_be_set_or_cleared_together", self.sql)

    def test_catalog_screen_saves_coordinates_only_through_guarded_rpc(self):
        self.assertIn("/rest/v1/rpc/commercial_update_location_coordinates", self.screen)
        self.assertIn('"p_organization_id"', self.screen)
        self.assertIn('"p_location_id"', self.screen)
        self.assertIn('"p_latitude"', self.screen)
        self.assertIn('"p_longitude"', self.screen)
        self.assertIn("parsedLatitude >= java.math.BigDecimal(\"-90\")", self.screen)
        self.assertIn("parsedLongitude <= java.math.BigDecimal(\"180\")", self.screen)
        self.assertIn("branch.copy(latitude=savedLat,longitude=savedLon)", self.screen)
        self.assertNotIn("/rest/v1/commercial_organization_locations?on_conflict", self.screen)

    def test_migration_is_atomic(self):
        self.assertTrue(self.sql.lstrip().startswith("BEGIN;"))
        self.assertTrue(self.sql.rstrip().endswith("COMMIT;"))


if __name__ == "__main__":
    unittest.main()
