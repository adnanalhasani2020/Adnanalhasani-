from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261103124206_20261103124206_20261103030000_android_cloud_read_column_grants.sql"


def test_reports_receive_only_the_organization_timezone_column():
    sql = " ".join(MIGRATION.read_text(encoding="utf-8").lower().split())
    assert "grant select (default_timezone) on table public.commercial_organizations to authenticated" in sql


def test_timezone_grant_does_not_expand_anon_or_table_wide_access():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert " to anon" not in sql
    assert "grant select on table public.commercial_organizations" not in sql
    assert "grant select (id," not in sql
    assert "row-level security" in sql
