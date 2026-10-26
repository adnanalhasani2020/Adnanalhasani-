from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026114510_restrict_accounting_integrity_view_access.sql"
INTEGRITY_TEST = ROOT / "supabase/tests/database/commercial_integrity_test.sql"


def test_accounting_integrity_diagnostics_are_not_exposed_to_api_clients():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "alter view public.commercial_accounting_integrity_checks set (security_invoker = true)" in sql
    assert "revoke all privileges on table public.commercial_accounting_integrity_checks" in sql
    assert "from public, anon, authenticated" in sql
    assert "grant select on table public.commercial_accounting_integrity_checks to service_role" in sql


def test_database_regression_suite_checks_diagnostic_view_privileges():
    sql = INTEGRITY_TEST.read_text(encoding="utf-8").lower()
    assert "not has_table_privilege('anon', 'public.commercial_accounting_integrity_checks', 'select')" in sql
    assert "not has_table_privilege('authenticated', 'public.commercial_accounting_integrity_checks', 'select')" in sql
    assert "has_table_privilege('service_role', 'public.commercial_accounting_integrity_checks', 'select')" in sql
