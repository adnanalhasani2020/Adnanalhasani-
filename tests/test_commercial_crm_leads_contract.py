from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106110000_commercial_crm_leads_followups.sql"
REPOSITORY = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCrmRepository.kt"
SCREEN = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/CommercialCrmScreen.kt"
STORE_APP = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/StoreApp.kt"
MORE = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial/LocalOperationalScreens.kt"


def test_crm_tables_are_tenant_and_activity_scoped_with_rpc_only_access():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "organization_id uuid not null" in sql
    assert "activity_id uuid not null" in sql
    assert "commercial_crm_leads_id_org_activity_uq" in sql
    assert "foreign key(lead_id,organization_id,activity_id)" in sql
    assert "enable row level security" in sql
    assert "revoke all on table public.commercial_crm_leads from public,anon,authenticated" in sql
    assert "revoke all on table public.commercial_crm_activities from public,anon,authenticated" in sql
    assert "set search_path = ''" in sql


def test_crm_rpc_checks_membership_roles_idempotency_and_optimistic_versions():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for token in (
        "auth.uid()",
        "m.state='active'",
        "crm_activity_not_available",
        "crm_staff_cannot_assign_other_users",
        "crm_lead_idempotency_key_reused",
        "crm_activity_idempotency_key_reused",
        "crm_lead_version_conflict",
        "crm_activity_version_conflict",
        "commercial_audit_events",
    ):
        assert token in sql
    for rpc in (
        "commercial_crm_lead_create",
        "commercial_crm_lead_list",
        "commercial_crm_lead_update",
        "commercial_crm_activity_create",
        "commercial_crm_activity_list",
        "commercial_crm_activity_update",
    ):
        assert f"function public.{rpc}" in sql


def test_android_crm_uses_only_rpc_repository_and_is_wired_to_cloud_menu():
    repository = REPOSITORY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    store = STORE_APP.read_text(encoding="utf-8")
    more = MORE.read_text(encoding="utf-8")
    for rpc in (
        "commercial_crm_lead_create",
        "commercial_crm_lead_list",
        "commercial_crm_lead_update",
        "commercial_crm_activity_create",
        "commercial_crm_activity_list",
        "commercial_crm_activity_update",
    ):
        assert rpc in repository
    assert "commercialMasterDataAccess(context)" in repository
    assert "CommercialCrmScreen(" in store
    assert "onCrm = { commercialCrmPage = true }" in store
    assert "إدارة العملاء المحتملين" in more
    assert "إضافة عميل محتمل" in screen
    assert "إكمال المتابعة" in screen
