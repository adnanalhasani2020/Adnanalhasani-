from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/PersonalWorkNotesScreen.kt"
SESSION = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSession.kt"
CLIENT = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupabaseClient.kt"


class PersonalWorkNotesSharedTransportContractTests(unittest.TestCase):
    def test_work_notes_reuse_the_shared_encrypted_session_owner(self):
        source = SCREEN.read_text(encoding="utf-8")
        session = SESSION.read_text(encoding="utf-8")
        self.assertIn("commercialCloudSessionPreferences(context)", source)
        self.assertNotIn("EncryptedSharedPreferences.create(", source)
        self.assertNotIn('"ruh_catalog_auth_session"', source)
        self.assertIn('"ruh_catalog_auth_session"', session)

    def test_work_notes_reuse_the_shared_supabase_http_transport(self):
        source = SCREEN.read_text(encoding="utf-8")
        client = CLIENT.read_text(encoding="utf-8")
        self.assertIn("SupabaseClient.request(", source)
        self.assertNotIn("HttpURLConnection", source)
        self.assertNotIn("URL(", source)
        self.assertIn("commercialCloudActionableError(raw, status)", client)
        self.assertIn("readTimeoutMs = 20000", source)
        self.assertIn("wrapNetworkErrors = false", source)


if __name__ == "__main__":
    unittest.main()
