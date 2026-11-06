from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def test_asset_maintenance_screen_is_cloud_only_and_wired():
    more = (ANDROID / "LocalOperationalScreens.kt").read_text(encoding="utf-8")
    store = (ANDROID / "StoreApp.kt").read_text(encoding="utf-8")
    screen = (ANDROID / "CommercialFixedAssetMaintenanceScreen.kt").read_text(encoding="utf-8")
    assert '"صيانة الأصول" -> onAssetMaintenance()' in more
    assert "onAssetMaintenance = { commercialAssetMaintenancePage = true }" in store
    assert "commercialAssetMaintenancePage -> CommercialFixedAssetMaintenanceScreen" in store
    assert "جدولة صيانة" in screen
    assert "تكلفة الصيانة تُسجل كمعلومة تشغيلية فقط" in screen


def test_asset_maintenance_repository_uses_authenticated_rpcs_and_idempotency():
    repository = (ANDROID / "CommercialFixedAssetMaintenanceRepository.kt").read_text(encoding="utf-8")
    assert "commercialMasterDataAccess(context)" in repository
    for rpc in (
        "commercial_fixed_asset_maintenance_create",
        "commercial_fixed_asset_maintenance_list",
        "commercial_fixed_asset_maintenance_update",
    ):
        assert f"/rest/v1/rpc/{rpc}" in repository
    assert "newCommercialAssetMaintenanceIdempotencyKey" in repository


def test_asset_maintenance_database_contract_is_tenant_scoped_and_non_posting():
    migration = (ROOT / "supabase/migrations/20261106150000_commercial_fixed_asset_maintenance.sql").read_text(encoding="utf-8")
    smoke = (ROOT / "supabase/tests/database/commercial_fixed_asset_maintenance_smoke_test.sql").read_text(encoding="utf-8")
    assert "FOREIGN KEY(organization_id,activity_id,asset_id)" in migration
    assert "ENABLE ROW LEVEL SECURITY" in migration
    assert "REVOKE ALL ON TABLE public.commercial_fixed_asset_maintenance FROM PUBLIC,anon,authenticated" in migration
    assert migration.count("SECURITY DEFINER SET search_path = ''") == 3
    assert "asset_maintenance_idempotency_key_reused" in migration
    assert "asset_maintenance_version_conflict" in migration
    assert "asset_maintenance_completion_before_schedule" in migration
    assert "maintenance_staff_scope_failed" in smoke
    assert "maintenance_for_disposed_asset_was_allowed" in smoke
    assert "maintenance_rls_or_table_privileges_invalid" in smoke
