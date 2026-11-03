from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261103030000_android_cloud_read_column_grants.sql"


def test_android_cloud_reads_receive_only_the_columns_used_by_existing_screens():
    sql = MIGRATION.read_text(encoding="utf-8")
    normalized = " ".join(sql.lower().split())
    assert "grant select ( id, default_currency_code, default_timezone ) on table public.commercial_organizations to authenticated" in normalized
    assert "grant select ( id, organization_id, name, location_type, latitude, longitude, state ) on table public.commercial_organization_locations to authenticated" in normalized


def test_read_grants_do_not_expand_anon_or_table_wide_access():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert " to anon" not in sql
    assert "grant select on table public.commercial_organizations" not in sql
    assert "grant select on table public.commercial_organization_locations" not in sql
    assert "row-level security remains enabled" in sql
