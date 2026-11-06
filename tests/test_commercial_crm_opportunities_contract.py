from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def test_opportunity_pipeline_is_cloud_only_and_wired():
    more = (ANDROID / "LocalOperationalScreens.kt").read_text(encoding="utf-8")
    store = (ANDROID / "StoreApp.kt").read_text(encoding="utf-8")
    screen = (ANDROID / "CommercialCrmOpportunitiesScreen.kt").read_text(encoding="utf-8")
    assert '"فرص المبيعات" -> onCrmOpportunities()' in more
    assert "onCrmOpportunities = { commercialCrmOpportunitiesPage = true }" in store
    assert "commercialCrmOpportunitiesPage -> CommercialCrmOpportunitiesScreen" in store
    assert "تاريخ الإغلاق المتوقع" in screen
    assert "سبب خسارة الفرصة" in screen
    assert "لا يتم إنشاء عميل مالي أو فاتورة أو حركة مخزون" in screen


def test_opportunity_repository_uses_tenant_scoped_authenticated_rpcs():
    repository = (ANDROID / "CommercialCrmRepository.kt").read_text(encoding="utf-8")
    assert "commercial_crm_opportunity_list" in repository
    assert "commercial_crm_opportunity_create" in repository
    assert "commercial_crm_opportunity_update" in repository
    assert "commercialMasterDataAccess(context)" in repository
    assert "commercial_cloud_context_v1" in repository
    assert "p_expected_version" in repository
    assert "newCommercialCrmIdempotencyKey()" in repository


def test_opportunity_database_contract_is_rpc_only_and_optimistically_locked():
    migration = (ROOT / "supabase/migrations/20261106120000_commercial_crm_opportunities.sql").read_text(encoding="utf-8")
    smoke = (ROOT / "supabase/tests/database/commercial_crm_opportunities_smoke_test.sql").read_text(encoding="utf-8")
    assert "FOREIGN KEY(lead_id,organization_id,activity_id)" in migration
    assert "ENABLE ROW LEVEL SECURITY" in migration
    assert "REVOKE ALL ON TABLE public.commercial_crm_opportunities FROM PUBLIC,anon,authenticated" in migration
    assert migration.count("SECURITY DEFINER SET search_path = ''") == 3
    assert "commercial_crm_context_role" in migration
    assert "crm_opportunity_version_conflict" in migration
    assert "crm_opportunity_idempotency_key_reused" in migration
    assert "opportunity_lost_reason_required" in smoke
    assert "cross_tenant_opportunity_list_was_allowed" in smoke
    assert "opportunity_rls_or_table_privileges_invalid" in smoke
