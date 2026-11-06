from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android-commercial/app/src/main/java/com/adnanalhasani/commercial"


def test_fixed_asset_register_is_cloud_only_and_wired():
    more = (ANDROID / "LocalOperationalScreens.kt").read_text(encoding="utf-8")
    store = (ANDROID / "StoreApp.kt").read_text(encoding="utf-8")
    screen = (ANDROID / "CommercialFixedAssetsScreen.kt").read_text(encoding="utf-8")
    assert '"الأصول الثابتة" -> onFixedAssets()' in more
    assert "onFixedAssets = { commercialFixedAssetsPage = true }" in store
    assert "commercialFixedAssetsPage -> CommercialFixedAssetsScreen" in store
    assert "معاينة الإهلاك" in screen
    assert "لا ينشئ قيد شراء أو قيد إهلاك أو حركة مخزون" in screen
    assert "تأكيد الاستبعاد" in screen


def test_fixed_asset_repository_uses_authenticated_rpc_boundary():
    repository = (ANDROID / "CommercialFixedAssetRepository.kt").read_text(encoding="utf-8")
    assert "commercialMasterDataAccess(context)" in repository
    for rpc in (
        "commercial_fixed_asset_create",
        "commercial_fixed_asset_list",
        "commercial_fixed_asset_update",
        "commercial_fixed_asset_depreciation_preview",
    ):
        assert f"/rest/v1/rpc/{rpc}" in repository
    assert "newCommercialFixedAssetIdempotencyKey" in repository


def test_fixed_asset_database_contract_is_tenant_scoped_and_non_posting():
    migration = (ROOT / "supabase/migrations/20261106113918_commercial_fixed_assets_register.sql").read_text(encoding="utf-8")
    smoke = (ROOT / "supabase/tests/database/commercial_fixed_assets_smoke_test.sql").read_text(encoding="utf-8")
    assert "UNIQUE(organization_id,activity_id,asset_code)" in migration
    assert "ENABLE ROW LEVEL SECURITY" in migration
    assert "REVOKE ALL ON TABLE public.commercial_fixed_assets FROM PUBLIC,anon,authenticated" in migration
    assert migration.count("SECURITY DEFINER SET search_path = ''") == 5
    assert "commercial_fixed_asset_context_role" in migration
    assert "asset_idempotency_key_reused" in migration
    assert "asset_version_conflict" in migration
    assert "double_declining" in migration
    assert "commercial_fixed_asset_depreciation_preview" in smoke
    assert "asset_disposal_was_allowed" not in smoke
    assert "asset_rls_or_table_privileges_invalid" in smoke

def test_fixed_asset_movements_are_audited_idempotent_and_rpc_only():
    migration = (ROOT / "supabase/migrations/20261106160000_commercial_fixed_asset_movements.sql").read_text(encoding="utf-8")
    workflow = (ROOT / ".github/workflows/commercial-fixed-assets-smoke.yml").read_text(encoding="utf-8")
    assert "commercial_fixed_asset_movements" in migration
    assert "commercial_fixed_asset_move" in migration
    assert "commercial_fixed_asset_movement_list" in migration
    assert "asset_movement_idempotency_conflict" in migration
    assert "asset_movement_requires_movement_rpc" in migration
    assert "ENABLE ROW LEVEL SECURITY" in migration
    assert "REVOKE ALL ON TABLE public.commercial_fixed_asset_movements FROM PUBLIC,anon,authenticated" in migration
    assert "commercial_fixed_asset_movements" in workflow
