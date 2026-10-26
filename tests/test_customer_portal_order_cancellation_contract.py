from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261026240000_harden_customer_portal_order_cancellation.sql"
DATABASE_TEST = ROOT / "supabase/tests/database/customer_portal_order_cancellation_test.sql"


def test_customer_cancellation_payment_guard_is_null_safe():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "v_order.payment_status is distinct from 'unpaid'" in sql
    assert "v_order.payment_status is distinct from 'credit'" in sql
    assert "v_order.status is distinct from 'pending'" in sql
    assert "for update" in sql


def test_customer_cancellation_keeps_security_boundary_and_retry_behavior():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "security definer" in sql
    assert "set search_path = ''" in sql
    assert "from public, anon" in sql
    assert "to authenticated" in sql
    assert "already_cancelled" in sql


def test_database_regression_suite_covers_cancellation_guards():
    sql = DATABASE_TEST.read_text(encoding="utf-8").lower()
    assert "plan(6)" in sql
    assert "not has_function_privilege('anon'" in sql
    assert "has_function_privilege('authenticated'" in sql
    assert "null or unknown payment states cannot pass" in sql
