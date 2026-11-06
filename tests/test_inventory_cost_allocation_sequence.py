from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261106150000_inventory_cost_allocation_sequence.sql"


def test_new_sale_cost_allocations_receive_database_owned_order():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "ADD COLUMN IF NOT EXISTS allocation_sequence bigint" in sql
    assert "commercial_assign_inventory_cost_allocation_sequence" in sql
    assert "FOR UPDATE" in sql
    assert "coalesce(max(a.allocation_sequence),0)+1" in sql
    assert "NEW.allocation_sequence:=v_next" in sql
    assert "commercial_inventory_cost_allocations_item_sequence_uidx" in sql


def test_partial_returns_restore_latest_consumed_allocation_first():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "ORDER BY a.allocation_sequence DESC NULLS LAST,a.created_at DESC,a.id DESC" in sql
    assert "commercial_inventory_return_cost_allocations" in sql
    assert "commercial_inventory_cost_layers" in sql
    assert "quantity_remaining=quantity_remaining+v_take" in sql


def test_historical_allocation_order_is_not_fabricated():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "Historical rows remain NULL" in sql
    assert "ADD COLUMN IF NOT EXISTS allocation_sequence bigint" in sql
    assert "UPDATE public.commercial_inventory_cost_allocations" not in sql
