from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031110000_inventory_cost_audit_report.sql"


def test_cost_audit_report_is_read_only_and_uses_invoker_rls():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    assert "create or replace view public.commercial_inventory_cost_audit_report" in sql
    assert "with (security_invoker = true)" in sql
    assert "grant select" in sql
    assert "revoke all" in sql
    assert "to authenticated" in sql
    assert "from public, anon" in sql
    assert "insert into" not in sql
    assert "update public." not in sql
    assert "delete from" not in sql


def test_cost_audit_report_covers_receipts_sales_returns_and_cancellations():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for source in (
        "commercial_inventory_cost_layers",
        "commercial_inventory_cost_allocations",
        "commercial_inventory_return_cost_allocations",
        "commercial_inventory_cost_reversals",
    ):
        assert source in sql
    for event in (
        "sale_allocation",
        "return_restoration",
        "sale_cancellation_reversal",
    ):
        assert event in sql


def test_cost_audit_report_preserves_source_and_minor_unit_amounts():
    sql = MIGRATION.read_text(encoding="utf-8").lower()
    for field in (
        "event_at",
        "organization_id",
        "location_id",
        "offering_id",
        "cost_layer_id",
        "quantity",
        "unit_cost_minor",
        "total_cost_minor",
        "costing_method",
        "source_ref",
    ):
        assert field in sql
    assert "round(l.quantity_received * l.source_unit_cost_minor::numeric)" in sql
    assert "currency minor units" in sql
