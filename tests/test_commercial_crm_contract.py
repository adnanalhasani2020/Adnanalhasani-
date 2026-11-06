from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def test_crm_screen_is_wired_into_cloud_navigation_only():
    screen = (ANDROID / "CommercialCrmScreen.kt").read_text(encoding="utf-8")
    navigation = (ANDROID / "StoreApp.kt").read_text(encoding="utf-8")
    more = (ANDROID / "LocalOperationalScreens.kt").read_text(encoding="utf-8")
    assert "CommercialCrmScreen(context = context" in navigation
    assert "onCrm = { commercialCrmPage = true }" in navigation
    assert '"إدارة علاقات العملاء" -> onCrm()' in more
    assert '"إدارة علاقات العملاء"' in more
    assert "loadCommercialCrmLeads(context" in screen
    assert "تسجيل اتصال أو مهمة متابعة" in screen


def test_crm_repository_uses_authenticated_rpc_boundary_and_idempotency():
    repository = (ANDROID / "CommercialCrmRepository.kt").read_text(encoding="utf-8")
    assert "commercialMasterDataAccess(context)" in repository
    for rpc in (
        "commercial_crm_list_leads",
        "commercial_crm_create_lead",
        "commercial_crm_update_lead",
        "commercial_crm_add_activity",
        "commercial_crm_list_activities",
    ):
        assert f"/rest/v1/rpc/{rpc}" in repository
    assert "newCommercialCrmIdempotencyKey" in repository
    assert "p_version" in repository


def test_crm_database_contract_is_tenant_scoped_and_rpc_only():
    migration = (ROOT / "supabase/migrations/20261106120000_commercial_crm_leads_activities.sql").read_text(encoding="utf-8")
    smoke = (ROOT / "supabase/tests/database/commercial_crm_leads_activities_smoke_test.sql").read_text(encoding="utf-8")
    assert "UNIQUE(organization_id,id)" in migration
    assert "FOREIGN KEY(organization_id,lead_id)" in migration
    assert "ENABLE ROW LEVEL SECURITY" in migration
    assert "REVOKE ALL ON public.commercial_crm_leads,public.commercial_crm_activities FROM PUBLIC,anon,authenticated" in migration
    assert migration.count("SECURITY DEFINER SET search_path = ''") == 5
    assert "auth.uid()" in migration
    assert "crm_lead_version_conflict" in migration
    assert "crm_idempotency_key_reused" in migration
    assert "crm_cross_tenant_list_was_allowed" in smoke
    assert "crm_staff_access_to_unassigned_lead_was_allowed" in smoke
    assert "crm_direct_table_access_not_revoked" in smoke
