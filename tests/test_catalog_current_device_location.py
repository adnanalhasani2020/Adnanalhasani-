from pathlib import Path
from catalog_source_helpers import catalog_android_source
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogScreen.kt"
LOCATION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogDeviceLocation.kt"
MARKET_UI = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogMarketDiscoverySection.kt"
MARKET_STATE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CatalogMarketUiState.kt"
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
        source = catalog_android_source()
        self.assertIn("rememberLauncherForActivityResult", source)
        self.assertIn("getFreshCatalogDeviceLocation(context)", source)
        self.assertIn("deviceLatitude = location.latitude.toString()", source)
        self.assertIn("deviceLongitude = location.longitude.toString()", source)
        self.assertIn("val latitude = if (useDeviceCenter) state.deviceLatitude?.toBigDecimalOrNull() else branch?.latitude?.toBigDecimalOrNull()", source)
        self.assertIn('"/rest/v1/rpc/commercial_catalog_nearby_popularity"', source)
        self.assertIn('"/rest/v1/rpc/commercial_catalog_price_suggestions"', source)
        self.assertIn("deviceLatitude=null\n                            deviceLongitude=null\n                            marketWorkflow.startNearbyCatalog()", source)
        self.assertIn("val useDeviceCenter = state.deviceLatitude != null && state.deviceLongitude != null", source)
        self.assertIn("state.nearbyMarketItems = (state.nearbyMarketItems + page).distinctBy { it.id }", source)

    def test_device_location_is_session_only_and_not_background_tracked(self):
        source = catalog_android_source()
        self.assertIn("Device coordinates are opt-in, foreground-only and kept in memory", source)
        self.assertIn("لا يتم حفظ الإحداثيات في ملفك أو تتبعك في الخلفية", source)
        self.assertNotIn("ACCESS_BACKGROUND_LOCATION", source)


if __name__ == "__main__":
    unittest.main()
