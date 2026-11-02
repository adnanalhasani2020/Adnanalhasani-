from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
LOCATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogDeviceLocation.kt"
MANIFEST = ROOT / "android-commercial/app/src/main/AndroidManifest.xml"


class CatalogCurrentDeviceLocationTests(unittest.TestCase):
    def test_location_permission_is_optional_coarse_and_foreground_only(self):
        manifest = MANIFEST.read_text(encoding="utf-8")
        self.assertIn('android.permission.ACCESS_COARSE_LOCATION', manifest)
        self.assertNotIn('android.permission.ACCESS_BACKGROUND_LOCATION', manifest)
        self.assertNotIn('android.permission.READ_PHONE_STATE', manifest)

    def test_location_helper_requires_permission_and_rejects_stale_fixes(self):
        source = LOCATION.read_text(encoding="utf-8")
        self.assertIn("ContextCompat.checkSelfPermission", source)
        self.assertIn("LocationManager.NETWORK_PROVIDER", source)
        self.assertIn("getCurrentLocation(", source)
        self.assertIn("ageMillis in 0..120_000L", source)
        self.assertIn("suspendCancellableCoroutine", source)
        self.assertNotIn("SharedPreferences", source)
        self.assertNotIn("ACCESS_BACKGROUND_LOCATION", source)

    def test_nearby_and_price_suggestions_use_opt_in_device_center(self):
        source = SCREEN.read_text(encoding="utf-8")
        self.assertIn("rememberLauncherForActivityResult", source)
        self.assertIn("getFreshCatalogDeviceLocation(context)", source)
        self.assertIn("deviceLatitude=deviceLatitude", source)
        self.assertIn("val lat=deviceLat ?: branch?.latitude?.toBigDecimalOrNull()", source)
        self.assertIn('"/rest/v1/rpc/commercial_catalog_nearby_popularity"', source)
        self.assertIn('"/rest/v1/rpc/commercial_catalog_price_suggestions"', source)
        self.assertIn("deviceLatitude=null;deviceLongitude=null;startNearbyCatalog()", source)
        self.assertIn("nearbyMarketItems=(nearbyMarketItems+page).distinctBy { it.id }", source)

    def test_device_location_is_session_only_and_not_background_tracked(self):
        source = SCREEN.read_text(encoding="utf-8")
        self.assertIn("Device coordinates are opt-in, foreground-only and kept in memory", source)
        self.assertIn("لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية", source)
        self.assertNotIn("ACCESS_BACKGROUND_LOCATION", source)


if __name__ == "__main__":
    unittest.main()
