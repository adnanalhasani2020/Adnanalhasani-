from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"

FILES = (
    "CommercialCloudReturnRefund.kt",
    "CommercialNotificationsScreen.kt",
    "CommercialAdsScreen.kt",
    "PersonalAccountsScreen.kt",
)


class CommercialSharedTransportContractTests(unittest.TestCase):
    def test_cloud_screens_use_the_shared_encrypted_session_and_http_client(self):
        for name in FILES:
            with self.subTest(file=name):
                source = (ANDROID / name).read_text(encoding="utf-8")
                self.assertIn("commercialCloudSessionPreferences(context)", source)
                self.assertIn("SupabaseClient.request(", source)
                self.assertNotIn("personalAccountPrefs", source)
                self.assertNotIn("notificationSessionPrefs", source)
                self.assertNotIn("adsPrefs(context)", source)
                self.assertNotIn("EncryptedSharedPreferences", source)
                self.assertNotIn("MasterKey", source)
                self.assertNotIn("HttpURLConnection", source)
                self.assertNotIn("java.net.URL", source)

    def test_refund_keeps_durable_idempotency_before_network_submission(self):
        source = (ANDROID / "CommercialCloudReturnRefund.kt").read_text(encoding="utf-8")
        self.assertIn("commercial_cloud_pending_return_refund_v1", source)
        self.assertIn('putString("idempotency_key", idempotencyKey)', source)
        self.assertIn("returnRefundRequest(", source)
        self.assertIn("readTimeoutMs = 25000", source)
        self.assertIn("wrapNetworkErrors = false", source)

    def test_other_cloud_screens_keep_their_request_timeout_and_ambiguous_result_handling(self):
        for name in FILES[1:]:
            with self.subTest(file=name):
                source = (ANDROID / name).read_text(encoding="utf-8")
                self.assertIn("readTimeoutMs = 20000", source)
                self.assertIn("wrapNetworkErrors = false", source)


if __name__ == "__main__":
    unittest.main()
