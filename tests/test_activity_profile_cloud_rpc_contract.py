from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/ActivityProfileScreen.kt"


def test_activity_profile_sends_all_required_cloud_rpc_arguments():
    source = SCREEN.read_text(encoding="utf-8")
    required = [
        "p_organization_name",
        "p_organization_slug",
        "p_activity_name",
        "p_activity_type",
        "p_business_level",
        "p_commercial_registration_number",
        "p_opened_on",
        "p_owner_full_name",
        "p_legal_name",
        "p_country_code",
        "p_timezone",
        "p_locale",
        "p_currency_code",
    ]
    for argument in required:
        assert f'.put("{argument}"' in source
    assert 'payload.put("p_locations", locations)' in source
    assert 'SupabaseClient.requestJson("/rest/v1/rpc/commercial_create_activity_profile", payload, token)' in source


def test_activity_profile_persists_all_three_cloud_context_identifiers():
    source = SCREEN.read_text(encoding="utf-8")
    assert 'optString("organization_id")' in source
    assert 'optString("activity_id")' in source
    assert 'optString("location_id")' in source
    assert '.putString("organization_id", organizationId)' in source
    assert '.putString("activity_id", activityId)' in source
    assert '.putString("location_id", locationId)' in source
