from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLIENT = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/SupabaseClient.kt"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudRepository.kt"


def test_supabase_transport_keeps_headers_timeouts_and_disconnect_behavior():
    client = CLIENT.read_text(encoding="utf-8")
    assert "connectTimeout = 15000" in client
    assert "readTimeout = 25000" in client
    assert 'setRequestProperty("apikey", anon)' in client
    assert 'setRequestProperty("Accept", "application/json")' in client
    assert 'setRequestProperty("Content-Type", "application/json")' in client
    assert 'setRequestProperty("Authorization", "Bearer $token")' in client
    assert "connection.disconnect()" in client
    assert "readTimeoutMs: Int = 25000" in client
    assert "readTimeoutMs = 20000" in client
    assert "wrapNetworkErrors = false" in client


def test_all_commercial_cloud_calls_use_the_single_shared_transport():
    repository = REPOSITORY.read_text(encoding="utf-8")
    assert "commercialCloudRequest(" not in repository
    assert "SupabaseClient.request(" in repository
    assert "private fun commercialCloudRequest(" not in repository

def test_profile_and_cloud_repository_share_transport_and_encrypted_session_store():
    profile = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ActivityProfileScreen.kt").read_text(encoding="utf-8")
    repository = REPOSITORY.read_text(encoding="utf-8")
    session = (ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCloudSession.kt").read_text(encoding="utf-8")
    assert "SupabaseClient.requestJson(" in profile
    assert "supabaseRequest(" not in profile
    assert "commercialCloudSessionPreferences(context)" in profile
    assert "commercialCloudSessionPreferences(context)" in repository
    assert "profileSessionPreferences(" not in profile
    assert "ruh_catalog_auth_session" in session
    assert "EncryptedSharedPreferences.create(" in session
