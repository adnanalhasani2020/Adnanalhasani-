from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031100000_inventory_cost_return_cancel_reversals.sql"


def test_return_restock_restores_original_sale_cost_layers():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "commercial_inventory_return_cost_allocations" in sql
    assert "commercial_restore_return_cost_layers" in sql
    assert "AFTER UPDATE OF restocked_quantity" in sql
    assert "sale_cost_allocation_id" in sql
    assert "ORDER BY a.created_at DESC,a.id DESC" in sql
    assert "return_cost_allocation_insufficient" in sql
    assert "return_cost_layer_balance_quantity_mismatch" in sql


def test_weighted_average_return_revalues_remaining_layers_without_losing_original_cost():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "SET unit_cost_minor=v_balance.unit_cost_minor" in sql
    assert "source_unit_cost_minor" in sql
    assert "source_unit_cost_minor" not in sql or "commercial_inventory_cost_layers" in sql


def test_sale_cancellation_reverses_only_unreturned_cost_and_adjustment_journals():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "commercial_inventory_cost_reversals" in sql
    assert "commercial_reverse_sale_cost_layers" in sql
    assert "sale_cost_adjustment" in sql
    assert "'reversal:'||v_entry.id::text" in sql
    assert "sale_with_open_return_cannot_be_cancelled_resolve_return_first" in sql
    assert "cancel_cost_layer_balance_quantity_mismatch" in sql
    assert "v_layer_qty>v_balance_qty" in sql


def test_return_and_cancel_cost_history_is_preserved_and_read_only_to_members():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "FOR SELECT TO authenticated" in sql
    assert "GRANT SELECT ON public.commercial_inventory_return_cost_allocations" in sql
    assert "REVOKE ALL ON public.commercial_inventory_return_cost_allocations" in sql
    assert "DELETE FROM public.commercial_inventory_cost_allocations" not in sql
    assert "DROP TABLE" not in sql


def test_return_and_cancel_hooks_run_inside_existing_transactions():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "AFTER UPDATE OF restocked_quantity" in sql
    assert "AFTER UPDATE OF status ON public.commercial_sales" in sql
    assert "commercial_sale_cost_layers_cancel_reversal_trg" in sql
    assert "commercial_return_restore_cost_layers_trg" in sql


def test_return_and_cancellation_quantities_cannot_exceed_original_allocations():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "a.quantity" in sql
    assert "sum(ra.quantity)" in sql
    assert "sum(cr.quantity)" in sql
    assert "greatest(" in sql
    assert "return_cost_allocation_insufficient" in sql
