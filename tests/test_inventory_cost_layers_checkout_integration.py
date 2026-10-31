from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATION = ROOT / "supabase/migrations/20261031090000_inventory_cost_layers_checkout_integration.sql"


def test_cost_layers_are_allocated_after_final_mixed_unit_sale_rows_exist():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "CREATE CONSTRAINT TRIGGER commercial_sale_item_costing_finalize_trg" in sql
    assert "DEFERRABLE INITIALLY DEFERRED" in sql
    assert "commercial_finalize_sale_inventory_costing_trigger" in sql
    assert "commercial_allocate_sale_item_cost(v_item.id,v_location)" in sql
    assert "commercial_stock_movements" in sql
    assert "sale_costing_stock_movement_quantity_mismatch" in sql


def test_post_checkout_allocation_is_bound_to_exact_sale_location_and_quantity():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "sale_inventory_movement_cost_scope_mismatch" in sql
    assert "sm.sale_id=v_item.sale_id" in sql
    assert "sm.offering_id=v_item.offering_id" in sql
    assert "sm.movement_type='sale'" in sql
    assert "v_movement_delta IS DISTINCT FROM -v_expected_sale_quantity" in sql
    assert "sale_item_not_owned_by_authenticated_user" in sql
    assert "ELSE v_layer.source_unit_cost_minor END" in sql


def test_costing_gl_is_reconciled_with_a_single_idempotent_adjustment():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "sale_cost_adjustment" in sql
    assert "sale-cost-adjustment:" in sql
    assert "commercial_private.commercial_gl_post_entry" in sql
    assert "v_target_cogs-v_original_cogs" in sql
    assert "sale_costing_adjustment_reconciliation_mismatch" in sql
    assert "inventory_costing_gl_mappings_incomplete" in sql


def test_cost_layer_history_is_preserved_and_failures_roll_back_checkout():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "inventory_cost_layers_insufficient" in sql
    assert "quantity_remaining=quantity_remaining-v_take" in sql
    assert "DEFERRABLE INITIALLY DEFERRED" in sql
    assert "DELETE FROM public.commercial_inventory_cost_layers" not in sql
    assert "DELETE FROM public.commercial_inventory_cost_allocations" not in sql

def test_sale_location_uuid_extraction_is_valid_sql():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "min(substring(sm.source_ref from ':location:([0-9a-fA-F-]{36})'))" in sql
    assert "min(substring(sm.source_ref from ':location:([0-9a-fA-F-]{36})\n    FROM" not in sql
